package api

import (
	"context"
	"errors"
	"net/http"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/store"
)

func (s *Server) groupRoutes(mux *http.ServeMux) {
	mux.Handle("GET /api/v1/channels/{id}/invites", s.authed(s.handleGroupInvites))
}

func hasID(list []ids.ID, id ids.ID) bool {
	for _, x := range list {
		if x == id {
			return true
		}
	}
	return false
}

// dmChannel loads a DM or group DM the caller belongs to.
func (s *Server) dmChannel(w http.ResponseWriter, r *http.Request) (*store.Channel, *store.User) {
	me := auth.UserFrom(r.Context())
	id, err := ids.Parse(r.PathValue("id"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil, nil
	}
	ch, err := s.Store.ChannelByID(r.Context(), id)
	if err != nil || !s.isCallable(ch) || !hasID(ch.Recipients, me.ID) {
		WriteError(w, ErrNotFound)
		return nil, nil
	}
	return ch, me
}

// handleGroupInvites lists a group's live invite links.
func (s *Server) handleGroupInvites(w http.ResponseWriter, r *http.Request) {
	ch, _ := s.dmChannel(w, r)
	if ch == nil {
		return
	}
	if ch.Type != "group_dm" {
		WriteError(w, E(http.StatusBadRequest, "not_group", "Only group DMs have invite links."))
		return
	}
	list, err := s.Store.InvitesForChannel(r.Context(), ch.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, list)
}

// handleDMDelete closes a DM for the caller, or removes the conversation for
// everyone when purge is asked for. A group is left, unless its owner deletes it.
func (s *Server) handleDMDelete(w http.ResponseWriter, r *http.Request) {
	ch, me := s.dmChannel(w, r)
	if ch == nil {
		return
	}
	purge := r.URL.Query().Get("purge") == "true"
	if purge && mutedFor(w, r) {
		return
	}
	if ch.Type == "group_dm" {
		if !purge {
			// leaving is the ordinary way out of a group
			s.leaveGroup(w, r, ch, me)
			return
		}
		if !managesGroup(ch, me) {
			WriteError(w, E(http.StatusForbidden, "not_owner", "Only the group's owner can delete it."))
			return
		}
		recipients := ch.Recipients
		if err := s.deleteConversation(r.Context(), ch); err != nil {
			WriteError(w, err)
			return
		}
		s.voiceChannelDeleted(r.Context(), ch)
		s.Bcast.EmitUsers(recipients, "channel_delete", map[string]any{"id": ch.ID})
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if !purge {
		if err := s.Store.HideDM(r.Context(), ch.ID, me.ID, true); err != nil {
			WriteError(w, err)
			return
		}
		s.Bcast.EmitUsers([]ids.ID{me.ID}, "channel_delete", map[string]any{"id": ch.ID, "closed": true})
		w.WriteHeader(http.StatusNoContent)
		return
	}
	// deleting a one-to-one conversation takes it away from both sides
	recipients := ch.Recipients
	if err := s.deleteConversation(r.Context(), ch); err != nil {
		WriteError(w, err)
		return
	}
	s.voiceChannelDeleted(r.Context(), ch)
	s.Bcast.EmitUsers(recipients, "channel_delete", map[string]any{"id": ch.ID})
	w.WriteHeader(http.StatusNoContent)
}

// deleteConversation deletes a DM or group together with its subchats, and
// tells whoever is in each subchat that it is gone. The caller tells the
// conversation's own members about the conversation.
func (s *Server) deleteConversation(ctx context.Context, ch *store.Channel) error {
	subs, err := s.Store.SubchatsFor(ctx, ch.ID)
	if err != nil {
		return err
	}
	if err := s.Store.DeleteChannel(ctx, ch.ID); err != nil {
		return err
	}
	for _, sub := range subs {
		s.Bcast.EmitUsers(sub.Recipients, "channel_delete", map[string]any{"id": sub.ID, "parent_id": ch.ID})
	}
	return nil
}

// subchatsJoined brings a new group member into the group's subchats, which
// hold the same people as the group: each appears for them, and a holder of
// its key is asked to share it.
func (s *Server) subchatsJoined(ctx context.Context, subs []ids.ID, uid ids.ID) {
	for _, id := range subs {
		sub, err := s.Store.ChannelByID(ctx, id)
		if err != nil {
			continue
		}
		others := make([]ids.ID, 0, len(sub.Recipients))
		for _, r := range sub.Recipients {
			if r != uid {
				others = append(others, r)
			}
		}
		s.Bcast.EmitUsers([]ids.ID{uid}, "channel_create", sub)
		s.Bcast.EmitUsers(others, "channel_update", sub)
		s.Bcast.KeysMaybeNeeded(id)
	}
}

// subchatsLeft follows someone out of a group's subchats: each disappears for
// them, and whoever is left moves to a fresh key they do not hold.
func (s *Server) subchatsLeft(ctx context.Context, parentID ids.ID, subs []ids.ID, uid ids.ID) {
	tr := true
	for _, id := range subs {
		s.Bcast.EmitUsers([]ids.ID{uid}, "channel_delete", map[string]any{"id": id, "parent_id": parentID})
		sub, err := s.Store.ChannelByID(ctx, id)
		if err != nil || len(sub.Recipients) == 0 {
			continue
		}
		s.Bcast.EmitUsers(sub.Recipients, "channel_update", sub)
		_ = s.Store.SetKeyFlags(ctx, id, &tr, nil)
		s.Bcast.EmitUsers(sub.Recipients, "key_rotation_needed", map[string]any{"conv_id": id})
	}
}

// leaveGroup removes the caller from a group DM.
func (s *Server) leaveGroup(w http.ResponseWriter, r *http.Request, ch *store.Channel, me *store.User) {
	if err := s.removeFromGroup(r, ch, me.ID); err != nil {
		WriteError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// removeFromGroup takes someone out of a group DM, handing ownership on if
// needed, deleting the group if nobody is left, and telling everyone.
func (s *Server) removeFromGroup(r *http.Request, ch *store.Channel, uid ids.ID) error {
	ctx := r.Context()
	// out of the group's call first, while they are still a recipient to hear it
	s.dropVoice(ctx, uid, func(c *store.Channel) bool { return c.ID == ch.ID })
	// Who is left comes from the removal's own transaction. Filtering the
	// recipient list loaded before it meant the last two members leaving at
	// once each saw the other still there, so the empty group was never
	// deleted and nothing ever cleaned it up.
	subs, remaining, err := s.Store.RemoveParticipant(ctx, ch.ID, uid)
	if err != nil {
		return err
	}
	s.subchatsLeft(ctx, ch.ID, subs, uid)
	if len(remaining) == 0 {
		s.voiceChannelDeleted(ctx, ch)
		_ = s.deleteConversation(ctx, ch)
		s.Bcast.EmitUsers([]ids.ID{uid}, "channel_delete", map[string]any{"id": ch.ID})
		return nil
	}
	if ch.OwnerID != nil && *ch.OwnerID == uid {
		next := remaining[0]
		_ = s.Store.UpdateChannel(ctx, ch.ID, store.ChannelPatch{OwnerID: &next})
	}
	if r.URL.Query().Get("silent") != "true" {
		s.systemMessage(r, ch, uid, "system_member_remove", []ids.ID{uid}, nil)
	}
	if updated, err := s.Store.ChannelByID(ctx, ch.ID); err == nil {
		s.Bcast.EmitUsers(remaining, "channel_update", updated)
	}
	s.Bcast.EmitUsers([]ids.ID{uid}, "channel_delete", map[string]any{"id": ch.ID})
	// whoever is left keeps talking under a fresh key
	if s.leaveRotates(ch.ID) {
		tr := true
		_ = s.Store.SetKeyFlags(ctx, ch.ID, &tr, nil)
		s.Bcast.EmitUsers(remaining, "key_rotation_needed", map[string]any{"conv_id": ch.ID})
	}
	return nil
}

// groupInvitePreview answers the public preview for a group DM invite.
func (s *Server) groupInvitePreview(w http.ResponseWriter, r *http.Request, in *store.Invite) {
	if in.ChannelID == nil {
		WriteError(w, E(http.StatusNotFound, "invite_invalid", "This invite is invalid or has expired."))
		return
	}
	ch, err := s.Store.ChannelByID(r.Context(), *in.ChannelID)
	if err != nil || ch.Type != "group_dm" {
		WriteError(w, E(http.StatusNotFound, "invite_invalid", "This invite is invalid or has expired."))
		return
	}
	name := ch.Name
	if name == "" {
		name = "Group chat"
	}
	resp := map[string]any{
		"code":       in.Code,
		"expires_at": in.ExpiresAt,
		"group":      map[string]any{"id": ch.ID, "name": name, "icon_id": ch.IconID, "member_count": len(ch.Recipients)},
	}
	if u, err := s.Store.UserByID(r.Context(), in.InviterID); err == nil {
		resp["inviter"] = u.Public()
	}
	WriteJSON(w, http.StatusOK, resp)
}

// groupInviteJoin adds the caller to the group the invite points at.
func (s *Server) groupInviteJoin(w http.ResponseWriter, r *http.Request, in *store.Invite, me *store.User) {
	ctx := r.Context()
	if in.ChannelID == nil {
		WriteError(w, E(http.StatusNotFound, "invite_invalid", "This invite is invalid or has expired."))
		return
	}
	ch, err := s.Store.ChannelByID(ctx, *in.ChannelID)
	if err != nil || ch.Type != "group_dm" {
		WriteError(w, E(http.StatusNotFound, "invite_invalid", "This invite is invalid or has expired."))
		return
	}
	if hasID(ch.Recipients, me.ID) {
		WriteJSON(w, http.StatusOK, map[string]any{"channel": ch})
		return
	}
	// spend the use first: two people tapping a one-use link together must
	// not both get in
	signup, err := s.Store.UseInviteFor(ctx, me.ID, in.Code)
	if errors.Is(err, store.ErrNotFound) {
		WriteError(w, E(http.StatusNotFound, "invite_invalid", "This invite is invalid or has expired."))
		return
	} else if err != nil {
		WriteError(w, err)
		return
	}
	subs, err := s.Store.AddParticipant(ctx, ch.ID, me.ID, me.ID)
	if err != nil {
		_ = s.Store.GiveBackInviteUse(ctx, me.ID, in.Code, signup)
		if errors.Is(err, store.ErrAlreadyExists) {
			WriteJSON(w, http.StatusOK, map[string]any{"channel": ch})
			return
		}
		WriteError(w, err)
		return
	}
	updated, err := s.Store.ChannelByID(ctx, ch.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	s.Bcast.EmitUsers(updated.Recipients, "channel_create", updated)
	s.systemMessage(r, updated, me.ID, "system_member_add", []ids.ID{me.ID}, nil)
	s.Bcast.PresenceChanged(me.ID)
	s.Bcast.KeysMaybeNeeded(updated.ID)
	s.subchatsJoined(ctx, subs, me.ID)
	users := []store.PublicUser{}
	if byID, err := s.Store.UsersByIDs(ctx, updated.Recipients); err == nil {
		for _, id := range updated.Recipients {
			if u := byID[id]; u != nil {
				users = append(users, u.Public())
			}
		}
	}
	WriteJSON(w, http.StatusCreated, map[string]any{"channel": updated, "users": users})
}
