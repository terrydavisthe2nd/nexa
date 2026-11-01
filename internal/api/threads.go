package api

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/perms"
	"evilion.xyz/chat/internal/store"
)

// Threads: conversations that branch off a channel, started from one of its
// messages or on their own, and forum posts, which are threads of a forum
// channel with a title, tags and a first message. A public thread is seen by
// everyone who sees its channel and sealed with its channel's key; a private
// one only by the people in it and moderators, with a key of its own.
// Quiet threads close themselves after a while and open again when someone
// writes in them, unless a moderator locked them.

const (
	maxThreadName = 100
	// maxForumTags is how many tags a forum offers, and maxPostTags how many one post wears.
	maxForumTags  = 20
	maxPostTags   = 5
	maxForumTopic = 4096
)

func (s *Server) threadRoutes(mux *http.ServeMux) {
	mux.Handle("POST /api/v1/channels/{id}/threads", s.authed(s.handleThreadCreate))
	mux.Handle("GET /api/v1/channels/{id}/threads", s.authed(s.handleThreadList))
	mux.Handle("GET /api/v1/channels/{id}/thread-members", s.authed(s.handleThreadMembers))
	mux.Handle("PUT /api/v1/channels/{id}/thread-members/{uid}", s.authed(s.handleThreadMemberAdd))
	mux.Handle("DELETE /api/v1/channels/{id}/thread-members/{uid}", s.authed(s.handleThreadMemberRemove))
	mux.Handle("PUT /api/v1/channels/{id}/pinned-post/{tid}", s.authed(s.handleForumPin(true)))
	mux.Handle("DELETE /api/v1/channels/{id}/pinned-post/{tid}", s.authed(s.handleForumPin(false)))
}

// threadEvent is a thread as thread_create and thread_update carry it.
type threadEvent struct {
	*store.Channel
	MemberCount int `json:"member_count"`
}

func (s *Server) threadView(ctx context.Context, th *store.Channel) threadEvent {
	members, _ := s.Store.ThreadMemberIDs(ctx, th.ID)
	return threadEvent{Channel: th, MemberCount: len(members)}
}

// emitThread tells everyone who can see a thread how it stands now.
func (s *Server) emitThread(ctx context.Context, event string, th *store.Channel) {
	viewers, err := s.Store.ChannelViewers(ctx, th.ID)
	if err != nil {
		return
	}
	s.Bcast.EmitUsers(viewers, event, s.threadView(ctx, th))
}

// threadParent loads the channel from {id} that threads are started in, with
// what the caller may do there.
func (s *Server) threadParent(w http.ResponseWriter, r *http.Request) (*store.Channel, *store.User, perms.Perm) {
	ch, me := s.channel(w, r, NeedView)
	if ch == nil {
		return nil, nil, 0
	}
	if ch.ServerID == nil || !store.HoldsThreads(ch.Type) {
		WriteError(w, E(http.StatusBadRequest, "no_threads", "Threads are started in a server's text, announcement and forum channels."))
		return nil, nil, 0
	}
	p, err := s.Store.PermsIn(r.Context(), ch, me.ID)
	if err != nil {
		WriteError(w, err)
		return nil, nil, 0
	}
	return ch, me, p
}

// cleanThreadName collapses a thread's name into one line of 1 to 100 characters.
func cleanThreadName(name string) (string, bool) {
	clean, ok := CleanText(strings.Join(strings.Fields(name), " "), maxThreadName)
	return clean, ok && clean != ""
}

func validAutoArchive(n int) bool {
	for _, c := range store.AutoArchiveChoices {
		if n == c {
			return true
		}
	}
	return false
}

// postTags checks the tags a forum post wears: tags of that forum, five at
// most, the moderated ones only from someone who manages threads.
func postTags(forum *store.Channel, want []ids.ID, p perms.Perm) ([]ids.ID, *Error) {
	if forum.Forum == nil {
		return []ids.ID{}, nil
	}
	tags := dedupeIDs(want, maxPostTags+1)
	if len(tags) > maxPostTags {
		return nil, E(http.StatusBadRequest, "too_many_tags", "A post wears at most 5 tags.")
	}
	byID := map[ids.ID]store.ForumTag{}
	for _, t := range forum.Forum.Tags {
		byID[t.ID] = t
	}
	for _, id := range tags {
		t, ok := byID[id]
		if !ok {
			return nil, E(http.StatusBadRequest, "unknown_tag", "One of those tags is not this forum's.")
		}
		if t.Moderated && !p.Has(perms.ManageThreads) {
			return nil, E(http.StatusForbidden, "moderated_tag", "Only moderators can put the "+t.Name+" tag on a post.")
		}
	}
	return tags, nil
}

