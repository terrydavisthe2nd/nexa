package api

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"evilion.xyz/chat/internal/botproto"
	"evilion.xyz/chat/internal/gateway"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/perms"
	"evilion.xyz/chat/internal/store"
)

// A bot's messages: read, sent, edited, deleted, reacted to and pinned. What
// a bot writes is in the clear, as a webhook's is, kept in messages.plain in
// the webhook's shape with the bot as its author; the apps show it under the
// bot's own name and picture and say it is not end-to-end encrypted.

func (s *Server) botMessageRoutes(mux *http.ServeMux) {
	s.botHandle(mux, "GET", "/channels/{channel}/messages", authBot, s.botMessagesList, bucketed("read"))
	s.botHandle(mux, "GET", "/channels/{channel}/messages/{message}", authBot, s.botMessageGet, bucketed("read"))
	s.botHandle(mux, "POST", "/channels/{channel}/messages", authBot, s.botMessageCreate, bucketed("message"))
	s.botHandle(mux, "PATCH", "/channels/{channel}/messages/{message}", authBot, s.botMessageEdit, bucketed("edit"))
	s.botHandle(mux, "DELETE", "/channels/{channel}/messages/{message}", authBot, s.botMessageDelete, bucketed("delete"))
	s.botHandle(mux, "POST", "/channels/{channel}/messages/bulk-delete", authBot, s.botBulkDelete, bucketed("bulk"))
	s.botHandle(mux, "POST", "/channels/{channel}/messages/{message}/crosspost", authBot, s.botCrosspost)
	s.botHandle(mux, "PUT", "/channels/{channel}/messages/{message}/reactions/{emoji}/@me", authBot, s.botReactionAdd, bucketed("reaction"))
	s.botHandle(mux, "DELETE", "/channels/{channel}/messages/{message}/reactions/{emoji}/@me", authBot, s.botReactionRemoveOwn, bucketed("reaction"))
	s.botHandle(mux, "DELETE", "/channels/{channel}/messages/{message}/reactions/{emoji}/{user}", authBot, s.botReactionRemoveUser, bucketed("reaction"))
	s.botHandle(mux, "GET", "/channels/{channel}/messages/{message}/reactions/{emoji}", authBot, s.botReactors, bucketed("read"))
	// clearing a message's reactions and unpinning one share a shape of
	// path (/messages/{message}/reactions, /messages/pins/{message})
	s.botHandle(mux, "DELETE", "/channels/{channel}/messages/{a}/{b}", authBot, func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("a") == "pins" {
			r.SetPathValue("message", r.PathValue("b"))
			s.botPin(false)(w, r)
			return
		}
		if r.PathValue("b") == "reactions" {
			r.SetPathValue("message", r.PathValue("a"))
			s.botReactionsClear(w, r)
			return
		}
		writeBotError(w, dErrUnknownEntity, nil)
	}, bucketed("reaction"))
	s.botHandle(mux, "DELETE", "/channels/{channel}/messages/{message}/reactions/{emoji}", authBot, s.botReactionsClearEmoji, bucketed("reaction"))
	s.botHandle(mux, "GET", "/channels/{channel}/pins", authBot, s.botPinsOld, bucketed("read"))
	s.botHandle(mux, "PUT", "/channels/{channel}/pins/{message}", authBot, s.botPin(true))
	s.botHandle(mux, "DELETE", "/channels/{channel}/pins/{message}", authBot, s.botPin(false))
	s.botHandle(mux, "GET", "/channels/{channel}/messages/pins", authBot, s.botPins, bucketed("read"))
	s.botHandle(mux, "PUT", "/channels/{channel}/messages/pins/{message}", authBot, s.botPin(true))
	s.botHandle(mux, "POST", "/channels/{channel}/typing", authBot, s.botTyping, bucketed("typing"))
	s.botHandle(mux, "GET", "/channels/{channel}/polls/{message}/answers/{answer}", authBot, s.botPollVoters, bucketed("read"))
	s.botHandle(mux, "POST", "/channels/{channel}/polls/{message}/expire", authBot, s.botPollExpire)
}

// botChannel loads {channel} for a bot and checks it may do need there,
// answering the protocol's errors: a channel of a server it is not in is
// Unknown Channel, one it cannot see Missing Access, and one it can see but
// may not act in Missing Permissions.
func (s *Server) botChannel(w http.ResponseWriter, r *http.Request, need string) *store.Channel {
	b := botOf(r)
	id, ok := pathID(r, "channel")
	if !ok {
		writeBotError(w, dErrUnknownChannel, nil)
		return nil
	}
	ch, err := s.Store.ChannelByID(r.Context(), id)
	if err != nil || ch.Type == "subchat" {
		writeBotError(w, dErrUnknownChannel, nil)
		return nil
	}
	if ch.ServerID != nil {
		if member, _ := s.Store.IsMember(r.Context(), *ch.ServerID, b.user.ID); !member {
			writeBotError(w, dErrUnknownChannel, nil)
			return nil
		}
	}
	ok, err = s.canAccess(r, b.user, ch, need)
	if err != nil {
		writeBotError(w, err, nil)
		return nil
	}
	if !ok {
		if store.IsDMLike(ch.Type) {
			writeBotError(w, dErrUnknownChannel, nil)
			return nil
		}
		if canView, _ := s.canAccess(r, b.user, ch, NeedView); canView && need != NeedView {
			writeBotError(w, dErrMissingPerms, nil)
			return nil
		}
		writeBotError(w, dErrMissingAccess, nil)
		return nil
	}
	// Discord requires Connect as well as the history permissions when
	// fetching messages from a voice channel; joining the call is not required.
	if ch.Type == "voice" && need == NeedHistory && !s.botPerms(r.Context(), ch, b.user.ID).Has(perms.Connect) {
		writeBotError(w, dErrMissingPerms, nil)
		return nil
	}
	return ch
}

