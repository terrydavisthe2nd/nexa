package api

import (
	"encoding/json"
	"net/http"
	"strconv"
	"strings"

	"evilion.xyz/chat/internal/botproto"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/perms"
	"evilion.xyz/chat/internal/store"
)

// The bot API's channel routes: a channel itself, its permission
// overwrites, invites, threads and forum posts, the people in a thread, and
// webhooks.

func (s *Server) botChannelRoutes(mux *http.ServeMux) {
	c := "/channels/{channel}"
	s.botHandle(mux, "GET", c, authBot, s.botChannelGet, bucketed("read"))
	s.botHandle(mux, "PATCH", c, authBot, s.botChannelPatch, bucketed("channelwrite"))
	s.botHandle(mux, "DELETE", c, authBot, s.botChannelDelete, bucketed("channelwrite"))
	s.botHandle(mux, "PUT", c+"/permissions/{overwrite}", authBot, s.botOverwritePut, bucketed("channelwrite"))
	s.botHandle(mux, "DELETE", c+"/permissions/{overwrite}", authBot, s.botOverwriteDelete, bucketed("channelwrite"))
	s.botHandle(mux, "GET", c+"/invites", authBot, s.botChannelInvites, bucketed("read"))
	s.botHandle(mux, "POST", c+"/invites", authBot, s.botInviteCreate, bucketed("channelwrite"))
	s.botHandle(mux, "POST", c+"/followers", authBot, func(w http.ResponseWriter, r *http.Request) {
		writeBotError(w, dErr(http.StatusBadRequest, 50024, "Following an announcement channel is done in the app here: its posts are end-to-end encrypted for the channel that follows."), nil)
	})
	s.botHandle(mux, "POST", c+"/messages/{message}/threads", authBot, s.botThreadFromMessage, bucketed("channelwrite"))
	s.botHandle(mux, "POST", c+"/threads", authBot, s.botThreadCreate, bucketed("channelwrite"))
	s.botHandle(mux, "GET", c+"/threads/archived/public", authBot, s.botArchivedThreads(false, false), bucketed("read"))
	s.botHandle(mux, "GET", c+"/threads/archived/private", authBot, s.botArchivedThreads(true, false), bucketed("read"))
	s.botHandle(mux, "GET", c+"/users/@me/threads/archived/private", authBot, s.botArchivedThreads(true, true), bucketed("read"))
	s.botHandle(mux, "GET", c+"/thread-members", authBot, s.botThreadMembers, bucketed("read"))
	s.botHandle(mux, "GET", c+"/thread-members/{user}", authBot, s.botThreadMember, bucketed("read"))
	s.botHandle(mux, "PUT", c+"/thread-members/{user}", authBot, s.botThreadMemberSet(true), bucketed("channelwrite"))
	s.botHandle(mux, "DELETE", c+"/thread-members/{user}", authBot, s.botThreadMemberSet(false), bucketed("channelwrite"))
	s.botHandle(mux, "POST", c+"/webhooks", authBot, s.botWebhookCreate, bucketed("channelwrite"))
	s.botHandle(mux, "GET", c+"/webhooks", authBot, s.botChannelWebhooks, bucketed("read"))
	s.botHandle(mux, "GET", "/webhooks/{webhook}", authBot, s.botWebhookGet, bucketed("read"))
	s.botHandle(mux, "PATCH", "/webhooks/{webhook}", authBot, s.botWebhookPatch, bucketed("channelwrite"))
	s.botHandle(mux, "DELETE", "/webhooks/{webhook}", authBot, s.botWebhookDelete, bucketed("channelwrite"))
	s.botHandle(mux, "PUT", c+"/recipients/{user}", authBot, func(w http.ResponseWriter, r *http.Request) {
		writeBotError(w, dErrFeatureUnavailable("Group direct messages made by bots"), nil)
	})
	s.botHandle(mux, "DELETE", c+"/recipients/{user}", authBot, func(w http.ResponseWriter, r *http.Request) {
		writeBotError(w, dErrFeatureUnavailable("Group direct messages made by bots"), nil)
	})
	s.botHandle(mux, "PUT", c+"/voice-status", authBot, func(w http.ResponseWriter, r *http.Request) {
		writeBotError(w, dErrFeatureUnavailable("Voice channel statuses"), nil)
	})
	s.botHandle(mux, "POST", c+"/send-soundboard-sound", authBot, func(w http.ResponseWriter, r *http.Request) {
		writeBotError(w, dErrVoiceNotAvailable, nil)
	})
}