// handleThreadCreate starts a thread: from a message (message_id), on its
// own, or as a forum post, whose first message comes with it.
func (s *Server) handleThreadCreate(w http.ResponseWriter, r *http.Request) {
	parent, me, p := s.threadParent(w, r)
	if parent == nil {
		return
	}
	ctx := r.Context()
	var req struct {
		Name        string          `json:"name"`
		MessageID   *ids.ID         `json:"message_id"`
		Private     bool            `json:"private"`
		AutoArchive int             `json:"auto_archive"`
		Slowmode    int             `json:"slowmode_seconds"`
		Tags        []ids.ID        `json:"applied_tags"`
		Message     json.RawMessage `json:"message"`
	}
	if err := ReadJSON(w, r, &req, maxEnvelopeBytes+32<<10); err != nil {
		WriteError(w, err)
		return
	}
	name, ok := cleanThreadName(req.Name)
	if !ok {
		WriteError(w, E(http.StatusBadRequest, "invalid_name", "A thread's name is 1 to 100 characters."))
		return
	}
	forum := store.IsForum(parent.Type)
	switch {
	case forum:
		// a forum post is a message in the forum: Send Messages there
		if !p.Has(perms.SendMessages) {
			WriteError(w, E(http.StatusForbidden, "missing_permission", "You cannot post in this forum."))
			return
		}
		if req.Private || req.MessageID != nil {
			WriteError(w, E(http.StatusBadRequest, "forum_post", "A forum post is public and starts with a message of its own."))
			return
		}
	case req.Private:
		if parent.Type != "text" {
			WriteError(w, E(http.StatusBadRequest, "no_private_threads", "Private threads are started in text channels."))
			return
		}
		if !p.Has(perms.CreatePrivateThreads) {
			WriteError(w, E(http.StatusForbidden, "missing_permission", "You cannot start private threads here."))
			return
		}
	default:
		if !p.Has(perms.CreatePublicThreads) {
			WriteError(w, E(http.StatusForbidden, "missing_permission", "You cannot start threads here."))
			return
		}
	}
	if mutedFor(w, r) {
		return
	}
	if ok, retry := s.limThread.Allow("thread:" + me.ID.String()); !ok {
		w.Header().Set("Retry-After", retryAfter(retry))
		WriteError(w, ErrRateLimited)
		return
	}
	if !s.mayTalkYet(w, r, parent, me) {
		return
	}
	// AutoMod reads titles, which the server can see for itself
	if aerr := s.checkTitle(r, parent, me, name); aerr != nil {
		WriteError(w, aerr)
		return
	}
	auto := req.AutoArchive
	if auto == 0 {
		auto = parent.DefaultAutoArchive
	}
	if auto == 0 {
		auto = store.DefaultAutoArchive
	}
	if !validAutoArchive(auto) {
		WriteError(w, E(http.StatusBadRequest, "invalid_auto_archive", "A thread closes after an hour, a day, three days or a week without messages."))
		return
	}
	if req.Slowmode < 0 || req.Slowmode > 21600 {
		WriteError(w, E(http.StatusBadRequest, "invalid_slowmode", "Slow mode is 0–21600 seconds."))
		return
	}
	nt := store.NewThread{Parent: parent, Owner: me.ID, Name: name, Private: req.Private, AutoArchive: auto, Slowmode: req.Slowmode}
	if forum {
		tags, terr := postTags(parent, req.Tags, p)
		if terr != nil {
			WriteError(w, terr)
			return
		}
		if parent.Forum != nil && parent.Forum.RequireTag && len(tags) == 0 {
			WriteError(w, E(http.StatusBadRequest, "tag_required", "Pick at least one tag for your post."))
			return
		}
		nt.Tags = tags
		// a forum's own slow mode paces posts; its posts take the one set for them
		if req.Slowmode == 0 && parent.Forum != nil {
			nt.Slowmode = parent.SlowmodeSeconds
		}
	}
	if req.MessageID != nil {
		m, err := s.Store.MessageByID(ctx, *req.MessageID, me.ID)
		if err != nil || m.ChannelID != parent.ID {
			WriteError(w, E(http.StatusBadRequest, "bad_message", "That message is not in this channel."))
			return
		}
		if strings.HasPrefix(m.Type, "system_") {
			WriteError(w, E(http.StatusBadRequest, "bad_message", "Threads start from what people said."))
			return
		}
		nt.Starter = req.MessageID
	}
	var notice *store.Message
	if req.MessageID == nil && !forum && !req.Private {
		// a thread started on its own is announced in its channel, and the
		// note is what it hangs from there
		notice = &store.Message{ChannelID: parent.ID, AuthorID: me.ID, Type: "system_thread", Mentions: []ids.ID{}}
		if err := s.Store.InsertMessage(ctx, notice); err != nil {
			WriteError(w, err)
			return
		}
		nt.Starter = &notice.ID
	}
	th, err := s.Store.CreateThread(ctx, nt)
	if err != nil {
		if notice != nil {
			_ = s.Store.DeleteMessage(ctx, notice.ID)
		}
		if errors.Is(err, store.ErrThreadExists) {
			WriteError(w, E(http.StatusConflict, "thread_exists", "That message already has a thread."))
			return
		}
		WriteError(w, err)
		return
	}
	s.audit(r, *parent.ServerID, me.ID, "thread_create", "channel", &th.ID, map[string]any{"name": th.Name, "type": th.Type, "parent_id": parent.ID}, "")
	// A forum post's first message is sealed for the post itself, which only
	// exists from here: the app sends it next, and until it does the post is
	// its author's alone (posted, it is announced to everyone; never posted,
	// it is cleared away).
	pending := forum && (len(req.Message) == 0 || string(req.Message) == "null")
	if pending {
		s.Bcast.EmitUsers([]ids.ID{me.ID}, "thread_create", s.threadView(ctx, th))
	} else {
		s.emitThread(ctx, "thread_create", th)
	}
	s.emitThreadMembers(ctx, th, []ids.ID{me.ID}, nil)
	if notice != nil {
		if full, err := s.Store.MessageByID(ctx, notice.ID, 0); err == nil {
			_ = s.Store.ThreadSummaries(ctx, []*store.Message{full})
			s.Bcast.EmitChannel(parent.ID, "message_create", full)
		}
	}
	resp := map[string]any{"thread": s.threadView(ctx, th)}
	if req.Private {
		// sealed with a key of its own, which the creator's app makes now
		resp["key_actions"] = map[string]any{"needs_own_key": true, "conv_id": th.ID}
	}
	if forum && !pending {
		var body messageBody
		if err := json.Unmarshal(req.Message, &body); err != nil {
			s.dropThread(ctx, th)
			WriteError(w, E(http.StatusBadRequest, "bad_envelope", "The post's message is malformed."))
			return
		}
		msg, mErr := s.postFirstMessage(r, th, me, &body)
		if mErr != nil {
			s.dropThread(ctx, th)
			WriteError(w, mErr)
			return
		}
		resp["message"] = msg
	}
	WriteJSON(w, http.StatusCreated, resp)
}