// botMessage loads {message} of a channel the bot can read.
func (s *Server) botMessage(w http.ResponseWriter, r *http.Request, ch *store.Channel) *store.Message {
	mid, ok := pathID(r, "message")
	if !ok {
		writeBotError(w, dErrUnknownMessage, nil)
		return nil
	}
	m, err := s.Store.MessageByID(r.Context(), mid, botOf(r).user.ID)
	if err != nil || m.ChannelID != ch.ID {
		writeBotError(w, dErrUnknownMessage, nil)
		return nil
	}
	return m
}

// botPerms is what the bot may do in a channel.
func (s *Server) botPerms(ctx context.Context, ch *store.Channel, uid ids.ID) perms.Perm {
	if store.IsDMLike(ch.Type) {
		// in a conversation the bot is in, it does what its people do
		return perms.ViewChannel | perms.SendMessages | perms.ReadMessageHistory | perms.AddReactions | perms.AttachFiles | perms.EmbedLinks | perms.UseExternalEmojis | perms.CreatePolls
	}
	p, err := s.Store.PermsIn(ctx, ch, uid)
	if err != nil {
		return 0
	}
	return p
}

// appCheck runs one of the app's checks that write their own refusal, and
// gives back the refusal as an error.
func appCheck(fn func(w http.ResponseWriter) bool) error {
	bw := &bufferWriter{header: http.Header{}}
	if fn(bw) {
		return nil
	}
	return (&nexaAnswer{status: bw.status, body: bw.buf.Bytes()}).err()
}

func (s *Server) botMessagesList(w http.ResponseWriter, r *http.Request) {
	ch := s.botChannel(w, r, NeedHistory)
	if ch == nil {
		return
	}
	b := botOf(r)
	q := r.URL.Query()
	mq := store.MessageQuery{Limit: 50}
	if l := q.Get("limit"); l != "" {
		n, err := strconv.Atoi(l)
		if err != nil || n < 1 || n > 100 {
			writeBotError(w, dFormError("NUMBER_TYPE_MAX", "int value should be between 1 and 100.", "limit"), nil)
			return
		}
		mq.Limit = n
	}
	at := func(k string) (ids.ID, bool) {
		v := q.Get(k)
		if v == "" {
			return 0, false
		}
		n, err := botproto.Parse(v)
		if err != nil {
			return 0, false
		}
		if n < 0 {
			n = 0
		}
		return ids.ID(n), true
	}
	if id, ok := at("around"); ok {
		mq.Around = id
	} else if id, ok := at("before"); ok {
		if id == 0 {
			// before the start: nothing
			WriteJSON(w, http.StatusOK, []dMessage{})
			return
		}
		mq.Before = id
	} else if id, ok := at("after"); ok {
		mq.After = id
		if id == 0 {
			// after the very start, the oldest first
			mq.After = 1
		}
	}
	list, err := s.Store.Messages(r.Context(), ch.ID, mq, b.user.ID)
	if err != nil {
		writeBotError(w, err, nil)
		return
	}
	s.withServerExtras(r.Context(), ch, list)
	bld := s.builderFor(r, b)
	out := bld.messagesObj(list, ch, msgOpts{content: bld.botReadsContent()})
	// newest first, as the protocol lists them
	for i, j := 0, len(out)-1; i < j; i, j = i+1, j-1 {
		out[i], out[j] = out[j], out[i]
	}
	WriteJSON(w, http.StatusOK, out)
}

func (s *Server) botMessageGet(w http.ResponseWriter, r *http.Request) {
	ch := s.botChannel(w, r, NeedHistory)
	if ch == nil {
		return
	}
	m := s.botMessage(w, r, ch)
	if m == nil {
		return
	}
	s.withServerExtras(r.Context(), ch, []*store.Message{m})
	b := s.builderFor(r, botOf(r))
	WriteJSON(w, http.StatusOK, b.messageObj(m, ch, msgOpts{content: b.botReadsContent()}))
}

// botSend is one message an application is about to post.
type botSend struct {
	ch          *store.Channel
	in          *whInput
	files       []whFile
	interaction *wmInteraction
	// asReply is what the message answers, read from its message_reference
	replyTo *store.Message
	// skipAccess is set for an answer to an interaction, which needs no
	// right to send in the channel: the person who used the command did
	skipAccess bool
	// loading is an answer still on its way (a deferred one), shown
	// thinking and empty until the application fills it in
	loading bool
}

