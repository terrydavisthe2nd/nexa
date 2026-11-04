package api

import (
	"context"
	"errors"
	"net/http"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/gateway"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/media"
	"evilion.xyz/chat/internal/perms"
	"evilion.xyz/chat/internal/store"
)

const (
	// only the one-shot create call is capped, and only to keep the request small;
	// a group grows without limit through invites and adds
	maxGroupCreate = 50
	maxChannelName = 100
)

// errLinkOnly refuses to put someone in a group who has turned off "Let
// friends add me to group chats": they join groups only from an invite link,
// which always works for them.
var errLinkOnly = E(http.StatusForbidden, "link_only", "They only join group chats from an invite link. Send them one in a message.")

func (s *Server) channelRoutes(mux *http.ServeMux) {
	mux.Handle("POST /api/v1/users/@me/channels", s.authed(s.handleDMCreate))
	mux.Handle("GET /api/v1/users/@me/channels", s.authed(s.handleDMList))
	mux.Handle("GET /api/v1/channels/{id}", s.authed(s.handleChannelGet))
	mux.Handle("PATCH /api/v1/channels/{id}", s.authed(s.handleChannelPatch))
	mux.Handle("PUT /api/v1/channels/{id}/icon", s.userLimited(s.limUpload, s.handleChannelIcon))
	mux.Handle("DELETE /api/v1/channels/{id}/icon", s.authed(s.handleChannelIconDelete))
	mux.Handle("POST /api/v1/channels/{id}/recipients/{uid}", s.stepUpUser(s.stepBuild, s.handleRecipientAdd))
	mux.Handle("DELETE /api/v1/channels/{id}/recipients/{uid}", s.authed(s.handleRecipientRemove))
	mux.Handle("DELETE /api/v1/channels/{id}/add-notice", s.authed(s.handleGroupAddNoticeDismiss))
	mux.Handle("POST /api/v1/channels/{id}/typing", s.authed(s.handleTyping))
	mux.Handle("POST /api/v1/channels/{id}/attachments", s.authed(s.handleAttachmentUpload))
	mux.Handle("GET /media/attachments/{id}", s.authed(s.handleAttachmentDownload))
	mux.Handle("GET /api/v1/channels/{id}/pins", s.authed(s.handlePins))
	mux.Handle("PUT /api/v1/channels/{id}/pins/{mid}", s.authed(s.handlePin))
	mux.Handle("DELETE /api/v1/channels/{id}/pins/{mid}", s.authed(s.handleUnpin))
}

// systemMessage inserts and broadcasts a system message in a channel.
func (s *Server) systemMessage(r *http.Request, ch *store.Channel, actor ids.ID, typ string, about []ids.ID, replyTo *ids.ID) {
	m := &store.Message{ChannelID: ch.ID, AuthorID: actor, Type: typ, Mentions: about, ReplyToID: replyTo}
	if m.Mentions == nil {
		m.Mentions = []ids.ID{}
	}
	if err := s.Store.InsertMessage(r.Context(), m); err != nil {
		s.Log.Warn("system message", "err", err)
		return
	}
	// The REST paths give these empty slices (scanMessage fills them in), so
	// the gateway has to as well: nil marshals as null, and the clients declare
	// both fields as always present.
	m.MentionRoles = []ids.ID{}
	m.Attachments = []store.Attachment{}
	m.Reactions = []store.ReactionSummary{}
	// a pin's notice quotes what was pinned
	s.withRefs(r.Context(), 0, m)
	s.Bcast.EmitChannel(ch.ID, "message_create", m)
}