func (s *Server) botChannelGet(w http.ResponseWriter, r *http.Request) {
	ch := s.botChannel(w, r, NeedView)
	if ch == nil {
		return
	}
	b := s.builderFor(r, botOf(r))
	dc, ok := b.channelObj(ch)
	if !ok {
		writeBotError(w, dErrUnknownChannel, nil)
		return
	}
	if store.IsThread(ch.Type) {
		uid := botOf(r).user.ID
		if in, _ := s.Store.IsThreadMember(r.Context(), ch.ID, uid); in {
			tm := dThreadMember{ID: botproto.SP(&ch.ID), UserID: botproto.SP(&uid), JoinTimestamp: botproto.Timestamp(ch.CreatedAt)}
			dc.Member = &tm
		}
	}
	WriteJSON(w, http.StatusOK, dc)
}

func (s *Server) botChannelPatch(w http.ResponseWriter, r *http.Request) {
	ch := s.botChannel(w, r, NeedView)
	if ch == nil {
		return
	}
	if store.IsDMLike(ch.Type) {
		writeBotError(w, dErrMissingPerms, nil)
		return
	}
	raw, derr := readBotObject(w, r, 256<<10)
	if derr != nil {
		writeBotError(w, derr, nil)
		return
	}
	if err := s.applyChannelPatch(r, ch, raw); err != nil {
		writeBotError(w, err, dErrUnknownChannel)
		return
	}
	fresh, err := s.Store.ChannelByID(r.Context(), ch.ID)
	if err != nil {
		writeBotError(w, err, dErrUnknownChannel)
		return
	}
	dc, _ := s.builderFor(r, botOf(r)).channelObj(fresh)
	WriteJSON(w, http.StatusOK, dc)
}