// botPlain builds what an application's message says from a request read
// already, under its bot's name and picture: its words, embeds, components,
// files, stickers and poll (with when it closes, and what opening it takes).
func (s *Server) botPlain(ctx context.Context, ch *store.Channel, bot *store.User, app *store.Application, send *botSend) (*wmPlain, []store.Attachment, error) {
	in := send.in
	stickers, err := s.botStickers(ctx, ch, in.StickerIDs)
	if err != nil {
		return nil, nil, err
	}
	atts, rows := fileAttachments(send.files, in.Attach)
	pl := &wmPlain{Name: bot.DisplayName, AvatarID: bot.AvatarID, Content: in.Content, TTS: in.TTS, Embeds: in.Embeds, Components: in.Components, Attachments: atts,
		ApplicationID: &app.ID, Interaction: send.interaction, Stickers: stickers}
	if pl.Name == "" {
		pl.Name = bot.Username
	}
	if in.Poll != nil {
		expires := time.Now().Add(time.Duration(in.PollHours) * time.Hour)
		in.Poll.Expiry = expires.UTC().Format("2006-01-02T15:04:05.000000+00:00")
		pl.Poll = in.Poll
	}
	s.finishPlain(pl, in.Flags&store.MsgFlagComponentsV2 != 0)
	if !send.loading && !pl.hasBody() && len(pl.Stickers) == 0 {
		return nil, nil, dErrEmpty
	}
	return pl, rows, nil
}

// botStickers resolves the stickers a bot sends: its own server's, or
// those of a pack anyone may use.
func (s *Server) botStickers(ctx context.Context, ch *store.Channel, list []ids.ID) ([]wmStickerItem, error) {
	out := []wmStickerItem{}
	for _, id := range list {
		st, err := s.Store.StickerByID(ctx, id)
		if err != nil {
			return nil, dErr(http.StatusBadRequest, 50081, "Invalid sticker sent")
		}
		pack, err := s.Store.PackByID(ctx, st.PackID)
		if err != nil || pack.Kind == "emoji" {
			return nil, dErr(http.StatusBadRequest, 50081, "Invalid sticker sent")
		}
		own := pack.ServerID != nil && ch.ServerID != nil && *pack.ServerID == *ch.ServerID
		if !own && !pack.Public {
			return nil, dErr(http.StatusBadRequest, 50081, "Invalid sticker sent")
		}
		out = append(out, wmStickerItem{ID: st.ID, Name: st.Name, FormatType: stickerFormatType(st.Format), BlobID: st.BlobID, Format: st.Format})
	}
	return out, nil
}

// botMentions works out whom an application's message pings, as the
// protocol has it: every kind of mention in the text unless allowed_mentions
// says otherwise, @everyone and roles nobody may mention only with Mention
// Everyone, and a reply's author unless replied_user is false.
func (s *Server) botMentions(ctx context.Context, ch *store.Channel, me *store.User, p *wmPlain, am *allowedMentions, replyTo *store.Message) (users, roles []ids.ID, everyone bool) {
	parse := am
	if parse == nil || !parse.set {
		parse = &allowedMentions{set: true, Parse: []string{"users", "roles", "everyone"}}
	}
	users, roles, everyone = s.webhookMentions(ctx, ch, p.texts(), parse)
	if ch.ServerID != nil {
		p := s.botPerms(ctx, ch, me.ID)
		if !p.Has(perms.MentionEveryone) {
			everyone = false
			roles = s.allowedRoleMentions(ctx, me, ch, roles)
		}
	} else {
		roles, everyone = []ids.ID{}, false
	}
	if replyTo != nil && (am == nil || am.RepliedUser == nil || *am.RepliedUser) && replyTo.AuthorID != me.ID && !hasID(users, replyTo.AuthorID) {
		users = append(users, replyTo.AuthorID)
	}
	return users, roles, everyone
}

