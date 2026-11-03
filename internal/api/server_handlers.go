package api

import (
	"cmp"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"time"
	"unicode"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/perms"
	"evilion.xyz/chat/internal/store"
)

const (
	maxServerName  = 100
	maxNickname    = 32
	maxTopic       = 1024
	maxServerRoles = 250
	maxServerChans = 500
	maxBanReason   = 512
)

func (s *Server) serverRoutes(mux *http.ServeMux) {
	mux.Handle("POST /api/v1/servers", s.authed(s.handleServerCreate))
	mux.Handle("GET /api/v1/servers/templates/discord/{code}", s.userLimited(s.limDiscord, s.handleDiscordTemplatePreview))
	mux.Handle("POST /api/v1/servers/import/discord", s.userLimited(s.limImport, s.handleDiscordTemplateImport))
	mux.Handle("GET /api/v1/servers/{id}", s.authed(s.handleServerGet))
	mux.Handle("PATCH /api/v1/servers/{id}", s.authed(s.handleServerPatch))
	mux.Handle("DELETE /api/v1/servers/{id}", s.authed(s.handleServerDelete))
	mux.Handle("PUT /api/v1/servers/{id}/icon", s.userLimited(s.limUpload, s.handleServerImage("icon")))
	mux.Handle("DELETE /api/v1/servers/{id}/icon", s.authed(s.handleServerImageDelete("icon")))
	mux.Handle("PUT /api/v1/servers/{id}/banner", s.userLimited(s.limUpload, s.handleServerImage("banner")))
	mux.Handle("DELETE /api/v1/servers/{id}/banner", s.authed(s.handleServerImageDelete("banner")))
	mux.Handle("POST /api/v1/servers/{id}/leave", s.authed(s.handleServerLeave))
	mux.Handle("POST /api/v1/servers/{id}/transfer", s.authed(s.handleServerTransfer))
	mux.Handle("GET /api/v1/servers/{id}/channels", s.authed(s.handleServerChannels))
	mux.Handle("POST /api/v1/servers/{id}/channels", s.stepUpUser(s.stepBuild, s.handleServerChannelCreate))
	mux.Handle("PATCH /api/v1/servers/{id}/channels", s.authed(s.handleServerChannelPositions))
	mux.Handle("DELETE /api/v1/channels/{id}", s.authed(s.handleServerChannelDelete))
	mux.Handle("PUT /api/v1/channels/{id}/permissions/{target}", s.authed(s.handleOverwritePut))
	mux.Handle("DELETE /api/v1/channels/{id}/permissions/{target}", s.authed(s.handleOverwriteDelete))
	mux.Handle("POST /api/v1/channels/{id}/permissions/sync", s.authed(s.handlePermissionsSync))
	mux.Handle("GET /api/v1/servers/{id}/roles", s.authed(s.handleRoles))
	mux.Handle("POST /api/v1/servers/{id}/roles", s.stepUpUser(s.stepBuild, s.handleRoleCreate))
	mux.Handle("PATCH /api/v1/servers/{id}/roles", s.authed(s.handleRolePositions))
	mux.Handle("PATCH /api/v1/servers/{id}/roles/{rid}", s.authed(s.handleRolePatch))
	mux.Handle("DELETE /api/v1/servers/{id}/roles/{rid}", s.authed(s.handleRoleDelete))
	mux.Handle("GET /api/v1/servers/{id}/members", s.authed(s.handleMembers))
	mux.Handle("GET /api/v1/servers/{id}/members/{uid}", s.authed(s.handleMemberGet))
	mux.Handle("PATCH /api/v1/servers/{id}/members/{uid}", s.authed(s.handleMemberPatch))
	mux.Handle("DELETE /api/v1/servers/{id}/members/{uid}", s.authed(s.handleMemberKick))
	mux.Handle("PUT /api/v1/servers/{id}/members/{uid}/roles/{rid}", s.authed(s.handleMemberRole(true)))
	mux.Handle("DELETE /api/v1/servers/{id}/members/{uid}/roles/{rid}", s.authed(s.handleMemberRole(false)))
	mux.Handle("GET /api/v1/servers/{id}/bans", s.authed(s.handleBans))
	mux.Handle("PUT /api/v1/servers/{id}/bans/{uid}", s.authed(s.handleBanPut))
	mux.Handle("DELETE /api/v1/servers/{id}/bans/{uid}", s.authed(s.handleBanDelete))
	mux.Handle("GET /api/v1/servers/{id}/invites", s.authed(s.handleServerInvites))
	mux.Handle("POST /api/v1/channels/{id}/invites", s.stepUpUser(s.stepBuild, s.handleInviteCreate))
	// An invite code is meant to be shared, so anyone who has one can replay
	// this; it is metered per address like every other public route.
	mux.Handle("GET /api/v1/invites/{code}", s.stepUpIP(s.stepLookup, s.handleInvitePreview))
	mux.Handle("POST /api/v1/invites/{code}", s.authed(s.handleInviteJoin))
	mux.Handle("DELETE /api/v1/invites/{code}", s.authed(s.handleInviteDelete))
	mux.Handle("GET /api/v1/servers/{id}/audit-log", s.authed(s.handleAuditLog))
	mux.Handle("GET /api/v1/servers/{id}/keys", s.authed(s.handleServerKeys))
}

// serverCtx loads the server from {id} and checks the caller holds need.
func (s *Server) serverCtx(w http.ResponseWriter, r *http.Request, need perms.Perm) (*store.Server, *store.User, perms.Perm) {
	me := auth.UserFrom(r.Context())
	id, err := ids.Parse(r.PathValue("id"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil, nil, 0
	}
	srv, err := s.Store.ServerByID(r.Context(), id)
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil, nil, 0
	}
	p, err := s.Store.ServerPerms(r.Context(), srv.ID, me.ID)
	if err != nil {
		WriteError(w, err)
		return nil, nil, 0
	}
	if p == 0 {
		// a member whose roles carry no bits (or who is timed out) still
		// belongs here and must be able to see and leave the server
		if ok, _ := s.Store.IsMember(r.Context(), srv.ID, me.ID); !ok {
			WriteError(w, ErrNotFound)
			return nil, nil, 0
		}
	}
	if need != 0 && !p.Has(need) {
		WriteError(w, E(http.StatusForbidden, "missing_permission", "You do not have permission to do that."))
		return nil, nil, 0
	}
	return srv, me, p
}

func (s *Server) audit(r *http.Request, serverID, actor ids.ID, action, targetType string, target *ids.ID, changes any, reason string) {
	// a bot gives its reasons the way its libraries do, in a header
	if reason == "" {
		reason = auditReason(r)
	}
	s.auditAs(r.Context(), serverID, actor, action, targetType, target, changes, reason)
}

// auditAs is audit outside a request's own handling. Every entry also goes
// at once to the members who may read the log, so an open log keeps up.
func (s *Server) auditAs(ctx context.Context, serverID, actor ids.ID, action, targetType string, target *ids.ID, changes any, reason string) {
	var raw json.RawMessage
	if changes != nil {
		raw, _ = json.Marshal(changes)
	}
	e := &store.AuditEntry{ServerID: serverID, ActorID: actor, Action: action, TargetType: targetType, TargetID: target, Changes: raw, Reason: reason}
	if err := s.Store.Audit(ctx, e); err != nil {
		s.Log.Warn("audit", "err", err)
		return
	}
	s.emitServerWithPerm(ctx, serverID, perms.ViewAuditLog, "audit_log_entry", e)
}

func (s *Server) emitServer(ctx context.Context, serverID ids.ID, event string, data any) {
	members, err := s.Store.MemberIDs(ctx, serverID)
	if err != nil {
		return
	}
	s.Bcast.EmitUsers(members, event, data)
}

// emitServerWithPerm is emitServer for events only members holding need may
// see (invite codes, ban reasons); also names users who get it regardless.
func (s *Server) emitServerWithPerm(ctx context.Context, serverID ids.ID, need perms.Perm, event string, data any, also ...ids.ID) {
	targets, err := s.Store.MembersWithPerm(ctx, serverID, need)
	if err != nil {
		return
	}
	for _, id := range also {
		if !hasID(targets, id) {
			targets = append(targets, id)
		}
	}
	s.Bcast.EmitUsers(targets, event, data)
}

// visibility maps user → set of viewable channel ids; used to diff after permission changes.
type visibility map[ids.ID]map[ids.ID]bool

func (s *Server) snapshotVisibility(ctx context.Context, serverID ids.ID) (visibility, error) {
	members, err := s.Store.MemberIDs(ctx, serverID)
	if err != nil {
		return nil, err
	}
	out := visibility{}
	for _, uid := range members {
		vis, err := s.Store.ViewableServerChannels(ctx, serverID, uid)
		if errors.Is(err, store.ErrNotFound) {
			// they left while we looked; everyone else still needs syncing
			continue
		}
		if err != nil {
			return nil, err
		}
		set := map[ids.ID]bool{}
		for id := range vis {
			set[id] = true
		}
		out[uid] = set
	}
	return out, nil
}

// syncVisibility emits channel_create/channel_delete to members whose view of
// the server changed, and channel_update for the changed channels to those who
// still see it. It also flags conversations whose viewer set shrank.
func (s *Server) syncVisibility(ctx context.Context, serverID ids.ID, before visibility, changed ...ids.ID) {
	defer s.recheckSFUServer(ctx, serverID)
	// A snapshot that failed comes back nil, which is not the same as a server
	// nobody can see anything in. Diffing against nil makes every channel look
	// newly visible to every member, which is one channel_create per pair —
	// on a large server, a hundred thousand events nobody asked for.
	if before == nil {
		s.Log.Warn("visibility sync skipped: no snapshot to compare against", "server", serverID.String())
		return
	}
	after, err := s.snapshotVisibility(ctx, serverID)
	if err != nil {
		s.Log.Warn("visibility sync", "server", serverID.String(), "err", err)
		return
	}
	chans, err := s.Store.ServerChannels(ctx, serverID)
	if err != nil {
		s.Log.Warn("visibility sync: channels", "server", serverID.String(), "err", err)
		return
	}
	byID := map[ids.ID]*store.Channel{}
	for _, c := range chans {
		byID[c.ID] = c
	}
	shrank := map[ids.ID]bool{}
	for uid, now := range after {
		prev := before[uid]
		for chID := range now {
			if !prev[chID] {
				if c := byID[chID]; c != nil {
					s.Bcast.EmitUsers([]ids.ID{uid}, "channel_create", c)
				}
			} else if slices.Contains(changed, chID) {
				if c := byID[chID]; c != nil {
					s.Bcast.EmitUsers([]ids.ID{uid}, "channel_update", c)
				}
			}
		}
		for chID := range prev {
			if !now[chID] {
				s.Bcast.EmitUsers([]ids.ID{uid}, "channel_delete", map[string]any{"id": chID})
				shrank[chID] = true
				// a voice channel someone can no longer see is one they are
				// no longer in: the SFU only asks the registry
				s.dropVoice(ctx, uid, func(c *store.Channel) bool { return c.ID == chID })
			}
		}
	}
	for chID := range shrank {
		if c := byID[chID]; c != nil && c.KeyConvID == chID {
			tr := true
			_ = s.Store.SetKeyFlags(ctx, chID, &tr, nil)
			if viewers, err := s.Store.ChannelViewers(ctx, chID); err == nil {
				s.Bcast.EmitUsers(viewers, "key_rotation_needed", map[string]any{"conv_id": chID})
			}
		}
	}
	// most channels share the server root: check each conversation once
	convs := map[ids.ID]bool{}
	for _, c := range byID {
		convs[c.KeyConvID] = true
	}
	for conv := range convs {
		s.Bcast.KeysMaybeNeeded(conv)
	}
}

// --- servers -------------------------------------------------------------

func (s *Server) handleServerCreate(w http.ResponseWriter, r *http.Request) {
	if !s.requireCaptcha(w, r, "create_server") {
		return
	}
	me := auth.UserFrom(r.Context())
	var req struct {
		Name string `json:"name"`
		// what the first categories and channels are called, in the
		// language the person is using; empty means the English defaults
		Defaults struct {
			TextCategory  string `json:"text_category"`
			VoiceCategory string `json:"voice_category"`
			TextChannel   string `json:"text_channel"`
			VoiceChannel  string `json:"voice_channel"`
		} `json:"defaults"`
	}
	if err := ReadJSON(w, r, &req, 4<<10); err != nil {
		WriteError(w, err)
		return
	}
	name, ok := cleanServerName(req.Name)
	if !ok {
		WriteError(w, E(http.StatusBadRequest, "invalid_name", "Server names are 1–100 characters."))
		return
	}
	defaults := store.DefaultNames{
		TextCategory:  cleanDefaultName(req.Defaults.TextCategory),
		VoiceCategory: cleanDefaultName(req.Defaults.VoiceCategory),
		TextChannel:   cleanDefaultName(req.Defaults.TextChannel),
		VoiceChannel:  cleanDefaultName(req.Defaults.VoiceChannel),
	}
	srv, err := s.Store.CreateServer(r.Context(), me.ID, name, defaults)
	if err != nil {
		WriteError(w, err)
		return
	}
	view, users, err := s.serverView(r.Context(), srv, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	s.Bcast.EmitUsers([]ids.ID{me.ID}, "server_create", serverCreateEvent{view, users})
	WriteJSON(w, http.StatusCreated, view)
}

func (s *Server) handleServerGet(w http.ResponseWriter, r *http.Request) {
	srv, me, _ := s.serverCtx(w, r, 0)
	if srv == nil {
		return
	}
	view, users, err := s.serverView(r.Context(), srv, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"server": view, "users": users})
}