func (s *Server) handleDMCreate(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	var req struct {
		RecipientIDs []ids.ID `json:"recipient_ids"`
		Name         string   `json:"name"`
	}
	if err := ReadJSON(w, r, &req, 8<<10); err != nil {
		WriteError(w, err)
		return
	}
	seen := map[ids.ID]bool{me.ID: true}
	var recipients []ids.ID
	for _, id := range req.RecipientIDs {
		if !seen[id] {
			seen[id] = true
			recipients = append(recipients, id)
		}
	}
	ctx := r.Context()
	// Nobody picked is a group with only its owner in it, to add people to
	// later (the default branch, whose checks have no one to check). A list
	// naming only yourself is a mistake rather than that.
	switch {
	case len(recipients) == 0 && len(req.RecipientIDs) > 0:
		WriteError(w, E(http.StatusBadRequest, "no_recipients", "Pick at least one other person."))
		return
	case len(recipients) == 1:
		target, err := s.Store.UserByID(ctx, recipients[0])
		if err != nil || target.Flags&store.FlagDeleted != 0 || isWebhookAccount(target) {
			WriteError(w, ErrNotFound)
			return
		}
		if isSystem(target) {
			WriteError(w, errNexaNoDM)
			return
		}
		ab, ba, err := s.Store.Blocked(ctx, me.ID, target.ID)
		if err != nil {
			WriteError(w, err)
			return
		}
		if ab || ba {
			WriteError(w, E(http.StatusForbidden, "blocked", "You cannot message this user."))
			return
		}
		friends, err := s.Store.AreFriends(ctx, me.ID, target.ID)
		if err != nil {
			WriteError(w, err)
			return
		}
		if !friends {
			switch target.DMPrivacy {
			case store.DMNobody:
				WriteError(w, E(http.StatusForbidden, "dm_closed", "This user does not accept direct messages."))
				return
			case store.DMFriends:
				WriteError(w, E(http.StatusForbidden, "dm_friends_only", "This user only accepts messages from friends."))
				return
			case store.DMFriendsServers:
				shared, err := s.Store.ShareServer(ctx, me.ID, target.ID)
				if err != nil {
					WriteError(w, err)
					return
				}
				if !shared {
					WriteError(w, E(http.StatusForbidden, "dm_friends_servers", "This user only accepts messages from friends and people they share a server with."))
					return
				}
			}
			// a first message to a stranger is what spam is made of
			exists, err := s.Store.DMExists(ctx, me.ID, target.ID)
			if err != nil {
				WriteError(w, err)
				return
			}
			if !exists && !s.requireCaptcha(w, r, "dm") {
				return
			}
		}
		// The other person is not shown the conversation yet: it reaches their
		// sidebar with the first message or call (revealDM), not the moment
		// someone opens it and perhaps never writes.
		ch, created, err := s.Store.StartDM(ctx, me.ID, target.ID)
		if err != nil {
			WriteError(w, err)
			return
		}
		if created {
			s.Bcast.EmitUsers([]ids.ID{me.ID}, "channel_create", ch)
			WriteJSON(w, http.StatusCreated, ch)
			return
		}
		// opening a DM the caller had closed puts it back for good, not just
		// until the next reconnect rebuilds the sidebar without it
		if reopened, err := s.Store.ReopenDM(ctx, ch.ID, me.ID); err == nil && reopened {
			s.Bcast.EmitUsers([]ids.ID{me.ID}, "channel_create", ch)
		}
		WriteJSON(w, http.StatusOK, ch)
	default:
		if len(recipients) > maxGroupCreate {
			WriteError(w, E(http.StatusBadRequest, "too_many", "Start with fewer people and invite the rest."))
			return
		}
		for _, id := range recipients {
			ok, err := s.Store.AreFriends(ctx, me.ID, id)
			if err != nil {
				WriteError(w, err)
				return
			}
			if !ok {
				WriteError(w, E(http.StatusForbidden, "not_friends", "You can only add friends to a group."))
				return
			}
		}
		// a new group is still a group: whoever joins groups only from a link
		// is sent one instead of being put in it
		people, err := s.Store.UsersByIDs(ctx, recipients)
		if err != nil {
			WriteError(w, err)
			return
		}
		for _, id := range recipients {
			if u := people[id]; u != nil && u.GroupLinkOnly {
				WriteError(w, errLinkOnly)
				return
			}
		}
		name, ok := CleanText(req.Name, maxChannelName)
		if !ok {
			WriteError(w, E(http.StatusBadRequest, "invalid_name", "Group names are at most 100 characters."))
			return
		}
		ch, err := s.Store.CreateGroupDM(ctx, me.ID, recipients, name)
		if err != nil {
			WriteError(w, err)
			return
		}
		s.Bcast.EmitUsers(ch.Recipients, "channel_create", ch)
		for _, id := range recipients {
			s.sendGroupAddNotice(ctx, ch.ID, id)
		}
		WriteJSON(w, http.StatusCreated, ch)
	}
}