// postBotMessage posts an application's message in a channel as its bot.
// What it says was read already; this checks it against the channel and the
// bot's rights, stores it and tells everyone.
func (s *Server) postBotMessage(r *http.Request, bot *store.User, app *store.Application, send *botSend) (*store.Message, error) {
	ctx := r.Context()
	ch, in := send.ch, send.in
	p := s.botPerms(ctx, ch, bot.ID)
	if !send.skipAccess {
		if store.IsThread(ch.Type) {
			if !p.Has(perms.SendMessagesInThreads) {
				return nil, dErrMissingPerms
			}
		} else if !p.Has(perms.SendMessages) {
			return nil, dErrMissingPerms
		}
		if store.IsForum(ch.Type) {
			return nil, dErr(http.StatusBadRequest, 40058, "Cannot send a message in a forum channel")
		}
		if ch.Type == "category" {
			return nil, dErrNotText
		}
		if wait := s.slowmodeLeft(ctx, ch, bot); wait > 0 {
			return nil, dErr(http.StatusTooManyRequests, 20016, "This action cannot be performed due to slowmode rate limit.")
		}
		if store.IsDMLike(ch.Type) {
			if blocked, err := s.Store.DMBlocked(ctx, ch, bot.ID); err != nil || blocked {
				return nil, dErrCannotDM
			}
		}
		if err := appCheck(func(w http.ResponseWriter) bool { return s.mayTalkYet(w, r, ch, bot) }); err != nil {
			return nil, err
		}
		if len(send.files) > 0 && !p.Has(perms.AttachFiles) {
			return nil, dErrMissingPerms
		}
		if len(in.Embeds) > 0 && !p.Has(perms.EmbedLinks) {
			return nil, dErrMissingPerms
		}
		if in.Poll != nil && !p.Has(perms.CreatePolls) {
			return nil, dErrMissingPerms
		}
	}
	if send.replyTo != nil && !send.skipAccess && !p.Has(perms.ReadMessageHistory) {
		return nil, dErr(http.StatusForbidden, 160002, "Cannot reply without permission to read message history")
	}
	if in.Nonce != "" && in.EnforceNonce {
		if existing, err := s.Store.MessageByNonce(ctx, ch.ID, bot.ID, in.Nonce, bot.ID); err == nil && time.Since(time.UnixMilli(existing.CreatedAt)) < 10*time.Minute {
			return existing, nil
		}
	}
	pl, rows, err := s.botPlain(ctx, ch, bot, app, send)
	if err != nil {
		return nil, err
	}
	var newPoll *store.NewPoll
	if pl.Poll != nil {
		expires, _ := botproto.ParseTimestamp(pl.Poll.Expiry)
		newPoll = &store.NewPoll{Answers: len(pl.Poll.Answers), Multiselect: pl.Poll.AllowMultiselect, ExpiresAt: expires}
	}
	v2 := in.Flags&store.MsgFlagComponentsV2 != 0
	plain, err := json.Marshal(pl)
	if err != nil {
		return nil, err
	}
	users, roles, everyone := s.botMentions(ctx, ch, bot, pl, in.Mentions, send.replyTo)
	flags := in.Flags & (store.MsgFlagSuppressEmbeds | store.MsgFlagSuppressNotifications | store.MsgFlagComponentsV2)
	if send.loading {
		flags |= store.MsgFlagLoading
	}
	m := &store.Message{ChannelID: ch.ID, AuthorID: bot.ID, Type: "default", Webhook: plain, Mentions: users, MentionRoles: roles, MentionEveryone: everyone,
		Flags: flags, NewPoll: newPoll, Attachments: rows, Nonce: in.Nonce}
	if send.replyTo != nil {
		rid := send.replyTo.ID
		m.ReplyToID, m.Type = &rid, "reply"
	}
	if !send.skipAccess && ch.ServerID != nil {
		pings := len(users) + len(roles)
		if everyone {
			pings++
		}
		if v := s.checkMessageRules(r, ch, bot, pings); v.blocked != nil {
			return nil, dErrAutomodBlocked
		}
	}
	if store.IsThread(ch.Type) {
		if err := appCheck(func(w http.ResponseWriter) bool { return s.threadActivity(w, r, ch, bot) }); err != nil {
			return nil, err
		}
	}
	if err := s.Store.InsertMessage(ctx, m); err != nil {
		if errors.Is(err, store.ErrAlreadyExists) && in.Nonce != "" {
			if existing, err := s.Store.MessageByNonce(ctx, ch.ID, bot.ID, in.Nonce, bot.ID); err == nil {
				return existing, nil
			}
		}
		return nil, err
	}
	if store.IsDMLike(ch.Type) {
		s.revealDM(ctx, ch)
	}
	full, err := s.Store.MessageByID(ctx, m.ID, 0)
	if err != nil {
		return nil, err
	}
	s.withRefs(ctx, 0, full)
	if store.IsThread(ch.Type) {
		s.announcePost(ctx, ch, ch.LastMessageID == nil)
	}
	s.Bcast.EmitChannel(ch.ID, "message_create", full)
	if ch.Type == store.ChannelPrivateThread {
		s.mentionedIntoThread(r, ch, bot, m.Mentions)
	}
	if m.Flags&store.MsgFlagSuppressEmbeds == 0 && !v2 && len(pl.Embeds) == 0 && pl.Content != "" {
		s.unfurlWebhookMessage(m.ID, ch.ID, pl.Content)
	}
	return full, nil
}

// readBotMessage reads a message an application sends: JSON, or multipart
// with its files, checked as the protocol checks it.
func (s *Server) readBotMessage(w http.ResponseWriter, r *http.Request, owner ids.ID, opts whParseOpts) (*whInput, []whFile, error) {
	raw, files, derr := s.readWebhookBody(w, r, owner)
	if derr != nil {
		return nil, nil, derr
	}
	in, errs := parseWebhookBody(raw, opts)
	if !errs.empty() {
		s.dropFiles(files)
		return nil, nil, errs.err()
	}
	if in.Reference != nil && in.Reference.Type == 1 {
		s.dropFiles(files)
		return nil, nil, dFormError("MESSAGE_REFERENCE_TYPE_UNSUPPORTED", "Forwarding messages is not available on this server.", "message_reference", "type")
	}
	return in, files, nil
}

func (s *Server) botMessageCreate(w http.ResponseWriter, r *http.Request) {
	ch := s.botChannel(w, r, NeedView)
	if ch == nil {
		return
	}
	b := botOf(r)
	in, files, err := s.readBotMessage(w, r, b.user.ID, whParseOpts{app: true})
	if err != nil {
		writeBotError(w, err, nil)
		return
	}
	send := &botSend{ch: ch, in: in, files: files}
	if ref := in.Reference; ref != nil {
		target, err := s.Store.MessageByID(r.Context(), ref.MessageID, b.user.ID)
		if err != nil || target.ChannelID != ch.ID || (!ref.ChannelID.IsZero() && ref.ChannelID != ch.ID) {
			if ref.FailIfNotExists {
				s.dropFiles(files)
				writeBotError(w, dFormError("MESSAGE_REFERENCE_UNKNOWN_MESSAGE", "Unknown message", "message_reference", "message_id"), nil)
				return
			}
		} else {
			send.replyTo = target
		}
	}
	m, err := s.postBotMessage(r, b.user, b.app, send)
	if err != nil {
		s.dropFiles(files)
		writeBotError(w, err, nil)
		return
	}
	bld := s.builderFor(r, b)
	WriteJSON(w, http.StatusOK, bld.messageObj(m, ch, msgOpts{content: true}))
}

