package api

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/perms"
	"evilion.xyz/chat/internal/store"
)

const (
	// maxEnvelopeBytes is one message's ceiling, for sending and editing alike.
	//
	// The server cannot count a message's characters: all it holds is
	// ciphertext, and the JSON sealed inside carries the text (whose mentions
	// and custom emoji are already expanded to <@id> tokens, several times
	// longer than the names typed), the keys and names of up to ten files, and
	// up to five link previews. The client holds what people type to 4000
	// characters (web/src/components/composer/limit.ts); this is what the
	// largest message it can legitimately send fits under, so a hand-built
	// message, or an edit that went round the client's limit, gets no further.
	maxEnvelopeBytes = 64 << 10
	maxMentions      = 50
	maxAttachments   = 10
	maxNonce         = 64
	maxEmojiLen      = 64
	// maxPreviewBytes bounds a picture's small copy: its sender makes it at
	// most 1 MiB before encryption, so the chat can show it in place of the
	// picture itself (web/src/lib/pictureCopy.ts). Anything bigger is a file
	// passed off as a copy to get past maxAttachments. Each copy is an upload
	// of its own, which is why the upload limiter allows two per file.
	maxPreviewBytes = 2 << 20
	// maxBulkDelete bounds one bulk delete; the client sends bigger selections in batches.
	maxBulkDelete = 100
	// A person's poll has 2 to 10 answers and runs from an hour to two weeks.
	minPollAnswers = 2
	maxPollAnswers = 10
	maxPollHours   = 14 * 24
)

func (s *Server) messageRoutes(mux *http.ServeMux) {
	mux.Handle("GET /api/v1/channels/{id}/messages", s.authed(s.handleMessages))
	mux.Handle("POST /api/v1/channels/{id}/messages", s.authed(s.handleMessageCreate))
	mux.Handle("GET /api/v1/channels/{id}/messages/{mid}", s.authed(s.handleMessageGet))
	mux.Handle("PATCH /api/v1/channels/{id}/messages/{mid}", s.authed(s.handleMessageEdit))
	mux.Handle("DELETE /api/v1/channels/{id}/messages/{mid}", s.authed(s.handleMessageDelete))
	mux.Handle("POST /api/v1/channels/{id}/messages/bulk-delete", s.authed(s.handleMessageBulkDelete))
	mux.Handle("POST /api/v1/channels/{id}/messages/{mid}/ack", s.authed(s.handleAck))
	mux.Handle("PUT /api/v1/channels/{id}/messages/{mid}/reactions/{emoji}/@me", s.userLimited(s.limReact, s.handleReactionAdd))
	mux.Handle("DELETE /api/v1/channels/{id}/messages/{mid}/reactions/{emoji}/@me", s.userLimited(s.limReact, s.handleReactionRemove))
	mux.Handle("GET /api/v1/channels/{id}/messages/{mid}/reactions/{emoji}", s.authed(s.handleReactors))
}

var errMessageTooLong = E(http.StatusRequestEntityTooLarge, "message_too_long", "The message is too long. Shorten it and try again.")

// envelope mirrors the client's encrypted message format.
type envelope struct {
	V     int    `json:"v"`
	Conv  ids.ID `json:"conv"`
	Epoch int    `json:"epoch"`
	IV    string `json:"iv"`
	CT    string `json:"ct"`
}

// validEnvelope checks structure only; the server never decrypts.
func validEnvelope(raw json.RawMessage) (*envelope, bool) {
	if len(raw) == 0 || len(raw) > maxEnvelopeBytes {
		return nil, false
	}
	var e envelope
	if err := json.Unmarshal(raw, &e); err != nil || e.V != 1 || e.Conv.IsZero() || e.Epoch < 0 || e.CT == "" {
		return nil, false
	}
	iv, err := base64.StdEncoding.DecodeString(e.IV)
	if err != nil || len(iv) != 12 {
		return nil, false
	}
	if _, err := base64.StdEncoding.DecodeString(e.CT); err != nil {
		return nil, false
	}
	return &e, true
}

// convForChannel is the conversation whose keys encrypt a channel.
func (s *Server) convForChannel(r *http.Request, ch *store.Channel) (ids.ID, error) {
	if s.isDM(ch) {
		return ch.ID, nil
	}
	if s.ConvForChannel != nil {
		return s.ConvForChannel(r.Context(), ch)
	}
	return ch.ID, nil
}

type messageBody struct {
	Ciphertext      json.RawMessage `json:"ciphertext"`
	KeyConvID       ids.ID          `json:"key_conv_id"`
	KeyEpoch        int             `json:"key_epoch"`
	ReplyToID       *ids.ID         `json:"reply_to_id"`
	Mentions        []ids.ID        `json:"mentions"`
	MentionRoles    []ids.ID        `json:"mention_roles"`
	MentionEveryone bool            `json:"mention_everyone"`
	Attachments     []struct {
		BlobID ids.ID `json:"blob_id"`
		Size   int64  `json:"size"`
		// Preview marks a picture's small copy, made by its sender for the
		// chat to show. It is attached like any file, so the same people can
		// fetch it, but it does not count as one of the message's files.
		Preview bool `json:"preview"`
	} `json:"attachments"`
	Nonce string `json:"nonce"`
	Flags int64  `json:"flags"`
	// Forward names the message this one forwards. Its files may then be
	// the original's (the same blobs, opened by the same keys inside the
	// message), so forwarding uploads nothing and counts against no one's
	// allowance; the sender has to be able to read the original.
	Forward *struct {
		ChannelID ids.ID `json:"channel_id"`
		MessageID ids.ID `json:"message_id"`
	} `json:"forward"`
	// Poll opens a poll with the message. What it asks and offers is sealed
	// in the ciphertext with the rest of the message; this is only what the
	// server needs to take votes (see pollBody).
	Poll json.RawMessage `json:"poll"`
	// BotCopies are the message sealed again, by the sender's app, to each
	// bot it is addressed to (a direct message with the bot, a mention of it,
	// a reply to it), so that bot can read it (bot_crypto.go); by bot id.
	BotCopies map[string][]byte `json:"bot_copies"`
}