// applyChannelPatch makes the protocol's changes to a server channel or a
// thread through the app's own routes: its settings, its place, its
// permission overwrites.
func (s *Server) applyChannelPatch(r *http.Request, ch *store.Channel, raw map[string]any) error {
	errs := &formErrors{}
	rd := jsonReader{errs}
	body := map[string]any{}
	if v, ok := rd.str(raw, "name", nil); ok {
		body["name"] = v
	}
	if store.IsThread(ch.Type) {
		if v, ok := rd.boolean(raw, "archived", nil); ok {
			body["archived"] = v
		}
		if v, ok := rd.boolean(raw, "locked", nil); ok {
			body["locked"] = v
		}
		if v, ok := rd.integer(raw, "auto_archive_duration", nil); ok {
			body["auto_archive"] = v
		}
		if v, ok := rd.integer(raw, "rate_limit_per_user", nil); ok {
			body["slowmode_seconds"] = v
		}
		if list, ok := rd.list(raw, "applied_tags", nil); ok {
			body["applied_tags"] = snowList(list)
		}
		if !errs.empty() {
			return errs.err()
		}
		if len(body) == 0 {
			return nil
		}
		if ans := s.callNexa(r, s.handleChannelPatch, http.MethodPatch, map[string]string{"id": ch.ID.String()}, nil, body); !ans.ok() {
			return ans.err()
		}
		return nil
	}
	if v, present := raw["topic"]; present {
		if v == nil {
			body["topic"] = ""
		} else if sv, ok := rd.str(raw, "topic", nil); ok {
			body["topic"] = sv
		}
	}
	if v, ok := rd.boolean(raw, "nsfw", nil); ok {
		body["nsfw"] = v
	}
	if v, present := raw["rate_limit_per_user"]; present {
		if v == nil {
			body["slowmode_seconds"] = 0
		} else if n, ok := rd.integer(raw, "rate_limit_per_user", nil); ok {
			body["slowmode_seconds"] = n
		}
	}
	if v, present := raw["user_limit"]; present {
		if v == nil {
			body["user_limit"] = 0
		} else if n, ok := rd.integer(raw, "user_limit", nil); ok {
			body["user_limit"] = n
		}
	}
	if v, ok := rd.integer(raw, "default_auto_archive_duration", nil); ok {
		body["default_auto_archive"] = v
	}
	if v, ok := rd.integer(raw, "type", nil); ok {
		if typ, ok := nexaChannelType(int(v)); ok && typ != ch.Type {
			body["type"] = typ
		}
	}
	var position *int
	if v, ok := rd.integer(raw, "position", nil); ok {
		n := int(v)
		position = &n
	}
	parentSet := false
	var parent *ids.ID
	if v, present := raw["parent_id"]; present {
		parentSet = true
		if v != nil {
			if sv, ok := rd.str(raw, "parent_id", nil); ok {
				if id, err := parseBotID(sv); err == nil {
					parent = &id
				}
			}
		}
	}
	var overwrites []map[string]any
	overwritesSet := false
	if list, ok := rd.list(raw, "permission_overwrites", nil); ok {
		overwritesSet = true
		for i, v := range list {
			obj, ok := v.(map[string]any)
			if !ok {
				continue
			}
			p := fieldPath("permission_overwrites", i)
			sid, _ := rd.str(obj, "id", p)
			id, err := parseBotID(sid)
			if err != nil {
				// @everyone's overwrite is the server's id, which may be
				// in either form
				errs.add(fieldPath(p, "id"), "NUMBER_TYPE_COERCE", "Value is not snowflake.")
				continue
			}
			t, _ := rd.integer(obj, "type", p)
			allow, _ := rd.str(obj, "allow", p)
			deny, _ := rd.str(obj, "deny", p)
			overwrites = append(overwrites, map[string]any{"id": id, "type": t, "allow": allow, "deny": deny})
		}
	}
	if !errs.empty() {
		return errs.err()
	}
	path := map[string]string{"id": ch.ID.String()}
	if len(body) > 0 {
		if ans := s.callNexa(r, s.handleChannelPatch, http.MethodPatch, path, nil, body); !ans.ok() {
			return ans.err()
		}
	}
	if position != nil || parentSet {
		move := map[string]any{"id": ch.ID, "position": ch.Position}
		if position != nil {
			move["position"] = *position
		}
		if parentSet {
			move["parent_id"] = parent
		}
		if ans := s.callNexa(r, s.handleServerChannelPositions, http.MethodPatch, map[string]string{"id": ch.ServerID.String()}, nil, []map[string]any{move}); !ans.ok() {
			return ans.err()
		}
	}
	if overwritesSet {
		if err := s.replaceOverwrites(r, ch, overwrites); err != nil {
			return err
		}
	}
	return nil
}

// snowList reads a list of ids in either form.
func snowList(list []any) []ids.ID {
	out := []ids.ID{}
	for _, v := range list {
		sv := ""
		switch x := v.(type) {
		case string:
			sv = x
		case json.Number:
			sv = x.String()
		}
		if id, err := parseBotID(sv); err == nil {
			out = append(out, id)
		}
	}
	return out
}

// replaceOverwrites makes a channel's permission overwrites exactly list.
func (s *Server) replaceOverwrites(r *http.Request, ch *store.Channel, list []map[string]any) error {
	ctx := r.Context()
	current, err := s.Store.Overwrites(ctx, ch.ID)
	if err != nil {
		return err
	}
	keep := map[ids.ID]bool{}
	for _, ow := range list {
		id := ow["id"].(ids.ID)
		keep[id] = true
		kind := "role"
		if t, _ := ow["type"].(int64); t == 1 {
			kind = "member"
		}
		body := map[string]any{"type": kind, "allow": ow["allow"], "deny": ow["deny"]}
		if ans := s.callNexa(r, s.handleOverwritePut, http.MethodPut, map[string]string{"id": ch.ID.String(), "target": id.String()}, nil, body); !ans.ok() {
			return ans.err()
		}
	}
	for _, ow := range current {
		if !keep[ow.TargetID] {
			if ans := s.callNexa(r, s.handleOverwriteDelete, http.MethodDelete, map[string]string{"id": ch.ID.String(), "target": ow.TargetID.String()}, nil, nil); !ans.ok() {
				return ans.err()
			}
		}
	}
	return nil
}