// plainEdit is an application's message as an edit leaves it.
type plainEdit struct {
	plain *wmPlain
	flags int64
}

// editedPlain applies an edit request to what an application's message
// said: fields the request left out stay, files it no longer lists go, new
// files are added. keep lists the files that stay (nil when the request did
// not say), add the new ones. A message still thinking stops.
func (s *Server) editedPlain(p *wmPlain, m *store.Message, in *whInput, files []whFile) (*plainEdit, []ids.ID, []store.Attachment, error) {
	flags := m.Flags &^ (store.MsgFlagPinned | store.MsgFlagLoading | store.MsgFlagEphemeral)
	if in.Present["flags"] {
		flags = (flags &^ store.MsgFlagSuppressEmbeds) | (in.Flags & store.MsgFlagSuppressEmbeds) | (in.Flags & store.MsgFlagComponentsV2)
	}
	v2 := flags&store.MsgFlagComponentsV2 != 0
	if in.Present["content"] {
		p.Content = in.Content
	}
	if in.Present["embeds"] {
		p.Embeds = in.Embeds
	}
	if in.Present["components"] {
		p.Components = in.Components
	}
	if v2 && (strings.TrimSpace(p.Content) != "" || len(p.Embeds) > 0 || p.Poll != nil) {
		return nil, nil, nil, dFormError("MESSAGE_CANNOT_USE_LEGACY_FIELDS_WITH_COMPONENTS_V2", "The IS_COMPONENTS_V2 flag cannot be used with content, embeds, polls or stickers.", "flags")
	}
	var keepIDs []ids.ID
	if in.Present["attachments"] {
		keepIDs = []ids.ID{}
		metas := map[ids.ID]whAttachReq{}
		for _, a := range in.Attach {
			if id, err := parseBotID(a.ID); err == nil {
				metas[id] = a
			}
		}
		kept := p.Attachments[:0:0]
		for _, a := range p.Attachments {
			meta, ok := metas[a.ID]
			if !ok {
				continue
			}
			if meta.HasDesc {
				a.Description = meta.Description
			}
			if meta.Spoiler != nil {
				a.Spoiler = *meta.Spoiler
			}
			kept = append(kept, a)
			keepIDs = append(keepIDs, a.ID)
		}
		p.Attachments = kept
	}
	added, rows := fileAttachments(files, in.Attach)
	if len(p.Attachments)+len(added) > whMaxFiles {
		return nil, nil, nil, dFormError("BASE_TYPE_MAX_LENGTH", "Must be 10 or fewer in length.", "attachments")
	}
	p.Attachments = append(p.Attachments, added...)
	p.LinkEmbeds = nil
	s.finishPlain(p, v2)
	if !p.hasBody() && len(p.Stickers) == 0 {
		return nil, nil, nil, dErrEmpty
	}
	return &plainEdit{plain: p, flags: flags}, keepIDs, rows, nil
}

// editBotMessage changes what an application's message says, from an edit
// request already read; the message is one its bot wrote.
func (s *Server) editBotMessage(r *http.Request, ch *store.Channel, m *store.Message, in *whInput, files []whFile) (*store.Message, error) {
	ctx := r.Context()
	var p wmPlain
	if err := json.Unmarshal(m.Webhook, &p); err != nil {
		return nil, err
	}
	pe, keepIDs, rows, err := s.editedPlain(&p, m, in, files)
	if err != nil {
		return nil, err
	}
	plain, err := json.Marshal(pe.plain)
	if err != nil {
		return nil, err
	}
	bot, err := s.Store.UserByID(ctx, m.AuthorID)
	if err != nil {
		return nil, err
	}
	var replyTo *store.Message
	if m.ReplyToID != nil {
		replyTo, _ = s.Store.MessageByID(ctx, *m.ReplyToID, 0)
	}
	users, roles, everyone := m.Mentions, m.MentionRoles, m.MentionEveryone
	if in.Present["content"] || in.Present["embeds"] || in.Present["components"] || in.Present["allowed_mentions"] || m.Flags&store.MsgFlagLoading != 0 {
		users, roles, everyone = s.botMentions(ctx, ch, bot, pe.plain, in.Mentions, replyTo)
	}
	// an answer the app was still thinking about is being given, not changed
	edited := time.Now().UnixMilli()
	if m.Flags&store.MsgFlagLoading != 0 {
		edited = 0
	}
	if err := s.Store.EditWebhookMessage(ctx, m.ID, store.WebhookMessageEdit{Plain: plain, Flags: pe.flags, Mentions: users, MentionRoles: roles, MentionEveryone: everyone,
		Keep: keepIDs, Add: rows, EditedAt: edited}); err != nil {
		return nil, err
	}
	updated, err := s.Store.MessageByID(ctx, m.ID, 0)
	if err != nil {
		return nil, err
	}
	s.withRefs(ctx, 0, updated)
	s.Bcast.EmitChannel(ch.ID, "message_update", messageUpdateEvent{updated, mentionsBefore{m.Mentions, m.MentionRoles, m.MentionEveryone}})
	if pe.flags&store.MsgFlagSuppressEmbeds == 0 && pe.flags&store.MsgFlagComponentsV2 == 0 && len(pe.plain.Embeds) == 0 && pe.plain.Content != "" {
		s.unfurlWebhookMessage(m.ID, ch.ID, pe.plain.Content)
	}
	return updated, nil
}