// checkBotCopies reads the copies a message carries for bots: each for a bot
// that can see the channel and that the message is addressed to.
func (s *Server) checkBotCopies(r *http.Request, ch *store.Channel, body *messageBody, replyTo *ids.ID) (map[ids.ID][]byte, error) {
	if len(body.BotCopies) == 0 {
		return nil, nil
	}
	if len(body.BotCopies) > store.MaxBotCopies {
		return nil, E(http.StatusBadRequest, "too_many_copies", "A message carries copies for at most 10 apps.")
	}
	ctx := r.Context()
	var repliedTo ids.ID
	if replyTo != nil {
		if target, err := s.Store.MessageByID(ctx, *replyTo, 0); err == nil {
			repliedTo = target.AuthorID
		}
	}
	out := map[ids.ID][]byte{}
	for raw, sealed := range body.BotCopies {
		id, err := ids.Parse(raw)
		if err != nil || len(sealed) == 0 || len(sealed) > maxEnvelopeBytes {
			return nil, E(http.StatusBadRequest, "bad_copy", "A copy for an app is malformed.")
		}
		// A copy the message has no business carrying is left behind, not
		// refused: mentioning an app that cannot see the channel still sends
		// the message, the app just never reads it.
		bot, err := s.Store.UserByID(ctx, id)
		if err != nil || bot.Flags&store.FlagBot == 0 {
			continue
		}
		addressed := hasID(body.Mentions, id) || repliedTo == id || (ch.Type == "dm" && hasID(ch.Recipients, id))
		if !addressed {
			continue
		}
		if ok, err := s.canAccess(r, bot, ch, NeedView); err != nil || !ok {
			continue
		}
		out[id] = sealed
	}
	return out, nil
}

// pollBody is what a person's poll tells the server: how many answers it has
// (their ids run from 1), how many hours it runs, and whether more than one
// answer may be picked. The question and the answers' words never leave the
// ciphertext.
type pollBody struct {
	Answers     *int  `json:"answer_count"`
	Duration    *int  `json:"duration"`
	Multiselect *bool `json:"allow_multiselect"`
}

var errBadPoll = E(http.StatusBadRequest, "bad_poll", "A poll has 2 to 10 answers and runs from 1 hour to 2 weeks.")

// personPoll reads a message's poll, if it has one. Anything but an object of
// the right shape is refused, down to a flag that is not true or false.
func personPoll(raw json.RawMessage, now time.Time) (*store.NewPoll, error) {
	if len(raw) == 0 || string(raw) == "null" {
		return nil, nil
	}
	var p pollBody
	if err := json.Unmarshal(raw, &p); err != nil || p.Answers == nil || p.Duration == nil {
		return nil, errBadPoll
	}
	if *p.Answers < minPollAnswers || *p.Answers > maxPollAnswers || *p.Duration < 1 || *p.Duration > maxPollHours {
		return nil, errBadPoll
	}
	return &store.NewPoll{Answers: *p.Answers, Multiselect: p.Multiselect != nil && *p.Multiselect, ExpiresAt: now.Add(time.Duration(*p.Duration) * time.Hour).UnixMilli()}, nil
}

// messageUpdateEvent is message_update's payload: the message as it is now,
// and what it mentioned before the edit.
type messageUpdateEvent struct {
	*store.Message
	Was mentionsBefore `json:"was"`
}

type mentionsBefore struct {
	Mentions        []ids.ID `json:"mentions"`
	MentionRoles    []ids.ID `json:"mention_roles"`
	MentionEveryone bool     `json:"mention_everyone"`
}

// bulkDeleteEvent is message_delete_bulk's payload: the ids, and what those
// of them that mentioned anyone mentioned, for readers whose unread badges
// counted messages they never loaded.
func (s *Server) bulkDeleteEvent(ctx context.Context, ch ids.ID, d *store.Deleted) map[string]any {
	ev := map[string]any{"channel_id": ch, "ids": d.IDs, "mentions": d.Mentions}
	s.withLastMessage(ctx, ev, ch)
	return ev
}

// withLastMessage adds where a channel's newest message now is to an event
// that takes messages away. Taking the newest one left clients pointing at a
// message that was gone, and a channel whose last message nobody can read up
// to stays unread however long it is looked at. Left out when the channel
// cannot be read, so a client keeps what it has.
func (s *Server) withLastMessage(ctx context.Context, ev map[string]any, chID ids.ID) {
	if ch, err := s.Store.ChannelByID(ctx, chID); err == nil {
		ev["last_message_id"] = ch.LastMessageID
	}
}