// dropThread takes back a thread whose first message could not be posted.
func (s *Server) dropThread(ctx context.Context, th *store.Channel) {
	viewers, _ := s.Store.ChannelViewers(ctx, th.ID)
	if err := s.Store.DeleteChannel(ctx, th.ID); err == nil {
		s.Bcast.EmitUsers(viewers, "thread_delete", map[string]any{"id": th.ID, "parent_id": th.ParentID, "server_id": th.ServerID})
	}
}

// postFirstMessage writes a forum post's opening message, as sending it would.
func (s *Server) postFirstMessage(r *http.Request, th *store.Channel, me *store.User, body *messageBody) (*store.Message, error) {
	ctx := r.Context()
	if len(body.Ciphertext) > maxEnvelopeBytes {
		return nil, errMessageTooLong
	}
	env, ok := validEnvelope(body.Ciphertext)
	if !ok {
		return nil, E(http.StatusBadRequest, "bad_envelope", "The encrypted message envelope is malformed.")
	}
	fresh, err := s.Store.ChannelByID(ctx, th.ID)
	if err != nil {
		return nil, err
	}
	if env.Conv != fresh.KeyConvID || body.KeyConvID != fresh.KeyConvID || env.Epoch != body.KeyEpoch {
		return nil, E(http.StatusBadRequest, "wrong_conversation", "The message is encrypted for a different conversation.")
	}
	if latest, exists, err := s.Store.LatestEpoch(ctx, env.Conv); err != nil {
		return nil, err
	} else if !exists || env.Epoch > latest {
		return nil, E(http.StatusBadRequest, "unknown_epoch", "That key epoch does not exist yet.")
	} else if env.Epoch < latest {
		return nil, store.ErrStaleEpoch
	}
	m := &store.Message{ChannelID: th.ID, AuthorID: me.ID, Type: "default", Ciphertext: body.Ciphertext, KeyConvID: &env.Conv, KeyEpoch: &env.Epoch,
		Mentions: dedupeIDs(body.Mentions, maxMentions), MentionRoles: s.allowedRoleMentions(ctx, me, th, body.MentionRoles), Nonce: body.Nonce, Flags: s.embedFlags(ctx, me, th, body.Flags&store.MsgFlagSuppressEmbeds)}
	seen := map[ids.ID]bool{}
	for _, a := range body.Attachments {
		if seen[a.BlobID] {
			continue
		}
		seen[a.BlobID] = true
		b, err := s.Store.BlobByID(ctx, a.BlobID)
		if err != nil || b.Kind != "attachment" || b.OwnerID == nil || *b.OwnerID != me.ID {
			return nil, E(http.StatusBadRequest, "bad_attachment", "One of the attachments is not yours or does not exist.")
		}
		if _, err := s.Store.AttachmentByBlob(ctx, a.BlobID); err == nil {
			return nil, E(http.StatusBadRequest, "bad_attachment", "An attachment was already used by another message.")
		}
		m.Attachments = append(m.Attachments, store.Attachment{BlobID: a.BlobID, Size: b.Size})
	}
	if len(m.Attachments) > 0 {
		if ok, _ := s.canAccess(r, me, th, NeedAttach); !ok {
			return nil, E(http.StatusForbidden, "missing_permission", "You cannot attach files here.")
		}
	}
	if err := s.Store.InsertEncryptedMessage(ctx, m); err != nil {
		return nil, err
	}
	full, err := s.Store.MessageByID(ctx, m.ID, me.ID)
	if err != nil {
		return nil, err
	}
	s.Bcast.EmitChannel(th.ID, "message_create", full)
	s.Bcast.KeysMaybeNeeded(env.Conv)
	return full, nil
}