func (s *Server) botMessageEdit(w http.ResponseWriter, r *http.Request) {
	ch := s.botChannel(w, r, NeedView)
	if ch == nil {
		return
	}
	m := s.botMessage(w, r, ch)
	if m == nil {
		return
	}
	b := botOf(r)
	in, files, err := s.readBotMessage(w, r, b.user.ID, whParseOpts{edit: true, app: true, withComponents: true})
	if err != nil {
		writeBotError(w, err, nil)
		return
	}
	bld := s.builderFor(r, b)
	if m.AuthorID != b.user.ID || len(m.Webhook) == 0 || m.WebhookID != nil {
		s.dropFiles(files)
		// someone else's message: only its embeds can be hidden, by whoever
		// may manage messages, as the app's own edit does
		onlyFlags := len(in.Present) == 1 && in.Present["flags"]
		if !onlyFlags {
			writeBotError(w, dErrNotAuthor, nil)
			return
		}
		ans := s.callNexa(r, s.handleMessageEdit, http.MethodPatch, map[string]string{"id": ch.ID.String(), "mid": m.ID.String()}, nil, map[string]any{"flags": in.Flags & store.MsgFlagSuppressEmbeds})
		if !ans.ok() {
			writeBotError(w, ans.err(), dErrUnknownMessage)
			return
		}
		fresh, err := s.Store.MessageByID(r.Context(), m.ID, b.user.ID)
		if err != nil {
			writeBotError(w, err, dErrUnknownMessage)
			return
		}
		WriteJSON(w, http.StatusOK, bld.messageObj(fresh, ch, msgOpts{content: bld.botReadsContent()}))
		return
	}
	updated, err := s.editBotMessage(r, ch, m, in, files)
	if err != nil {
		s.dropFiles(files)
		writeBotError(w, err, dErrUnknownMessage)
		return
	}
	WriteJSON(w, http.StatusOK, bld.messageObj(updated, ch, msgOpts{content: true}))
}