func (s *Server) handleServerPatch(w http.ResponseWriter, r *http.Request) {
	srv, me, _ := s.serverCtx(w, r, perms.ManageServer)
	if srv == nil {
		return
	}
	var raw map[string]json.RawMessage
	if err := ReadJSON(w, r, &raw, 16<<10); err != nil {
		WriteError(w, err)
		return
	}
	var p store.ServerPatch
	changes := map[string]any{}
	if v, ok := raw["nsfw"]; ok {
		var on bool
		if string(v) == "null" || json.Unmarshal(v, &on) != nil {
			WriteError(w, E(http.StatusBadRequest, "invalid_value", "nsfw must be true or false."))
			return
		}
		p.NSFW = &on
		changes["nsfw"] = on
	}
	if v, ok := raw["name"]; ok {
		var name string
		if json.Unmarshal(v, &name) != nil {
			WriteError(w, E(http.StatusBadRequest, "invalid_name", "Server names are 1–100 characters."))
			return
		}
		clean, ok := cleanServerName(name)
		if !ok {
			WriteError(w, E(http.StatusBadRequest, "invalid_name", "Server names are 1–100 characters."))
			return
		}
		p.Name = &clean
		changes["name"] = clean
	}
	if v, ok := raw["description"]; ok {
		var d string
		if string(v) != "null" && json.Unmarshal(v, &d) != nil {
			WriteError(w, E(http.StatusBadRequest, "invalid_description", "Description is malformed."))
			return
		}
		clean, ok := CleanText(d, 1000)
		if !ok {
			WriteError(w, E(http.StatusBadRequest, "invalid_description", "Descriptions are at most 1000 characters."))
			return
		}
		p.Description = &clean
		changes["description"] = clean
	}
	if v, ok := raw["system_channel_id"]; ok {
		if string(v) == "null" {
			var nilID *ids.ID
			p.SystemChannelID = &nilID
		} else {
			var id ids.ID
			if json.Unmarshal(v, &id) != nil {
				WriteError(w, E(http.StatusBadRequest, "invalid_channel", "system_channel_id is malformed."))
				return
			}
			ch, err := s.Store.ChannelByID(r.Context(), id)
			if err != nil || ch.ServerID == nil || *ch.ServerID != srv.ID || ch.Type != "text" {
				WriteError(w, E(http.StatusBadRequest, "invalid_channel", "The system channel must be a text channel of this server."))
				return
			}
			idp := &id
			p.SystemChannelID = &idp
		}
		changes["system_channel_id"] = string(v)
	}
	if v, ok := raw["default_notifications"]; ok {
		var n int
		if json.Unmarshal(v, &n) != nil || (n != 0 && n != 1) {
			WriteError(w, E(http.StatusBadRequest, "invalid_value", "default_notifications must be 0 or 1."))
			return
		}
		p.DefaultNotifications = &n
		changes["default_notifications"] = n
	}
	// pausing or resuming invites is a line of its own in the audit log, even
	// when the same request changes other settings too
	pauseAction, pauseChanges := "", map[string]any{}
	if v, ok := raw["invites_paused_until"]; ok {
		// pausing invites stops every way in at once: links, Discovery, the tag
		if string(v) == "null" {
			var open *int64
			p.InvitesPausedUntil = &open
			pauseAction = "invites_resume"
		} else {
			var until int64
			now := time.Now().UnixMilli()
			if json.Unmarshal(v, &until) != nil || until <= now || until > maxPauseUntil {
				WriteError(w, E(http.StatusBadRequest, "invalid_pause", "Pause invites until a time in the future."))
				return
			}
			up := &until
			p.InvitesPausedUntil = &up
			pauseAction = "invites_pause"
			pauseChanges["invites_paused_until"] = until
		}
	}
	for key, dst := range map[string]**int{"verify_account_minutes": &p.VerifyAccountMinutes, "verify_member_minutes": &p.VerifyMemberMinutes} {
		v, ok := raw[key]
		if !ok {
			continue
		}
		var n int
		if json.Unmarshal(v, &n) != nil || n < 0 || n > maxVerifyMinutes {
			WriteError(w, E(http.StatusBadRequest, "invalid_value", "The wait is between 0 minutes and 30 days."))
			return
		}
		*dst = &n
		changes[key] = n
	}
	if !s.discoveryPatch(w, raw, &p, changes) {
		return
	}
	// Discovery shows a server to everyone on Nexa: putting one there is a
	// captcha away, so a script cannot fill it with servers made for spam
	if p.Discoverable != nil && *p.Discoverable && !srv.Discoverable && !s.requireCaptcha(w, r, "discovery_list") {
		return
	}
	if err := s.Store.UpdateServer(r.Context(), srv.ID, p); err != nil {
		WriteError(w, err)
		return
	}
	// A discarded error here used to marshal as null: every member received
	// {"t":"server_update","d":null} and the caller a 200 with a body of
	// null. The write has landed, so saying so and letting the caller repeat
	// the request is better than telling everyone nothing at all.
	if p.NSFW != nil && *p.NSFW && !s.clearNSFWTag(w, r, srv.ID) {
		return
	}
	updated, err := s.Store.ServerByID(r.Context(), srv.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	if len(changes) > 0 || pauseAction == "" {
		s.audit(r, srv.ID, me.ID, "server_update", "server", &srv.ID, changes, "")
	}
	if pauseAction != "" {
		s.audit(r, srv.ID, me.ID, pauseAction, "server", &srv.ID, pauseChanges, "")
	}
	s.emitServer(r.Context(), srv.ID, "server_update", updated)
	if p.InvitesPausedUntil != nil || p.Discoverable != nil || p.DiscoveryHidden != nil {
		s.forgetCounts(srv.ID)
	}
	WriteJSON(w, http.StatusOK, updated)
}

// maxPauseUntil is as far ahead as invites can be paused: far enough to read
// as "until resumed".
const maxPauseUntil = int64(32503680000000) // the year 3000

// maxVerifyMinutes is the longest wait a server can ask of new accounts or members: 30 days.
const maxVerifyMinutes = 30 * 24 * 60

func (s *Server) handleServerImage(kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		srv, me, _ := s.serverCtx(w, r, perms.ManageServer)
		if srv == nil {
			return
		}
		picKind := map[bool]string{true: "banner", false: "icon"}[kind == "banner"]
		maxW, maxH := avatarSide, avatarSide
		if kind == "banner" {
			maxW, maxH = serverBannerW, serverBannerH
		}
		b := s.cropUpload(w, r, picKind, maxW, maxH, me.ID)
		if b == nil {
			return
		}
		bid := b.ID
		col := map[bool]string{true: "banner_id", false: "icon_id"}[kind == "banner"]
		if err := s.Store.SwapImage(r.Context(), "servers", col, srv.ID, &bid); err != nil {
			s.releaseBlob(r.Context(), b.ID)
			WriteError(w, err)
			return
		}
		updated, err := s.Store.ServerByID(r.Context(), srv.ID)
		if err != nil {
			WriteError(w, err)
			return
		}
		s.audit(r, srv.ID, me.ID, "server_update", "server", &srv.ID, map[string]any{kind: b.ID}, "")
		s.emitServer(r.Context(), srv.ID, "server_update", updated)
		WriteJSON(w, http.StatusOK, updated)
	}
}

func (s *Server) handleServerImageDelete(kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		srv, me, _ := s.serverCtx(w, r, perms.ManageServer)
		if srv == nil {
			return
		}
		col := map[bool]string{true: "banner_id", false: "icon_id"}[kind == "banner"]
		if err := s.Store.SwapImage(r.Context(), "servers", col, srv.ID, nil); err != nil {
			WriteError(w, err)
			return
		}
		updated, err := s.Store.ServerByID(r.Context(), srv.ID)
		if err != nil {
			WriteError(w, err)
			return
		}
		s.audit(r, srv.ID, me.ID, "server_update", "server", &srv.ID, map[string]any{kind: nil}, "")
		s.emitServer(r.Context(), srv.ID, "server_update", updated)
		w.WriteHeader(http.StatusNoContent)
	}
}