// threadSummary is a thread in a list: the thread, whether the caller is in
// it, a few of the people in it, and for a forum post its first message.
type threadSummary struct {
	threadEvent
	Joined       bool           `json:"joined"`
	MemberIDs    []ids.ID       `json:"member_ids"`
	FirstMessage *store.Message `json:"first_message,omitempty"`
}

// handleThreadList lists a channel's threads, or a forum's posts, that the
// caller can see: those still going, or (archived=1) those closed.
func (s *Server) handleThreadList(w http.ResponseWriter, r *http.Request) {
	parent, me, _ := s.threadParent(w, r)
	if parent == nil {
		return
	}
	ctx := r.Context()
	q := r.URL.Query()
	before, _ := strconv.ParseInt(q.Get("before"), 10, 64)
	limit, _ := strconv.Atoi(q.Get("limit"))
	tq := store.ThreadQuery{Parent: parent.ID, Archived: q.Get("archived") == "1", Before: before, Limit: limit, Query: q.Get("q"), Sort: q.Get("sort"), Forum: store.IsForum(parent.Type), Viewer: me.ID}
	for _, raw := range strings.Split(q.Get("tags"), ",") {
		if id, err := ids.Parse(strings.TrimSpace(raw)); err == nil {
			tq.Tags = append(tq.Tags, id)
		}
	}
	// a few more than asked, since some may be private threads the caller cannot see
	want := tq.Limit
	if want <= 0 || want > 100 {
		want = 50
	}
	tq.Limit = want + 1
	list, err := s.Store.Threads(ctx, tq)
	if err != nil {
		WriteError(w, err)
		return
	}
	more := len(list) > want
	if more {
		list = list[:want]
	}
	out := []threadSummary{}
	var forumIDs []ids.ID
	for _, th := range list {
		p, err := s.Store.PermsIn(ctx, th, me.ID)
		if err != nil || !p.Has(perms.ViewChannel) {
			continue
		}
		members, _ := s.Store.ThreadMemberIDs(ctx, th.ID)
		sum := threadSummary{threadEvent: threadEvent{Channel: th, MemberCount: len(members)}, MemberIDs: members[:min(len(members), 3)]}
		for _, id := range members {
			if id == me.ID {
				sum.Joined = true
				break
			}
		}
		out = append(out, sum)
		forumIDs = append(forumIDs, th.ID)
	}
	if store.IsForum(parent.Type) && len(forumIDs) > 0 {
		firsts, err := s.Store.FirstMessages(ctx, forumIDs, me.ID)
		if err == nil {
			for i := range out {
				out[i].FirstMessage = firsts[out[i].ID]
			}
		}
	}
	resp := map[string]any{"threads": out, "has_more": more}
	// the pinned post comes first, wherever it is in the order
	if parent.Forum != nil && parent.Forum.PinnedPost != nil && !tq.Archived && before == 0 && tq.Query == "" && len(tq.Tags) == 0 {
		resp["pinned_post"] = parent.Forum.PinnedPost
	}
	who := []ids.ID{}
	for _, t := range out {
		if t.OwnerID != nil {
			who = append(who, *t.OwnerID)
		}
		who = append(who, t.MemberIDs...)
	}
	resp["users"] = s.publicUsers(ctx, who)
	WriteJSON(w, http.StatusOK, resp)
}

// threadCtx loads the thread a thread route names, with the caller's
// permissions in it.
func (s *Server) threadCtx(w http.ResponseWriter, r *http.Request) (*store.Channel, *store.User, perms.Perm) {
	ch, me := s.channel(w, r, NeedView)
	if ch == nil {
		return nil, nil, 0
	}
	if !store.IsThread(ch.Type) {
		WriteError(w, E(http.StatusBadRequest, "not_thread", "That is not a thread."))
		return nil, nil, 0
	}
	p, err := s.Store.PermsIn(r.Context(), ch, me.ID)
	if err != nil {
		WriteError(w, err)
		return nil, nil, 0
	}
	return ch, me, p
}