func (s *Server) botMessageDelete(w http.ResponseWriter, r *http.Request) {
	ch := s.botChannel(w, r, NeedView)
	if ch == nil {
		return
	}
	m := s.botMessage(w, r, ch)
	if m == nil {
		return
	}
	ans := s.callNexa(r, s.handleMessageDelete, http.MethodDelete, map[string]string{"id": ch.ID.String(), "mid": m.ID.String()}, nil, nil)
	if !ans.ok() {
		writeBotError(w, ans.err(), dErrUnknownMessage)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) botBulkDelete(w http.ResponseWriter, r *http.Request) {
	ch := s.botChannel(w, r, NeedManage)
	if ch == nil {
		return
	}
	var body struct {
		Messages []snow `json:"messages"`
	}
	if err := ReadJSON(w, r, &body, 16<<10); err != nil {
		writeBotError(w, dErrBadJSON, nil)
		return
	}
	list := botproto.IDs(body.Messages)
	if len(list) < 2 || len(list) > 100 {
		writeBotError(w, dErrBulkCount, nil)
		return
	}
	cutoff := time.Now().Add(-14 * 24 * time.Hour)
	for _, id := range list {
		if id.Time().Before(cutoff) {
			writeBotError(w, dErrTooOldToBulk, nil)
			return
		}
	}
	ans := s.callNexa(r, s.handleMessageBulkDelete, http.MethodPost, map[string]string{"id": ch.ID.String()}, nil, map[string]any{"ids": list})
	if !ans.ok() {
		writeBotError(w, ans.err(), dErrUnknownMessage)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) botCrosspost(w http.ResponseWriter, r *http.Request) {
	ch := s.botChannel(w, r, NeedView)
	if ch == nil {
		return
	}
	m := s.botMessage(w, r, ch)
	if m == nil {
		return
	}
	if ch.Type != store.ChannelAnnouncement {
		writeBotError(w, dErrWrongChannelType, nil)
		return
	}
	if m.Flags&store.MsgFlagPublished != 0 {
		writeBotError(w, dErr(http.StatusBadRequest, 40033, "This message has already been crossposted."), nil)
		return
	}
	ans := s.callNexa(r, s.handleCrosspost, http.MethodPost, map[string]string{"id": ch.ID.String(), "mid": m.ID.String()}, nil, nil)
	if !ans.ok() {
		writeBotError(w, ans.err(), dErrUnknownMessage)
		return
	}
	fresh, err := s.Store.MessageByID(r.Context(), m.ID, botOf(r).user.ID)
	if err != nil {
		writeBotError(w, err, dErrUnknownMessage)
		return
	}
	s.withServerExtras(r.Context(), ch, []*store.Message{fresh})
	b := s.builderFor(r, botOf(r))
	WriteJSON(w, http.StatusOK, b.messageObj(fresh, ch, msgOpts{content: b.botReadsContent()}))
}

// --- reactions --------------------------------------------------------------------------

// reactionKeyFor turns an emoji as the protocol puts it in a path (a
// character, or name:id) into Nexa's reaction key (the character, or
// :name:blob and a:name:blob), which every app writes the same way.
func (s *Server) reactionKeyFor(ctx context.Context, raw string) (string, bool) {
	e := strings.TrimSpace(raw)
	if dec, err := url.PathUnescape(e); err == nil {
		e = dec
	}
	if strings.HasPrefix(e, "<") && strings.HasSuffix(e, ">") {
		e = strings.Trim(e, "<>")
	}
	if m := reReactionKey.FindStringSubmatch(e); m != nil {
		id, err := parseBotID(m[3])
		if err != nil {
			return "", false
		}
		animated := m[1] == "a"
		if b, err := s.Store.BlobByID(ctx, id); err == nil {
			animated = b.Animated
		}
		if animated {
			return "a:" + m[2] + ":" + id.String(), true
		}
		return ":" + m[2] + ":" + id.String(), true
	}
	return parseEmoji(e)
}

// botReactionTarget loads the channel and message of a reaction route.
func (s *Server) botReactionTarget(w http.ResponseWriter, r *http.Request, need string) (*store.Channel, *store.Message, string) {
	ch := s.botChannel(w, r, need)
	if ch == nil {
		return nil, nil, ""
	}
	m := s.botMessage(w, r, ch)
	if m == nil {
		return nil, nil, ""
	}
	key := ""
	if raw := r.PathValue("emoji"); raw != "" {
		var ok bool
		if key, ok = s.reactionKeyFor(r.Context(), raw); !ok {
			writeBotError(w, dErrUnknownEmoji, nil)
			return nil, nil, ""
		}
	}
	return ch, m, key
}

func (s *Server) botReactionAdd(w http.ResponseWriter, r *http.Request) {
	ch, m, key := s.botReactionTarget(w, r, NeedHistory)
	if ch == nil {
		return
	}
	// the app's own route checks Add Reactions and Use External Emojis
	ans := s.callNexa(r, s.handleReactionAdd, http.MethodPut, map[string]string{"id": ch.ID.String(), "mid": m.ID.String(), "emoji": key}, nil, nil)
	if !ans.ok() {
		writeBotError(w, ans.err(), dErrUnknownMessage)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) botReactionRemoveOwn(w http.ResponseWriter, r *http.Request) {
	ch, m, key := s.botReactionTarget(w, r, NeedView)
	if ch == nil {
		return
	}
	ans := s.callNexa(r, s.handleReactionRemove, http.MethodDelete, map[string]string{"id": ch.ID.String(), "mid": m.ID.String(), "emoji": key}, nil, nil)
	if !ans.ok() {
		writeBotError(w, ans.err(), dErrUnknownMessage)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// botReactionRemoveUser takes someone's reaction off, which needs Manage Messages.
func (s *Server) botReactionRemoveUser(w http.ResponseWriter, r *http.Request) {
	ch, m, key := s.botReactionTarget(w, r, NeedManage)
	if ch == nil {
		return
	}
	uid, ok := pathID(r, "user")
	if !ok {
		writeBotError(w, dErrUnknownUser, nil)
		return
	}
	removed, err := s.Store.RemoveReaction(r.Context(), m.ID, uid, key)
	if err != nil {
		writeBotError(w, err, nil)
		return
	}
	if removed {
		s.Bcast.EmitChannel(ch.ID, "reaction_remove", map[string]any{"channel_id": ch.ID, "message_id": m.ID, "user_id": uid, "emoji": key})
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) botReactors(w http.ResponseWriter, r *http.Request) {
	ch, m, key := s.botReactionTarget(w, r, NeedHistory)
	if ch == nil {
		return
	}
	q := r.URL.Query()
	limit := 25
	if l := q.Get("limit"); l != "" {
		n, err := strconv.Atoi(l)
		if err != nil || n < 1 || n > 100 {
			writeBotError(w, dFormError("NUMBER_TYPE_MAX", "int value should be between 1 and 100.", "limit"), nil)
			return
		}
		limit = n
	}
	// a super reaction (type 1) is not a thing here: there are none
	if q.Get("type") == "1" {
		WriteJSON(w, http.StatusOK, []dUser{})
		return
	}
	var after ids.ID
	if v := q.Get("after"); v != "" {
		if n, err := botproto.Parse(v); err == nil && n > 0 {
			after = ids.ID(n)
		}
	}
	list, err := s.Store.ReactorsAfter(r.Context(), m.ID, key, after, limit)
	if err != nil {
		writeBotError(w, err, nil)
		return
	}
	b := s.builderFor(r, botOf(r))
	b.preloadUsers(list)
	out := make([]dUser, 0, len(list))
	for _, id := range list {
		out = append(out, b.userByID(id))
	}
	WriteJSON(w, http.StatusOK, out)
}

func (s *Server) botReactionsClear(w http.ResponseWriter, r *http.Request) {
	ch, m, _ := s.botReactionTarget(w, r, NeedManage)
	if ch == nil {
		return
	}
	removed, err := s.Store.RemoveAllReactions(r.Context(), m.ID)
	if err != nil {
		writeBotError(w, err, nil)
		return
	}
	if removed {
		s.Bcast.EmitChannel(ch.ID, gateway.EvReactionRemoveAll, map[string]any{"channel_id": ch.ID, "message_id": m.ID})
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) botReactionsClearEmoji(w http.ResponseWriter, r *http.Request) {
	ch, m, key := s.botReactionTarget(w, r, NeedManage)
	if ch == nil {
		return
	}
	removed, err := s.Store.RemoveEmojiReactions(r.Context(), m.ID, key)
	if err != nil {
		writeBotError(w, err, nil)
		return
	}
	if removed {
		s.Bcast.EmitChannel(ch.ID, gateway.EvReactionRemoveEmoji, map[string]any{"channel_id": ch.ID, "message_id": m.ID, "emoji": key})
	}
	w.WriteHeader(http.StatusNoContent)
}

// --- pins, typing, polls ------------------------------------------------------------------

func (s *Server) botPinsOld(w http.ResponseWriter, r *http.Request) {
	ch := s.botChannel(w, r, NeedHistory)
	if ch == nil {
		return
	}
	b := botOf(r)
	list, _, err := s.Store.PinsPage(r.Context(), ch.ID, 0, 50, b.user.ID)
	if err != nil {
		writeBotError(w, err, nil)
		return
	}
	bld := s.builderFor(r, b)
	WriteJSON(w, http.StatusOK, bld.messagesObj(list, ch, msgOpts{content: bld.botReadsContent()}))
}

func (s *Server) botPins(w http.ResponseWriter, r *http.Request) {
	ch := s.botChannel(w, r, NeedHistory)
	if ch == nil {
		return
	}
	b := botOf(r)
	q := r.URL.Query()
	limit := 50
	if l := q.Get("limit"); l != "" {
		if n, err := strconv.Atoi(l); err == nil && n >= 1 && n <= 50 {
			limit = n
		}
	}
	var before int64
	if v := q.Get("before"); v != "" {
		if ms, ok := botproto.ParseTimestamp(v); ok {
			before = ms
		}
	}
	list, times, err := s.Store.PinsPage(r.Context(), ch.ID, before, limit+1, b.user.ID)
	if err != nil {
		writeBotError(w, err, nil)
		return
	}
	more := len(list) > limit
	if more {
		list, times = list[:limit], times[:limit]
	}
	bld := s.builderFor(r, b)
	msgs := bld.messagesObj(list, ch, msgOpts{content: bld.botReadsContent()})
	items := make([]map[string]any, 0, len(msgs))
	for i, m := range msgs {
		items = append(items, map[string]any{"pinned_at": botproto.Timestamp(times[i]), "message": m})
	}
	WriteJSON(w, http.StatusOK, map[string]any{"items": items, "has_more": more})
}

func (s *Server) botPin(pin bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ch := s.botChannel(w, r, NeedView)
		if ch == nil {
			return
		}
		m := s.botMessage(w, r, ch)
		if m == nil {
			return
		}
		h, method := s.handlePin, http.MethodPut
		if !pin {
			h, method = s.handleUnpin, http.MethodDelete
		}
		ans := s.callNexa(r, h, method, map[string]string{"id": ch.ID.String(), "mid": m.ID.String()}, nil, nil)
		if !ans.ok() {
			e := ans.err()
			var ae *Error
			if errors.As(e, &ae) && ae.Code == "limit_reached" {
				writeBotError(w, dErr(http.StatusBadRequest, 30003, "Maximum number of pins reached"), nil)
				return
			}
			writeBotError(w, e, dErrUnknownMessage)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

func (s *Server) botTyping(w http.ResponseWriter, r *http.Request) {
	ch := s.botChannel(w, r, NeedSend)
	if ch == nil {
		return
	}
	s.Bcast.Typing(r.Context(), botOf(r).user.ID, ch.ID)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) botPollVoters(w http.ResponseWriter, r *http.Request) {
	ch := s.botChannel(w, r, NeedHistory)
	if ch == nil {
		return
	}
	m := s.botMessage(w, r, ch)
	if m == nil {
		return
	}
	if m.Poll == nil {
		writeBotError(w, dErrUnknownMessage, nil)
		return
	}
	answer, err := strconv.Atoi(r.PathValue("answer"))
	if err != nil || answer < 1 || answer > len(m.Poll.Counts) {
		writeBotError(w, dErr(http.StatusNotFound, 10008, "Unknown Answer"), nil)
		return
	}
	q := r.URL.Query()
	var after ids.ID
	if v := q.Get("after"); v != "" {
		if n, err := botproto.Parse(v); err == nil && n > 0 {
			after = ids.ID(n)
		}
	}
	limit, _ := strconv.Atoi(q.Get("limit"))
	if limit <= 0 || limit > 100 {
		limit = 25
	}
	list, err := s.Store.PollVoters(r.Context(), m.ID, answer, after, limit)
	if err != nil {
		writeBotError(w, err, nil)
		return
	}
	b := s.builderFor(r, botOf(r))
	b.preloadUsers(list)
	out := make([]dUser, 0, len(list))
	for _, id := range list {
		out = append(out, b.userByID(id))
	}
	WriteJSON(w, http.StatusOK, map[string]any{"users": out})
}

func (s *Server) botPollExpire(w http.ResponseWriter, r *http.Request) {
	ch := s.botChannel(w, r, NeedView)
	if ch == nil {
		return
	}
	m := s.botMessage(w, r, ch)
	if m == nil {
		return
	}
	if m.Poll == nil {
		writeBotError(w, dErr(http.StatusBadRequest, 520006, "Cannot expire a non-poll message"), nil)
		return
	}
	if m.AuthorID != botOf(r).user.ID {
		writeBotError(w, dErrMissingPerms, nil)
		return
	}
	if m.Poll.Finalized {
		writeBotError(w, dErr(http.StatusBadRequest, 520001, "Poll expired"), nil)
		return
	}
	s.endPoll(r.Context(), m.ID, ch.ID, time.Now().UnixMilli())
	fresh, err := s.Store.MessageByID(r.Context(), m.ID, botOf(r).user.ID)
	if err != nil {
		writeBotError(w, err, dErrUnknownMessage)
		return
	}
	b := s.builderFor(r, botOf(r))
	WriteJSON(w, http.StatusOK, b.messageObj(fresh, ch, msgOpts{content: true}))
}