func (s *Server) handleServerDelete(w http.ResponseWriter, r *http.Request) {
	srv, me, _ := s.serverCtx(w, r, 0)
	if srv == nil {
		return
	}
	if srv.OwnerID != me.ID {
		WriteError(w, E(http.StatusForbidden, "not_owner", "Only the owner can delete a server."))
		return
	}
	// Both reads happen before the delete, and both are who to tell about it:
	// once the rows are gone there is no second chance to find out, and a
	// member who was never told keeps a dead server in their sidebar.
	members, err := s.Store.MemberIDs(r.Context(), srv.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	chans, err := s.Store.ServerChannels(r.Context(), srv.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	// whoever wore its tag has it taken off with the server (store.DeleteServer)
	wearers, err := s.Store.TagWearers(r.Context(), srv.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	if err := s.Store.DeleteServer(r.Context(), srv.ID); err != nil {
		WriteError(w, err)
		return
	}
	s.tagChanged(r.Context(), wearers)
	s.voiceServerDeleted(chans, members)
	s.Bcast.EmitUsers(members, "server_delete", map[string]any{"id": srv.ID})
	w.WriteHeader(http.StatusNoContent)
}

// removeMember drops a membership, tells everyone, and flags keys for rotation.
func (s *Server) removeMember(r *http.Request, srv *store.Server, uid ids.ID, event string, extra map[string]any) error {
	ctx := r.Context()
	// What they could read decides which private channels get a new key. A
	// failed read used to leave this empty and only the server root was
	// flagged, so everyone in a private channel went on encrypting under a key
	// the person just kicked still holds — silently, behind a 204. Read it
	// before anything is written, so refusing costs nothing.
	visible, err := s.Store.ViewableServerChannels(ctx, srv.ID, uid)
	if err != nil && !errors.Is(err, store.ErrNotFound) {
		return err
	}
	// out of the server's calls first, while everyone in them can still be told
	s.dropVoice(ctx, uid, func(ch *store.Channel) bool { return ch.ServerID != nil && *ch.ServerID == srv.ID })
	if err := s.Store.RemoveMember(ctx, srv.ID, uid); err != nil {
		return err
	}
	s.forgetCounts(srv.ID)
	// nobody wears the tag of a server they are no longer in
	s.takeOffLeaver(ctx, srv.ID, uid)
	payload := map[string]any{"server_id": srv.ID, "user_id": uid}
	for k, v := range extra {
		payload[k] = v
	}
	s.emitServer(ctx, srv.ID, event, payload)
	s.Bcast.EmitUsers([]ids.ID{uid}, "server_delete", map[string]any{"id": srv.ID})
	// a bot leaves behind the role it brought and its application's install
	if u, err := s.Store.UserByID(ctx, uid); err == nil && u.Flags&store.FlagBot != 0 {
		s.botLeftServer(r, srv, uid)
	}
	return s.rotateFor(r, srv, visible)
}

// rotateAfter starts a new key in every conversation of a server that uid
// could read and should read no more (a bot that stopped reading
// everything): the server's root and its private channels and threads.
func (s *Server) rotateAfter(r *http.Request, srv *store.Server, uid ids.ID) error {
	visible, err := s.Store.ViewableServerChannels(r.Context(), srv.ID, uid)
	if err != nil && !errors.Is(err, store.ErrNotFound) {
		return err
	}
	return s.rotateFor(r, srv, visible)
}

// rotateFor flags the server root, and the private channels and threads of
// visible, as needing a new key, and asks their members' apps to make one.
func (s *Server) rotateFor(r *http.Request, srv *store.Server, visible map[ids.ID]perms.Perm) error {
	ctx := r.Context()
	// rotate the server root and every private channel the user could read
	convs := []ids.ID{srv.ID}
	chans, err := s.Store.ServerChannels(ctx, srv.ID)
	if err != nil {
		return err
	}
	for _, c := range chans {
		if _, ok := visible[c.ID]; ok && c.KeyConvID == c.ID {
			convs = append(convs, c.ID)
		}
	}
	// and every private thread with a key of its own they could read
	if threads, err := s.Store.PrivateThreads(ctx, srv.ID); err == nil {
		for _, th := range threads {
			if _, ok := visible[th.ID]; ok && th.KeyConvID == th.ID {
				convs = append(convs, th.ID)
			}
		}
	}
	tr := true
	remaining, err := s.Store.MemberIDs(ctx, srv.ID)
	if err != nil {
		return err
	}
	// Every conversation is attempted even after one of them fails, so a
	// single bad read cannot leave the rest of them unflagged; the first
	// failure is what the caller hears about.
	var failed error
	for _, conv := range convs {
		if err := s.Store.SetKeyFlags(ctx, conv, &tr, nil); err != nil {
			failed = cmp.Or(failed, err)
			continue
		}
		targets := remaining
		if conv != srv.ID {
			if targets, err = s.Store.ChannelViewers(ctx, conv); err != nil {
				failed = cmp.Or(failed, err)
				continue
			}
		}
		s.Bcast.EmitUsers(targets, "key_rotation_needed", map[string]any{"conv_id": conv})
	}
	return failed
}

func (s *Server) handleServerLeave(w http.ResponseWriter, r *http.Request) {
	srv, me, _ := s.serverCtx(w, r, 0)
	if srv == nil {
		return
	}
	if srv.OwnerID == me.ID {
		WriteError(w, E(http.StatusBadRequest, "owner_cannot_leave", "Transfer ownership or delete the server first."))
		return
	}
	if err := s.removeMember(r, srv, me.ID, "member_remove", map[string]any{"reason": "leave"}); err != nil {
		WriteError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleServerTransfer(w http.ResponseWriter, r *http.Request) {
	srv, me, _ := s.serverCtx(w, r, 0)
	if srv == nil {
		return
	}
	if srv.OwnerID != me.ID {
		WriteError(w, E(http.StatusForbidden, "not_owner", "Only the owner can transfer a server."))
		return
	}
	var req struct {
		UserID ids.ID `json:"user_id"`
	}
	if err := ReadJSON(w, r, &req, 1<<10); err != nil {
		WriteError(w, err)
		return
	}
	if ok, _ := s.Store.IsMember(r.Context(), srv.ID, req.UserID); !ok {
		WriteError(w, E(http.StatusBadRequest, "not_member", "The new owner must be a member."))
		return
	}
	if heir, err := s.Store.UserByID(r.Context(), req.UserID); err != nil || heir.Flags&(store.FlagDeleted|store.FlagDisabled|store.NotPeople) != 0 {
		WriteError(w, E(http.StatusBadRequest, "not_member", "The new owner must be a member."))
		return
	}
	// The owner sees every channel, so who that is decides who sees the
	// private ones: the old owner may lose some, the new one gains them.
	before, err := s.snapshotVisibility(r.Context(), srv.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	if err := s.Store.UpdateServer(r.Context(), srv.ID, store.ServerPatch{OwnerID: &req.UserID}); err != nil {
		WriteError(w, err)
		return
	}
	s.syncVisibility(r.Context(), srv.ID, before)
	updated, err := s.Store.ServerByID(r.Context(), srv.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	s.audit(r, srv.ID, me.ID, "server_transfer", "user", &req.UserID, nil, "")
	s.emitServer(r.Context(), srv.ID, "server_update", updated)
	WriteJSON(w, http.StatusOK, updated)
}

// --- channels ------------------------------------------------------------

func (s *Server) handleServerChannels(w http.ResponseWriter, r *http.Request) {
	srv, me, _ := s.serverCtx(w, r, 0)
	if srv == nil {
		return
	}
	view, _, err := s.serverView(r.Context(), srv, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, view.Channels)
}

// cleanDefaultName is a name a new server starts with, offered by the client.
// Anything it cannot use becomes empty, which the store reads as "use English".
func cleanDefaultName(name string) string {
	clean, ok := cleanServerName(name)
	if !ok {
		return ""
	}
	return clean
}

// cleanServerName collapses whitespace in a server or role name.
func cleanServerName(name string) (string, bool) {
	clean, ok := CleanText(strings.Join(strings.Fields(name), " "), maxServerName)
	return clean, ok && clean != ""
}

func cleanChannelName(name, typ string) (string, bool) {
	clean, ok := CleanText(strings.ReplaceAll(name, "\n", " "), maxServerName)
	if !ok || clean == "" {
		return "", false
	}
	// Every kind keeps its spaces and capitals; runs of whitespace become one space.
	clean = strings.Join(strings.Fields(clean), " ")
	return clean, clean != ""
}

// maxLinkChannelURL is the longest address a link channel may lead to.
const maxLinkChannelURL = 1024

// cleanLinkURL checks the address a link channel opens: a whole http or
// https address with a host and no user name or password in it, which the
// apps show in full before they open it.
func cleanLinkURL(raw string) (string, bool) {
	raw = strings.TrimSpace(raw)
	if raw == "" || len(raw) > maxLinkChannelURL || strings.ContainsFunc(raw, func(r rune) bool { return r < 0x20 || r == 0x7f || unicode.IsSpace(r) }) {
		return "", false
	}
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.Hostname() == "" || u.User != nil || u.Opaque != "" {
		return "", false
	}
	out := u.String()
	if len(out) > maxLinkChannelURL {
		return "", false
	}
	return out, true
}

func (s *Server) handleServerChannelCreate(w http.ResponseWriter, r *http.Request) {
	srv, me, _ := s.serverCtx(w, r, perms.ManageChannels)
	if srv == nil {
		return
	}
	var req struct {
		Type     string  `json:"type"`
		Name     string  `json:"name"`
		ParentID *ids.ID `json:"parent_id"`
		// URL is where a link channel leads; required for one, refused for the rest.
		URL string `json:"url"`
	}
	if err := ReadJSON(w, r, &req, 4<<10); err != nil {
		WriteError(w, err)
		return
	}
	if !OneOf(req.Type, "text", "voice", "category", store.ChannelAnnouncement, store.ChannelForum, store.ChannelMedia, store.ChannelLink) {
		WriteError(w, E(http.StatusBadRequest, "invalid_type", "type must be text, voice, announcement, forum, media, link or category."))
		return
	}
	name, ok := cleanChannelName(req.Name, req.Type)
	if !ok {
		WriteError(w, E(http.StatusBadRequest, "invalid_name", "Channel names are 1–100 characters."))
		return
	}
	linkURL := ""
	if req.Type == store.ChannelLink {
		if linkURL, ok = cleanLinkURL(req.URL); !ok {
			WriteError(w, E(http.StatusBadRequest, "invalid_url", "A link channel needs a full web address starting with https:// or http://."))
			return
		}
	} else if strings.TrimSpace(req.URL) != "" {
		WriteError(w, E(http.StatusBadRequest, "invalid_url", "Only a link channel has an address."))
		return
	}
	var parent *store.Channel
	if req.Type == "category" {
		req.ParentID = nil
	} else if req.ParentID != nil {
		var err error
		parent, err = s.Store.ChannelByID(r.Context(), *req.ParentID)
		if err != nil || parent.ServerID == nil || *parent.ServerID != srv.ID || parent.Type != "category" {
			WriteError(w, E(http.StatusBadRequest, "invalid_parent", "parent_id must be a category of this server."))
			return
		}
	}
	// A failed read here used to count as zero channels, which passes the cap
	// no matter how many there really are.
	existing, err := s.Store.ServerChannels(r.Context(), srv.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	if len(existing) >= maxServerChans {
		WriteError(w, E(http.StatusBadRequest, "too_many_channels", "This server has reached the channel limit."))
		return
	}
	// This runs before the write, so a failure here costs nothing but the
	// request; carrying on would flood every member with channel_create.
	before, err := s.snapshotVisibility(r.Context(), srv.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	ch, err := s.Store.CreateServerChannel(r.Context(), srv.ID, req.Type, name, req.ParentID, linkURL)
	if errors.Is(err, store.ErrTooMany) {
		WriteError(w, E(http.StatusBadRequest, "too_many_channels", "A server can have at most 500 channels."))
		return
	}
	if err != nil {
		WriteError(w, err)
		return
	}
	// a channel made in a category starts synced with it
	own := false
	if parent != nil && len(parent.Overwrites) > 0 {
		if own, err = s.copyOverwrites(r.Context(), ch, parent.Overwrites); err != nil {
			WriteError(w, err)
			return
		}
		if ch, err = s.Store.ChannelByID(r.Context(), ch.ID); err != nil {
			WriteError(w, err)
			return
		}
	}
	created := map[string]any{"name": name, "type": req.Type}
	if linkURL != "" {
		created["url"] = linkURL
	}
	s.audit(r, srv.ID, me.ID, "channel_create", "channel", &ch.ID, created, "")
	s.syncVisibility(r.Context(), srv.ID, before)
	if own {
		// the maker's app makes the key at once, as for a channel made private
		WriteJSON(w, http.StatusCreated, struct {
			*store.Channel
			KeyActions map[string]any `json:"key_actions"`
		}{ch, map[string]any{"needs_own_key": true, "conv_id": ch.ID}})
		return
	}
	WriteJSON(w, http.StatusCreated, ch)
}

func (s *Server) handleServerChannelPositions(w http.ResponseWriter, r *http.Request) {
	srv, me, _ := s.serverCtx(w, r, perms.ManageChannels)
	if srv == nil {
		return
	}
	var req []struct {
		ID       ids.ID          `json:"id"`
		Position int             `json:"position"`
		ParentID json.RawMessage `json:"parent_id"`
		// LockPermissions syncs a channel moved into a category with it; left
		// out, a channel that was synced where it came from (or had no
		// overwrites outside a category) is synced with where it goes
		LockPermissions *bool `json:"lock_permissions"`
	}
	if err := ReadJSON(w, r, &req, 64<<10); err != nil {
		WriteError(w, err)
		return
	}
	// Every requested channel is looked up in this; empty, none of them is in
	// the server and the request is refused for the wrong reason.
	chans, err := s.Store.ServerChannels(r.Context(), srv.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	byID := map[ids.ID]*store.Channel{}
	for _, c := range chans {
		byID[c.ID] = c
	}
	var list []store.ChannelPosition
	type move struct {
		ch     *store.Channel
		parent *store.Channel
	}
	var syncs []move
	for _, item := range req {
		c := byID[item.ID]
		if c == nil {
			WriteError(w, E(http.StatusBadRequest, "invalid_channel", "One of the channels is not in this server."))
			return
		}
		pos := store.ChannelPosition{ID: item.ID, Position: item.Position}
		if len(item.ParentID) > 0 {
			pos.ParentSet = true
			if string(item.ParentID) != "null" {
				var pid ids.ID
				if json.Unmarshal(item.ParentID, &pid) != nil {
					WriteError(w, E(http.StatusBadRequest, "invalid_parent", "parent_id is malformed."))
					return
				}
				parent := byID[pid]
				if parent == nil || parent.Type != "category" || c.Type == "category" {
					WriteError(w, E(http.StatusBadRequest, "invalid_parent", "Channels can only be moved into categories."))
					return
				}
				pos.ParentID = &pid
				if c.ParentID == nil || *c.ParentID != pid {
					var was []store.Overwrite
					if c.ParentID != nil && byID[*c.ParentID] != nil {
						was = byID[*c.ParentID].Overwrites
					}
					lock := sameOverwrites(c.Overwrites, was)
					if item.LockPermissions != nil {
						lock = *item.LockPermissions
					}
					// syncing changes who may do what, which is Manage Roles
					// in the channel; without it a move keeps the overwrites
					if lock && !s.hasIn(r.Context(), c.ID, me.ID, perms.ManageRoles) {
						if item.LockPermissions != nil {
							WriteError(w, E(http.StatusForbidden, "missing_permission", "You need Manage Roles to sync a channel's permissions."))
							return
						}
						lock = false
					}
					if lock && !sameOverwrites(c.Overwrites, parent.Overwrites) {
						syncs = append(syncs, move{c, parent})
					}
				}
			}
		}
		list = append(list, pos)
	}
	var before visibility
	if len(syncs) > 0 {
		if before, err = s.snapshotVisibility(r.Context(), srv.ID); err != nil {
			WriteError(w, err)
			return
		}
	}
	if err := s.Store.SetChannelPositions(r.Context(), srv.ID, list); err != nil {
		WriteError(w, err)
		return
	}
	s.audit(r, srv.ID, me.ID, "channel_positions", "server", &srv.ID, req, "")
	if len(syncs) > 0 {
		var touched []ids.ID
		for _, m := range syncs {
			if _, err := s.copyOverwrites(r.Context(), m.ch, m.parent.Overwrites); err != nil {
				WriteError(w, err)
				return
			}
			touched = append(touched, m.ch.ID)
		}
		s.syncVisibility(r.Context(), srv.ID, before, touched...)
	}
	updated, err := s.Store.ServerChannels(r.Context(), srv.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	// every member receives the new order for the channels they can see
	members, err := s.Store.MemberIDs(r.Context(), srv.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	for _, uid := range members {
		vis, err := s.Store.ViewableServerChannels(r.Context(), srv.ID, uid)
		if err != nil {
			continue
		}
		out := []map[string]any{}
		for _, c := range updated {
			if _, ok := vis[c.ID]; ok {
				out = append(out, map[string]any{"id": c.ID, "position": c.Position, "parent_id": c.ParentID})
			}
		}
		s.Bcast.EmitUsers([]ids.ID{uid}, "channel_positions", map[string]any{"server_id": srv.ID, "channels": out})
	}
	WriteJSON(w, http.StatusOK, updated)
}

// handleServerChannelPatch backs PATCH /channels/{id} for server channels.
func (s *Server) handleServerChannelPatch(w http.ResponseWriter, r *http.Request, ch *store.Channel, me *store.User) {
	if store.IsThread(ch.Type) {
		s.threadPatch(w, r, ch, me)
		return
	}
	p, _, err := s.Store.ChannelPerms(r.Context(), ch.ID, me.ID)
	if err != nil || !p.Has(perms.ManageChannels) {
		WriteError(w, E(http.StatusForbidden, "missing_permission", "You need Manage Channels to edit this channel."))
		return
	}
	var req struct {
		Name               *string              `json:"name"`
		Topic              *string              `json:"topic"`
		NSFW               *bool                `json:"nsfw"`
		SlowmodeSeconds    *int                 `json:"slowmode_seconds"`
		UserLimit          *int                 `json:"user_limit"`
		Glyph              *string              `json:"glyph"`
		Type               *string              `json:"type"`
		DefaultAutoArchive *int                 `json:"default_auto_archive"`
		Forum              *store.ForumSettings `json:"forum"`
		URL                *string              `json:"url"`
	}
	if err := ReadJSON(w, r, &req, 64<<10); err != nil {
		WriteError(w, err)
		return
	}
	var patch store.ChannelPatch
	changes := map[string]any{}
	if req.Name != nil {
		name, ok := cleanChannelName(*req.Name, ch.Type)
		if !ok {
			WriteError(w, E(http.StatusBadRequest, "invalid_name", "Channel names are 1–100 characters."))
			return
		}
		patch.Name = &name
		changes["name"] = name
	}
	if req.Topic != nil {
		// a forum's topic is its post guidelines, which run longer
		limit := maxTopic
		if store.IsForum(ch.Type) {
			limit = maxForumTopic
		}
		topic, ok := CleanText(*req.Topic, limit)
		if !ok {
			WriteError(w, E(http.StatusBadRequest, "invalid_topic", fmt.Sprintf("Topics are at most %d characters.", limit)))
			return
		}
		patch.Topic = &topic
		changes["topic"] = topic
	}
	if req.Type != nil && *req.Type != ch.Type {
		// a text channel can become an announcement channel and back; the
		// rest keep what they are
		if !(ch.Type == "text" && *req.Type == store.ChannelAnnouncement) && !(ch.Type == store.ChannelAnnouncement && *req.Type == "text") {
			WriteError(w, E(http.StatusBadRequest, "invalid_type", "Only a text channel becomes an announcement channel, and back."))
			return
		}
		if *req.Type == "text" {
			if n, _ := s.Store.FollowerCount(r.Context(), ch.ID); n > 0 {
				WriteError(w, E(http.StatusBadRequest, "has_followers", "Other servers follow this channel. It stays an announcement channel while they do."))
				return
			}
		}
		patch.Type = req.Type
		changes["type"] = *req.Type
	}
	if req.DefaultAutoArchive != nil {
		if !store.HoldsThreads(ch.Type) || !validAutoArchive(*req.DefaultAutoArchive) {
			WriteError(w, E(http.StatusBadRequest, "invalid_auto_archive", "Threads close after an hour, a day, three days or a week without messages."))
			return
		}
		patch.AutoArchive = req.DefaultAutoArchive
		changes["default_auto_archive"] = *req.DefaultAutoArchive
	}
	if req.Forum != nil {
		if !store.IsForum(ch.Type) {
			WriteError(w, E(http.StatusBadRequest, "not_forum", "Only a forum or media channel has forum settings."))
			return
		}
		f, ferr := cleanForum(ch.Forum, req.Forum)
		if ferr != nil {
			WriteError(w, ferr)
			return
		}
		if f.DefaultReaction != "" {
			if _, ok := parseEmoji(f.DefaultReaction); !ok {
				WriteError(w, E(http.StatusBadRequest, "bad_emoji", "The default reaction is one emoji."))
				return
			}
		}
		if ch.Type == store.ChannelMedia {
			f.Layout = "gallery"
		} else {
			f.HideDownloads = false
		}
		patch.Forum = f
		forumChange := map[string]any{"tags": len(f.Tags), "require_tag": f.RequireTag, "sort": f.Sort, "layout": f.Layout, "default_reaction": f.DefaultReaction}
		if ch.Type == store.ChannelMedia {
			forumChange["hide_downloads"] = f.HideDownloads
		}
		changes["forum"] = forumChange
	}
	if req.URL != nil {
		if ch.Type != store.ChannelLink {
			WriteError(w, E(http.StatusBadRequest, "invalid_url", "Only a link channel has an address."))
			return
		}
		u, ok := cleanLinkURL(*req.URL)
		if !ok {
			WriteError(w, E(http.StatusBadRequest, "invalid_url", "A link channel needs a full web address starting with https:// or http://."))
			return
		}
		patch.URL = &u
		changes["url"] = u
	}
	if req.NSFW != nil {
		patch.NSFW = req.NSFW
		changes["nsfw"] = *req.NSFW
	}
	if req.SlowmodeSeconds != nil {
		if *req.SlowmodeSeconds < 0 || *req.SlowmodeSeconds > 21600 {
			WriteError(w, E(http.StatusBadRequest, "invalid_slowmode", "Slow mode is 0–21600 seconds."))
			return
		}
		patch.SlowmodeSeconds = req.SlowmodeSeconds
		changes["slowmode_seconds"] = *req.SlowmodeSeconds
	}
	if req.UserLimit != nil {
		if *req.UserLimit < 0 || *req.UserLimit > 99 {
			WriteError(w, E(http.StatusBadRequest, "invalid_limit", "User limit is 0–99."))
			return
		}
		patch.UserLimit = req.UserLimit
		changes["user_limit"] = *req.UserLimit
	}
	if req.Glyph != nil {
		glyph, ok := s.channelGlyph(r.Context(), *ch.ServerID, *req.Glyph)
		if !ok {
			WriteError(w, E(http.StatusBadRequest, "invalid_glyph", "A channel icon is one of the app's icons, a single emoji, or one of this server's custom emoji."))
			return
		}
		patch.Glyph = &glyph
		changes["glyph"] = glyph
		if ch.IconID != nil {
			// picking an icon, an emoji or the default puts the uploaded picture away
			if err := s.Store.SwapImage(r.Context(), "channels", "icon_id", ch.ID, nil); err != nil {
				WriteError(w, err)
				return
			}
			changes["icon"] = false
		}
	}
	if err := s.Store.UpdateChannel(r.Context(), ch.ID, patch); err != nil {
		WriteError(w, err)
		return
	}
	updated, err := s.Store.ChannelByID(r.Context(), ch.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	s.audit(r, *ch.ServerID, me.ID, "channel_update", "channel", &ch.ID, changes, "")
	s.Bcast.EmitChannel(ch.ID, "channel_update", updated)
	WriteJSON(w, http.StatusOK, updated)
}

func (s *Server) handleServerChannelDelete(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	id, err := ids.Parse(r.PathValue("id"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	ch, err := s.Store.ChannelByID(r.Context(), id)
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	if ch.Type == "subchat" {
		if !hasID(ch.Recipients, me.ID) {
			WriteError(w, ErrNotFound)
			return
		}
		s.handleSubchatDelete(w, r, ch, me)
		return
	}
	if s.isDM(ch) {
		// the same verb closes or deletes a conversation
		s.handleDMDelete(w, r)
		return
	}
	if ch.ServerID == nil {
		WriteError(w, ErrNotFound)
		return
	}
	if mutedFor(w, r) {
		return
	}
	p, _, err := s.Store.ChannelPerms(r.Context(), ch.ID, me.ID)
	if err != nil || p == 0 {
		WriteError(w, ErrNotFound)
		return
	}
	if store.IsThread(ch.Type) {
		s.threadDelete(w, r, ch, me)
		return
	}
	if !p.Has(perms.ManageChannels) {
		WriteError(w, E(http.StatusForbidden, "missing_permission", "You need Manage Channels to delete this channel."))
		return
	}
	// Read before the channel goes: afterwards there is nobody to ask who
	// could see it, and whoever is not told keeps it in their sidebar.
	viewers, err := s.Store.ChannelViewers(r.Context(), ch.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	// a category's channels are left without one; only they change shape
	var children []ids.ID
	if ch.Type == "category" {
		if all, err := s.Store.ServerChannels(r.Context(), *ch.ServerID); err == nil {
			for _, c := range all {
				if c.ParentID != nil && *c.ParentID == ch.ID {
					children = append(children, c.ID)
				}
			}
		}
	}
	srv, _ := s.Store.ServerByID(r.Context(), *ch.ServerID)
	s.voiceChannelDeleted(r.Context(), ch)
	if err := s.Store.DeleteChannel(r.Context(), ch.ID); err != nil {
		WriteError(w, err)
		return
	}
	s.audit(r, *ch.ServerID, me.ID, "channel_delete", "channel", &ch.ID, map[string]any{"name": ch.Name}, "")
	s.Bcast.EmitUsers(viewers, "channel_delete", map[string]any{"id": ch.ID, "server_id": ch.ServerID})
	for _, id := range children {
		if c, err := s.Store.ChannelByID(r.Context(), id); err == nil {
			s.Bcast.EmitChannel(c.ID, "channel_update", c)
		}
	}
	// the server's join notices went to this channel, and now go nowhere
	if srv != nil && srv.SystemChannelID != nil && *srv.SystemChannelID == ch.ID {
		if updated, err := s.Store.ServerByID(r.Context(), srv.ID); err == nil {
			s.emitServer(r.Context(), srv.ID, "server_update", updated)
		}
	}
	w.WriteHeader(http.StatusNoContent)
}

// channelManageCtx loads a server channel and requires ManageRoles (overwrite edits).
func (s *Server) channelManageCtx(w http.ResponseWriter, r *http.Request) (*store.Channel, *store.User, perms.Perm) {
	me := auth.UserFrom(r.Context())
	id, err := ids.Parse(r.PathValue("id"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil, nil, 0
	}
	ch, err := s.Store.ChannelByID(r.Context(), id)
	if err != nil || ch.ServerID == nil {
		WriteError(w, ErrNotFound)
		return nil, nil, 0
	}
	p, _, err := s.Store.ChannelPerms(r.Context(), ch.ID, me.ID)
	if err != nil || p == 0 {
		WriteError(w, ErrNotFound)
		return nil, nil, 0
	}
	// a channel's permissions are Manage Roles there (Manage Permissions in a
	// channel's own list), as in Discord; Manage Channels alone edits the rest
	if !p.Has(perms.ManageRoles) {
		WriteError(w, E(http.StatusForbidden, "missing_permission", "You need Manage Roles to edit permissions."))
		return nil, nil, 0
	}
	return ch, me, p
}

func (s *Server) handleOverwritePut(w http.ResponseWriter, r *http.Request) {
	ch, me, actorPerms := s.channelManageCtx(w, r)
	if ch == nil {
		return
	}
	target, err := ids.Parse(r.PathValue("target"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	var req struct {
		Type  string     `json:"type"`
		Allow perms.Perm `json:"allow"`
		Deny  perms.Perm `json:"deny"`
	}
	if err := ReadJSON(w, r, &req, 2<<10); err != nil {
		WriteError(w, err)
		return
	}
	if !OneOf(req.Type, "role", "member") {
		WriteError(w, E(http.StatusBadRequest, "invalid_type", "type must be role or member."))
		return
	}
	srv, err := s.Store.ServerByID(r.Context(), *ch.ServerID)
	if err != nil {
		WriteError(w, err)
		return
	}
	if !s.canOverwrite(w, r, srv, me.ID, req.Type, target) {
		return
	}
	// One can only change what one holds (owners and admins hold everything).
	// What changes is measured against the overwrite already there, as a
	// role's permissions are: an overwrite that denies something the editor
	// lacks can still have its other permissions edited, where before it
	// could not be touched at all.
	var old store.Overwrite
	for _, o := range ch.Overwrites {
		if o.TargetID == target {
			old = o
			break
		}
	}
	changed := (req.Allow ^ old.Allow) | (req.Deny ^ old.Deny)
	if actorPerms != perms.All && changed&^actorPerms != 0 {
		WriteError(w, E(http.StatusForbidden, "missing_permission", "You cannot manage permissions you do not have."))
		return
	}
	before, err := s.snapshotVisibility(r.Context(), *ch.ServerID)
	if err != nil {
		WriteError(w, err)
		return
	}
	// the channels synced with a category follow it
	var synced []*store.Channel
	if ch.Type == "category" {
		if synced, err = s.syncedChildren(r.Context(), ch); err != nil {
			WriteError(w, err)
			return
		}
	}
	ow := store.Overwrite{ChannelID: ch.ID, TargetID: target, TargetType: req.Type, Allow: req.Allow &^ req.Deny, Deny: req.Deny}
	if err := s.Store.SetOverwrite(r.Context(), ow); err != nil {
		WriteError(w, err)
		return
	}
	touched := []ids.ID{ch.ID}
	for _, c := range synced {
		cow := ow
		cow.ChannelID = c.ID
		if err := s.Store.SetOverwrite(r.Context(), cow); err != nil {
			WriteError(w, err)
			return
		}
		s.markIfPrivate(r.Context(), c)
		touched = append(touched, c.ID)
	}
	s.audit(r, *ch.ServerID, me.ID, "overwrite_update", "channel", &ch.ID, ow, "")
	resp := map[string]any{"overwrite": ow}
	if s.markIfPrivate(r.Context(), ch) {
		resp["key_actions"] = map[string]any{"needs_own_key": true, "conv_id": ch.ID}
	}
	s.syncVisibility(r.Context(), *ch.ServerID, before, touched...)
	WriteJSON(w, http.StatusOK, resp)
}

// handlePermissionsSync gives a channel its category's overwrites, the Sync
// Now of a channel's permissions. Each overwrite it changes is held to what
// writing that overwrite by hand would be: the actor's own permissions and
// the role hierarchy.
func (s *Server) handlePermissionsSync(w http.ResponseWriter, r *http.Request) {
	ch, me, actorPerms := s.channelManageCtx(w, r)
	if ch == nil {
		return
	}
	if ch.ParentID == nil || ch.Type == "category" {
		WriteError(w, E(http.StatusBadRequest, "no_category", "This channel is not in a category."))
		return
	}
	parent, err := s.Store.ChannelByID(r.Context(), *ch.ParentID)
	if err != nil {
		WriteError(w, err)
		return
	}
	srv, err := s.Store.ServerByID(r.Context(), *ch.ServerID)
	if err != nil {
		WriteError(w, err)
		return
	}
	mine := map[ids.ID]store.Overwrite{}
	for _, ow := range ch.Overwrites {
		mine[ow.TargetID] = ow
	}
	theirs := map[ids.ID]store.Overwrite{}
	for _, ow := range parent.Overwrites {
		theirs[ow.TargetID] = ow
	}
	check := func(target ids.ID, kind string, a, b store.Overwrite) bool {
		bits := (a.Allow ^ b.Allow) | (a.Deny ^ b.Deny)
		if bits == 0 {
			return true
		}
		if actorPerms != perms.All && bits&^actorPerms != 0 {
			WriteError(w, E(http.StatusForbidden, "missing_permission", "You cannot manage permissions you do not have."))
			return false
		}
		return s.canOverwrite(w, r, srv, me.ID, kind, target)
	}
	for id, ow := range theirs {
		if !check(id, ow.TargetType, mine[id], ow) {
			return
		}
	}
	for id, ow := range mine {
		if _, kept := theirs[id]; !kept && !check(id, ow.TargetType, ow, store.Overwrite{}) {
			return
		}
	}
	before, err := s.snapshotVisibility(r.Context(), *ch.ServerID)
	if err != nil {
		WriteError(w, err)
		return
	}
	own, err := s.copyOverwrites(r.Context(), ch, parent.Overwrites)
	if err != nil {
		WriteError(w, err)
		return
	}
	s.audit(r, *ch.ServerID, me.ID, "overwrite_sync", "channel", &ch.ID, map[string]any{"category": parent.ID}, "")
	s.syncVisibility(r.Context(), *ch.ServerID, before, ch.ID)
	resp := map[string]any{}
	if own {
		resp["key_actions"] = map[string]any{"needs_own_key": true, "conv_id": ch.ID}
	}
	WriteJSON(w, http.StatusOK, resp)
}

// canOverwrite checks that the actor outranks whoever the overwrite is about.
//
// Every other role-touching handler applies the hierarchy; this one only ever
// checked that the target belonged to the server. Somebody given Manage Roles
// in one channel could therefore write an overwrite denying View Channel to a
// moderator role far above their own, or to a specific higher-ranked member,
// and lock them out of it — which also emits channel_delete to them and flags
// the conversation for a key rotation. It answers false having written the
// refusal.
func (s *Server) canOverwrite(w http.ResponseWriter, r *http.Request, srv *store.Server, actor ids.ID, kind string, target ids.ID) bool {
	if kind == "role" {
		role, err := s.Store.RoleByID(r.Context(), target)
		if err != nil || role.ServerID != srv.ID {
			WriteError(w, E(http.StatusBadRequest, "invalid_target", "That role is not in this server."))
			return false
		}
		highest, isOwner := s.actorHighest(r.Context(), srv, actor)
		if !perms.CanManageRole(highest, isOwner, perms.Role{ID: role.ID, Position: role.Position}) {
			WriteError(w, E(http.StatusForbidden, "role_hierarchy", "You can only set permissions for roles below your highest role."))
			return false
		}
		return true
	}
	// An overwrite of one's own is a way to keep permissions after the role
	// that granted them is taken away, or to lift a deny someone above put
	// there; only the owner, who has everything anyway, may touch theirs.
	if target == actor && srv.OwnerID != actor {
		WriteError(w, E(http.StatusForbidden, "self", "You cannot set your own permissions."))
		return false
	}
	if ok, _ := s.Store.IsMember(r.Context(), srv.ID, target); !ok {
		// whatever someone who has gone left behind can always be cleared
		if r.Method == http.MethodDelete {
			return true
		}
		WriteError(w, E(http.StatusBadRequest, "invalid_target", "That user is not a member."))
		return false
	}
	if !s.canActOn(r.Context(), srv, actor, target) {
		WriteError(w, E(http.StatusForbidden, "role_hierarchy", "You cannot set permissions for this member."))
		return false
	}
	return true
}

// channelBecamePrivate reports whether @everyone can no longer view a channel
// that still encrypts under the server root.
func (s *Server) channelBecamePrivate(ctx context.Context, ch *store.Channel) bool {
	fresh, err := s.Store.ChannelByID(ctx, ch.ID)
	if err != nil || fresh.KeyConvID == ch.ID {
		return false
	}
	roles, err := s.Store.Roles(ctx, *ch.ServerID)
	if err != nil {
		return false
	}
	pr := make([]perms.Role, len(roles))
	for i, ro := range roles {
		pr[i] = perms.Role{ID: ro.ID, Position: ro.Position, Permissions: ro.Permissions}
	}
	po := make([]perms.Overwrite, len(fresh.Overwrites))
	for i, ow := range fresh.Overwrites {
		po[i] = perms.Overwrite{TargetID: ow.TargetID, Kind: ow.TargetType, Allow: ow.Allow, Deny: ow.Deny}
	}
	return !perms.Compute(*ch.ServerID, pr, perms.Member{}, false, po).Has(perms.ViewChannel)
}

func (s *Server) handleOverwriteDelete(w http.ResponseWriter, r *http.Request) {
	ch, me, _ := s.channelManageCtx(w, r)
	if ch == nil {
		return
	}
	target, err := ids.Parse(r.PathValue("target"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	srv, err := s.Store.ServerByID(r.Context(), *ch.ServerID)
	if err != nil {
		WriteError(w, err)
		return
	}
	// Removing an overwrite changes who can see the channel just as writing
	// one does, so it answers to the same hierarchy. Which kind of target it
	// is comes from the overwrite that is there.
	kind := "member"
	fresh, err := s.Store.ChannelByID(r.Context(), ch.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	found := false
	for _, ow := range fresh.Overwrites {
		if ow.TargetID == target {
			kind, found = ow.TargetType, true
			break
		}
	}
	if found && !s.canOverwrite(w, r, srv, me.ID, kind, target) {
		return
	}
	before, err := s.snapshotVisibility(r.Context(), *ch.ServerID)
	if err != nil {
		WriteError(w, err)
		return
	}
	var synced []*store.Channel
	if fresh.Type == "category" {
		if synced, err = s.syncedChildren(r.Context(), fresh); err != nil {
			WriteError(w, err)
			return
		}
	}
	if err := s.Store.DeleteOverwrite(r.Context(), ch.ID, target); err != nil {
		WriteError(w, err)
		return
	}
	changed := []ids.ID{ch.ID}
	for _, c := range synced {
		if err := s.Store.DeleteOverwrite(r.Context(), c.ID, target); err != nil && !errors.Is(err, store.ErrNotFound) {
			WriteError(w, err)
			return
		}
		changed = append(changed, c.ID)
	}
	s.audit(r, *ch.ServerID, me.ID, "overwrite_delete", "channel", &ch.ID, map[string]any{"target": target}, "")
	s.syncVisibility(r.Context(), *ch.ServerID, before, changed...)
	w.WriteHeader(http.StatusNoContent)
}

// --- roles ---------------------------------------------------------------

func (s *Server) actorHighest(ctx context.Context, srv *store.Server, uid ids.ID) (int, bool) {
	if srv.OwnerID == uid {
		return 1 << 30, true
	}
	if s.Store.IsInstanceAdmin(ctx, uid) {
		// above every role, but still answerable to the server's owner
		return 1<<30 - 1, false
	}
	m, err := s.Store.Member(ctx, srv.ID, uid)
	if err != nil {
		return 0, false
	}
	roles, _ := s.Store.Roles(ctx, srv.ID)
	pr := make([]perms.Role, len(roles))
	for i, ro := range roles {
		pr[i] = perms.Role{ID: ro.ID, Position: ro.Position, Permissions: ro.Permissions}
	}
	return perms.HighestPosition(pr, perms.Member{UserID: uid, RoleIDs: m.RoleIDs}), false
}

func (s *Server) handleRoles(w http.ResponseWriter, r *http.Request) {
	srv, _, _ := s.serverCtx(w, r, 0)
	if srv == nil {
		return
	}
	roles, err := s.Store.Roles(r.Context(), srv.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, roles)
}

type roleBody struct {
	Name        *string     `json:"name"`
	Color       *int        `json:"color"`
	Hoist       *bool       `json:"hoist"`
	Mentionable *bool       `json:"mentionable"`
	Permissions *perms.Perm `json:"permissions"`
	// the second and third colours of a gradient or shimmer: a number, or
	// null to go back to one colour
	Color2    json.RawMessage `json:"color2"`
	Color3    json.RawMessage `json:"color3"`
	IconEmoji *string         `json:"icon_emoji"`
}

// roleColor reads an optional role colour: absent, null, or 0–0xFFFFFF.
func roleColor(raw json.RawMessage) (set bool, v *int, ok bool) {
	if len(raw) == 0 {
		return false, nil, true
	}
	if string(raw) == "null" {
		return true, nil, true
	}
	var n int
	if json.Unmarshal(raw, &n) != nil || n < 0 || n > 0xFFFFFF {
		return true, nil, false
	}
	return true, &n, true
}

func (s *Server) handleRoleCreate(w http.ResponseWriter, r *http.Request) {
	srv, me, actorPerms := s.serverCtx(w, r, perms.ManageRoles)
	if srv == nil {
		return
	}
	var req roleBody
	if err := ReadJSON(w, r, &req, 4<<10); err != nil {
		WriteError(w, err)
		return
	}
	name := "new role"
	if req.Name != nil {
		clean, ok := cleanServerName(*req.Name)
		if !ok {
			WriteError(w, E(http.StatusBadRequest, "invalid_name", "Role names are 1–100 characters."))
			return
		}
		name = clean
	}
	var p perms.Perm
	if req.Permissions != nil {
		p = *req.Permissions
	}
	if actorPerms != perms.All && (p&^actorPerms) != 0 {
		WriteError(w, E(http.StatusForbidden, "missing_permission", "You cannot grant permissions you do not have."))
		return
	}
	color := 0
	if req.Color != nil && *req.Color >= 0 && *req.Color <= 0xFFFFFF {
		color = *req.Color
	}
	roles, _ := s.Store.Roles(r.Context(), srv.ID)
	if len(roles) >= maxServerRoles {
		WriteError(w, E(http.StatusBadRequest, "too_many_roles", "This server has reached the role limit."))
		return
	}
	role, err := s.Store.CreateRole(r.Context(), srv.ID, name, p, color)
	if errors.Is(err, store.ErrTooMany) {
		WriteError(w, E(http.StatusBadRequest, "too_many_roles", "A server can have at most 250 roles."))
		return
	}
	if err != nil {
		WriteError(w, err)
		return
	}
	if (req.Hoist != nil && *req.Hoist) || (req.Mentionable != nil && *req.Mentionable) {
		if err := s.Store.UpdateRole(r.Context(), role.ID, store.RolePatch{Hoist: req.Hoist, Mentionable: req.Mentionable}); err != nil {
			WriteError(w, err)
			return
		}
		// The role exists either way. A failed re-read used to leave role nil
		// and the next line took its address, so the panic came after the row
		// was written and the client never learned the id.
		if fresh, err := s.Store.RoleByID(r.Context(), role.ID); err == nil {
			role = fresh
		} else {
			role.Hoist, role.Mentionable = req.Hoist != nil && *req.Hoist, req.Mentionable != nil && *req.Mentionable
			s.Log.Warn("role create: re-read", "role", role.ID.String(), "err", err)
		}
	}
	s.audit(r, srv.ID, me.ID, "role_create", "role", &role.ID, map[string]any{"name": name}, "")
	// the new role went in at the bottom and pushed every other role up one
	if members, err := s.Store.MemberIDs(r.Context(), srv.ID); err == nil {
		if all, err := s.Store.Roles(r.Context(), srv.ID); err == nil {
			for _, ro := range all {
				if ro.ID != role.ID && ro.ID != srv.ID {
					s.Bcast.EmitUsers(members, "role_update", ro)
				}
			}
		}
		s.Bcast.EmitUsers(members, "role_create", role)
	}
	WriteJSON(w, http.StatusCreated, role)
}

func (s *Server) roleCtx(w http.ResponseWriter, r *http.Request) (*store.Server, *store.User, *store.Role, perms.Perm) {
	srv, me, actorPerms := s.serverCtx(w, r, perms.ManageRoles)
	if srv == nil {
		return nil, nil, nil, 0
	}
	rid, err := ids.Parse(r.PathValue("rid"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil, nil, nil, 0
	}
	role, err := s.Store.RoleByID(r.Context(), rid)
	if err != nil || role.ServerID != srv.ID {
		WriteError(w, ErrNotFound)
		return nil, nil, nil, 0
	}
	highest, isOwner := s.actorHighest(r.Context(), srv, me.ID)
	if role.ID != srv.ID && !perms.CanManageRole(highest, isOwner, perms.Role{ID: role.ID, Position: role.Position}) {
		WriteError(w, E(http.StatusForbidden, "role_hierarchy", "You can only manage roles below your highest role."))
		return nil, nil, nil, 0
	}
	return srv, me, role, actorPerms
}

func (s *Server) handleRolePatch(w http.ResponseWriter, r *http.Request) {
	srv, me, role, actorPerms := s.roleCtx(w, r)
	if srv == nil {
		return
	}
	var req roleBody
	if err := ReadJSON(w, r, &req, 4<<10); err != nil {
		WriteError(w, err)
		return
	}
	var p store.RolePatch
	changes := map[string]any{}
	if req.Name != nil && role.ID != srv.ID {
		clean, ok := cleanServerName(*req.Name)
		if !ok {
			WriteError(w, E(http.StatusBadRequest, "invalid_name", "Role names are 1–100 characters."))
			return
		}
		p.Name = &clean
		changes["name"] = clean
	}
	if req.Color != nil {
		if *req.Color < 0 || *req.Color > 0xFFFFFF {
			WriteError(w, E(http.StatusBadRequest, "invalid_color", "color must be 0–16777215."))
			return
		}
		p.Color = req.Color
		changes["color"] = *req.Color
	}
	if req.Hoist != nil && role.ID != srv.ID {
		p.Hoist = req.Hoist
		changes["hoist"] = *req.Hoist
	}
	if req.Mentionable != nil {
		p.Mentionable = req.Mentionable
		changes["mentionable"] = *req.Mentionable
	}
	for key, raw := range map[string]json.RawMessage{"color2": req.Color2, "color3": req.Color3} {
		set, v, ok := roleColor(raw)
		if !ok {
			WriteError(w, E(http.StatusBadRequest, "invalid_color", key+" must be 0–16777215 or null."))
			return
		}
		if !set {
			continue
		}
		if key == "color2" {
			p.Color2 = &v
		} else {
			p.Color3 = &v
		}
		changes[key] = v
	}
	if req.IconEmoji != nil && role.ID != srv.ID {
		icon, ok := s.roleEmoji(r.Context(), srv.ID, *req.IconEmoji)
		if !ok {
			WriteError(w, E(http.StatusBadRequest, "invalid_icon", "A role's icon is one emoji, or one of this server's own."))
			return
		}
		p.IconEmoji = &icon
		changes["icon_emoji"] = icon
		// an emoji takes the place of a picture
		if icon != "" && role.IconID != nil {
			if err := s.Store.SwapRoleIcon(r.Context(), role.ID, nil); err != nil {
				WriteError(w, err)
				return
			}
		}
	}
	if req.Permissions != nil {
		diff := *req.Permissions ^ role.Permissions
		if actorPerms != perms.All && (diff&^actorPerms) != 0 {
			WriteError(w, E(http.StatusForbidden, "missing_permission", "You cannot change permissions you do not have."))
			return
		}
		p.Permissions = req.Permissions
		changes["permissions"] = req.Permissions
	}
	// only a permission change can alter who sees what
	var before visibility
	if req.Permissions != nil {
		var err error
		if before, err = s.snapshotVisibility(r.Context(), srv.ID); err != nil {
			WriteError(w, err)
			return
		}
	}
	if err := s.Store.UpdateRole(r.Context(), role.ID, p); err != nil {
		WriteError(w, err)
		return
	}
	updated, err := s.Store.RoleByID(r.Context(), role.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	s.audit(r, srv.ID, me.ID, "role_update", "role", &role.ID, changes, "")
	s.emitServer(r.Context(), srv.ID, "role_update", updated)
	if req.Permissions != nil {
		s.syncVisibility(r.Context(), srv.ID, before)
	}
	WriteJSON(w, http.StatusOK, updated)
}

// roleEmoji checks a role's emoji icon: "" for none, a single emoji, or
// "custom:<id>" naming one of the server's own emoji.
func (s *Server) roleEmoji(ctx context.Context, serverID ids.ID, raw string) (string, bool) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return "", true
	}
	if rest, ok := strings.CutPrefix(raw, "custom:"); ok {
		id, err := ids.Parse(rest)
		if err != nil {
			return "", false
		}
		e, err := s.Store.EmojiByID(ctx, id)
		if err != nil || e.ServerID != serverID {
			return "", false
		}
		return "custom:" + id.String(), true
	}
	if len(raw) > maxEmojiLen || !isEmojiSequence(raw) {
		return "", false
	}
	return raw, true
}

func (s *Server) handleRoleDelete(w http.ResponseWriter, r *http.Request) {
	srv, me, role, _ := s.roleCtx(w, r)
	if srv == nil {
		return
	}
	if role.ID == srv.ID {
		WriteError(w, E(http.StatusBadRequest, "everyone_role", "@everyone cannot be deleted."))
		return
	}
	before, err := s.snapshotVisibility(r.Context(), srv.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	if err := s.Store.DeleteRole(r.Context(), role.ID); err != nil {
		WriteError(w, err)
		return
	}
	s.audit(r, srv.ID, me.ID, "role_delete", "role", &role.ID, map[string]any{"name": role.Name}, "")
	s.emitServer(r.Context(), srv.ID, "role_delete", map[string]any{"id": role.ID, "server_id": srv.ID})
	s.syncVisibility(r.Context(), srv.ID, before)
	s.tagRoleGone(r.Context(), srv, role.ID)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleRolePositions(w http.ResponseWriter, r *http.Request) {
	srv, me, _ := s.serverCtx(w, r, perms.ManageRoles)
	if srv == nil {
		return
	}
	var req []struct {
		ID       ids.ID `json:"id"`
		Position int    `json:"position"`
	}
	if err := ReadJSON(w, r, &req, 32<<10); err != nil {
		WriteError(w, err)
		return
	}
	highest, isOwner := s.actorHighest(r.Context(), srv, me.ID)
	positions := map[ids.ID]int{}
	for _, item := range req {
		role, err := s.Store.RoleByID(r.Context(), item.ID)
		if err != nil || role.ServerID != srv.ID || role.ID == srv.ID {
			continue
		}
		if item.Position == role.Position {
			continue
		}
		if !perms.CanManageRole(highest, isOwner, perms.Role{ID: role.ID, Position: role.Position}) {
			// clients send the whole list renumbered, so the actor's own role and
			// those above it show up too (shifted, when earlier deletions left
			// gaps); those stay exactly where they are
			continue
		}
		if !isOwner && item.Position >= highest {
			WriteError(w, E(http.StatusForbidden, "role_hierarchy", "You can only reorder roles below your highest role."))
			return
		}
		positions[item.ID] = item.Position
	}
	if err := s.Store.SetRolePositions(r.Context(), srv.ID, positions); err != nil {
		WriteError(w, err)
		return
	}
	roles, err := s.Store.Roles(r.Context(), srv.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	s.audit(r, srv.ID, me.ID, "role_positions", "server", &srv.ID, req, "")
	// The membership is read once rather than once per role: emitServer starts
	// with MemberIDs, so dragging roles in a server at the role cap used to
	// read the whole member list 250 times over to send 250 events.
	if members, err := s.Store.MemberIDs(r.Context(), srv.ID); err == nil {
		for _, ro := range roles {
			s.Bcast.EmitUsers(members, "role_update", ro)
		}
	}
	WriteJSON(w, http.StatusOK, roles)
}

// --- members -------------------------------------------------------------

func (s *Server) handleMembers(w http.ResponseWriter, r *http.Request) {
	srv, _, _ := s.serverCtx(w, r, 0)
	if srv == nil {
		return
	}
	after, _ := ids.Parse(r.URL.Query().Get("after"))
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	members, err := s.Store.Members(r.Context(), srv.ID, after, limit, r.URL.Query().Get("q"))
	if err != nil {
		WriteError(w, err)
		return
	}
	list := make([]ids.ID, 0, len(members))
	for _, m := range members {
		list = append(list, m.UserID)
	}
	byID, err := s.Store.UsersByIDs(r.Context(), list)
	if err != nil {
		WriteError(w, err)
		return
	}
	users := make([]store.PublicUser, 0, len(list))
	for _, id := range list {
		if u := byID[id]; u != nil {
			users = append(users, u.Public())
		}
	}
	WriteJSON(w, http.StatusOK, map[string]any{"members": members, "users": users})
}

func (s *Server) memberTarget(w http.ResponseWriter, r *http.Request, srv *store.Server) (*store.Member, bool) {
	uid, err := ids.Parse(r.PathValue("uid"))
	if r.PathValue("uid") == "@me" {
		uid, err = auth.UserFrom(r.Context()).ID, nil
	}
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil, false
	}
	m, err := s.Store.Member(r.Context(), srv.ID, uid)
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil, false
	}
	return m, true
}

func (s *Server) handleMemberGet(w http.ResponseWriter, r *http.Request) {
	srv, _, _ := s.serverCtx(w, r, 0)
	if srv == nil {
		return
	}
	m, ok := s.memberTarget(w, r, srv)
	if !ok {
		return
	}
	WriteJSON(w, http.StatusOK, m)
}

// canActOn checks role hierarchy between actor and target.
func (s *Server) canActOn(ctx context.Context, srv *store.Server, actor, target ids.ID) bool {
	if actor == target {
		return true
	}
	ah, aOwner := s.actorHighest(ctx, srv, actor)
	th, tOwner := s.actorHighest(ctx, srv, target)
	return perms.CanActOn(ah, th, aOwner, tOwner)
}

func (s *Server) handleMemberPatch(w http.ResponseWriter, r *http.Request) {
	srv, me, actorPerms := s.serverCtx(w, r, 0)
	if srv == nil {
		return
	}
	m, ok := s.memberTarget(w, r, srv)
	if !ok {
		return
	}
	var raw map[string]json.RawMessage
	if err := ReadJSON(w, r, &raw, 16<<10); err != nil {
		WriteError(w, err)
		return
	}
	self := m.UserID == me.ID
	changes := map[string]any{}
	var patch store.MemberPatch
	if v, ok := raw["nick"]; ok {
		need := perms.ManageNicknames
		if self {
			need = perms.ChangeNickname
		}
		if !actorPerms.Has(need) && !actorPerms.Has(perms.ManageNicknames) {
			WriteError(w, E(http.StatusForbidden, "missing_permission", "You cannot change that nickname."))
			return
		}
		if !self && !s.canActOn(r.Context(), srv, me.ID, m.UserID) {
			WriteError(w, E(http.StatusForbidden, "role_hierarchy", "You cannot manage this member."))
			return
		}
		var nick string
		if string(v) != "null" && json.Unmarshal(v, &nick) != nil {
			WriteError(w, E(http.StatusBadRequest, "invalid_nick", "Nickname is malformed."))
			return
		}
		clean, ok := CleanText(strings.ReplaceAll(nick, "\n", " "), maxNickname)
		if !ok {
			WriteError(w, E(http.StatusBadRequest, "invalid_nick", "Nicknames are at most 32 characters."))
			return
		}
		patch.Nickname = &clean
		changes["nick"] = clean
	}
	if v, ok := raw["timeout_until"]; ok {
		if !actorPerms.Has(perms.ModerateMembers) || self || !s.canActOn(r.Context(), srv, me.ID, m.UserID) {
			WriteError(w, E(http.StatusForbidden, "missing_permission", "You cannot time out this member."))
			return
		}
		if string(v) == "null" {
			var nilP *int64
			patch.TimeoutUntil = &nilP
		} else {
			var until int64
			if json.Unmarshal(v, &until) != nil || until < time.Now().UnixMilli() || until > time.Now().Add(28*24*time.Hour).UnixMilli() {
				WriteError(w, E(http.StatusBadRequest, "invalid_timeout", "timeout_until must be a future time within 28 days."))
				return
			}
			up := &until
			patch.TimeoutUntil = &up
		}
		changes["timeout_until"] = string(v)
	}
	// a member's profile in this server: their own to write; a moderator may
	// only clear someone else's
	for key, max := range map[string]int{"bio": maxAboutMe, "pronouns": maxPronouns} {
		v, ok := raw[key]
		if !ok {
			continue
		}
		var text string
		if string(v) != "null" && json.Unmarshal(v, &text) != nil {
			WriteError(w, E(http.StatusBadRequest, "invalid_"+key, "That is malformed."))
			return
		}
		if !self {
			if text != "" || !actorPerms.Has(perms.ManageNicknames) || !s.canActOn(r.Context(), srv, me.ID, m.UserID) {
				WriteError(w, E(http.StatusForbidden, "missing_permission", "You can only reset someone else's server profile."))
				return
			}
		}
		clean, ok := CleanText(text, max)
		if !ok {
			WriteError(w, E(http.StatusBadRequest, "invalid_"+key, fmt.Sprintf("That is at most %d characters.", max)))
			return
		}
		if key == "bio" {
			patch.Bio = &clean
		} else {
			patch.Pronouns = &clean
		}
		if !self {
			changes[key] = clean
		}
	}
	// the profile's theme in this server: the member's own to choose, a
	// moderator's only to clear
	for _, key := range []string{"accent_color", "accent_color_2"} {
		v, ok := raw[key]
		if !ok {
			continue
		}
		_, color, ok := roleColor(v)
		if !ok {
			WriteError(w, E(http.StatusBadRequest, "invalid_color", key+" must be 0–16777215 or null."))
			return
		}
		if !self && (color != nil || !actorPerms.Has(perms.ManageNicknames) || !s.canActOn(r.Context(), srv, me.ID, m.UserID)) {
			WriteError(w, E(http.StatusForbidden, "missing_permission", "You can only reset someone else's server profile."))
			return
		}
		if key == "accent_color" {
			patch.AccentColor = &color
		} else {
			patch.AccentColor2 = &color
		}
		if !self {
			changes[key] = nil
		}
	}
	// its widgets: a list of the member's own for this server, or null for
	// the ones on their account
	widgetsSet := false
	var widgets *string
	if v, ok := raw["widgets"]; ok {
		if string(v) == "null" {
			if !self && (!actorPerms.Has(perms.ManageNicknames) || !s.canActOn(r.Context(), srv, me.ID, m.UserID)) {
				WriteError(w, E(http.StatusForbidden, "missing_permission", "You can only reset someone else's server profile."))
				return
			}
		} else {
			if !self {
				WriteError(w, E(http.StatusForbidden, "missing_permission", "You can only reset someone else's server profile."))
				return
			}
			// every save is shown to everyone who opens the profile there
			if ok, retry := s.limProfile.Allow("profile:" + me.ID.String()); !ok {
				w.Header().Set("Retry-After", retryAfter(retry))
				WriteError(w, ErrRateLimited)
				return
			}
			before, err := s.Store.MemberWidgets(r.Context(), srv.ID, m.UserID)
			if err != nil {
				WriteError(w, err)
				return
			}
			was := ""
			if before != nil {
				was = *before
			}
			clean, problem := s.cleanWidgets(r.Context(), v, was)
			if problem != "" {
				WriteError(w, E(http.StatusBadRequest, "invalid_widgets", problem))
				return
			}
			widgets = &clean
		}
		widgetsSet = true
		if !self {
			changes["widgets"] = nil
		}
	}
	if v, ok := raw["quarantined"]; ok {
		var on bool
		if json.Unmarshal(v, &on) != nil || on || self || !actorPerms.Has(perms.ModerateMembers) || !s.canActOn(r.Context(), srv, me.ID, m.UserID) {
			WriteError(w, E(http.StatusForbidden, "missing_permission", "You can let someone out of quarantine with Time Out Members."))
			return
		}
		mine := me.ID
		s.setQuarantine(r, srv, m.UserID, false, &mine, "")
	}
	var newRoles []ids.ID
	rolesSet := false
	if v, ok := raw["roles"]; ok {
		if !actorPerms.Has(perms.ManageRoles) {
			WriteError(w, E(http.StatusForbidden, "missing_permission", "You need Manage Roles to change roles."))
			return
		}
		if json.Unmarshal(v, &newRoles) != nil {
			WriteError(w, E(http.StatusBadRequest, "invalid_roles", "roles must be a list of role ids."))
			return
		}
		if !self && !s.canActOn(r.Context(), srv, me.ID, m.UserID) {
			WriteError(w, E(http.StatusForbidden, "role_hierarchy", "You cannot manage this member."))
			return
		}
		highest, isOwner := s.actorHighest(r.Context(), srv, me.ID)
		cur := map[ids.ID]bool{}
		for _, id := range m.RoleIDs {
			cur[id] = true
		}
		next := map[ids.ID]bool{}
		for _, id := range newRoles {
			if id == srv.ID {
				continue
			}
			next[id] = true
		}
		for id := range mergeKeys(cur, next) {
			if cur[id] == next[id] {
				continue
			}
			role, err := s.Store.RoleByID(r.Context(), id)
			if err != nil || role.ServerID != srv.ID {
				WriteError(w, E(http.StatusBadRequest, "invalid_roles", "One of the roles is not in this server."))
				return
			}
			if !perms.CanManageRole(highest, isOwner, perms.Role{ID: role.ID, Position: role.Position}) {
				WriteError(w, E(http.StatusForbidden, "role_hierarchy", "You can only assign roles below your highest role."))
				return
			}
		}
		rolesSet = true
		changes["roles"] = newRoles
	}
	// a nickname alone changes nobody's view; skip the per-member snapshot
	var before visibility
	if rolesSet || patch.TimeoutUntil != nil {
		var err error
		if before, err = s.snapshotVisibility(r.Context(), srv.ID); err != nil {
			WriteError(w, err)
			return
		}
	}
	if patch.Nickname != nil || patch.TimeoutUntil != nil || patch.Bio != nil || patch.Pronouns != nil || patch.AccentColor != nil || patch.AccentColor2 != nil {
		if err := s.Store.UpdateMember(r.Context(), srv.ID, m.UserID, patch); err != nil {
			WriteError(w, err)
			return
		}
	}
	if widgetsSet {
		if err := s.Store.SetMemberWidgets(r.Context(), srv.ID, m.UserID, widgets); err != nil {
			if errors.Is(err, store.ErrNotFound) {
				WriteError(w, E(http.StatusBadRequest, "invalid_widgets", "One of those pictures has gone. Put it up again."))
				return
			}
			WriteError(w, err)
			return
		}
	}
	if rolesSet {
		if err := s.Store.SetMemberRoles(r.Context(), srv.ID, m.UserID, newRoles); err != nil {
			WriteError(w, err)
			return
		}
	}
	if patch.TimeoutUntil != nil && *patch.TimeoutUntil != nil {
		// a timed-out member cannot connect or speak, so they leave the call too
		s.dropVoice(r.Context(), m.UserID, func(ch *store.Channel) bool { return ch.ServerID != nil && *ch.ServerID == srv.ID })
	}
	updated, err := s.Store.Member(r.Context(), srv.ID, m.UserID)
	if err != nil {
		WriteError(w, err)
		return
	}
	// a member's own profile changes are theirs, not the log's
	if len(changes) > 0 {
		action := "member_update"
		if _, ok := changes["timeout_until"]; ok && len(changes) == 1 {
			action = map[bool]string{true: "member_timeout_remove", false: "member_timeout"}[patch.TimeoutUntil != nil && *patch.TimeoutUntil == nil]
		}
		s.audit(r, srv.ID, me.ID, action, "user", &m.UserID, changes, "")
	}
	s.emitServer(r.Context(), srv.ID, "member_update", updated)
	if rolesSet || patch.TimeoutUntil != nil {
		s.syncVisibility(r.Context(), srv.ID, before)
	}
	// AutoMod reads a nickname or a server profile when it changes
	if patch.Nickname != nil || patch.Bio != nil || patch.Pronouns != nil {
		s.checkProfileRules(r, srv, m.UserID)
	}
	if rolesSet {
		// a tag kept to some roles comes off whoever no longer has one
		s.RecheckTagWearers(r.Context(), srv.ID, []ids.ID{m.UserID})
	}
	WriteJSON(w, http.StatusOK, updated)
}

func mergeKeys(a, b map[ids.ID]bool) map[ids.ID]bool {
	out := map[ids.ID]bool{}
	for k := range a {
		out[k] = true
	}
	for k := range b {
		out[k] = true
	}
	return out
}

func (s *Server) handleMemberRole(add bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		srv, me, _ := s.serverCtx(w, r, perms.ManageRoles)
		if srv == nil {
			return
		}
		m, ok := s.memberTarget(w, r, srv)
		if !ok {
			return
		}
		rid, err := ids.Parse(r.PathValue("rid"))
		if err != nil {
			WriteError(w, ErrNotFound)
			return
		}
		role, err := s.Store.RoleByID(r.Context(), rid)
		if err != nil || role.ServerID != srv.ID || role.ID == srv.ID {
			WriteError(w, ErrNotFound)
			return
		}
		highest, isOwner := s.actorHighest(r.Context(), srv, me.ID)
		if !perms.CanManageRole(highest, isOwner, perms.Role{ID: role.ID, Position: role.Position}) || (m.UserID != me.ID && !s.canActOn(r.Context(), srv, me.ID, m.UserID)) {
			WriteError(w, E(http.StatusForbidden, "role_hierarchy", "You can only assign roles below your highest role."))
			return
		}
		before, err := s.snapshotVisibility(r.Context(), srv.ID)
		if err != nil {
			WriteError(w, err)
			return
		}
		if add {
			err = s.Store.AddMemberRole(r.Context(), srv.ID, m.UserID, rid)
		} else {
			err = s.Store.RemoveMemberRole(r.Context(), srv.ID, m.UserID, rid)
		}
		if err != nil {
			WriteError(w, err)
			return
		}
		updated, err := s.Store.Member(r.Context(), srv.ID, m.UserID)
		if err != nil {
			WriteError(w, err)
			return
		}
		s.audit(r, srv.ID, me.ID, map[bool]string{true: "member_role_add", false: "member_role_remove"}[add], "user", &m.UserID, map[string]any{"role": rid}, "")
		s.emitServer(r.Context(), srv.ID, "member_update", updated)
		s.syncVisibility(r.Context(), srv.ID, before)
		if !add {
			s.RecheckTagWearers(r.Context(), srv.ID, []ids.ID{m.UserID})
		}
		WriteJSON(w, http.StatusOK, updated)
	}
}

func (s *Server) handleMemberKick(w http.ResponseWriter, r *http.Request) {
	srv, me, _ := s.serverCtx(w, r, perms.KickMembers)
	if srv == nil {
		return
	}
	m, ok := s.memberTarget(w, r, srv)
	if !ok {
		return
	}
	if m.UserID == me.ID {
		WriteError(w, E(http.StatusBadRequest, "self", "Use leave to remove yourself."))
		return
	}
	if !s.canActOn(r.Context(), srv, me.ID, m.UserID) {
		WriteError(w, E(http.StatusForbidden, "role_hierarchy", "You cannot kick this member."))
		return
	}
	reason := strings.TrimSpace(r.URL.Query().Get("reason"))
	if err := s.removeMember(r, srv, m.UserID, "member_remove", map[string]any{"reason": "kick"}); err != nil {
		WriteError(w, err)
		return
	}
	s.audit(r, srv.ID, me.ID, "member_kick", "user", &m.UserID, nil, reason)
	w.WriteHeader(http.StatusNoContent)
}

// --- bans ----------------------------------------------------------------

func (s *Server) handleBans(w http.ResponseWriter, r *http.Request) {
	srv, _, _ := s.serverCtx(w, r, perms.BanMembers)
	if srv == nil {
		return
	}
	bans, err := s.Store.Bans(r.Context(), srv.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	list := make([]ids.ID, 0, len(bans))
	for _, b := range bans {
		list = append(list, b.UserID)
	}
	byID, err := s.Store.UsersByIDs(r.Context(), list)
	if err != nil {
		WriteError(w, err)
		return
	}
	users := []store.PublicUser{}
	for _, id := range list {
		if u := byID[id]; u != nil {
			users = append(users, u.Public())
		}
	}
	WriteJSON(w, http.StatusOK, map[string]any{"bans": bans, "users": users})
}

func (s *Server) handleBanPut(w http.ResponseWriter, r *http.Request) {
	srv, me, _ := s.serverCtx(w, r, perms.BanMembers)
	if srv == nil {
		return
	}
	uid, err := ids.Parse(r.PathValue("uid"))
	if err != nil || uid == me.ID {
		WriteError(w, ErrNotFound)
		return
	}
	if _, err := s.Store.UserByID(r.Context(), uid); err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	var req struct {
		Reason            string `json:"reason"`
		DeleteMessageDays int    `json:"delete_message_days"`
	}
	if r.ContentLength > 0 {
		if err := ReadJSON(w, r, &req, 4<<10); err != nil {
			WriteError(w, err)
			return
		}
	}
	reason, _ := CleanText(req.Reason, maxBanReason)
	if ok, _ := s.Store.IsMember(r.Context(), srv.ID, uid); ok && !s.canActOn(r.Context(), srv, me.ID, uid) {
		WriteError(w, E(http.StatusForbidden, "role_hierarchy", "You cannot ban this member."))
		return
	}
	// the ban goes in before the removal, so a rejoin racing it is refused
	if err := s.Store.Ban(r.Context(), &store.Ban{ServerID: srv.ID, UserID: uid, Reason: reason, BannedBy: me.ID}); err != nil {
		WriteError(w, err)
		return
	}
	if ok, _ := s.Store.IsMember(r.Context(), srv.ID, uid); ok {
		if err := s.removeMember(r, srv, uid, "member_remove", map[string]any{"reason": "ban"}); err != nil && !errors.Is(err, store.ErrNotFound) {
			WriteError(w, err)
			return
		}
	}
	// The ban is recorded before the purge, so a purge that fails does not also
	// cost the log entry and the notice for a ban that did land.
	s.audit(r, srv.ID, me.ID, "member_ban", "user", &uid, map[string]any{"delete_message_days": req.DeleteMessageDays}, reason)
	s.emitServerWithPerm(r.Context(), srv.ID, perms.BanMembers, "ban_add", map[string]any{"server_id": srv.ID, "user_id": uid, "reason": reason})
	if req.DeleteMessageDays > 0 {
		if req.DeleteMessageDays > 7 {
			req.DeleteMessageDays = 7
		}
		since := time.Now().Add(-time.Duration(req.DeleteMessageDays) * 24 * time.Hour).UnixMilli()
		deleted, err := s.Store.DeleteMessagesByUserSince(r.Context(), srv.ID, uid, since)
		if err != nil {
			// Nothing was deleted: the purge is one transaction. Saying so lets
			// the moderator try again rather than believe messages are gone
			// that are still there. Repeating the ban is the retry.
			s.Log.Warn("ban purge", "server", srv.ID.String(), "user", uid.String(), "err", err)
			WriteError(w, err)
			return
		}
		for ch, d := range deleted {
			s.Bcast.EmitChannel(ch, "message_delete_bulk", s.bulkDeleteEvent(r.Context(), ch, d))
		}
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleBanDelete(w http.ResponseWriter, r *http.Request) {
	srv, me, _ := s.serverCtx(w, r, perms.BanMembers)
	if srv == nil {
		return
	}
	uid, err := ids.Parse(r.PathValue("uid"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	if err := s.Store.Unban(r.Context(), srv.ID, uid); err != nil {
		WriteError(w, err)
		return
	}
	s.audit(r, srv.ID, me.ID, "member_unban", "user", &uid, nil, "")
	s.emitServerWithPerm(r.Context(), srv.ID, perms.BanMembers, "ban_remove", map[string]any{"server_id": srv.ID, "user_id": uid})
	w.WriteHeader(http.StatusNoContent)
}

// --- invites -------------------------------------------------------------

// serverCountsTTL is how long a server's member and online counts are reused.
// Both routes that show them are public — an invite code is meant to be shared
// — so the work behind them has to be bounded whatever the request rate is.
const serverCountsTTL = 30 * time.Second

type serverCounts struct {
	at             time.Time
	members, onlin int
}

// counts answers "how many people are in this server, and how many of them are
// here now" cheaply enough to put behind a public URL.
//
// It used to read every member id and then ask the hub about each one, taking
// the hub's read lock once per member. On a large server that is tens of
// thousands of lock acquisitions per request, contending directly with the
// register and unregister that need the write lock — so a handful of
// concurrent invite previews stalled every new WebSocket on Nexa. Now
// it takes one snapshot of who is online, intersects it with the membership,
// and keeps the answer for a moment.
// forgetCounts drops a server's cached counts, so a change Nexa made
// itself is visible on the invite page at once rather than up to the TTL later.
func (s *Server) forgetCounts(serverID ids.ID) {
	s.countsMu.Lock()
	delete(s.serverCounts, serverID)
	s.countsMu.Unlock()
}

func (s *Server) counts(ctx context.Context, serverID ids.ID) (members, online int) {
	s.countsMu.Lock()
	if hit, ok := s.serverCounts[serverID]; ok && time.Since(hit.at) < serverCountsTTL {
		s.countsMu.Unlock()
		return hit.members, hit.onlin
	}
	s.countsMu.Unlock()

	members, _ = s.Store.MemberCount(ctx, serverID)
	if h, ok := s.Bcast.(interface{ OnlineUsers() []ids.ID }); ok {
		if ids_, err := s.Store.MemberIDs(ctx, serverID); err == nil {
			isMember := make(map[ids.ID]bool, len(ids_))
			for _, id := range ids_ {
				isMember[id] = true
			}
			for _, id := range h.OnlineUsers() {
				if isMember[id] {
					online++
				}
			}
		}
	}
	now := time.Now()
	s.countsMu.Lock()
	defer s.countsMu.Unlock()
	if s.serverCounts == nil {
		s.serverCounts = map[ids.ID]serverCounts{}
	}
	for id, hit := range s.serverCounts {
		if now.Sub(hit.at) >= serverCountsTTL {
			delete(s.serverCounts, id)
		}
	}
	s.serverCounts[serverID] = serverCounts{now, members, online}
	return members, online
}

func (s *Server) handleServerInvites(w http.ResponseWriter, r *http.Request) {
	srv, _, _ := s.serverCtx(w, r, perms.ManageServer)
	if srv == nil {
		return
	}
	list, err := s.Store.InvitesForServer(r.Context(), srv.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, list)
}

func (s *Server) handleInviteCreate(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	id, err := ids.Parse(r.PathValue("id"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	ch, err := s.Store.ChannelByID(r.Context(), id)
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	if ch.Type == "group_dm" {
		// every member of a group can hand out a link to it
		if !hasID(ch.Recipients, me.ID) {
			WriteError(w, ErrNotFound)
			return
		}
	} else if ch.ServerID == nil {
		WriteError(w, ErrNotFound)
		return
	} else if store.HoldsNoMessages(ch.Type) {
		// an invite lands its guest in a channel, and a category or a link is not one
		WriteError(w, E(http.StatusBadRequest, "not_text", "Invite people to a channel, not a category or a link."))
		return
	} else {
		p, _, err := s.Store.ChannelPerms(r.Context(), ch.ID, me.ID)
		if err != nil || p == 0 {
			WriteError(w, ErrNotFound)
			return
		}
		if !p.Has(perms.CreateInstantInvite) {
			WriteError(w, E(http.StatusForbidden, "missing_permission", "You cannot create invites here."))
			return
		}
	}
	var req struct {
		MaxAge  int64 `json:"max_age"`
		MaxUses int   `json:"max_uses"`
	}
	if r.ContentLength > 0 {
		if err := ReadJSON(w, r, &req, 1<<10); err != nil {
			WriteError(w, err)
			return
		}
	}
	if req.MaxAge < 0 || req.MaxAge > 30*24*3600 || req.MaxUses < 0 || req.MaxUses > 1000 {
		WriteError(w, E(http.StatusBadRequest, "invalid_invite", "max_age is 0–30 days in seconds and max_uses is 0–1000."))
		return
	}
	in, err := s.Store.CreateInvite(r.Context(), ch.ServerID, &ch.ID, me.ID, req.MaxAge, req.MaxUses)
	if err != nil {
		WriteError(w, err)
		return
	}
	if ch.ServerID == nil {
		s.Bcast.EmitUsers(ch.Recipients, "invite_create", in)
	} else {
		s.audit(r, *ch.ServerID, me.ID, "invite_create", "invite", nil, map[string]any{"code": in.Code, "max_age": req.MaxAge, "max_uses": req.MaxUses}, "")
		s.emitServerWithPerm(r.Context(), *ch.ServerID, perms.ManageServer, "invite_create", in, me.ID)
	}
	WriteJSON(w, http.StatusCreated, in)
}

// liveInvite loads an invite that can still be used. An account that signed up
// with it and has not joined through it yet may still use it however spent or
// expired it is: the sign-up spent the use its join rides on.
func (s *Server) liveInvite(r *http.Request, code string) (*store.Invite, error) {
	in, err := s.Store.InviteByCode(r.Context(), code)
	if err == nil || !errors.Is(err, store.ErrNotFound) {
		return in, err
	}
	u := auth.UserFrom(r.Context())
	if u == nil {
		// the preview is public, and says more to the account it was spent on
		if token := auth.BearerToken(r); token != "" {
			u, _, _ = s.Auth.Check(r.Context(), token, auth.ClientIP(r))
		}
	}
	if u == nil || s.Store.SignupInvite(r.Context(), u.ID) != code {
		return nil, err
	}
	return s.Store.InviteByCodeAny(r.Context(), code)
}

func (s *Server) handleInvitePreview(w http.ResponseWriter, r *http.Request) {
	in, err := s.liveInvite(r, r.PathValue("code"))
	if err != nil {
		WriteError(w, E(http.StatusNotFound, "invite_invalid", "This invite is invalid or has expired."))
		return
	}
	if in.ServerID == nil {
		s.groupInvitePreview(w, r, in)
		return
	}
	srv, err := s.Store.ServerByID(r.Context(), *in.ServerID)
	if err != nil {
		WriteError(w, E(http.StatusNotFound, "invite_invalid", "This invite is invalid or has expired."))
		return
	}
	restricted := srv.NSFW
	w.Header().Set("Cache-Control", "no-store")
	if restricted && r.URL.Query().Get("nsfw") != "1" {
		WriteJSON(w, http.StatusOK, map[string]any{"code": in.Code, "nsfw": true, "age_gate": true})
		return
	}
	count, online := s.counts(r.Context(), srv.ID)
	resp := map[string]any{"code": in.Code, "server": map[string]any{"id": srv.ID, "name": srv.Name, "icon_id": srv.IconID, "banner_id": srv.BannerID, "description": srv.Description, "member_count": count, "online_count": online}, "expires_at": in.ExpiresAt, "joins_paused": joinsPaused(srv), "nsfw": restricted}
	if in.ChannelID != nil {
		if ch, err := s.Store.ChannelByID(r.Context(), *in.ChannelID); err == nil {
			resp["channel"] = map[string]any{"id": ch.ID, "name": ch.Name, "type": ch.Type}
		}
	}
	if in.Vanity {
		// the server's own link: nobody in particular invited them
		resp["vanity"] = true
	} else if u, err := s.Store.UserByID(r.Context(), in.InviterID); err == nil {
		resp["inviter"] = u.Public()
	}
	WriteJSON(w, http.StatusOK, resp)
}

func (s *Server) handleInviteJoin(w http.ResponseWriter, r *http.Request) {
	if !s.requireCaptcha(w, r, "join") {
		return
	}
	me := auth.UserFrom(r.Context())
	in, err := s.liveInvite(r, r.PathValue("code"))
	if err != nil {
		WriteError(w, E(http.StatusNotFound, "invite_invalid", "This invite is invalid or has expired."))
		return
	}
	if in.ServerID == nil {
		s.groupInviteJoin(w, r, in, me)
		return
	}
	srv, err := s.Store.ServerByID(r.Context(), *in.ServerID)
	if err != nil {
		WriteError(w, E(http.StatusNotFound, "invite_invalid", "This invite is invalid or has expired."))
		return
	}
	if !s.requireNSFWConsent(w, r, srv.ID) {
		return
	}
	// the ban check, the pause and the invite use happen with the insert, so
	// neither a ban or pause written a moment ago nor a used-up invite can be
	// slipped past
	s.forgetCounts(srv.ID)
	member, err := s.Store.JoinByInvite(r.Context(), srv.ID, me.ID, in.Code)
	switch {
	case errors.Is(err, store.ErrBanned):
		WriteError(w, E(http.StatusForbidden, "banned", "You are banned from this server."))
		return
	case errors.Is(err, store.ErrJoinsPaused):
		WriteError(w, errJoinsPaused)
		return
	case errors.Is(err, store.ErrNotFound):
		WriteError(w, E(http.StatusNotFound, "invite_invalid", "This invite is invalid or has expired."))
		return
	case err != nil && err != store.ErrAlreadyExists:
		WriteError(w, err)
		return
	}
	status := http.StatusOK
	if err == nil {
		status = http.StatusCreated
		// the invite kept for the server's tag is joining from the tag
		if srv.Tag != nil && srv.Tag.Invite == in.Code {
			if err := s.Store.SetJoinMethod(r.Context(), srv.ID, me.ID, "tag"); err == nil {
				member.JoinMethod = "tag"
			}
		}
		s.joined(r, srv, me, member)
	}
	view, users, err := s.serverView(r.Context(), srv, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	if status == http.StatusCreated {
		s.Bcast.EmitUsers([]ids.ID{me.ID}, "server_create", serverCreateEvent{view, users})
	}
	WriteJSON(w, status, map[string]any{"server": view, "users": users})
}

// joined tells everyone about someone who has just come into a server, by
// whatever way: the members see them, the system channel greets them, the
// server's checks run on their name, and keys are asked for on their behalf.
func (s *Server) joined(r *http.Request, srv *store.Server, me *store.User, member *store.Member) {
	ctx := r.Context()
	s.emitServer(ctx, srv.ID, "member_add", map[string]any{"server_id": srv.ID, "member": member, "user": me.Public()})
	// the people already here have never seen this user's presence
	s.Bcast.PresenceChanged(me.ID)
	if srv.SystemChannelID != nil {
		if ch, err := s.Store.ChannelByID(ctx, *srv.SystemChannelID); err == nil {
			s.systemMessage(r, ch, me.ID, "system_join", []ids.ID{me.ID}, nil)
		}
	}
	// AutoMod reads the newcomer's name before anyone else has to
	s.checkProfileRules(r, srv, me.ID)
	s.Bcast.KeysMaybeNeeded(srv.ID)
	// channels with keys of their own the newcomer can see need grants too
	if visible, err := s.Store.ViewableServerChannels(ctx, srv.ID, me.ID); err == nil {
		if chans, err := s.Store.ServerChannels(ctx, srv.ID); err == nil {
			for _, c := range chans {
				if _, ok := visible[c.ID]; ok && c.KeyConvID == c.ID {
					s.Bcast.KeysMaybeNeeded(c.ID)
				}
			}
		}
	}
}

func (s *Server) handleInviteDelete(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	in, err := s.Store.InviteByCode(r.Context(), r.PathValue("code"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	if in.ServerID == nil {
		// a group DM invite: any member of that group may revoke it
		if in.ChannelID == nil {
			WriteError(w, ErrNotFound)
			return
		}
		ch, err := s.Store.ChannelByID(r.Context(), *in.ChannelID)
		if err != nil || !hasID(ch.Recipients, me.ID) {
			WriteError(w, ErrNotFound)
			return
		}
		if err := s.Store.DeleteInvite(r.Context(), in.Code); err != nil {
			WriteError(w, err)
			return
		}
		s.Bcast.EmitUsers(ch.Recipients, "invite_delete", map[string]any{"channel_id": ch.ID, "code": in.Code})
		w.WriteHeader(http.StatusNoContent)
		return
	}
	p, err := s.Store.ServerPerms(r.Context(), *in.ServerID, me.ID)
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	if p == 0 {
		if ok, _ := s.Store.IsMember(r.Context(), *in.ServerID, me.ID); !ok {
			WriteError(w, ErrNotFound)
			return
		}
	}
	if in.InviterID != me.ID && !p.Has(perms.ManageServer) {
		WriteError(w, E(http.StatusForbidden, "missing_permission", "You cannot revoke this invite."))
		return
	}
	if err := s.Store.DeleteInvite(r.Context(), in.Code); err != nil {
		WriteError(w, err)
		return
	}
	s.audit(r, *in.ServerID, me.ID, "invite_delete", "invite", nil, map[string]any{"code": in.Code}, "")
	s.emitServerWithPerm(r.Context(), *in.ServerID, perms.ManageServer, "invite_delete", map[string]any{"server_id": *in.ServerID, "code": in.Code}, in.InviterID)
	w.WriteHeader(http.StatusNoContent)
}

// --- audit + keys --------------------------------------------------------

func (s *Server) handleAuditLog(w http.ResponseWriter, r *http.Request) {
	srv, _, _ := s.serverCtx(w, r, perms.ViewAuditLog)
	if srv == nil {
		return
	}
	q := r.URL.Query()
	before, _ := ids.Parse(q.Get("before"))
	limit, _ := strconv.Atoi(q.Get("limit"))
	var f store.AuditFilter
	if a := strings.TrimSpace(q.Get("action")); a != "" {
		f.Actions = strings.Split(a, ",")
	}
	if id, err := ids.Parse(q.Get("actor_id")); err == nil {
		f.Actor = &id
	}
	if id, err := ids.Parse(q.Get("target_id")); err == nil {
		f.Target = &id
	}
	entries, err := s.Store.AuditEntriesFor(r.Context(), srv.ID, f, before, limit)
	if err != nil {
		WriteError(w, err)
		return
	}
	set := map[ids.ID]bool{}
	var list []ids.ID
	for _, e := range entries {
		if !set[e.ActorID] {
			set[e.ActorID] = true
			list = append(list, e.ActorID)
		}
		if e.TargetType == "user" && e.TargetID != nil && !set[*e.TargetID] {
			set[*e.TargetID] = true
			list = append(list, *e.TargetID)
		}
	}
	byID, err := s.Store.UsersByIDs(r.Context(), list)
	if err != nil {
		WriteError(w, err)
		return
	}
	users := []store.PublicUser{}
	for _, id := range list {
		if u := byID[id]; u != nil {
			users = append(users, u.Public())
		}
	}
	WriteJSON(w, http.StatusOK, map[string]any{"entries": entries, "users": users})
}

func (s *Server) handleServerKeys(w http.ResponseWriter, r *http.Request) {
	srv, me, _ := s.serverCtx(w, r, 0)
	if srv == nil {
		return
	}
	type convInfo struct {
		ConvID ids.ID            `json:"conv_id"`
		Name   string            `json:"name"`
		Epochs []*store.KeyEpoch `json:"epochs"`
		Flags  *store.KeyFlags   `json:"flags"`
	}
	out := []convInfo{}
	// This page is what an owner reads to decide whether their keys are in
	// order. A read that failed and came back empty would tell them there are
	// no epochs and nothing needs rotating, which is the opposite of what a
	// page like this is for.
	eps, err := s.Store.Epochs(r.Context(), srv.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	flags, err := s.Store.KeyFlags(r.Context(), srv.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	out = append(out, convInfo{ConvID: srv.ID, Name: srv.Name, Epochs: eps, Flags: flags})
	visible, err := s.Store.ViewableServerChannels(r.Context(), srv.ID, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	chans, err := s.Store.ServerChannels(r.Context(), srv.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	for _, c := range chans {
		if _, ok := visible[c.ID]; !ok || c.KeyConvID != c.ID {
			continue
		}
		eps, err := s.Store.Epochs(r.Context(), c.ID)
		if err != nil {
			WriteError(w, err)
			return
		}
		flags, err := s.Store.KeyFlags(r.Context(), c.ID)
		if err != nil {
			WriteError(w, err)
			return
		}
		out = append(out, convInfo{ConvID: c.ID, Name: "#" + c.Name, Epochs: eps, Flags: flags})
	}
	WriteJSON(w, http.StatusOK, out)
}