func (s *Server) handleThreadMembers(w http.ResponseWriter, r *http.Request) {
	th, _, _ := s.threadCtx(w, r)
	if th == nil {
		return
	}
	list, err := s.Store.ThreadMembers(r.Context(), th.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	who := make([]ids.ID, 0, len(list))
	for _, m := range list {
		who = append(who, m.UserID)
	}
	WriteJSON(w, http.StatusOK, map[string]any{"members": list, "users": s.publicUsers(r.Context(), who)})
}

// emitThreadMembers tells the people in a thread, and whoever came or went,
// who is in it now.
func (s *Server) emitThreadMembers(ctx context.Context, th *store.Channel, added, removed []ids.ID) {
	members, err := s.Store.ThreadMemberIDs(ctx, th.ID)
	if err != nil {
		return
	}
	to := append(append(append([]ids.ID{}, members...), added...), removed...)
	ev := map[string]any{"thread_id": th.ID, "server_id": th.ServerID, "added": added, "removed": removed, "member_count": len(members)}
	if added == nil {
		ev["added"] = []ids.ID{}
	}
	if removed == nil {
		ev["removed"] = []ids.ID{}
	}
	s.Bcast.EmitUsers(dedupeIDs(to, len(to)+1), "thread_members_update", ev)
}

// handleThreadMemberAdd joins a thread (@me) or adds someone to it.
func (s *Server) handleThreadMemberAdd(w http.ResponseWriter, r *http.Request) {
	th, me, p := s.threadCtx(w, r)
	if th == nil {
		return
	}
	ctx := r.Context()
	uid := me.ID
	if raw := r.PathValue("uid"); raw != "@me" {
		id, err := ids.Parse(raw)
		if err != nil {
			WriteError(w, ErrNotFound)
			return
		}
		uid = id
	}
	if uid != me.ID {
		// people in a thread may bring others in; moderators always may
		in, _ := s.Store.IsThreadMember(ctx, th.ID, me.ID)
		if !in && !p.Has(perms.ManageThreads) {
			WriteError(w, E(http.StatusForbidden, "missing_permission", "Join the thread to add people to it."))
			return
		}
		if !s.mayAddToThread(ctx, th, uid) {
			WriteError(w, E(http.StatusBadRequest, "cannot_see", "They cannot see the channel this thread is in."))
			return
		}
	}
	if th.Thread != nil && th.Thread.Locked && !p.Has(perms.ManageThreads) && uid != me.ID {
		WriteError(w, E(http.StatusForbidden, "thread_locked", "This thread is locked."))
		return
	}
	added, err := s.Store.AddThreadMember(ctx, th, uid)
	if errors.Is(err, store.ErrLimit) {
		WriteError(w, E(http.StatusBadRequest, "thread_full", "This thread has as many people as it can hold."))
		return
	}
	if err != nil {
		WriteError(w, err)
		return
	}
	if added {
		s.threadMembersChanged(r, th, me, []ids.ID{uid}, nil)
	}
	w.WriteHeader(http.StatusNoContent)
}

// mayAddToThread reports whether someone can be put in a thread: a member of
// its server who can see its channel.
func (s *Server) mayAddToThread(ctx context.Context, th *store.Channel, uid ids.ID) bool {
	if th.ParentID == nil {
		return false
	}
	parent, err := s.Store.ChannelByID(ctx, *th.ParentID)
	if err != nil {
		return false
	}
	p, err := s.Store.PermsIn(ctx, parent, uid)
	return err == nil && p.Has(perms.ViewChannel)
}

// threadMembersChanged says so to everyone it concerns, posts the note about
// it in a private thread, and for a private thread hands keys to whoever came
// in or asks for a new key once someone has gone.
func (s *Server) threadMembersChanged(r *http.Request, th *store.Channel, me *store.User, added, removed []ids.ID) {
	ctx := r.Context()
	s.emitThreadMembers(ctx, th, added, removed)
	if th.Type != store.ChannelPrivateThread {
		return
	}
	// coming in, they see it; going, they stop
	if fresh, err := s.Store.ChannelByID(ctx, th.ID); err == nil {
		for _, id := range added {
			s.Bcast.EmitUsers([]ids.ID{id}, "thread_create", s.threadView(ctx, fresh))
		}
		for _, id := range removed {
			if p, err := s.Store.PermsIn(ctx, fresh, id); err == nil && !p.Has(perms.ViewChannel) {
				s.Bcast.EmitUsers([]ids.ID{id}, "thread_delete", map[string]any{"id": th.ID, "parent_id": th.ParentID, "server_id": th.ServerID})
			}
		}
		for _, id := range added {
			if id != me.ID {
				s.systemMessage(r, fresh, me.ID, "system_thread_add", []ids.ID{id}, nil)
			}
		}
		for _, id := range removed {
			if id != me.ID {
				s.systemMessage(r, fresh, me.ID, "system_thread_remove", []ids.ID{id}, nil)
			}
		}
		if fresh.KeyConvID == fresh.ID {
			if len(added) > 0 {
				s.Bcast.KeysMaybeNeeded(fresh.ID)
			}
			if len(removed) > 0 {
				tr := true
				_ = s.Store.SetKeyFlags(ctx, fresh.ID, &tr, nil)
				if viewers, err := s.Store.ChannelViewers(ctx, fresh.ID); err == nil {
					s.Bcast.EmitUsers(viewers, "key_rotation_needed", map[string]any{"conv_id": fresh.ID})
				}
			}
		}
	}
}

// handleThreadMemberRemove leaves a thread (@me) or takes someone out of it:
// a moderator, or for a private thread the person who started it.
func (s *Server) handleThreadMemberRemove(w http.ResponseWriter, r *http.Request) {
	th, me, p := s.threadCtx(w, r)
	if th == nil {
		return
	}
	ctx := r.Context()
	uid := me.ID
	if raw := r.PathValue("uid"); raw != "@me" {
		id, err := ids.Parse(raw)
		if err != nil {
			WriteError(w, ErrNotFound)
			return
		}
		uid = id
	}
	if uid != me.ID {
		owner := th.OwnerID != nil && *th.OwnerID == me.ID && th.Type == store.ChannelPrivateThread
		// a thread's people are its starter's and the moderators' to look
		// after, whatever anyone's roles: leaving a thread costs nothing but it
		if !p.Has(perms.ManageThreads) && !owner {
			WriteError(w, E(http.StatusForbidden, "missing_permission", "You cannot take people out of this thread."))
			return
		}
	}
	removed, err := s.Store.RemoveThreadMember(ctx, th, uid)
	if err != nil {
		WriteError(w, err)
		return
	}
	if removed {
		s.threadMembersChanged(r, th, me, nil, []ids.ID{uid})
	}
	w.WriteHeader(http.StatusNoContent)
}

// handleForumPin pins one post at the top of a forum, in place of any other,
// or takes it down.
func (s *Server) handleForumPin(pin bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		forum, me, p := s.threadParent(w, r)
		if forum == nil {
			return
		}
		if !store.IsForum(forum.Type) || forum.Forum == nil {
			WriteError(w, E(http.StatusBadRequest, "not_forum", "Only a forum has a pinned post."))
			return
		}
		if !p.Has(perms.ManageThreads) {
			WriteError(w, E(http.StatusForbidden, "missing_permission", "You need Manage Threads to pin posts."))
			return
		}
		tid, err := ids.Parse(r.PathValue("tid"))
		if err != nil {
			WriteError(w, ErrNotFound)
			return
		}
		th, err := s.Store.ChannelByID(r.Context(), tid)
		if err != nil || th.ParentID == nil || *th.ParentID != forum.ID {
			WriteError(w, ErrNotFound)
			return
		}
		settings := *forum.Forum
		if pin {
			settings.PinnedPost = &tid
		} else if settings.PinnedPost != nil && *settings.PinnedPost == tid {
			settings.PinnedPost = nil
		}
		if err := s.Store.UpdateChannel(r.Context(), forum.ID, store.ChannelPatch{Forum: &settings}); err != nil {
			WriteError(w, err)
			return
		}
		updated, err := s.Store.ChannelByID(r.Context(), forum.ID)
		if err != nil {
			WriteError(w, err)
			return
		}
		action := map[bool]string{true: "thread_pin", false: "thread_unpin"}[pin]
		s.audit(r, *forum.ServerID, me.ID, action, "channel", &tid, map[string]any{"name": th.Name}, "")
		s.Bcast.EmitChannel(forum.ID, "channel_update", updated)
		WriteJSON(w, http.StatusOK, updated)
	}
}