// sendGroupAddNotice gives someone a friend just put in a group Nexa's note
// about it: who added them, and how to join groups only from links instead.
// It goes to them alone, and is kept until they dismiss it (READY carries it
// to a device that was offline).
func (s *Server) sendGroupAddNotice(ctx context.Context, chID, uid ids.ID) {
	n, err := s.Store.GroupAddNoticeIn(ctx, chID, uid)
	if err != nil {
		if !errors.Is(err, store.ErrNotFound) {
			s.Log.Warn("group add notice", "channel", chID.String(), "user", uid.String(), "err", err)
		}
		return
	}
	s.Bcast.EmitUsers([]ids.ID{uid}, gateway.EvGroupAddNotice, n)
}

// handleGroupAddNoticeDismiss clears Nexa's note about who added the caller
// to a group, on every device they are signed in on.
func (s *Server) handleGroupAddNoticeDismiss(w http.ResponseWriter, r *http.Request) {
	ch, me := s.channel(w, r, NeedView)
	if ch == nil {
		return
	}
	cleared, err := s.Store.DismissGroupAddNotice(r.Context(), ch.ID, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	if cleared {
		s.Bcast.EmitUsers([]ids.ID{me.ID}, gateway.EvGroupAddNoticeDelete, map[string]any{"channel_id": ch.ID})
	}
	w.WriteHeader(http.StatusNoContent)
}

// revealDM puts a conversation back in the sidebar of everyone in it who does
// not have it there: whoever closed it, and whoever someone started it with
// (StartDM) before anything was said in it. It runs when something new
// happens in the conversation, a message or a call, before that goes out, so
// the conversation reaches them first. A subchat is listed under the
// conversation it belongs to, so that one comes back too.
func (s *Server) revealDM(ctx context.Context, ch *store.Channel) {
	if ch.Type == "subchat" && ch.ParentID != nil {
		if reopened, err := s.Store.UnhideDM(ctx, *ch.ParentID); err == nil && len(reopened) > 0 {
			if parent, err := s.Store.ChannelByID(ctx, *ch.ParentID); err == nil {
				s.Bcast.EmitUsers(reopened, "channel_create", parent)
			}
		}
	}
	if reopened, err := s.Store.UnhideDM(ctx, ch.ID); err == nil && len(reopened) > 0 {
		s.Bcast.EmitUsers(reopened, "channel_create", ch)
	}
}

func (s *Server) handleDMList(w http.ResponseWriter, r *http.Request) {
	list, err := s.Store.DMChannelsForUser(r.Context(), auth.UserFrom(r.Context()).ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, list)
}

func (s *Server) handleChannelGet(w http.ResponseWriter, r *http.Request) {
	ch, _ := s.channel(w, r, NeedView)
	if ch == nil {
		return
	}
	WriteJSON(w, http.StatusOK, ch)
}

func (s *Server) handleChannelPatch(w http.ResponseWriter, r *http.Request) {
	// Each kind checks its own right below: Manage Channels for a server
	// channel (asking for Manage Messages here too locked out channel managers),
	// who may manage a subchat, and ownership for handing a group on.
	ch, me := s.channel(w, r, NeedView)
	if ch == nil {
		return
	}
	if !s.isDM(ch) {
		if s.ServerChannelPatch == nil {
			WriteError(w, ErrForbidden)
			return
		}
		s.ServerChannelPatch(w, r, ch, me)
		return
	}
	if ch.Type == "subchat" {
		s.handleSubchatPatch(w, r, ch, me)
		return
	}
	if ch.Type != "group_dm" {
		WriteError(w, E(http.StatusBadRequest, "not_group", "Only group DMs can be edited."))
		return
	}
	var req struct {
		Name    *string `json:"name"`
		OwnerID *ids.ID `json:"owner_id"`
	}
	if err := ReadJSON(w, r, &req, 4<<10); err != nil {
		WriteError(w, err)
		return
	}
	var p store.ChannelPatch
	if req.Name != nil {
		name, ok := CleanText(*req.Name, maxChannelName)
		if !ok {
			WriteError(w, E(http.StatusBadRequest, "invalid_name", "Group names are at most 100 characters."))
			return
		}
		p.Name = &name
	}
	if req.OwnerID != nil {
		if !managesGroup(ch, me) {
			WriteError(w, E(http.StatusForbidden, "not_owner", "Only the group owner can transfer ownership."))
			return
		}
		found := false
		for _, id := range ch.Recipients {
			if id == *req.OwnerID {
				found = true
			}
		}
		if !found {
			WriteError(w, E(http.StatusBadRequest, "not_member", "The new owner must be in the group."))
			return
		}
		p.OwnerID = req.OwnerID
	}
	// naming the owner they already have changes nothing, and says nothing
	handedOn := req.OwnerID != nil && (ch.OwnerID == nil || *ch.OwnerID != *req.OwnerID)
	if err := s.Store.UpdateChannel(r.Context(), ch.ID, p); err != nil {
		WriteError(w, err)
		return
	}
	updated, err := s.Store.ChannelByID(r.Context(), ch.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	s.Bcast.EmitUsers(updated.Recipients, "channel_update", updated)
	if p.Name != nil && *p.Name != ch.Name {
		s.systemMessage(r, updated, me.ID, "system_name_change", nil, nil)
	}
	if handedOn {
		// said in the group, as adding and removing people are: who made whom its owner
		s.systemMessage(r, updated, me.ID, "system_owner_change", []ids.ID{*req.OwnerID}, nil)
	}
	WriteJSON(w, http.StatusOK, updated)
}

// iconChannel is the channel whose picture a request changes: a group DM its
// owner manages, or a server channel the caller has Manage Channels in, which
// wears it in place of its # or speaker (the picture takes over from its glyph).
func (s *Server) iconChannel(w http.ResponseWriter, r *http.Request) (*store.Channel, *store.User) {
	ch, me := s.channel(w, r, NeedView)
	if ch == nil {
		return nil, nil
	}
	if ch.ServerID != nil && !store.IsThread(ch.Type) {
		p, _, err := s.Store.ChannelPerms(r.Context(), ch.ID, me.ID)
		if err != nil || !p.Has(perms.ManageChannels) {
			WriteError(w, E(http.StatusForbidden, "missing_permission", "You need Manage Channels to edit this channel."))
			return nil, nil
		}
		return ch, me
	}
	if ch.Type != "group_dm" {
		WriteError(w, E(http.StatusBadRequest, "not_group", "Only group DMs and server channels have icons."))
		return nil, nil
	}
	if ok, err := s.canAccess(r, me, ch, NeedManage); err != nil || !ok {
		WriteError(w, E(http.StatusForbidden, "missing_permission", "You do not have permission to do that here."))
		return nil, nil
	}
	return ch, me
}

// iconChanged tells everyone who sees the channel, and records it for a server's audit log.
func (s *Server) iconChanged(r *http.Request, ch *store.Channel, me *store.User, set bool) (*store.Channel, error) {
	updated, err := s.Store.ChannelByID(r.Context(), ch.ID)
	if err != nil {
		return nil, err
	}
	if ch.ServerID != nil {
		s.audit(r, *ch.ServerID, me.ID, "channel_update", "channel", &ch.ID, map[string]any{"icon": set}, "")
		s.Bcast.EmitChannel(ch.ID, "channel_update", updated)
	} else {
		s.Bcast.EmitUsers(updated.Recipients, "channel_update", updated)
		if ch.Type == "group_dm" && (set || ch.IconID != nil) {
			s.systemMessage(r, updated, me.ID, "system_icon_change", nil, nil)
		}
	}
	return updated, nil
}

func (s *Server) handleChannelIcon(w http.ResponseWriter, r *http.Request) {
	ch, me := s.iconChannel(w, r)
	if ch == nil {
		return
	}
	b := s.cropUpload(w, r, "icon", avatarSide, avatarSide, me.ID)
	if b == nil {
		return
	}
	bid := b.ID
	if err := s.Store.SwapImage(r.Context(), "channels", "icon_id", ch.ID, &bid); err != nil {
		s.releaseBlob(r.Context(), b.ID)
		WriteError(w, err)
		return
	}
	if ch.ServerID != nil && ch.Glyph != "" {
		// the picture is the channel's icon now, not whatever was picked before
		empty := ""
		if err := s.Store.UpdateChannel(r.Context(), ch.ID, store.ChannelPatch{Glyph: &empty}); err != nil {
			WriteError(w, err)
			return
		}
	}
	// The icon has already been swapped. A discarded error here left updated
	// nil, so the next line read Recipients off nothing and panicked after the
	// change had landed.
	updated, err := s.iconChanged(r, ch, me, true)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, updated)
}

func (s *Server) handleChannelIconDelete(w http.ResponseWriter, r *http.Request) {
	ch, me := s.iconChannel(w, r)
	if ch == nil {
		return
	}
	if err := s.Store.SwapImage(r.Context(), "channels", "icon_id", ch.ID, nil); err != nil {
		WriteError(w, err)
		return
	}
	if _, err := s.iconChanged(r, ch, me, false); err != nil {
		WriteError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleRecipientAdd(w http.ResponseWriter, r *http.Request) {
	ch, me := s.channel(w, r, NeedManage)
	if ch == nil {
		return
	}
	if ch.Type != "group_dm" {
		WriteError(w, E(http.StatusBadRequest, "not_group", "Only group DMs can gain members."))
		return
	}
	uid, err := ids.Parse(r.PathValue("uid"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	ok, err := s.Store.AreFriends(r.Context(), me.ID, uid)
	if err != nil {
		WriteError(w, err)
		return
	}
	if !ok {
		WriteError(w, E(http.StatusForbidden, "not_friends", "You can only add your friends."))
		return
	}
	target, err := s.Store.UserByID(r.Context(), uid)
	if err != nil {
		WriteError(w, err)
		return
	}
	if target.GroupLinkOnly {
		WriteError(w, errLinkOnly)
		return
	}
	subs, err := s.Store.AddParticipant(r.Context(), ch.ID, uid, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	updated, err := s.Store.ChannelByID(r.Context(), ch.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	s.Bcast.EmitUsers([]ids.ID{uid}, "channel_create", updated)
	s.Bcast.EmitUsers(ch.Recipients, "channel_update", updated)
	s.systemMessage(r, updated, me.ID, "system_member_add", []ids.ID{uid}, nil)
	s.sendGroupAddNotice(r.Context(), ch.ID, uid)
	// the group has never seen the newcomer's presence, nor they the group's
	s.Bcast.PresenceChanged(uid)
	s.Bcast.KeysMaybeNeeded(ch.ID)
	s.subchatsJoined(r.Context(), subs, uid)
	WriteJSON(w, http.StatusOK, updated)
}

func (s *Server) handleRecipientRemove(w http.ResponseWriter, r *http.Request) {
	ch, me := s.channel(w, r, NeedView)
	if ch == nil {
		return
	}
	if ch.Type != "group_dm" {
		WriteError(w, E(http.StatusBadRequest, "not_group", "You cannot leave a direct message."))
		return
	}
	uid, err := ids.Parse(r.PathValue("uid"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	if uid != me.ID && !managesGroup(ch, me) {
		WriteError(w, E(http.StatusForbidden, "not_owner", "Only the group owner can remove others."))
		return
	}
	// muted, someone can still leave, but not put anybody else out
	if uid != me.ID && mutedFor(w, r) {
		return
	}
	// a Nexa admin outranks everyone in a group but its owner, as in a server
	if uid != me.ID && ch.OwnerID != nil && *ch.OwnerID == uid {
		WriteError(w, E(http.StatusForbidden, "is_owner", "The group's owner cannot be removed. Transfer ownership first."))
		return
	}
	// out of the group's call first: the SFU only asks the voice registry
	// who is in it, so someone put out of the group would otherwise go on
	// hearing and being heard until they hung up
	s.dropVoice(r.Context(), uid, func(c *store.Channel) bool { return c.ID == ch.ID })
	// Who is left comes back from the same transaction as the removal: a read
	// of its own could fail, and a failed read looks exactly like an empty
	// group — the next branch deletes the conversation and every message in it.
	subs, remaining, err := s.Store.RemoveParticipant(r.Context(), ch.ID, uid)
	if err != nil {
		WriteError(w, err)
		return
	}
	s.subchatsLeft(r.Context(), ch.ID, subs, uid)
	if len(remaining) == 0 {
		s.voiceChannelDeleted(r.Context(), ch)
		_ = s.deleteConversation(r.Context(), ch)
		s.Bcast.EmitUsers([]ids.ID{uid}, "channel_delete", map[string]any{"id": ch.ID})
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if uid != me.ID {
		// every member can list the group's links, so whoever was just removed
		// has seen them; they stop working rather than letting them back in
		_ = s.Store.RevokeChannelInvites(r.Context(), ch.ID)
	}
	if ch.OwnerID != nil && *ch.OwnerID == uid {
		newOwner := remaining[0]
		_ = s.Store.UpdateChannel(r.Context(), ch.ID, store.ChannelPatch{OwnerID: &newOwner})
	}
	updated, err := s.Store.ChannelByID(r.Context(), ch.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	s.Bcast.EmitUsers([]ids.ID{uid}, "channel_delete", map[string]any{"id": ch.ID})
	s.Bcast.EmitUsers(remaining, "channel_update", updated)
	if uid != me.ID || r.URL.Query().Get("silent") != "true" {
		s.systemMessage(r, updated, me.ID, "system_member_remove", []ids.ID{uid}, nil)
	}
	tr := true
	_ = s.Store.SetKeyFlags(r.Context(), ch.ID, &tr, nil)
	s.Bcast.EmitUsers(remaining, "key_rotation_needed", map[string]any{"conv_id": ch.ID})
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleTyping(w http.ResponseWriter, r *http.Request) {
	ch, me := s.channel(w, r, NeedSend)
	if ch == nil || s.dmBlocked(w, r, ch, me) {
		return
	}
	s.Bcast.Typing(r.Context(), me.ID, ch.ID)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleAttachmentUpload(w http.ResponseWriter, r *http.Request) {
	ch, me := s.channel(w, r, NeedAttach)
	if ch == nil {
		return
	}
	if ok, retry := s.limUpload.Allow("up:" + me.ID.String()); !ok {
		w.Header().Set("Retry-After", retryAfter(retry))
		WriteError(w, ErrRateLimited)
		return
	}
	// One request only takes what fits through the proxy in front; anything
	// larger goes through /uploads in pieces.
	limit := min(s.fileMaxFor(r.Context(), me.Plan, &ch.ID), MaxSingleUploadBytes)
	size := limit
	if r.ContentLength > 0 && r.ContentLength < size {
		size = r.ContentLength
	}
	release, err := s.reserveUpload(r.Context(), me.ID, size, false, true)
	if err != nil {
		WriteError(w, err)
		return
	}
	defer release()
	r.Body = http.MaxBytesReader(w, r.Body, limit+1)
	b, err := s.Media.PutEncrypted(r.Context(), r.Body, limit, r.ContentLength, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusCreated, map[string]any{"blob_id": b.ID, "size": b.Size, "channel_id": ch.ID})
}

func (s *Server) handleAttachmentDownload(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	id, err := ids.Parse(r.PathValue("id"))
	if err != nil {
		http.NotFound(w, r)
		return
	}
	b, err := s.Store.BlobByID(r.Context(), id)
	// a webhook's files are served here too, the same way: as bytes, to
	// people who can read the channel, for the app to make something of
	if err != nil || (b.Kind != "attachment" && b.Kind != media.KindWebhookFile) {
		http.NotFound(w, r)
		return
	}
	// A file is its uploader's until something holds it: then it is for
	// whoever can read the conversation it was sent in, or is in the note
	// that holds it.
	// (a published post holds its files in every channel it went to)
	allowed := false
	held, err := s.Store.AttachmentChannels(r.Context(), id)
	if err == nil && len(held) == 0 {
		err = store.ErrNotFound
	}
	for _, chID := range held {
		if ch, cerr := s.Store.ChannelByID(r.Context(), chID); cerr == nil {
			if ok, aerr := s.canAccess(r, me, ch, NeedHistory); aerr == nil && ok {
				allowed = true
				break
			}
		}
	}
	if !allowed {
		if ok, nerr := s.Store.NoteFileReadable(r.Context(), id, me.ID); nerr == nil && ok {
			allowed = true
		}
	}
	// held by nothing yet: only the uploader may fetch it
	if !allowed && errors.Is(err, store.ErrNotFound) && b.OwnerID != nil && *b.OwnerID == me.ID {
		allowed = true
	}
	if !allowed {
		http.NotFound(w, r)
		return
	}
	s.Media.ServeEncrypted(w, r, b)
}

func (s *Server) handlePins(w http.ResponseWriter, r *http.Request) {
	ch, me := s.channel(w, r, NeedHistory)
	if ch == nil {
		return
	}
	list, err := s.Store.Pins(r.Context(), ch.ID, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	s.withRefs(r.Context(), me.ID, list...)
	WriteJSON(w, http.StatusOK, list)
}

func (s *Server) handlePin(w http.ResponseWriter, r *http.Request) {
	ch, me := s.channel(w, r, NeedPin)
	// a pin posts a notice into the conversation, so a block stops it as it
	// stops a message
	if ch == nil || s.dmBlocked(w, r, ch, me) {
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
	if err := s.Store.PinMessage(r.Context(), ch.ID, mid, me.ID); err != nil {
		WriteError(w, err)
		return
	}
	if ch.ServerID != nil {
		author := m.AuthorID
		s.audit(r, *ch.ServerID, me.ID, "message_pin", "user", &author, map[string]any{"channel_id": ch.ID, "message_id": mid}, "")
	}
	s.Bcast.EmitChannel(ch.ID, "pin_update", map[string]any{"channel_id": ch.ID, "message_id": mid, "pinned": true})
	s.systemMessage(r, ch, me.ID, "system_pin", []ids.ID{}, &mid)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleUnpin(w http.ResponseWriter, r *http.Request) {
	ch, me := s.channel(w, r, NeedPin)
	if ch == nil || s.dmBlocked(w, r, ch, me) {
		return
	}
	mid, err := ids.Parse(r.PathValue("mid"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	if err := s.Store.UnpinMessage(r.Context(), ch.ID, mid); err != nil {
		WriteError(w, err)
		return
	}
	if ch.ServerID != nil {
		changes := map[string]any{"channel_id": ch.ID, "message_id": mid}
		var target *ids.ID
		if m, err := s.Store.MessageByID(r.Context(), mid, me.ID); err == nil {
			author := m.AuthorID
			target = &author
		}
		s.audit(r, *ch.ServerID, me.ID, "message_unpin", "user", target, changes, "")
	}
	s.Bcast.EmitChannel(ch.ID, "pin_update", map[string]any{"channel_id": ch.ID, "message_id": mid, "pinned": false})
	w.WriteHeader(http.StatusNoContent)
}