// allowedRoleMentions keeps the role mentions a message may make: roles of the
// channel's own server other than @everyone, which has a flag of its own, and
// of those only the ones anyone may mention, unless the author may mention
// everyone here. The list is what unread badges count, so a role nobody may
// ping is not pinged by a client that lists it anyway. Conversations have no
// roles.
func (s *Server) allowedRoleMentions(ctx context.Context, me *store.User, ch *store.Channel, want []ids.ID) []ids.ID {
	want = dedupeIDs(want, maxMentions)
	if len(want) == 0 || s.isDM(ch) || ch.ServerID == nil {
		return []ids.ID{}
	}
	roles, err := s.Store.Roles(ctx, *ch.ServerID)
	if err != nil {
		return []ids.ID{}
	}
	byID := make(map[ids.ID]*store.Role, len(roles))
	for _, r := range roles {
		byID[r.ID] = r
	}
	anyRole := false
	if s.ChannelAccess != nil {
		anyRole, _ = s.ChannelAccess(ctx, me, ch, "mention_everyone")
	}
	out := make([]ids.ID, 0, len(want))
	for _, id := range want {
		if r := byID[id]; r != nil && r.ID != *ch.ServerID && (r.Mentionable || anyRole) {
			out = append(out, id)
		}
	}
	return out
}

// embedFlags adds Suppress Embeds to a message whose author may not embed
// links in the channel. Previews are made by the sender's app and sealed in
// the message, where the server cannot see them, so this flag is how the
// server holds Embed Links: every app leaves a flagged message's previews
// out, and the author cannot clear it (handleMessageEdit).
func (s *Server) embedFlags(ctx context.Context, me *store.User, ch *store.Channel, flags int64) int64 {
	if s.isDM(ch) || s.ChannelAccess == nil {
		return flags
	}
	if ok, _ := s.ChannelAccess(ctx, me, ch, NeedEmbed); !ok {
		return flags | store.MsgFlagSuppressEmbeds
	}
	return flags
}

// mayEmbed reports whether someone may embed links in a channel now.
func (s *Server) mayEmbed(ctx context.Context, ch *store.Channel, uid ids.ID) bool {
	if s.isDM(ch) {
		return true
	}
	p, err := s.Store.PermsIn(ctx, ch, uid)
	return err == nil && p.Has(perms.EmbedLinks)
}

func dedupeIDs(list []ids.ID, max int) []ids.ID {
	seen := map[ids.ID]bool{}
	out := make([]ids.ID, 0, len(list))
	for _, id := range list {
		if id.IsZero() || seen[id] {
			continue
		}
		seen[id] = true
		out = append(out, id)
		if len(out) >= max {
			break
		}
	}
	return out
}

// dmBlocked answers 403 when the caller and the other person in a 1:1 DM have
// blocked each other, so a block also closes a conversation that already exists.
func (s *Server) dmBlocked(w http.ResponseWriter, r *http.Request, ch *store.Channel, me *store.User) bool {
	blocked, err := s.Store.DMBlocked(r.Context(), ch, me.ID)
	if err != nil {
		WriteError(w, err)
		return true
	}
	if blocked {
		WriteError(w, E(http.StatusForbidden, "blocked", "You cannot message this user."))
		return true
	}
	return false
}

// checkEnvelope validates the ciphertext against the channel's conversation.
// Edits use the channel's current conversation too: its old server key may
// belong to people who can no longer read a channel that became private.
func (s *Server) checkEnvelope(w http.ResponseWriter, r *http.Request, ch *store.Channel, body *messageBody) (*envelope, bool) {
	// told apart from a malformed envelope, so the sender can say what to do
	if len(body.Ciphertext) > maxEnvelopeBytes {
		WriteError(w, errMessageTooLong)
		return nil, false
	}
	env, ok := validEnvelope(body.Ciphertext)
	if !ok {
		WriteError(w, E(http.StatusBadRequest, "bad_envelope", "The encrypted message envelope is malformed."))
		return nil, false
	}
	conv, err := s.convForChannel(r, ch)
	if err != nil {
		WriteError(w, err)
		return nil, false
	}
	if env.Conv != conv || body.KeyConvID != conv || env.Epoch != body.KeyEpoch {
		WriteError(w, E(http.StatusBadRequest, "wrong_conversation", "The message is encrypted for a different conversation."))
		return nil, false
	}
	latest, exists, err := s.Store.LatestEpoch(r.Context(), conv)
	if err != nil {
		WriteError(w, err)
		return nil, false
	}
	if !exists || env.Epoch > latest {
		WriteError(w, E(http.StatusBadRequest, "unknown_epoch", "That key epoch does not exist yet."))
		return nil, false
	}
	if env.Epoch < latest {
		WriteError(w, store.ErrStaleEpoch)
		return nil, false
	}
	return env, true
}