// threadPatch changes a thread: its name, whether it is closed or locked,
// when it closes itself, its slow mode, and a forum post's tags. Its starter
// may rename it, close it again and retag it; the rest is for moderators.
func (s *Server) threadPatch(w http.ResponseWriter, r *http.Request, th *store.Channel, me *store.User) {
	ctx := r.Context()
	p, err := s.Store.PermsIn(ctx, th, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	mod := p.Has(perms.ManageThreads)
	owner := th.OwnerID != nil && *th.OwnerID == me.ID
	var req struct {
		Name        *string   `json:"name"`
		Archived    *bool     `json:"archived"`
		Locked      *bool     `json:"locked"`
		AutoArchive *int      `json:"auto_archive"`
		Slowmode    *int      `json:"slowmode_seconds"`
		Tags        *[]ids.ID `json:"applied_tags"`
	}
	if err := ReadJSON(w, r, &req, 8<<10); err != nil {
		WriteError(w, err)
		return
	}
	var patch store.ChannelPatch
	changes := map[string]any{}
	deny := func() {
		WriteError(w, E(http.StatusForbidden, "missing_permission", "You need Manage Threads to change that."))
	}
	locked := th.Thread != nil && th.Thread.Locked
	if locked && !mod {
		WriteError(w, E(http.StatusForbidden, "thread_locked", "This thread is locked. Only moderators can change it."))
		return
	}
	if req.Name != nil {
		if !mod && !owner {
			deny()
			return
		}
		name, ok := cleanThreadName(*req.Name)
		if !ok {
			WriteError(w, E(http.StatusBadRequest, "invalid_name", "A thread's name is 1 to 100 characters."))
			return
		}
		if th.ParentID != nil {
			if parent, err := s.Store.ChannelByID(ctx, *th.ParentID); err == nil {
				if aerr := s.checkTitle(r, parent, me, name); aerr != nil {
					WriteError(w, aerr)
					return
				}
			}
		}
		patch.Name = &name
		changes["name"] = name
	}
	if req.Archived != nil {
		if !mod && !owner {
			deny()
			return
		}
		patch.Archived = req.Archived
		changes["archived"] = *req.Archived
	}
	if req.Locked != nil {
		if !mod {
			deny()
			return
		}
		patch.Locked = req.Locked
		changes["locked"] = *req.Locked
	}
	if req.AutoArchive != nil {
		if !mod && !owner {
			deny()
			return
		}
		if !validAutoArchive(*req.AutoArchive) {
			WriteError(w, E(http.StatusBadRequest, "invalid_auto_archive", "A thread closes after an hour, a day, three days or a week without messages."))
			return
		}
		patch.AutoArchive = req.AutoArchive
		changes["auto_archive"] = *req.AutoArchive
	}
	if req.Slowmode != nil {
		if !mod {
			deny()
			return
		}
		if *req.Slowmode < 0 || *req.Slowmode > 21600 {
			WriteError(w, E(http.StatusBadRequest, "invalid_slowmode", "Slow mode is 0–21600 seconds."))
			return
		}
		patch.SlowmodeSeconds = req.Slowmode
		changes["slowmode_seconds"] = *req.Slowmode
	}
	if req.Tags != nil {
		if !mod && !owner {
			deny()
			return
		}
		parent, err := s.Store.ChannelByID(ctx, *th.ParentID)
		if err != nil || !store.IsForum(parent.Type) {
			WriteError(w, E(http.StatusBadRequest, "not_forum", "Only forum posts wear tags."))
			return
		}
		pp, _ := s.Store.PermsIn(ctx, parent, me.ID)
		tags, terr := postTags(parent, *req.Tags, pp)
		if terr != nil {
			WriteError(w, terr)
			return
		}
		if parent.Forum != nil && parent.Forum.RequireTag && len(tags) == 0 {
			WriteError(w, E(http.StatusBadRequest, "tag_required", "Posts here need at least one tag."))
			return
		}
		patch.AppliedTags = &tags
		changes["applied_tags"] = tags
	}
	if err := s.Store.UpdateChannel(ctx, th.ID, patch); err != nil {
		WriteError(w, err)
		return
	}
	updated, err := s.Store.ChannelByID(ctx, th.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	s.audit(r, *th.ServerID, me.ID, "thread_update", "channel", &th.ID, changes, "")
	s.emitThread(ctx, "thread_update", updated)
	WriteJSON(w, http.StatusOK, updated)
}

// threadDelete deletes a thread with everything in it: a moderator's to do,
// or the starter's while nobody else has written in it.
func (s *Server) threadDelete(w http.ResponseWriter, r *http.Request, th *store.Channel, me *store.User) {
	ctx := r.Context()
	p, err := s.Store.PermsIn(ctx, th, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	if !p.Has(perms.ManageThreads) {
		owner := th.OwnerID != nil && *th.OwnerID == me.ID
		others := false
		if owner {
			if list, err := s.Store.Messages(ctx, th.ID, store.MessageQuery{Limit: 100}, me.ID); err == nil {
				for _, m := range list {
					if m.AuthorID != me.ID && !strings.HasPrefix(m.Type, "system_") {
						others = true
						break
					}
				}
			}
		}
		if !owner || others {
			WriteError(w, E(http.StatusForbidden, "missing_permission", "You need Manage Threads to delete this thread."))
			return
		}
	}
	viewers, err := s.Store.ChannelViewers(ctx, th.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	if err := s.Store.DeleteChannel(ctx, th.ID); err != nil {
		WriteError(w, err)
		return
	}
	s.audit(r, *th.ServerID, me.ID, "thread_delete", "channel", &th.ID, map[string]any{"name": th.Name}, "")
	s.Bcast.EmitUsers(viewers, "thread_delete", map[string]any{"id": th.ID, "parent_id": th.ParentID, "server_id": th.ServerID})
	w.WriteHeader(http.StatusNoContent)
}

// threadSweep is how often quiet threads are closed.
const threadSweep = time.Minute

// StartThreads closes threads as they go quiet, until ctx ends.
func (s *Server) StartThreads(ctx context.Context) {
	s.background.Go(func() {
		t := time.NewTicker(threadSweep)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				s.closeQuietThreads(ctx)
				s.clearEmptyPosts(ctx)
			}
		}
	})
}

// emptyPostAge is how long a forum post may wait for its first message.
const emptyPostAge = 30 * time.Minute

// clearEmptyPosts takes away forum posts whose first message never came.
func (s *Server) clearEmptyPosts(ctx context.Context) {
	list, err := s.Store.EmptyForumPosts(ctx, time.Now().Add(-emptyPostAge).UnixMilli(), 200)
	if err != nil {
		s.Log.Warn("threads: empty posts", "err", err)
		return
	}
	for _, th := range list {
		if err := s.Store.DeleteChannel(ctx, th.ID); err == nil && th.OwnerID != nil {
			s.Bcast.EmitUsers([]ids.ID{*th.OwnerID}, "thread_delete", map[string]any{"id": th.ID, "parent_id": th.ParentID, "server_id": th.ServerID})
		}
	}
}

func (s *Server) closeQuietThreads(ctx context.Context) {
	due, err := s.Store.DueThreads(ctx, time.Now().UnixMilli(), 200)
	if err != nil {
		s.Log.Warn("threads: due", "err", err)
		return
	}
	yes := true
	for _, th := range due {
		if err := s.Store.UpdateChannel(ctx, th.ID, store.ChannelPatch{Archived: &yes}); err != nil {
			continue
		}
		if updated, err := s.Store.ChannelByID(ctx, th.ID); err == nil {
			s.emitThread(ctx, "thread_update", updated)
		}
	}
}

// threadActivity is what writing in a thread does to it: whoever writes is
// in it, and a closed thread opens again, unless it is locked, which only
// moderators may write in. It answers false having written the refusal.
func (s *Server) threadActivity(w http.ResponseWriter, r *http.Request, th *store.Channel, me *store.User) bool {
	if th.Thread == nil {
		return true
	}
	ctx := r.Context()
	// a forum post's own first message, from its author: the post goes up
	// for everyone once the message is in (announcePost)
	if th.LastMessageID == nil && th.OwnerID != nil && *th.OwnerID != me.ID && s.isForumPost(ctx, th) {
		WriteError(w, E(http.StatusForbidden, "post_pending", "This post is not up yet."))
		return false
	}
	if th.Thread.Locked || th.Thread.Archived {
		p, err := s.Store.PermsIn(ctx, th, me.ID)
		if err != nil {
			WriteError(w, err)
			return false
		}
		if th.Thread.Locked && !p.Has(perms.ManageThreads) {
			WriteError(w, E(http.StatusForbidden, "thread_locked", "This thread is locked."))
			return false
		}
		if th.Thread.Archived {
			no := false
			if err := s.Store.UpdateChannel(ctx, th.ID, store.ChannelPatch{Archived: &no}); err == nil {
				if fresh, err := s.Store.ChannelByID(ctx, th.ID); err == nil {
					s.emitThread(ctx, "thread_update", fresh)
					*th = *fresh
				}
			}
		}
	}
	if added, err := s.Store.AddThreadMember(ctx, th, me.ID); err == nil && added {
		s.emitThreadMembers(ctx, th, []ids.ID{me.ID}, nil)
	}
	return true
}

// isForumPost reports whether a thread is a forum's post.
func (s *Server) isForumPost(ctx context.Context, th *store.Channel) bool {
	if th.Type != store.ChannelThread || th.ParentID == nil {
		return false
	}
	parent, err := s.Store.ChannelByID(ctx, *th.ParentID)
	return err == nil && store.IsForum(parent.Type)
}

// announcePost tells everyone of a forum post once its first message is in.
func (s *Server) announcePost(ctx context.Context, th *store.Channel, wasEmpty bool) {
	if !wasEmpty || !s.isForumPost(ctx, th) {
		return
	}
	if fresh, err := s.Store.ChannelByID(ctx, th.ID); err == nil {
		s.emitThread(ctx, "thread_create", fresh)
	}
}

// mentionedIntoThread brings the people a message in a private thread
// mentions into it, as long as they can see its channel.
func (s *Server) mentionedIntoThread(r *http.Request, th *store.Channel, me *store.User, mentions []ids.ID) {
	if th.Type != store.ChannelPrivateThread || len(mentions) == 0 {
		return
	}
	ctx := r.Context()
	var added []ids.ID
	for _, id := range mentions {
		if id == me.ID || !s.mayAddToThread(ctx, th, id) {
			continue
		}
		if ok, err := s.Store.AddThreadMember(ctx, th, id); err == nil && ok {
			added = append(added, id)
		}
	}
	if len(added) > 0 {
		s.threadMembersChanged(r, th, me, added, nil)
	}
}

// cleanForum checks a forum's settings: up to 20 tags, each named, new ones
// given ids; the sort, the layout, and whether a post needs a tag. The pinned
// post is kept as it was: pinning has a route of its own.
func cleanForum(old, next *store.ForumSettings) (*store.ForumSettings, *Error) {
	out := &store.ForumSettings{Tags: []store.ForumTag{}, DefaultReaction: strings.TrimSpace(next.DefaultReaction), RequireTag: next.RequireTag, Sort: next.Sort, Layout: next.Layout, HideDownloads: next.HideDownloads}
	if old != nil {
		out.PinnedPost = old.PinnedPost
	}
	if !OneOf(out.Sort, "activity", "created") {
		out.Sort = "activity"
	}
	if !OneOf(out.Layout, "list", "gallery") {
		out.Layout = "list"
	}
	if len(next.Tags) > maxForumTags {
		return nil, E(http.StatusBadRequest, "too_many_tags", "A forum has at most 20 tags.")
	}
	known := map[ids.ID]bool{}
	if old != nil {
		for _, t := range old.Tags {
			known[t.ID] = true
		}
	}
	names := map[string]bool{}
	for _, t := range next.Tags {
		name, ok := CleanText(strings.Join(strings.Fields(t.Name), " "), 20)
		if !ok || name == "" {
			return nil, E(http.StatusBadRequest, "invalid_tag", "A tag's name is 1 to 20 characters.")
		}
		key := strings.ToLower(name)
		if names[key] {
			return nil, E(http.StatusBadRequest, "invalid_tag", "Two tags cannot share a name.")
		}
		names[key] = true
		id := t.ID
		if id.IsZero() || !known[id] {
			id = ids.New()
		}
		emoji := strings.TrimSpace(t.Emoji)
		if emoji != "" {
			if _, ok := parseEmoji(emoji); !ok {
				return nil, E(http.StatusBadRequest, "invalid_tag", "A tag's emoji is one emoji.")
			}
		}
		out.Tags = append(out.Tags, store.ForumTag{ID: id, Name: name, Emoji: emoji, Moderated: t.Moderated})
	}
	if out.RequireTag && len(out.Tags) == 0 {
		return nil, E(http.StatusBadRequest, "invalid_tag", "Add a tag before asking every post for one.")
	}
	return out, nil
}