func (s *Server) botChannelDelete(w http.ResponseWriter, r *http.Request) {
	ch := s.botChannel(w, r, NeedView)
	if ch == nil {
		return
	}
	dc, _ := s.builderFor(r, botOf(r)).channelObj(ch)
	if store.IsDMLike(ch.Type) {
		// a bot closing a conversation closes nothing for the other person
		WriteJSON(w, http.StatusOK, dc)
		return
	}
	h := s.handleServerChannelDelete
	if ans := s.callNexa(r, h, http.MethodDelete, map[string]string{"id": ch.ID.String()}, nil, nil); !ans.ok() {
		writeBotError(w, ans.err(), dErrUnknownChannel)
		return
	}
	WriteJSON(w, http.StatusOK, dc)
}

func (s *Server) botOverwritePut(w http.ResponseWriter, r *http.Request) {
	ch := s.botChannel(w, r, NeedView)
	if ch == nil {
		return
	}
	id, ok := pathID(r, "overwrite")
	if !ok {
		writeBotError(w, dErrUnknownOverwrite, nil)
		return
	}
	raw, derr := readBotObject(w, r, 4<<10)
	if derr != nil {
		writeBotError(w, derr, nil)
		return
	}
	rd := jsonReader{&formErrors{}}
	t, _ := rd.integer(raw, "type", nil)
	allow, _ := rd.str(raw, "allow", nil)
	deny, _ := rd.str(raw, "deny", nil)
	kind := "role"
	if t == 1 {
		kind = "member"
	}
	if allow == "" {
		allow = "0"
	}
	if deny == "" {
		deny = "0"
	}
	body := map[string]any{"type": kind, "allow": allow, "deny": deny}
	if ans := s.callNexa(r, s.handleOverwritePut, http.MethodPut, map[string]string{"id": ch.ID.String(), "target": id.String()}, nil, body); !ans.ok() {
		writeBotError(w, ans.err(), dErrUnknownOverwrite)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) botOverwriteDelete(w http.ResponseWriter, r *http.Request) {
	ch := s.botChannel(w, r, NeedView)
	if ch == nil {
		return
	}
	id, ok := pathID(r, "overwrite")
	if !ok {
		writeBotError(w, dErrUnknownOverwrite, nil)
		return
	}
	if ans := s.callNexa(r, s.handleOverwriteDelete, http.MethodDelete, map[string]string{"id": ch.ID.String(), "target": id.String()}, nil, nil); !ans.ok() {
		writeBotError(w, ans.err(), dErrUnknownOverwrite)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// --- invites ---------------------------------------------------------------------------------

func (s *Server) botChannelInvites(w http.ResponseWriter, r *http.Request) {
	ch := s.botChannel(w, r, NeedManage)
	if ch == nil {
		return
	}
	list, err := s.Store.InvitesForChannel(r.Context(), ch.ID)
	if err != nil {
		writeBotError(w, err, nil)
		return
	}
	b := s.builderFor(r, botOf(r))
	out := make([]dInvite, 0, len(list))
	for _, inv := range list {
		out = append(out, b.inviteObj(inv, true))
	}
	WriteJSON(w, http.StatusOK, out)
}

func (s *Server) botInviteCreate(w http.ResponseWriter, r *http.Request) {
	ch := s.botChannel(w, r, NeedView)
	if ch == nil {
		return
	}
	raw := map[string]any{}
	if r.ContentLength != 0 {
		var derr *dError
		if raw, derr = readBotObject(w, r, 4<<10); derr != nil {
			writeBotError(w, derr, nil)
			return
		}
	}
	rd := jsonReader{&formErrors{}}
	maxAge := int64(86400)
	if v, ok := rd.integer(raw, "max_age", nil); ok {
		maxAge = v
	}
	if maxAge < 0 || maxAge > 604800 {
		writeBotError(w, dFormError("NUMBER_TYPE_MAX", "int value should be between 0 and 604800.", "max_age"), nil)
		return
	}
	maxUses := int64(0)
	if v, ok := rd.integer(raw, "max_uses", nil); ok {
		maxUses = v
	}
	if maxUses < 0 || maxUses > 100 {
		writeBotError(w, dFormError("NUMBER_TYPE_MAX", "int value should be between 0 and 100.", "max_uses"), nil)
		return
	}
	ans := s.callNexa(r, s.handleInviteCreate, http.MethodPost, map[string]string{"id": ch.ID.String()}, nil, map[string]any{"max_age": maxAge, "max_uses": maxUses})
	if !ans.ok() {
		writeBotError(w, ans.err(), dErrUnknownChannel)
		return
	}
	var inv store.Invite
	if err := ans.decode(&inv); err != nil {
		writeBotError(w, err, nil)
		return
	}
	WriteJSON(w, http.StatusOK, s.builderFor(r, botOf(r)).inviteObj(&inv, true))
}

// --- threads ---------------------------------------------------------------------------------

// threadType is a thread's type from the protocol's number: 11 public, 12
// private (10 is an announcement thread, made public here).
func threadPrivate(t int64) bool { return t == dChanPrivateThread }

func (s *Server) botThreadFromMessage(w http.ResponseWriter, r *http.Request) {
	ch := s.botChannel(w, r, NeedView)
	if ch == nil {
		return
	}
	m := s.botMessage(w, r, ch)
	if m == nil {
		return
	}
	raw, derr := readBotObject(w, r, 16<<10)
	if derr != nil {
		writeBotError(w, derr, nil)
		return
	}
	rd := jsonReader{&formErrors{}}
	name, _ := rd.str(raw, "name", nil)
	body := map[string]any{"name": name, "message_id": m.ID}
	if v, ok := rd.integer(raw, "auto_archive_duration", nil); ok {
		body["auto_archive"] = v
	}
	if v, ok := rd.integer(raw, "rate_limit_per_user", nil); ok {
		body["slowmode_seconds"] = v
	}
	s.createThread(w, r, ch, body, nil)
}

func (s *Server) botThreadCreate(w http.ResponseWriter, r *http.Request) {
	ch := s.botChannel(w, r, NeedView)
	if ch == nil {
		return
	}
	b := botOf(r)
	if store.IsForum(ch.Type) {
		// a forum post: the thread and its first message, which the bot
		// sends as it sends any message
		raw, files, err := s.readWebhookBody(w, r, b.user.ID)
		if err != nil {
			writeBotError(w, err, nil)
			return
		}
		rd := jsonReader{&formErrors{}}
		name, _ := rd.str(raw, "name", nil)
		msg, ok := rd.object(raw, "message", nil)
		if !ok {
			s.dropFiles(files)
			writeBotError(w, dFormError("BASE_TYPE_REQUIRED", "This field is required", "message"), nil)
			return
		}
		in, errs := parseWebhookBody(msg, whParseOpts{app: true})
		if !errs.empty() {
			s.dropFiles(files)
			writeBotError(w, errs.err(), nil)
			return
		}
		body := map[string]any{"name": name}
		if v, ok := rd.integer(raw, "auto_archive_duration", nil); ok {
			body["auto_archive"] = v
		}
		if v, ok := rd.integer(raw, "rate_limit_per_user", nil); ok {
			body["slowmode_seconds"] = v
		}
		if list, ok := rd.list(raw, "applied_tags", nil); ok {
			body["applied_tags"] = snowList(list)
		}
		s.createThread(w, r, ch, body, &botSend{in: in, files: files})
		return
	}
	raw, derr := readBotObject(w, r, 16<<10)
	if derr != nil {
		writeBotError(w, derr, nil)
		return
	}
	rd := jsonReader{&formErrors{}}
	name, _ := rd.str(raw, "name", nil)
	t := int64(dChanPrivateThread)
	if v, ok := rd.integer(raw, "type", nil); ok {
		t = v
	}
	body := map[string]any{"name": name, "private": threadPrivate(t)}
	if v, ok := rd.integer(raw, "auto_archive_duration", nil); ok {
		body["auto_archive"] = v
	}
	if v, ok := rd.integer(raw, "rate_limit_per_user", nil); ok {
		body["slowmode_seconds"] = v
	}
	s.createThread(w, r, ch, body, nil)
}

// createThread starts a thread through the app's own route and answers with
// it; first is a forum post's first message.
func (s *Server) createThread(w http.ResponseWriter, r *http.Request, parent *store.Channel, body map[string]any, first *botSend) {
	ctx := r.Context()
	b := botOf(r)
	ans := s.callNexa(r, s.handleThreadCreate, http.MethodPost, map[string]string{"id": parent.ID.String()}, nil, body)
	if !ans.ok() {
		if first != nil {
			s.dropFiles(first.files)
		}
		writeBotError(w, ans.err(), dErrUnknownChannel)
		return
	}
	var created struct {
		ID ids.ID `json:"id"`
	}
	if err := ans.decode(&created); err != nil || created.ID.IsZero() {
		var wrapped struct {
			Thread struct {
				ID ids.ID `json:"id"`
			} `json:"thread"`
		}
		if json.Unmarshal(ans.body, &wrapped) == nil {
			created.ID = wrapped.Thread.ID
		}
	}
	th, err := s.Store.ChannelByID(ctx, created.ID)
	if err != nil {
		writeBotError(w, err, dErrUnknownChannel)
		return
	}
	bld := s.builderFor(r, b)
	dc, _ := bld.channelObj(th)
	if first == nil {
		WriteJSON(w, http.StatusCreated, dc)
		return
	}
	first.ch = th
	m, err := s.postBotMessage(r, b.user, b.app, first)
	if err != nil {
		s.dropFiles(first.files)
		writeBotError(w, err, nil)
		return
	}
	th, _ = s.Store.ChannelByID(ctx, th.ID)
	out := map[string]any{}
	raw, _ := json.Marshal(dc)
	if th != nil {
		if fresh, ok := bld.channelObj(th); ok {
			raw, _ = json.Marshal(fresh)
		}
	}
	_ = json.Unmarshal(raw, &out)
	out["message"] = bld.messageObj(m, th, msgOpts{content: true})
	WriteJSON(w, http.StatusCreated, out)
}

func (s *Server) botArchivedThreads(private, joined bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ch := s.botChannel(w, r, NeedHistory)
		if ch == nil {
			return
		}
		ctx := r.Context()
		b := botOf(r)
		p := s.botPerms(ctx, ch, b.user.ID)
		if private && !joined && !p.Has(perms.ManageThreads) {
			writeBotError(w, dErrMissingPerms, nil)
			return
		}
		q := r.URL.Query()
		limit := 50
		if v := q.Get("limit"); v != "" {
			if n, err := strconv.Atoi(v); err == nil && n >= 2 && n <= 100 {
				limit = n
			}
		}
		tq := store.ThreadQuery{Parent: ch.ID, Archived: true, Limit: limit + 1}
		if v := q.Get("before"); v != "" {
			if ms, ok := botproto.ParseTimestamp(v); ok {
				tq.Before = ms
			} else if n, err := botproto.Parse(v); err == nil {
				tq.Before = n
			}
		}
		list, err := s.Store.Threads(ctx, tq)
		if err != nil {
			writeBotError(w, err, nil)
			return
		}
		bld := s.builderFor(r, b)
		out := []dChannel{}
		members := []dThreadMember{}
		for _, th := range list {
			if (th.Type == store.ChannelPrivateThread) != private {
				continue
			}
			in, _ := s.Store.IsThreadMember(ctx, th.ID, b.user.ID)
			if joined && !in {
				continue
			}
			if tp, err := s.Store.PermsIn(ctx, th, b.user.ID); err != nil || !tp.Has(perms.ViewChannel) {
				continue
			}
			if dc, ok := bld.channelObj(th); ok {
				out = append(out, dc)
			}
			if in {
				members = append(members, dThreadMember{ID: botproto.SP(&th.ID), UserID: botproto.SP(&b.user.ID), JoinTimestamp: botproto.Timestamp(th.CreatedAt)})
			}
		}
		more := len(out) > limit
		if more {
			out = out[:limit]
		}
		WriteJSON(w, http.StatusOK, map[string]any{"threads": out, "members": members, "has_more": more})
	}
}

// botThreadCtx loads the {channel} of a thread member route, a thread.
func (s *Server) botThreadCtx(w http.ResponseWriter, r *http.Request) *store.Channel {
	ch := s.botChannel(w, r, NeedView)
	if ch == nil {
		return nil
	}
	if !store.IsThread(ch.Type) {
		writeBotError(w, dErrWrongChannelType, nil)
		return nil
	}
	return ch
}

func (s *Server) threadMemberObj(r *http.Request, b *dBuilder, th *store.Channel, tm store.ThreadMember, withMember bool) dThreadMember {
	var m *store.Member
	if withMember && th.ServerID != nil {
		m, _ = s.Store.Member(r.Context(), *th.ServerID, tm.UserID)
	}
	return b.threadMemberObj(tm, m)
}

func (s *Server) botThreadMembers(w http.ResponseWriter, r *http.Request) {
	th := s.botThreadCtx(w, r)
	if th == nil {
		return
	}
	list, err := s.Store.ThreadMembers(r.Context(), th.ID)
	if err != nil {
		writeBotError(w, err, nil)
		return
	}
	q := r.URL.Query()
	withMember := boolQuery(r, "with_member", false)
	limit := 100
	if v := q.Get("limit"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n >= 1 && n <= 100 {
			limit = n
		}
	}
	var after int64
	if v := q.Get("after"); v != "" {
		after, _ = botproto.Parse(v)
	}
	b := s.builderFor(r, botOf(r))
	out := []dThreadMember{}
	for _, tm := range list {
		if int64(tm.UserID) <= after {
			continue
		}
		out = append(out, s.threadMemberObj(r, b, th, tm, withMember))
		if len(out) >= limit {
			break
		}
	}
	WriteJSON(w, http.StatusOK, out)
}

func (s *Server) botThreadMember(w http.ResponseWriter, r *http.Request) {
	th := s.botThreadCtx(w, r)
	if th == nil {
		return
	}
	uid, ok := pathID(r, "user")
	if !ok {
		writeBotError(w, dErrUnknownMember, nil)
		return
	}
	list, err := s.Store.ThreadMembers(r.Context(), th.ID)
	if err != nil {
		writeBotError(w, err, nil)
		return
	}
	for _, tm := range list {
		if tm.UserID == uid {
			WriteJSON(w, http.StatusOK, s.threadMemberObj(r, s.builderFor(r, botOf(r)), th, tm, boolQuery(r, "with_member", false)))
			return
		}
	}
	writeBotError(w, dErrUnknownMember, nil)
}

func (s *Server) botThreadMemberSet(join bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		th := s.botThreadCtx(w, r)
		if th == nil {
			return
		}
		uid := r.PathValue("user")
		if uid != "@me" {
			id, ok := pathID(r, "user")
			if !ok {
				writeBotError(w, dErrUnknownUser, nil)
				return
			}
			uid = id.String()
		}
		h, method := s.handleThreadMemberAdd, http.MethodPut
		if !join {
			h, method = s.handleThreadMemberRemove, http.MethodDelete
		}
		if ans := s.callNexa(r, h, method, map[string]string{"id": th.ID.String(), "uid": uid}, nil, nil); !ans.ok() {
			writeBotError(w, ans.err(), dErrUnknownMember)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// --- webhooks ----------------------------------------------------------------------------------

func (s *Server) botWebhookCreate(w http.ResponseWriter, r *http.Request) {
	ch := s.botChannel(w, r, NeedView)
	if ch == nil {
		return
	}
	raw, derr := readBotObject(w, r, s.Cfg.MaxAvatarBytes*3+16<<10)
	if derr != nil {
		writeBotError(w, derr, nil)
		return
	}
	rd := jsonReader{&formErrors{}}
	name, _ := rd.str(raw, "name", nil)
	body := map[string]any{"name": name}
	if v, ok := rd.str(raw, "avatar", nil); ok && v != "" {
		body["avatar"] = v
	}
	ans := s.callNexa(r, s.handleWebhookCreate, http.MethodPost, map[string]string{"id": ch.ID.String()}, nil, body)
	if !ans.ok() {
		e := ans.err()
		if ae, ok := e.(*Error); ok && ae.Status == http.StatusBadRequest {
			writeBotError(w, dFormError(strings.ToUpper(ae.Code), ae.Message, "name"), nil)
			return
		}
		writeBotError(w, e, dErrUnknownChannel)
		return
	}
	var created struct {
		ID ids.ID `json:"id"`
	}
	_ = ans.decode(&created)
	wh, err := s.Store.WebhookByID(r.Context(), created.ID)
	if err != nil {
		writeBotError(w, err, dErrUnknownWebhook)
		return
	}
	WriteJSON(w, http.StatusOK, s.webhookObj(r.Context(), wh, true))
}

func (s *Server) botChannelWebhooks(w http.ResponseWriter, r *http.Request) {
	ch := s.botChannel(w, r, NeedWebhooks)
	if ch == nil {
		return
	}
	list, err := s.Store.WebhooksByChannel(r.Context(), ch.ID)
	if err != nil {
		writeBotError(w, err, nil)
		return
	}
	out := make([]dWebhook, 0, len(list))
	for _, wh := range list {
		out = append(out, s.webhookObj(r.Context(), wh, true))
	}
	WriteJSON(w, http.StatusOK, out)
}

// botWebhook loads the {webhook} of a route, which the bot may manage.
func (s *Server) botWebhook(w http.ResponseWriter, r *http.Request) *store.Webhook {
	id, ok := pathID(r, "webhook")
	if !ok {
		writeBotError(w, dErrUnknownWebhook, nil)
		return nil
	}
	ctx := r.Context()
	wh, err := s.Store.WebhookByID(ctx, id)
	if err != nil {
		writeBotError(w, dErrUnknownWebhook, nil)
		return nil
	}
	ch, err := s.Store.ChannelByID(ctx, wh.ChannelID)
	if err != nil {
		writeBotError(w, dErrUnknownWebhook, nil)
		return nil
	}
	p, err := s.Store.PermsIn(ctx, ch, botOf(r).user.ID)
	if err != nil || !p.Has(perms.ViewChannel) {
		writeBotError(w, dErrUnknownWebhook, nil)
		return nil
	}
	if !p.Has(perms.ManageWebhooks) {
		writeBotError(w, dErrMissingPerms, nil)
		return nil
	}
	return wh
}

func (s *Server) botWebhookGet(w http.ResponseWriter, r *http.Request) {
	if wh := s.botWebhook(w, r); wh != nil {
		WriteJSON(w, http.StatusOK, s.webhookObj(r.Context(), wh, true))
	}
}

func (s *Server) botWebhookPatch(w http.ResponseWriter, r *http.Request) {
	wh := s.botWebhook(w, r)
	if wh == nil {
		return
	}
	raw, derr := readBotObject(w, r, s.Cfg.MaxAvatarBytes*3+16<<10)
	if derr != nil {
		writeBotError(w, derr, nil)
		return
	}
	rd := jsonReader{&formErrors{}}
	body := map[string]any{}
	if v, ok := rd.str(raw, "name", nil); ok {
		body["name"] = v
	}
	if v, present := raw["avatar"]; present {
		body["avatar"] = v
	}
	if v, ok := rd.str(raw, "channel_id", nil); ok {
		if id, err := parseBotID(v); err == nil {
			body["channel_id"] = id
		}
	}
	ans := s.callNexa(r, s.handleWebhookPatch, http.MethodPatch, map[string]string{"wid": wh.ID.String()}, nil, body)
	if !ans.ok() {
		writeBotError(w, ans.err(), dErrUnknownWebhook)
		return
	}
	fresh, err := s.Store.WebhookByID(r.Context(), wh.ID)
	if err != nil {
		writeBotError(w, err, dErrUnknownWebhook)
		return
	}
	WriteJSON(w, http.StatusOK, s.webhookObj(r.Context(), fresh, true))
}

func (s *Server) botWebhookDelete(w http.ResponseWriter, r *http.Request) {
	wh := s.botWebhook(w, r)
	if wh == nil {
		return
	}
	if ans := s.callNexa(r, s.handleWebhookDelete, http.MethodDelete, map[string]string{"wid": wh.ID.String()}, nil, nil); !ans.ok() {
		writeBotError(w, ans.err(), dErrUnknownWebhook)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