func (s *Server) handleMessages(w http.ResponseWriter, r *http.Request) {
	ch, me := s.channel(w, r, NeedView)
	if ch == nil {
		return
	}
	// without Read Message History a channel shows only what is said while
	// one is in it: what came before answers as nothing, not as an error
	if ok, err := s.canAccess(r, me, ch, NeedHistory); err != nil || !ok {
		WriteJSON(w, http.StatusOK, []*store.Message{})
		return
	}
	q := store.MessageQuery{}
	get := func(k string) ids.ID {
		v := r.URL.Query().Get(k)
		if v == "" {
			return 0
		}
		id, _ := ids.Parse(v)
		return id
	}
	// ?ids= asks for particular messages, as a reader quoting replies does
	// for originals it has not loaded; what is missing from the answer is gone
	if raw := r.URL.Query().Get("ids"); raw != "" {
		var want []ids.ID
		for _, part := range strings.Split(raw, ",") {
			if id, err := ids.Parse(strings.TrimSpace(part)); err == nil {
				want = append(want, id)
			}
		}
		list, err := s.Store.MessagesByIDs(r.Context(), ch.ID, dedupeIDs(want, maxLookupIDs), me.ID)
		if err != nil {
			WriteError(w, err)
			return
		}
		s.withRefs(r.Context(), me.ID, list...)
		s.withServerExtras(r.Context(), ch, list)
		WriteJSON(w, http.StatusOK, list)
		return
	}
	q.Before, q.After, q.Around = get("before"), get("after"), get("around")
	if l := r.URL.Query().Get("limit"); l != "" {
		q.Limit, _ = strconv.Atoi(l)
	}
	list, err := s.Store.Messages(r.Context(), ch.ID, q, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	s.withRefs(r.Context(), me.ID, list...)
	s.withServerExtras(r.Context(), ch, list)
	WriteJSON(w, http.StatusOK, list)
}

// withServerExtras fills in, for a page of a server channel, the threads its
// messages started and which of an announcement channel's have been published.
func (s *Server) withServerExtras(ctx context.Context, ch *store.Channel, list []*store.Message) {
	if ch.ServerID == nil || len(list) == 0 {
		return
	}
	if store.HoldsThreads(ch.Type) {
		if err := s.Store.ThreadSummaries(ctx, list); err != nil {
			s.Log.Warn("thread summaries", "err", err)
		}
	}
	if ch.Type == store.ChannelAnnouncement {
		for _, m := range list {
			m.Published = m.Flags&store.MsgFlagPublished != 0
		}
	}
}

// maxLookupIDs is the most messages one ?ids= lookup answers.
const maxLookupIDs = 100

// withRefs fills in the messages that msgs reply to (store.WithReferences).
// A failure leaves them unfilled, which readers take as "look it up".
func (s *Server) withRefs(ctx context.Context, viewer ids.ID, msgs ...*store.Message) {
	if err := s.Store.WithReferences(ctx, msgs, viewer); err != nil {
		s.Log.Warn("message references", "err", err)
	}
}

func (s *Server) handleMessageGet(w http.ResponseWriter, r *http.Request) {
	ch, me := s.channel(w, r, NeedHistory)
	if ch == nil {
		return
	}
	mid, err := ids.Parse(r.PathValue("mid"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	m, err := s.Store.MessageByID(r.Context(), mid, me.ID)
	if err != nil || m.ChannelID != ch.ID {
		WriteError(w, ErrNotFound)
		return
	}
	s.withRefs(r.Context(), me.ID, m)
	s.withServerExtras(r.Context(), ch, []*store.Message{m})
	WriteJSON(w, http.StatusOK, m)
}

func (s *Server) handleMessageCreate(w http.ResponseWriter, r *http.Request) {
	ch, me := s.channel(w, r, NeedSend)
	if ch == nil {
		return
	}
	// a forum holds posts, each a thread with a first message of its own
	if store.IsForum(ch.Type) {
		WriteError(w, E(http.StatusBadRequest, "forum_posts_only", "Start a post to say something in a forum."))
		return
	}
	var body messageBody
	if err := ReadJSON(w, r, &body, maxEnvelopeBytes+16<<10); err != nil {
		WriteError(w, err)
		return
	}
	if !s.messageAllowed(w, "msg:", me, ch) {
		return
	}
	if len(body.Nonce) > maxNonce {
		WriteError(w, E(http.StatusBadRequest, "bad_nonce", "Nonce is too long."))
		return
	}
	ctx := r.Context()
	if body.Nonce != "" {
		if existing, err := s.Store.MessageByNonce(ctx, ch.ID, me.ID, body.Nonce, me.ID); err == nil {
			WriteJSON(w, http.StatusOK, existing)
			return
		}
	}
	if wait := s.slowmodeLeft(ctx, ch, me); wait > 0 {
		w.Header().Set("Retry-After", retryAfter(wait))
		WriteError(w, E(http.StatusTooManyRequests, "slowmode", "Slow mode is on: wait before sending another message."))
		return
	}
	if s.dmBlocked(w, r, ch, me) {
		return
	}
	if !s.mayTalkYet(w, r, ch, me) {
		return
	}
	env, ok := s.checkEnvelope(w, r, ch, &body)
	if !ok {
		return
	}
	files, previews := 0, 0
	for _, a := range body.Attachments {
		if a.Preview {
			previews++
		} else {
			files++
		}
	}
	if files > maxAttachments {
		WriteError(w, E(http.StatusBadRequest, "too_many_attachments", "At most 10 files per message."))
		return
	}
	// at most one small copy for each file, and none on their own
	if previews > files {
		WriteError(w, E(http.StatusBadRequest, "bad_attachment", "A message has at most one preview for each of its files."))
		return
	}
	if !s.isDM(ch) && body.MentionEveryone && s.ChannelAccess != nil {
		if ok, _ := s.ChannelAccess(ctx, me, ch, "mention_everyone"); !ok {
			body.MentionEveryone = false
		}
	}
	if !s.isDM(ch) && len(body.Attachments) > 0 && s.ChannelAccess != nil {
		if ok, _ := s.ChannelAccess(ctx, me, ch, NeedAttach); !ok {
			WriteError(w, E(http.StatusForbidden, "missing_permission", "You cannot attach files here."))
			return
		}
	}
	poll, err := personPoll(body.Poll, time.Now())
	if err != nil {
		WriteError(w, err)
		return
	}
	// anyone in a conversation may ask it something; a server says who may
	if poll != nil && !s.isDM(ch) && s.ChannelAccess != nil {
		if ok, _ := s.ChannelAccess(ctx, me, ch, NeedPolls); !ok {
			WriteError(w, E(http.StatusForbidden, "cannot_create_polls", "You cannot create polls here."))
			return
		}
	}
	m := &store.Message{ChannelID: ch.ID, AuthorID: me.ID, Type: "default", Ciphertext: body.Ciphertext, KeyConvID: &env.Conv, KeyEpoch: &env.Epoch,
		Mentions: dedupeIDs(body.Mentions, maxMentions), MentionRoles: s.allowedRoleMentions(ctx, me, ch, body.MentionRoles), MentionEveryone: body.MentionEveryone, Nonce: body.Nonce, Flags: s.embedFlags(ctx, me, ch, body.Flags&store.MsgFlagSuppressEmbeds), NewPoll: poll}
	// AutoMod's rules about what the server can see for itself: how many
	// people a message pings, and how fast its sender is going
	if !s.isDM(ch) {
		pings := len(m.Mentions) + len(m.MentionRoles)
		if m.MentionEveryone {
			pings++
		}
		if v := s.checkMessageRules(r, ch, me, pings); v.blocked != nil {
			writeAutomodBlocked(w, v.blocked, v.message, "This server's AutoMod stopped that message.")
			return
		}
	}
	// writing in a thread is joining it, and opens it again if it had closed
	if store.IsThread(ch.Type) && !s.threadActivity(w, r, ch, me) {
		return
	}
	if body.ReplyToID != nil {
		target, err := s.Store.MessageByID(ctx, *body.ReplyToID, me.ID)
		if err != nil || target.ChannelID != ch.ID {
			WriteError(w, E(http.StatusBadRequest, "bad_reply", "The message you are replying to is not in this channel."))
			return
		}
		m.ReplyToID = body.ReplyToID
		m.Type = "reply"
	}
	// the files a forward may carry over from its original
	forwarded := map[ids.ID]bool{}
	if body.Forward != nil {
		// a forward is the message passed on and nothing else
		if body.ReplyToID != nil || poll != nil {
			WriteError(w, E(http.StatusBadRequest, "bad_forward", "A forward can't also be a reply or a poll."))
			return
		}
		if ok, retry := s.limForward.Allow("fwd:" + me.ID.String()); !ok {
			w.Header().Set("Retry-After", retryAfter(retry))
			WriteError(w, ErrRateLimited)
			return
		}
		src, ok := s.forwardSource(r, me, body.Forward.ChannelID, body.Forward.MessageID)
		if !ok {
			WriteError(w, E(http.StatusBadRequest, "bad_forward", "The message you are forwarding is not one you can read."))
			return
		}
		for _, a := range src.Attachments {
			forwarded[a.BlobID] = true
		}
		// a forward pings nobody: it is someone else's words, passed on
		m.Mentions, m.MentionRoles, m.MentionEveryone = nil, nil, false
		m.Flags |= store.MsgFlagForwarded
	}
	copies, err := s.checkBotCopies(r, ch, &body, m.ReplyToID)
	if err != nil {
		WriteError(w, err)
		return
	}
	seenBlobs := map[ids.ID]bool{}
	for _, a := range body.Attachments {
		if seenBlobs[a.BlobID] {
			continue // the same file listed twice is attached once
		}
		seenBlobs[a.BlobID] = true
		b, err := s.Store.BlobByID(ctx, a.BlobID)
		if err == nil && forwarded[a.BlobID] {
			m.Attachments = append(m.Attachments, store.Attachment{BlobID: a.BlobID, Size: b.Size})
			continue
		}
		if err != nil || b.Kind != "attachment" || b.OwnerID == nil || *b.OwnerID != me.ID {
			WriteError(w, E(http.StatusBadRequest, "bad_attachment", "One of the attachments is not yours or does not exist."))
			return
		}
		if a.Preview && b.Size > maxPreviewBytes {
			WriteError(w, E(http.StatusBadRequest, "bad_attachment", "A preview is too large to be one."))
			return
		}
		if _, err := s.Store.AttachmentByBlob(ctx, a.BlobID); err == nil {
			WriteError(w, E(http.StatusBadRequest, "bad_attachment", "An attachment was already used by another message."))
			return
		}
		m.Attachments = append(m.Attachments, store.Attachment{BlobID: a.BlobID, Size: b.Size})
	}
	if err := s.Store.InsertEncryptedMessage(ctx, m); err != nil {
		if errors.Is(err, store.ErrAlreadyExists) {
			if existing, err := s.Store.MessageByNonce(ctx, ch.ID, me.ID, body.Nonce, me.ID); err == nil {
				WriteJSON(w, http.StatusOK, existing)
				return
			}
		}
		WriteError(w, err)
		return
	}
	// the copies for bots go in before anyone is told of the message, so a
	// bot hearing of it can read it
	if len(copies) > 0 {
		if err := s.Store.PutBotCopies(ctx, m.ID, copies); err != nil {
			WriteError(w, err)
			return
		}
	}
	if s.isDM(ch) {
		// A conversation someone closed, or has not been shown yet because
		// nothing was said in it, comes back once it has something new in it.
		// This waits for the message to be stored, so one refused above does
		// not put an empty conversation in front of them.
		s.revealDM(ctx, ch)
	}
	full, err := s.Store.MessageByID(ctx, m.ID, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	s.withRefs(ctx, me.ID, full)
	// a forum post goes up for everyone with its first message, before it
	if store.IsThread(ch.Type) {
		s.announcePost(ctx, ch, ch.LastMessageID == nil)
	}
	s.Bcast.EmitChannel(ch.ID, "message_create", full)
	s.Bcast.KeysMaybeNeeded(env.Conv)
	// the people a message in a private thread mentions are brought into it
	if ch.Type == store.ChannelPrivateThread {
		s.mentionedIntoThread(r, ch, me, m.Mentions)
	}
	WriteJSON(w, http.StatusCreated, full)
}

func (s *Server) handleMessageEdit(w http.ResponseWriter, r *http.Request) {
	// Removing a message's embeds is moderation as much as editing, so it
	// needs only sight of the channel; new words need Send, checked below.
	ch, me := s.channel(w, r, NeedView)
	if ch == nil {
		return
	}
	mid, err := ids.Parse(r.PathValue("mid"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	m, err := s.Store.MessageByID(r.Context(), mid, me.ID)
	if err != nil || m.ChannelID != ch.ID {
		WriteError(w, ErrNotFound)
		return
	}
	var body messageBody
	if err := ReadJSON(w, r, &body, maxEnvelopeBytes+16<<10); err != nil {
		WriteError(w, err)
		return
	}
	if !s.messageAllowed(w, "edit:", me, ch) {
		return
	}
	if len(body.Ciphertext) == 0 || string(body.Ciphertext) == "null" {
		// flags-only edit (e.g. suppress embeds): the author, or whoever may
		// delete the message, which covers a webhook's that has no author here
		if m.AuthorID != me.ID && !s.canDeleteOthers(r, ch, me) {
			WriteError(w, ErrForbidden)
			return
		}
		flags := (m.Flags &^ store.MsgFlagSuppressEmbeds) | (body.Flags & store.MsgFlagSuppressEmbeds)
		// previews hidden because the author may not embed links here stay
		// hidden, whoever asks to show them
		if flags&store.MsgFlagSuppressEmbeds == 0 && m.WebhookID == nil && !s.mayEmbed(r.Context(), ch, m.AuthorID) {
			flags |= store.MsgFlagSuppressEmbeds
		}
		if err := s.Store.SetMessageFlags(r.Context(), mid, flags); err != nil {
			WriteError(w, err)
			return
		}
	} else {
		if ok, err := s.canAccess(r, me, ch, NeedSend); err != nil || !ok {
			WriteError(w, E(http.StatusForbidden, "missing_permission", "You do not have permission to do that here."))
			return
		}
		if m.AuthorID != me.ID || m.Type != "default" && m.Type != "reply" {
			WriteError(w, ErrForbidden)
			return
		}
		// the question and answers are what people voted on
		if m.Poll != nil {
			WriteError(w, E(http.StatusBadRequest, "poll_edit", "A poll cannot be edited."))
			return
		}
		if s.dmBlocked(w, r, ch, me) {
			return
		}
		env, ok := s.checkEnvelope(w, r, ch, &body)
		if !ok {
			return
		}
		// an edit is held to what sending it would have been: without the
		// permission, @everyone in the new text pings nobody, as it did not
		// the first time
		if !s.isDM(ch) && body.MentionEveryone && s.ChannelAccess != nil {
			if ok, _ := s.ChannelAccess(r.Context(), me, ch, "mention_everyone"); !ok {
				body.MentionEveryone = false
			}
		}
		// readers open a message with the key its row names, so the row follows the new envelope
		// an edit carries the copies for bots again; ones it leaves out go
		copies, err := s.checkBotCopies(r, ch, &body, m.ReplyToID)
		if err != nil {
			WriteError(w, err)
			return
		}
		if err := s.Store.PutBotCopies(r.Context(), mid, copies); err != nil {
			WriteError(w, err)
			return
		}
		if err := s.Store.UpdateEncryptedMessage(r.Context(), mid, body.Ciphertext, env.Conv, env.Epoch, dedupeIDs(body.Mentions, maxMentions), s.allowedRoleMentions(r.Context(), me, ch, body.MentionRoles), body.MentionEveryone, time.Now().UnixMilli()); err != nil {
			WriteError(w, err)
			return
		}
		// new words are held to Embed Links as new messages are
		if flags := s.embedFlags(r.Context(), me, ch, m.Flags); flags != m.Flags {
			if err := s.Store.SetMessageFlags(r.Context(), mid, flags); err != nil {
				WriteError(w, err)
				return
			}
		}
	}
	updated, err := s.Store.MessageByID(r.Context(), mid, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	// Reactions carry a "me" flag for whoever loaded them, so everyone gets a
	// copy loaded for no one rather than the editor's. What it mentioned before
	// goes along: an edit can add or take away a mention an unread badge
	// counted, and a reader who never loaded the message cannot tell which.
	if shared, err := s.Store.MessageByID(r.Context(), mid, 0); err == nil {
		s.withRefs(r.Context(), 0, shared)
		s.Bcast.EmitChannel(ch.ID, "message_update", messageUpdateEvent{shared, mentionsBefore{m.Mentions, m.MentionRoles, m.MentionEveryone}})
	}
	s.withRefs(r.Context(), me.ID, updated)
	WriteJSON(w, http.StatusOK, updated)
}

func (s *Server) handleMessageDelete(w http.ResponseWriter, r *http.Request) {
	ch, me := s.channel(w, r, NeedView)
	if ch == nil {
		return
	}
	mid, err := ids.Parse(r.PathValue("mid"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	m, err := s.Store.MessageByID(r.Context(), mid, me.ID)
	if err != nil || m.ChannelID != ch.ID {
		WriteError(w, ErrNotFound)
		return
	}
	if m.AuthorID != me.ID && !s.canDeleteOthers(r, ch, me) {
		WriteError(w, ErrForbidden)
		return
	}
	if m.AuthorID != me.ID && mutedFor(w, r) {
		return
	}
	if err := s.Store.DeleteMessage(r.Context(), mid); err != nil {
		WriteError(w, err)
		return
	}
	s.auditDeletes(r, ch, me, map[ids.ID]ids.ID{mid: m.AuthorID})
	if ch.Type == store.ChannelAnnouncement {
		s.originalsGone(r.Context(), []ids.ID{mid})
	}
	// what it mentioned goes along: a reader who never loaded it had it
	// counted in an unread badge, and nothing else says to take it back
	ev := map[string]any{"id": mid, "channel_id": ch.ID, "author_id": m.AuthorID,
		"mentions": m.Mentions, "mention_roles": m.MentionRoles, "mention_everyone": m.MentionEveryone}
	s.withLastMessage(r.Context(), ev, ch.ID)
	s.Bcast.EmitChannel(ch.ID, "message_delete", ev)
	w.WriteHeader(http.StatusNoContent)
}

// handleMessageBulkDelete removes up to maxBulkDelete messages of one channel.
// Every message is checked before anything goes: one the caller may not
// delete refuses the whole request. Ids already gone, or from another
// channel, are skipped; the response lists the ones deleted.
func (s *Server) handleMessageBulkDelete(w http.ResponseWriter, r *http.Request) {
	ch, me := s.channel(w, r, NeedView)
	if ch == nil {
		return
	}
	var body struct {
		IDs []ids.ID `json:"ids"`
	}
	if err := ReadJSON(w, r, &body, 16<<10); err != nil {
		WriteError(w, err)
		return
	}
	list := dedupeIDs(body.IDs, maxBulkDelete+1)
	if len(list) == 0 || len(list) > maxBulkDelete {
		WriteError(w, E(http.StatusBadRequest, "bad_ids", "Pick between 1 and 100 messages to delete."))
		return
	}
	authors, err := s.Store.MessageAuthors(r.Context(), ch.ID, list)
	if err != nil {
		WriteError(w, err)
		return
	}
	found := make([]ids.ID, 0, len(authors))
	others := false
	for _, id := range list {
		author, ok := authors[id]
		if !ok {
			continue
		}
		found = append(found, id)
		others = others || author != me.ID
	}
	if others && !s.canDeleteOthers(r, ch, me) {
		WriteError(w, ErrForbidden)
		return
	}
	deleted, err := s.Store.DeleteMessages(r.Context(), ch.ID, found)
	if err != nil {
		WriteError(w, err)
		return
	}
	if len(deleted.IDs) > 0 {
		gone := map[ids.ID]ids.ID{}
		for _, id := range deleted.IDs {
			gone[id] = authors[id]
		}
		s.auditDeletes(r, ch, me, gone)
		if ch.Type == store.ChannelAnnouncement {
			s.originalsGone(r.Context(), deleted.IDs)
		}
		s.Bcast.EmitChannel(ch.ID, "message_delete_bulk", s.bulkDeleteEvent(r.Context(), ch.ID, deleted))
	}
	WriteJSON(w, http.StatusOK, map[string]any{"ids": deleted.IDs})
}

// canDeleteOthers says whether the caller may remove messages other people
// wrote in a channel: a group DM's owner, anyone with Manage Messages in a
// server channel, and whoever runs Nexa.
func (s *Server) canDeleteOthers(r *http.Request, ch *store.Channel, me *store.User) bool {
	if ch.Type == "group_dm" && ownsGroup(ch, me) {
		return true
	}
	if !s.isDM(ch) && s.ChannelAccess != nil {
		if ok, _ := s.ChannelAccess(r.Context(), me, ch, NeedManage); ok {
			return true
		}
	}
	return me.Flags&store.FlagAdmin != 0
}

func (s *Server) handleAck(w http.ResponseWriter, r *http.Request) {
	ch, me := s.channel(w, r, NeedView)
	if ch == nil {
		return
	}
	mid, err := ids.Parse(r.PathValue("mid"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	var body struct {
		Manual bool `json:"manual"`
	}
	if r.ContentLength > 0 {
		_ = ReadJSON(w, r, &body, 1<<10)
	}
	if err := s.Store.Ack(r.Context(), me.ID, ch.ID, mid, body.Manual); err != nil {
		WriteError(w, err)
		return
	}
	states, err := s.Store.ReadStatesFor(r.Context(), me.ID, []ids.ID{ch.ID})
	if err != nil || len(states) == 0 {
		WriteError(w, err)
		return
	}
	states[0].Manual = body.Manual
	s.Bcast.EmitUsers([]ids.ID{me.ID}, "read_state", states[0])
	WriteJSON(w, http.StatusOK, states[0])
}

// parseEmoji takes a reaction key from the path, which the mux has already
// unescaped once (unescaping it again turned "100%" into an error and a key
// sent as "%2541" into "A"). A key is a custom emoji — `:name:id`, or
// `a:name:id` for an animated one — or an emoji sequence; anything else is
// text, and text is not a reaction.
func parseEmoji(raw string) (string, bool) {
	e := strings.TrimSpace(raw)
	if e == "" || len(e) > maxEmojiLen {
		return "", false
	}
	if customReaction.MatchString(e) || isEmojiSequence(e) {
		return e, true
	}
	return "", false
}

var customReaction = regexp.MustCompile(`^a?:?[A-Za-z0-9_]{1,32}:[0-9]{1,20}$`)

// isEmojiSequence reports whether s is made only of what emoji are made of:
// pictographs and symbols, the joiners and selectors that combine them, skin
// tones, flag letters and tags, and the digits and marks of keycaps.
func isEmojiSequence(s string) bool {
	keycap := strings.ContainsRune(s, '\u20E3')
	for _, r := range s {
		switch {
		case r >= 0x1F000 && r <= 0x1FAFF, r >= 0x2000 && r <= 0x3300, r == 0x00A9, r == 0x00AE:
		case r == 0x200D, r == 0xFE0F, r == 0xFE0E, r == 0x20E3, r >= 0xE0020 && r <= 0xE007F:
		case keycap && (r == '#' || r == '*' || (r >= '0' && r <= '9')):
		default:
			return false
		}
	}
	return true
}

// reactionTarget loads the channel, message and emoji of a reaction route.
// Only adding a reaction needs Add Reactions: taking your own back needs to see
// the channel, and listing who reacted needs its history.
func (s *Server) reactionTarget(w http.ResponseWriter, r *http.Request, need string) (*store.Channel, *store.User, *store.Message, string) {
	ch, me := s.channel(w, r, need)
	if ch == nil {
		return nil, nil, nil, ""
	}
	mid, err := ids.Parse(r.PathValue("mid"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil, nil, nil, ""
	}
	m, err := s.Store.MessageByID(r.Context(), mid, me.ID)
	if err != nil || m.ChannelID != ch.ID {
		WriteError(w, ErrNotFound)
		return nil, nil, nil, ""
	}
	emoji, ok := parseEmoji(r.PathValue("emoji"))
	if !ok {
		WriteError(w, E(http.StatusBadRequest, "bad_emoji", "That emoji is malformed."))
		return nil, nil, nil, ""
	}
	return ch, me, m, emoji
}

func (s *Server) handleReactionAdd(w http.ResponseWriter, r *http.Request) {
	// Reacting needs Read Message History; a new reaction needs Add Reactions
	// too, while adding oneself to one already there does not
	ch, me, m, emoji := s.reactionTarget(w, r, NeedHistory)
	if ch == nil || s.dmBlocked(w, r, ch, me) {
		return
	}
	if !hasReaction(m, emoji) {
		if ok, _ := s.canAccess(r, me, ch, NeedReact); !ok {
			WriteError(w, E(http.StatusForbidden, "missing_permission", "You cannot add new reactions here."))
			return
		}
	}
	// someone else's emoji from another server, where the server allows it;
	// joining a reaction already there is always fine
	if !s.isDM(ch) && s.externalEmoji(r.Context(), ch, emoji) && !hasReaction(m, emoji) {
		if ok, _ := s.canAccess(r, me, ch, NeedExternalEmoji); !ok {
			WriteError(w, E(http.StatusForbidden, "external_emoji", "This server does not allow emoji from other servers."))
			return
		}
	}
	added, err := s.Store.AddReaction(r.Context(), m.ID, me.ID, emoji)
	if err != nil {
		if errors.Is(err, store.ErrLimit) {
			WriteError(w, E(http.StatusBadRequest, "too_many_reactions", "A message can have at most 20 different reactions."))
			return
		}
		WriteError(w, err)
		return
	}
	if added {
		s.Bcast.EmitChannel(ch.ID, "reaction_add", map[string]any{"channel_id": ch.ID, "message_id": m.ID, "user_id": me.ID, "emoji": emoji})
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleReactionRemove(w http.ResponseWriter, r *http.Request) {
	ch, me, m, emoji := s.reactionTarget(w, r, NeedView)
	if ch == nil {
		return
	}
	removed, err := s.Store.RemoveReaction(r.Context(), m.ID, me.ID, emoji)
	if err != nil {
		WriteError(w, err)
		return
	}
	if removed {
		s.Bcast.EmitChannel(ch.ID, "reaction_remove", map[string]any{"channel_id": ch.ID, "message_id": m.ID, "user_id": me.ID, "emoji": emoji})
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleReactors(w http.ResponseWriter, r *http.Request) {
	ch, _, m, emoji := s.reactionTarget(w, r, NeedHistory)
	if ch == nil {
		return
	}
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	users, err := s.Store.Reactors(r.Context(), m.ID, emoji, limit)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, users)
}

var _ = auth.UserFrom

// hasReaction reports whether a message already carries a reaction.
func hasReaction(m *store.Message, emoji string) bool {
	for _, r := range m.Reactions {
		if r.Emoji == emoji {
			return true
		}
	}
	return false
}

// messageAllowed spends a message (or an edit, whose kind is "edit:") from
// the account's allowance in this channel and across every channel, so a
// script cannot spread a flood over many conversations.
func (s *Server) messageAllowed(w http.ResponseWriter, kind string, me *store.User, ch *store.Channel) bool {
	for _, try := range []func() (bool, time.Duration){
		func() (bool, time.Duration) { return s.limMsg.Allow(kind + me.ID.String() + ":" + ch.ID.String()) },
		func() (bool, time.Duration) {
			// an app answers in many servers at once and has the bot API's
			// own global limit instead
			if me.Flags&store.FlagBot != 0 {
				return true, 0
			}
			return s.limMsgAll.Allow("all:" + me.ID.String())
		},
	} {
		if ok, retry := try(); !ok {
			w.Header().Set("Retry-After", retryAfter(retry))
			WriteError(w, ErrRateLimited)
			return false
		}
	}
	return true
}

// forwardSource is the message a forward passes on, when the sender can read
// it where it is: the history of its channel, unlocked if it is a subchat.
func (s *Server) forwardSource(r *http.Request, me *store.User, channelID, messageID ids.ID) (*store.Message, bool) {
	src, err := s.Store.MessageByID(r.Context(), messageID, me.ID)
	// a person's message only: not a system line, a webhook's or an app's
	if err != nil || src.ChannelID != channelID || (src.Type != "default" && src.Type != "reply") || src.WebhookID != nil {
		return nil, false
	}
	ch, err := s.Store.ChannelByID(r.Context(), channelID)
	if err != nil {
		return nil, false
	}
	if ok, err := s.canAccess(r, me, ch, NeedHistory); err != nil || !ok || !s.unlocked(r, ch) {
		return nil, false
	}
	return src, true
}
