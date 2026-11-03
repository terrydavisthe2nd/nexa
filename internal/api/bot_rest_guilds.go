package api

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"

	"evilion.xyz/chat/internal/botproto"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/perms"
	"evilion.xyz/chat/internal/store"
)

// The bot API's server (guild) routes. Where the app has a route that does
// the same (changing the server, a role, a member; banning, pruning), the
// bot route translates the request and runs it (callNexa), so a bot is held
// to the rules and the role hierarchy a person is, and everyone hears of it
// the same way.

func (s *Server) botGuildRoutes(mux *http.ServeMux) {
	g := "/guilds/{guild}"
	s.botHandle(mux, "GET", g, authBot, s.botGuildGet, bucketed("read"))
	s.botHandle(mux, "PATCH", g, authBot, s.botGuildPatch, bucketed("guild"))
	s.botHandle(mux, "DELETE", g, authBot, s.botGuildDelete, bucketed("guild"))
	s.botHandle(mux, "POST", "/guilds", authBot, func(w http.ResponseWriter, r *http.Request) {
		writeBotError(w, dErrFeatureUnavailable("Servers made by bots"), nil)
	})
	s.botHandle(mux, "GET", g+"/preview", authBot, s.botGuildPreview, bucketed("read"))
	s.botHandle(mux, "GET", g+"/channels", authBot, s.botGuildChannels, bucketed("read"))
	s.botHandle(mux, "POST", g+"/channels", authBot, s.botGuildChannelCreate, bucketed("channelwrite"))
	s.botHandle(mux, "PATCH", g+"/channels", authBot, s.botGuildChannelPositions, bucketed("channelwrite"))
	s.botHandle(mux, "GET", g+"/threads/active", authBot, s.botActiveThreads, bucketed("read"))
	s.botHandle(mux, "GET", g+"/members", authBot, s.botMembersList, bucketed("read"))
	s.botHandle(mux, "GET", g+"/members/search", authBot, s.botMembersSearch, bucketed("read"))
	s.botHandle(mux, "GET", g+"/members/{user}", authBot, s.botMemberGet, bucketed("read"))
	s.botHandle(mux, "PUT", g+"/members/{user}", authBot, s.botMemberAdd, bucketed("member"))
	s.botHandle(mux, "PATCH", g+"/members/{user}", authBot, s.botMemberPatch, bucketed("member"))
	s.botHandle(mux, "PATCH", g+"/members/@me", authBot, s.botMemberPatchMe, bucketed("member"))
	s.botHandle(mux, "PATCH", g+"/members/@me/nick", authBot, s.botMemberNick, bucketed("member"))
	s.botHandle(mux, "DELETE", g+"/members/{user}", authBot, s.botMemberKick, bucketed("member"))
	s.botHandle(mux, "PUT", g+"/members/{user}/roles/{role}", authBot, s.botMemberRole(true), bucketed("member"))
	s.botHandle(mux, "DELETE", g+"/members/{user}/roles/{role}", authBot, s.botMemberRole(false), bucketed("member"))
	s.botHandle(mux, "GET", g+"/bans", authBot, s.botBans, bucketed("read"))
	s.botHandle(mux, "GET", g+"/bans/{user}", authBot, s.botBanGet, bucketed("read"))
	s.botHandle(mux, "PUT", g+"/bans/{user}", authBot, s.botBanPut, bucketed("member"))
	s.botHandle(mux, "DELETE", g+"/bans/{user}", authBot, s.botBanDelete, bucketed("member"))
	s.botHandle(mux, "POST", g+"/bulk-ban", authBot, s.botBulkBan, bucketed("member"))
	s.botHandle(mux, "GET", g+"/roles", authBot, s.botRoles, bucketed("read"))
	s.botHandle(mux, "GET", g+"/roles/member-counts", authBot, s.botRoleCounts, bucketed("read"))
	s.botHandle(mux, "GET", g+"/roles/{role}", authBot, s.botRoleGet, bucketed("read"))
	s.botHandle(mux, "POST", g+"/roles", authBot, s.botRoleCreate, bucketed("role"))
	s.botHandle(mux, "PATCH", g+"/roles", authBot, s.botRolePositions, bucketed("role"))
	s.botHandle(mux, "PATCH", g+"/roles/{role}", authBot, s.botRolePatch, bucketed("role"))
	s.botHandle(mux, "DELETE", g+"/roles/{role}", authBot, s.botRoleDelete, bucketed("role"))
	s.botHandle(mux, "GET", g+"/prune", authBot, s.botPruneCount, bucketed("read"))
	s.botHandle(mux, "POST", g+"/prune", authBot, s.botPrune, bucketed("member"))
	s.botHandle(mux, "GET", g+"/regions", authBot, s.botVoiceRegions)
	s.botHandle(mux, "GET", g+"/invites", authBot, s.botGuildInvites, bucketed("read"))
	s.botHandle(mux, "GET", g+"/integrations", authBot, s.botIntegrations, bucketed("read"))
	s.botHandle(mux, "DELETE", g+"/integrations/{integration}", authBot, s.botIntegrationDelete)
	s.botHandle(mux, "GET", g+"/audit-logs", authBot, s.botAuditLog, bucketed("read"))
	s.botHandle(mux, "GET", g+"/webhooks", authBot, s.botGuildWebhooks, bucketed("read"))
	s.botHandle(mux, "GET", g+"/voice-states/@me", authBot, s.botVoiceStateGet, bucketed("read"))
	s.botHandle(mux, "GET", g+"/voice-states/{user}", authBot, s.botVoiceStateGet, bucketed("read"))
	s.botHandle(mux, "PUT", g+"/incident-actions", authBot, s.botIncidentActions)
	s.botHandle(mux, "GET", g+"/vanity-url", authBot, func(w http.ResponseWriter, r *http.Request) {
		if srv := s.botGuild(w, r, perms.ManageServer); srv != nil {
			in, err := s.Store.VanityInvite(r.Context(), srv.ID)
			if err != nil {
				WriteJSON(w, http.StatusOK, map[string]any{"code": nil, "uses": 0})
				return
			}
			WriteJSON(w, http.StatusOK, map[string]any{"code": in.Code, "uses": in.Uses})
		}
	})
	// what this server has none of: a widget, a welcome screen, onboarding,
	// membership screening, scheduled events, a soundboard, templates
	widgetOff := func(w http.ResponseWriter, r *http.Request) {
		writeBotError(w, dErr(http.StatusForbidden, 50004, "Widget Disabled"), nil)
	}
	s.botHandle(mux, "GET", g+"/widget.json", authNone, widgetOff)
	s.botHandle(mux, "GET", g+"/widget.png", authNone, widgetOff)
	s.botHandle(mux, "GET", g+"/widget", authBot, func(w http.ResponseWriter, r *http.Request) {
		if srv := s.botGuild(w, r, perms.ManageServer); srv != nil {
			WriteJSON(w, http.StatusOK, map[string]any{"enabled": false, "channel_id": nil})
		}
	})
	s.botHandle(mux, "PATCH", g+"/widget", authBot, func(w http.ResponseWriter, r *http.Request) {
		writeBotError(w, dErrFeatureUnavailable("Server widgets"), nil)
	})
	s.botHandle(mux, "GET", g+"/welcome-screen", authBot, func(w http.ResponseWriter, r *http.Request) {
		writeBotError(w, dErr(http.StatusNotFound, 10069, "Unknown Guild Welcome Screen"), nil)
	})
	s.botHandle(mux, "PATCH", g+"/welcome-screen", authBot, func(w http.ResponseWriter, r *http.Request) {
		writeBotError(w, dErrFeatureUnavailable("Welcome screens"), nil)
	})
	s.botHandle(mux, "GET", g+"/onboarding", authBot, func(w http.ResponseWriter, r *http.Request) {
		if srv := s.botGuild(w, r, 0); srv != nil {
			WriteJSON(w, http.StatusOK, map[string]any{"guild_id": snow(srv.ID), "prompts": []any{}, "default_channel_ids": []any{}, "enabled": false, "mode": 0})
		}
	})
	s.botHandle(mux, "PUT", g+"/onboarding", authBot, func(w http.ResponseWriter, r *http.Request) {
		writeBotError(w, dErrFeatureUnavailable("Onboarding prompts"), nil)
	})
	s.botHandle(mux, "GET", g+"/member-verification", authBot, func(w http.ResponseWriter, r *http.Request) {
		writeBotError(w, dErr(http.StatusNotFound, 10068, "Unknown Guild Member Verification Form"), nil)
	})
	s.botHandle(mux, "PATCH", g+"/member-verification", authBot, func(w http.ResponseWriter, r *http.Request) {
		writeBotError(w, dErrFeatureUnavailable("Membership screening forms"), nil)
	})
	emptyList := func(w http.ResponseWriter, r *http.Request) {
		if srv := s.botGuild(w, r, 0); srv != nil {
			WriteJSON(w, http.StatusOK, []any{})
		}
	}
	s.botHandle(mux, "GET", g+"/scheduled-events", authBot, emptyList, bucketed("read"))
	s.botHandle(mux, "GET", g+"/templates", authBot, emptyList, bucketed("read"))
	s.botHandle(mux, "GET", g+"/soundboard-sounds", authBot, func(w http.ResponseWriter, r *http.Request) {
		if srv := s.botGuild(w, r, 0); srv != nil {
			WriteJSON(w, http.StatusOK, map[string]any{"items": []any{}})
		}
	}, bucketed("read"))
	unknownEvent := func(w http.ResponseWriter, r *http.Request) {
		writeBotError(w, dErr(http.StatusNotFound, 10070, "Unknown Guild Scheduled Event"), nil)
	}
	s.botHandle(mux, "GET", g+"/scheduled-events/{event}", authBot, unknownEvent)
	s.botHandle(mux, "PATCH", g+"/scheduled-events/{event}", authBot, unknownEvent)
	s.botHandle(mux, "DELETE", g+"/scheduled-events/{event}", authBot, unknownEvent)
	s.botHandle(mux, "GET", g+"/scheduled-events/{event}/users", authBot, unknownEvent)
	s.botHandle(mux, "POST", g+"/scheduled-events", authBot, func(w http.ResponseWriter, r *http.Request) {
		writeBotError(w, dErrFeatureUnavailable("Scheduled events"), nil)
	})
	unknownSound := func(w http.ResponseWriter, r *http.Request) {
		writeBotError(w, dErr(http.StatusNotFound, 10097, "Unknown Soundboard Sound"), nil)
	}
	s.botHandle(mux, "GET", g+"/soundboard-sounds/{sound}", authBot, unknownSound)
	s.botHandle(mux, "PATCH", g+"/soundboard-sounds/{sound}", authBot, unknownSound)
	s.botHandle(mux, "DELETE", g+"/soundboard-sounds/{sound}", authBot, unknownSound)
	s.botHandle(mux, "POST", g+"/soundboard-sounds", authBot, func(w http.ResponseWriter, r *http.Request) {
		writeBotError(w, dErrFeatureUnavailable("Soundboard sounds"), nil)
	})
	unknownTemplate := func(w http.ResponseWriter, r *http.Request) {
		writeBotError(w, dErr(http.StatusNotFound, 10057, "Unknown Guild Template"), nil)
	}
	s.botHandle(mux, "PUT", g+"/templates/{template}", authBot, unknownTemplate)
	s.botHandle(mux, "PATCH", g+"/templates/{template}", authBot, unknownTemplate)
	s.botHandle(mux, "DELETE", g+"/templates/{template}", authBot, unknownTemplate)
	s.botHandle(mux, "POST", g+"/templates", authBot, func(w http.ResponseWriter, r *http.Request) {
		writeBotError(w, dErrFeatureUnavailable("Server templates"), nil)
	})
	s.botHandle(mux, "PATCH", g+"/voice-states/@me", authBot, func(w http.ResponseWriter, r *http.Request) {
		writeBotError(w, dErrFeatureUnavailable("Stage channels"), nil)
	})
	s.botHandle(mux, "PATCH", g+"/voice-states/{user}", authBot, func(w http.ResponseWriter, r *http.Request) {
		writeBotError(w, dErrFeatureUnavailable("Stage channels"), nil)
	})
	s.botHandle(mux, "POST", g+"/mfa", authBot, func(w http.ResponseWriter, r *http.Request) {
		writeBotError(w, dErrNoBotEndpoint, nil)
	})
	s.botHandle(mux, "GET", g+"/messages/search", authBot, func(w http.ResponseWriter, r *http.Request) {
		// messages are end-to-end encrypted here: the server has nothing to search
		if srv := s.botGuild(w, r, 0); srv != nil {
			WriteJSON(w, http.StatusOK, map[string]any{"doing_deep_historical_index": false, "total_results": 0, "messages": []any{}, "threads": []any{}, "members": []any{}})
		}
	})
}

// botGuild loads the {guild} of a route the bot is a member of, and checks
// it holds need there.
func (s *Server) botGuild(w http.ResponseWriter, r *http.Request, need perms.Perm) *store.Server {
	b := botOf(r)
	id, ok := pathID(r, "guild")
	if !ok {
		writeBotError(w, dErrUnknownGuild, nil)
		return nil
	}
	ctx := r.Context()
	srv, err := s.Store.ServerByID(ctx, id)
	if err != nil {
		writeBotError(w, dErrUnknownGuild, nil)
		return nil
	}
	if member, _ := s.Store.IsMember(ctx, srv.ID, b.user.ID); !member {
		writeBotError(w, dErrMissingAccess, nil)
		return nil
	}
	if need != 0 {
		p, err := s.Store.ServerPerms(ctx, srv.ID, b.user.ID)
		if err != nil {
			writeBotError(w, err, nil)
			return nil
		}
		if !p.Has(need) {
			writeBotError(w, dErrMissingPerms, nil)
			return nil
		}
	}
	return srv
}

// nexaGuild runs one of the app's server routes for a server.
func (s *Server) nexaGuild(r *http.Request, h http.HandlerFunc, method string, srv *store.Server, extra map[string]string, query url.Values, body any) *nexaAnswer {
	path := map[string]string{"id": srv.ID.String()}
	for k, v := range extra {
		path[k] = v
	}
	return s.callNexa(r, h, method, path, query, body)
}

// --- the server itself -----------------------------------------------------------------------

func (s *Server) botGuildGet(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, 0)
	if srv == nil {
		return
	}
	b := s.builderFor(r, botOf(r))
	out := b.guildObj(srv)
	if boolQuery(r, "with_counts", false) {
		if n, err := s.Store.MemberCount(r.Context(), srv.ID); err == nil {
			online := s.onlineCount(r.Context(), srv.ID)
			out.ApproximateMemberCount, out.ApproximatePresenceCount = &n, &online
		}
	}
	WriteJSON(w, http.StatusOK, out)
}

// verificationMinutes is how long an account and a member must wait to talk
// at each of the protocol's verification levels.
func verificationMinutes(level int64) (account, member int) {
	switch level {
	case 0, 1:
		return 0, 0
	case 2:
		return 5, 0
	default:
		return 5, 10
	}
}

func (s *Server) botGuildPatch(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, perms.ManageServer)
	if srv == nil {
		return
	}
	raw, derr := readBotObject(w, r, s.Cfg.MaxAvatarBytes*3+64<<10)
	if derr != nil {
		writeBotError(w, derr, nil)
		return
	}
	errs := &formErrors{}
	rd := jsonReader{errs}
	body := map[string]any{}
	if v, ok := rd.str(raw, "name", nil); ok {
		body["name"] = v
	}
	if v, present := raw["description"]; present {
		if v == nil {
			body["description"] = nil
		} else if sv, ok := rd.str(raw, "description", nil); ok {
			body["description"] = sv
		}
	}
	if v, present := raw["system_channel_id"]; present {
		if v == nil {
			body["system_channel_id"] = nil
		} else if sv, ok := rd.str(raw, "system_channel_id", nil); ok {
			if id, err := parseBotID(sv); err == nil {
				body["system_channel_id"] = id
			} else {
				errs.add(fieldPath("system_channel_id"), "NUMBER_TYPE_COERCE", "Value is not snowflake.")
			}
		}
	}
	if v, ok := rd.integer(raw, "default_message_notifications", nil); ok {
		body["default_notifications"] = v
	}
	if v, ok := rd.integer(raw, "verification_level", nil); ok {
		account, member := verificationMinutes(v)
		body["verify_account_minutes"], body["verify_member_minutes"] = account, member
	}
	var icon, banner *rawImage
	if v, present := raw["icon"]; present {
		icon = s.readImageField(rd, v, "icon")
	}
	if v, present := raw["banner"]; present {
		banner = s.readImageField(rd, v, "banner")
	}
	if v, present := raw["owner_id"]; present && v != nil {
		errs.add(fieldPath("owner_id"), "GUILD_OWNER_TRANSFER_BOT", "Bots cannot own servers here.")
	}
	if !errs.empty() {
		writeBotError(w, errs.err(), nil)
		return
	}
	if len(body) > 0 {
		if ans := s.nexaGuild(r, s.handleServerPatch, http.MethodPatch, srv, nil, nil, body); !ans.ok() {
			writeBotError(w, ans.err(), dErrUnknownGuild)
			return
		}
	}
	for _, img := range []struct {
		v    *rawImage
		kind string
	}{{icon, "icon"}, {banner, "banner"}} {
		if img.v == nil {
			continue
		}
		var ans *nexaAnswer
		if img.v.data == nil {
			ans = s.nexaGuild(r, s.handleServerImageDelete(img.kind), http.MethodDelete, srv, nil, nil, nil)
		} else {
			ans = s.nexaGuild(r, s.handleServerImage(img.kind), http.MethodPut, srv, nil, nil, &rawBody{data: img.v.data, contentType: "application/octet-stream"})
		}
		if !ans.ok() {
			writeBotError(w, ans.err(), dErrUnknownGuild)
			return
		}
	}
	fresh, err := s.Store.ServerByID(r.Context(), srv.ID)
	if err != nil {
		writeBotError(w, err, dErrUnknownGuild)
		return
	}
	WriteJSON(w, http.StatusOK, s.builderFor(r, botOf(r)).guildObj(fresh))
}

func (s *Server) botGuildDelete(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, 0)
	if srv == nil {
		return
	}
	if srv.OwnerID != botOf(r).user.ID {
		writeBotError(w, dErrMissingPerms, nil)
		return
	}
	if ans := s.nexaGuild(r, s.handleServerDelete, http.MethodDelete, srv, nil, nil, nil); !ans.ok() {
		writeBotError(w, ans.err(), dErrUnknownGuild)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// botGuildPreview is a server's public face, for a member or anyone when it
// is listed in Discovery.
func (s *Server) botGuildPreview(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r, "guild")
	if !ok {
		writeBotError(w, dErrUnknownGuild, nil)
		return
	}
	ctx := r.Context()
	srv, err := s.Store.ServerByID(ctx, id)
	if err != nil {
		writeBotError(w, dErrUnknownGuild, nil)
		return
	}
	if member, _ := s.Store.IsMember(ctx, srv.ID, botOf(r).user.ID); !member && !srv.Discoverable {
		writeBotError(w, dErrUnknownGuild, nil)
		return
	}
	b := s.builderFor(r, botOf(r))
	g := b.guildObj(srv)
	n, _ := s.Store.MemberCount(ctx, srv.ID)
	WriteJSON(w, http.StatusOK, map[string]any{"id": g.ID, "name": g.Name, "icon": g.Icon, "splash": nil, "discovery_splash": nil, "emojis": g.Emojis,
		"features": g.Features, "approximate_member_count": n, "approximate_presence_count": s.onlineCount(ctx, srv.ID), "description": g.Description,
		"stickers": g.Stickers})
}

func (s *Server) botIncidentActions(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, perms.ManageServer)
	if srv == nil {
		return
	}
	var body struct {
		InvitesDisabledUntil *string `json:"invites_disabled_until"`
		DMsDisabledUntil     *string `json:"dms_disabled_until"`
	}
	if err := ReadJSON(w, r, &body, 4<<10); err != nil {
		writeBotError(w, dErrBadJSON, nil)
		return
	}
	patch := map[string]any{"invites_paused_until": nil}
	if body.InvitesDisabledUntil != nil {
		ms, ok := botproto.ParseTimestamp(*body.InvitesDisabledUntil)
		if !ok {
			writeBotError(w, dFormError("DATE_TYPE_INVALID", "Invalid date", "invites_disabled_until"), nil)
			return
		}
		patch["invites_paused_until"] = ms
	}
	if ans := s.nexaGuild(r, s.handleServerPatch, http.MethodPatch, srv, nil, nil, patch); !ans.ok() {
		writeBotError(w, ans.err(), dErrUnknownGuild)
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"invites_disabled_until": body.InvitesDisabledUntil, "dms_disabled_until": nil, "dm_spam_detected_at": nil, "raid_detected_at": nil})
}

// --- channels ----------------------------------------------------------------------------------

func (s *Server) botGuildChannels(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, 0)
	if srv == nil {
		return
	}
	ctx := r.Context()
	chans, err := s.Store.ServerChannels(ctx, srv.ID)
	if err != nil {
		writeBotError(w, err, nil)
		return
	}
	b := s.builderFor(r, botOf(r))
	visible, _ := s.Store.ViewableServerChannels(ctx, srv.ID, botOf(r).user.ID)
	out := []dChannel{}
	for _, ch := range chans {
		if store.IsThread(ch.Type) {
			continue
		}
		if _, ok := visible[ch.ID]; !ok {
			continue
		}
		if dc, ok := b.channelObj(ch); ok {
			out = append(out, dc)
		}
	}
	WriteJSON(w, http.StatusOK, out)
}

func (s *Server) botGuildChannelCreate(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, perms.ManageChannels)
	if srv == nil {
		return
	}
	raw, derr := readBotObject(w, r, 256<<10)
	if derr != nil {
		writeBotError(w, derr, nil)
		return
	}
	errs := &formErrors{}
	rd := jsonReader{errs}
	name, _ := rd.str(raw, "name", nil)
	if strings.TrimSpace(name) == "" {
		errs.add(fieldPath("name"), "BASE_TYPE_REQUIRED", "This field is required")
	}
	t, _ := rd.integer(raw, "type", nil)
	typ, ok := nexaChannelType(int(t))
	if !ok || store.IsThread(typ) || typ == "dm" || typ == "group_dm" {
		errs.add(fieldPath("type"), "CHANNEL_TYPE_INVALID", "That kind of channel is not available on this server.")
	}
	body := map[string]any{"type": typ, "name": name}
	if v, ok := rd.str(raw, "parent_id", nil); ok && v != "" {
		if id, err := parseBotID(v); err == nil {
			body["parent_id"] = id
		}
	}
	if !errs.empty() {
		writeBotError(w, errs.err(), nil)
		return
	}
	ans := s.nexaGuild(r, s.handleServerChannelCreate, http.MethodPost, srv, nil, nil, body)
	if !ans.ok() {
		writeBotError(w, ans.err(), dErrUnknownChannel)
		return
	}
	var created store.Channel
	if err := ans.decode(&created); err != nil {
		writeBotError(w, err, nil)
		return
	}
	ch, err := s.Store.ChannelByID(r.Context(), created.ID)
	if err != nil {
		writeBotError(w, err, dErrUnknownChannel)
		return
	}
	// the rest of what the channel was made with is applied as a change
	delete(raw, "name")
	delete(raw, "type")
	delete(raw, "parent_id")
	if len(raw) > 0 {
		if err := s.applyChannelPatch(r, ch, raw); err != nil {
			writeBotError(w, err, dErrUnknownChannel)
			return
		}
		ch, _ = s.Store.ChannelByID(r.Context(), created.ID)
	}
	dc, _ := s.builderFor(r, botOf(r)).channelObj(ch)
	WriteJSON(w, http.StatusCreated, dc)
}

func (s *Server) botGuildChannelPositions(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, perms.ManageChannels)
	if srv == nil {
		return
	}
	var body []struct {
		ID       snow            `json:"id"`
		Position *int            `json:"position"`
		ParentID json.RawMessage `json:"parent_id"`
	}
	if err := ReadJSON(w, r, &body, 64<<10); err != nil {
		writeBotError(w, dErrBadJSON, nil)
		return
	}
	ctx := r.Context()
	moves := []map[string]any{}
	for _, item := range body {
		ch, err := s.Store.ChannelByID(ctx, ids.ID(item.ID))
		if err != nil || ch.ServerID == nil || *ch.ServerID != srv.ID {
			writeBotError(w, dErrUnknownChannel, nil)
			return
		}
		pos := ch.Position
		if item.Position != nil {
			pos = *item.Position
		}
		move := map[string]any{"id": ch.ID, "position": pos}
		if len(item.ParentID) > 0 {
			if string(item.ParentID) == "null" {
				move["parent_id"] = nil
			} else {
				var p snow
				if json.Unmarshal(item.ParentID, &p) == nil && p != 0 {
					move["parent_id"] = ids.ID(p)
				}
			}
		}
		moves = append(moves, move)
	}
	if ans := s.nexaGuild(r, s.handleServerChannelPositions, http.MethodPatch, srv, nil, nil, moves); !ans.ok() {
		writeBotError(w, ans.err(), dErrUnknownChannel)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// botActiveThreads lists the threads still going in a server that the bot
// can see, with its own membership of each.
func (s *Server) botActiveThreads(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, 0)
	if srv == nil {
		return
	}
	ctx := r.Context()
	b := botOf(r)
	threads, err := s.Store.ActiveThreads(ctx, srv.ID)
	if err != nil {
		writeBotError(w, err, nil)
		return
	}
	bld := s.builderFor(r, b)
	out := []dChannel{}
	members := []dThreadMember{}
	for _, th := range threads {
		p, err := s.Store.PermsIn(ctx, th, b.user.ID)
		if err != nil || !p.Has(perms.ViewChannel) {
			continue
		}
		if dc, ok := bld.channelObj(th); ok {
			out = append(out, dc)
		}
		if in, _ := s.Store.IsThreadMember(ctx, th.ID, b.user.ID); in {
			members = append(members, dThreadMember{ID: botproto.SP(&th.ID), UserID: botproto.SP(&b.user.ID), JoinTimestamp: botproto.Timestamp(th.CreatedAt), Flags: 0})
		}
	}
	WriteJSON(w, http.StatusOK, map[string]any{"threads": out, "members": members})
}

// --- members -------------------------------------------------------------------------------------

func (s *Server) botMembersList(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, 0)
	if srv == nil {
		return
	}
	b := botOf(r)
	// listing a server's members takes the Server Members intent
	if b.app.Flags&(botproto.AppFlagGatewayGuildMembers|botproto.AppFlagGatewayGuildMembersLimited) == 0 {
		writeBotError(w, dErrMissingAccess, nil)
		return
	}
	q := r.URL.Query()
	limit := 1
	if v := q.Get("limit"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 || n > 1000 {
			writeBotError(w, dFormError("NUMBER_TYPE_MAX", "int value should be between 1 and 1000.", "limit"), nil)
			return
		}
		limit = n
	}
	var after ids.ID
	if v := q.Get("after"); v != "" {
		if n, err := botproto.Parse(v); err == nil && n > 0 {
			after = ids.ID(n)
		}
	}
	list, err := s.Store.Members(r.Context(), srv.ID, after, limit, "")
	if err != nil {
		writeBotError(w, err, nil)
		return
	}
	s.writeMembers(w, r, list)
}

func (s *Server) writeMembers(w http.ResponseWriter, r *http.Request, list []*store.Member) {
	bld := s.builderFor(r, botOf(r))
	bld.preloadMembers(list)
	out := make([]dMember, 0, len(list))
	for _, m := range list {
		out = append(out, bld.memberObj(m, true))
	}
	WriteJSON(w, http.StatusOK, out)
}

func (s *Server) botMembersSearch(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, 0)
	if srv == nil {
		return
	}
	q := r.URL.Query()
	query := q.Get("query")
	if query == "" {
		writeBotError(w, dFormError("BASE_TYPE_REQUIRED", "This field is required", "query"), nil)
		return
	}
	limit := 1
	if v := q.Get("limit"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 || n > 1000 {
			writeBotError(w, dFormError("NUMBER_TYPE_MAX", "int value should be between 1 and 1000.", "limit"), nil)
			return
		}
		limit = n
	}
	list, err := s.Store.Members(r.Context(), srv.ID, 0, limit, query)
	if err != nil {
		writeBotError(w, err, nil)
		return
	}
	s.writeMembers(w, r, list)
}

// botMember loads the {user} member of a server route.
func (s *Server) botMember(w http.ResponseWriter, r *http.Request, srv *store.Server) *store.Member {
	uid := botOf(r).user.ID
	if r.PathValue("user") != "@me" {
		id, ok := pathID(r, "user")
		if !ok {
			writeBotError(w, dErrUnknownMember, nil)
			return nil
		}
		uid = id
	}
	m, err := s.Store.Member(r.Context(), srv.ID, uid)
	if err != nil {
		if _, uerr := s.Store.UserByID(r.Context(), uid); uerr != nil {
			writeBotError(w, dErrUnknownUser, nil)
		} else {
			writeBotError(w, dErrUnknownMember, nil)
		}
		return nil
	}
	return m
}

func (s *Server) botMemberGet(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, 0)
	if srv == nil {
		return
	}
	m := s.botMember(w, r, srv)
	if m == nil {
		return
	}
	bld := s.builderFor(r, botOf(r))
	bld.preloadMembers([]*store.Member{m})
	WriteJSON(w, http.StatusOK, bld.memberObj(m, true))
}

// botMemberAdd brings into a server someone who let the application do so
// (an OAuth2 token with guilds.join), as the protocol's Add Guild Member.
func (s *Server) botMemberAdd(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, perms.CreateInstantInvite)
	if srv == nil {
		return
	}
	b := botOf(r)
	uid, ok := pathID(r, "user")
	if !ok {
		writeBotError(w, dErrUnknownUser, nil)
		return
	}
	ctx := r.Context()
	var body struct {
		AccessToken string  `json:"access_token"`
		Nick        *string `json:"nick"`
		Roles       []snow  `json:"roles"`
	}
	if err := ReadJSON(w, r, &body, 16<<10); err != nil {
		writeBotError(w, dErrBadJSON, nil)
		return
	}
	t, err := s.Store.OAuthTokenByAccess(ctx, tokenHash(body.AccessToken))
	if err != nil || t.ApplicationID != b.app.ID || t.UserID != uid || t.ExpiresAt < time.Now().UnixMilli() || !(&botCtx{token: t}).hasScope("guilds.join") {
		writeBotError(w, dErr(http.StatusForbidden, 50025, "Invalid OAuth2 access token"), nil)
		return
	}
	if member, _ := s.Store.IsMember(ctx, srv.ID, uid); member {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	user, err := s.Store.UserByID(ctx, uid)
	if err != nil {
		writeBotError(w, dErrUnknownUser, nil)
		return
	}
	s.forgetCounts(srv.ID)
	member, err := s.Store.Join(ctx, srv.ID, uid, store.JoinInfo{})
	switch {
	case errors.Is(err, store.ErrBanned):
		writeBotError(w, dErr(http.StatusForbidden, 40007, "The user is banned from this guild."), nil)
		return
	case errors.Is(err, store.ErrJoinsPaused):
		writeBotError(w, dErr(http.StatusForbidden, 40002, "You need to verify your account in order to perform this action."), nil)
		return
	case err != nil:
		writeBotError(w, err, nil)
		return
	}
	s.joined(r, srv, user, member)
	if view, users, err := s.serverView(ctx, srv, uid); err == nil {
		s.Bcast.EmitUsers([]ids.ID{uid}, "server_create", serverCreateEvent{view, users})
	}
	patch := map[string]any{}
	if body.Nick != nil {
		patch["nick"] = *body.Nick
	}
	if len(body.Roles) > 0 {
		patch["roles"] = botproto.IDs(body.Roles)
	}
	if len(patch) > 0 {
		_ = s.nexaGuild(r, s.handleMemberPatch, http.MethodPatch, srv, map[string]string{"uid": uid.String()}, nil, patch)
	}
	fresh, err := s.Store.Member(ctx, srv.ID, uid)
	if err != nil {
		writeBotError(w, err, dErrUnknownMember)
		return
	}
	bld := s.builderFor(r, b)
	bld.preloadMembers([]*store.Member{fresh})
	WriteJSON(w, http.StatusCreated, bld.memberObj(fresh, true))
}

// memberPatchBody turns the protocol's member changes into the app's.
func memberPatchBody(rd jsonReader, raw map[string]any) (map[string]any, map[string]any) {
	body, voice := map[string]any{}, map[string]any{}
	if v, present := raw["nick"]; present {
		if v == nil {
			body["nick"] = ""
		} else if sv, ok := rd.str(raw, "nick", nil); ok {
			body["nick"] = sv
		}
	}
	if list, ok := rd.list(raw, "roles", nil); ok {
		roles := []ids.ID{}
		for i, v := range list {
			sv := ""
			switch x := v.(type) {
			case string:
				sv = x
			case json.Number:
				sv = x.String()
			}
			id, err := parseBotID(sv)
			if err != nil {
				rd.errs.add(fieldPath("roles", i), "NUMBER_TYPE_COERCE", "Value is not snowflake.")
				continue
			}
			roles = append(roles, id)
		}
		body["roles"] = roles
	}
	if v, present := raw["communication_disabled_until"]; present {
		if v == nil {
			body["timeout_until"] = nil
		} else if sv, ok := rd.str(raw, "communication_disabled_until", nil); ok {
			ms, ok := botproto.ParseTimestamp(sv)
			if !ok {
				rd.errs.add(fieldPath("communication_disabled_until"), "DATE_TYPE_INVALID", "Invalid date")
			} else {
				body["timeout_until"] = ms
			}
		}
	}
	if v, ok := rd.boolean(raw, "mute", nil); ok {
		voice["mute"] = v
	}
	if v, ok := rd.boolean(raw, "deaf", nil); ok {
		voice["deaf"] = v
	}
	if v, present := raw["channel_id"]; present {
		if v == nil {
			voice["channel_id"] = nil
		} else if sv, ok := rd.str(raw, "channel_id", nil); ok {
			if id, err := parseBotID(sv); err == nil {
				voice["channel_id"] = id
			}
		}
	}
	return body, voice
}

func (s *Server) botMemberPatch(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, 0)
	if srv == nil {
		return
	}
	m := s.botMember(w, r, srv)
	if m == nil {
		return
	}
	raw, derr := readBotObject(w, r, 64<<10)
	if derr != nil {
		writeBotError(w, derr, nil)
		return
	}
	s.patchMember(w, r, srv, m, raw)
}

// patchMember applies the protocol's member changes, and answers with the
// member as they are after.
func (s *Server) patchMember(w http.ResponseWriter, r *http.Request, srv *store.Server, m *store.Member, raw map[string]any) {
	errs := &formErrors{}
	body, voice := memberPatchBody(jsonReader{errs}, raw)
	if !errs.empty() {
		writeBotError(w, errs.err(), nil)
		return
	}
	ctx := r.Context()
	if len(body) > 0 {
		if ans := s.nexaGuild(r, s.handleMemberPatch, http.MethodPatch, srv, map[string]string{"uid": m.UserID.String()}, nil, body); !ans.ok() {
			writeBotError(w, ans.err(), dErrUnknownMember)
			return
		}
	}
	if len(voice) > 0 {
		if _, inVoice := s.Voice.Get(m.UserID); inVoice {
			if ans := s.nexaGuild(r, s.handleVoiceModerate, http.MethodPatch, srv, map[string]string{"uid": m.UserID.String()}, nil, voice); !ans.ok() {
				writeBotError(w, ans.err(), dErrUnknownMember)
				return
			}
		} else if _, moving := voice["channel_id"]; moving {
			writeBotError(w, dErr(http.StatusBadRequest, 40032, "Target user is not connected to voice."), nil)
			return
		} else if err := s.setVoiceFlags(r, srv, m, voice); err != nil {
			writeBotError(w, err, dErrUnknownMember)
			return
		}
	}
	fresh, err := s.Store.Member(ctx, srv.ID, m.UserID)
	if err != nil {
		writeBotError(w, err, dErrUnknownMember)
		return
	}
	bld := s.builderFor(r, botOf(r))
	bld.preloadMembers([]*store.Member{fresh})
	WriteJSON(w, http.StatusOK, bld.memberObj(fresh, true))
}

// setVoiceFlags server-mutes or deafens someone not in a call now, which
// holds from when they next join one, with the permissions it takes there.
func (s *Server) setVoiceFlags(r *http.Request, srv *store.Server, m *store.Member, voice map[string]any) error {
	ctx := r.Context()
	b := botOf(r)
	p, err := s.Store.ServerPerms(ctx, srv.ID, b.user.ID)
	if err != nil {
		return err
	}
	set, clear := int64(0), int64(0)
	if v, ok := voice["mute"].(bool); ok {
		if !p.Has(perms.MuteMembers) {
			return dErrMissingPerms
		}
		if v {
			set |= store.MemberVoiceMuted
		} else {
			clear |= store.MemberVoiceMuted
		}
	}
	if v, ok := voice["deaf"].(bool); ok {
		if !p.Has(perms.DeafenMembers) {
			return dErrMissingPerms
		}
		if v {
			set |= store.MemberVoiceDeafened
		} else {
			clear |= store.MemberVoiceDeafened
		}
	}
	if !s.canActOn(ctx, srv, b.user.ID, m.UserID) {
		return dErrMissingPerms
	}
	if err := s.Store.SetMemberFlags(ctx, srv.ID, m.UserID, set, clear); err != nil {
		return err
	}
	if fresh, err := s.Store.Member(ctx, srv.ID, m.UserID); err == nil {
		s.emitServer(ctx, srv.ID, "member_update", map[string]any{"server_id": srv.ID, "member": fresh})
	}
	return nil
}

func (s *Server) botMemberPatchMe(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, 0)
	if srv == nil {
		return
	}
	m := s.botMember(w, r, srv)
	if m == nil {
		return
	}
	raw, derr := readBotObject(w, r, s.Cfg.MaxAvatarBytes*3+64<<10)
	if derr != nil {
		writeBotError(w, derr, nil)
		return
	}
	// a bot changes only its own nickname, server picture, banner and bio
	allowed := map[string]any{}
	if v, present := raw["nick"]; present {
		allowed["nick"] = v
	}
	errs := &formErrors{}
	rd := jsonReader{errs}
	if v, present := raw["bio"]; present {
		if v == nil {
			if ans := s.nexaGuild(r, s.handleMemberPatch, http.MethodPatch, srv, map[string]string{"uid": m.UserID.String()}, nil, map[string]any{"bio": ""}); !ans.ok() {
				writeBotError(w, ans.err(), dErrUnknownMember)
				return
			}
		} else if sv, ok := rd.str(raw, "bio", nil); ok {
			if ans := s.nexaGuild(r, s.handleMemberPatch, http.MethodPatch, srv, map[string]string{"uid": m.UserID.String()}, nil, map[string]any{"bio": sv}); !ans.ok() {
				writeBotError(w, ans.err(), dErrUnknownMember)
				return
			}
		}
	}
	for _, kind := range []string{"avatar", "banner"} {
		v, present := raw[kind]
		if !present {
			continue
		}
		img := s.readImageField(rd, v, kind)
		if img == nil {
			continue
		}
		var ans *nexaAnswer
		if img.data == nil {
			ans = s.nexaGuild(r, s.handleMemberImageDelete(kind == "banner"), http.MethodDelete, srv, map[string]string{"uid": m.UserID.String()}, nil, nil)
		} else {
			ans = s.nexaGuild(r, s.handleMemberImage(kind == "banner"), http.MethodPut, srv, map[string]string{"uid": m.UserID.String()}, nil, &rawBody{data: img.data, contentType: "application/octet-stream"})
		}
		if !ans.ok() {
			writeBotError(w, ans.err(), dErrUnknownMember)
			return
		}
	}
	if !errs.empty() {
		writeBotError(w, errs.err(), nil)
		return
	}
	s.patchMember(w, r, srv, m, allowed)
}

// botMemberNick is the older way to change the bot's own nickname.
func (s *Server) botMemberNick(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, perms.ChangeNickname)
	if srv == nil {
		return
	}
	var body struct {
		Nick *string `json:"nick"`
	}
	if err := ReadJSON(w, r, &body, 4<<10); err != nil {
		writeBotError(w, dErrBadJSON, nil)
		return
	}
	nick := ""
	if body.Nick != nil {
		nick = *body.Nick
	}
	uid := botOf(r).user.ID
	if ans := s.nexaGuild(r, s.handleMemberPatch, http.MethodPatch, srv, map[string]string{"uid": uid.String()}, nil, map[string]any{"nick": nick}); !ans.ok() {
		writeBotError(w, ans.err(), dErrUnknownMember)
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"nick": body.Nick})
}

func (s *Server) botMemberKick(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, 0)
	if srv == nil {
		return
	}
	m := s.botMember(w, r, srv)
	if m == nil {
		return
	}
	q := url.Values{}
	if reason := auditReason(r); reason != "" {
		q.Set("reason", reason)
	}
	if ans := s.nexaGuild(r, s.handleMemberKick, http.MethodDelete, srv, map[string]string{"uid": m.UserID.String()}, q, nil); !ans.ok() {
		writeBotError(w, ans.err(), dErrUnknownMember)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) botMemberRole(add bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		srv := s.botGuild(w, r, 0)
		if srv == nil {
			return
		}
		m := s.botMember(w, r, srv)
		if m == nil {
			return
		}
		rid, ok := pathID(r, "role")
		if !ok {
			writeBotError(w, dErrUnknownRole, nil)
			return
		}
		role, err := s.Store.RoleByID(r.Context(), rid)
		if err != nil || role.ServerID != srv.ID {
			writeBotError(w, dErrUnknownRole, nil)
			return
		}
		method := http.MethodPut
		if !add {
			method = http.MethodDelete
		}
		if ans := s.nexaGuild(r, s.handleMemberRole(add), method, srv, map[string]string{"uid": m.UserID.String(), "rid": rid.String()}, nil, nil); !ans.ok() {
			writeBotError(w, ans.err(), dErrUnknownRole)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// --- bans -------------------------------------------------------------------------------------------

func (s *Server) botBans(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, perms.BanMembers)
	if srv == nil {
		return
	}
	list, err := s.Store.Bans(r.Context(), srv.ID)
	if err != nil {
		writeBotError(w, err, nil)
		return
	}
	sort.Slice(list, func(i, j int) bool { return list[i].UserID < list[j].UserID })
	q := r.URL.Query()
	limit := 1000
	if v := q.Get("limit"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 || n > 1000 {
			writeBotError(w, dFormError("NUMBER_TYPE_MAX", "int value should be between 1 and 1000.", "limit"), nil)
			return
		}
		limit = n
	}
	var before, after int64
	if v := q.Get("before"); v != "" {
		before, _ = botproto.Parse(v)
	}
	if v := q.Get("after"); v != "" {
		after, _ = botproto.Parse(v)
	}
	b := s.builderFor(r, botOf(r))
	out := []dBan{}
	if before != 0 && q.Get("after") == "" {
		for i := len(list) - 1; i >= 0 && len(out) < limit; i-- {
			if int64(list[i].UserID) < before {
				out = append(out, b.banObj(list[i]))
			}
		}
		for i, j := 0, len(out)-1; i < j; i, j = i+1, j-1 {
			out[i], out[j] = out[j], out[i]
		}
	} else {
		for _, ban := range list {
			if int64(ban.UserID) <= after {
				continue
			}
			out = append(out, b.banObj(ban))
			if len(out) >= limit {
				break
			}
		}
	}
	WriteJSON(w, http.StatusOK, out)
}

func (s *Server) botBanGet(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, perms.BanMembers)
	if srv == nil {
		return
	}
	uid, ok := pathID(r, "user")
	if !ok {
		writeBotError(w, dErrUnknownBan, nil)
		return
	}
	list, err := s.Store.Bans(r.Context(), srv.ID)
	if err != nil {
		writeBotError(w, err, nil)
		return
	}
	for _, ban := range list {
		if ban.UserID == uid {
			WriteJSON(w, http.StatusOK, s.builderFor(r, botOf(r)).banObj(ban))
			return
		}
	}
	writeBotError(w, dErrUnknownBan, nil)
}

// deleteDays reads how much of someone's history a ban takes with it, in
// days as the app counts it.
func deleteDays(raw map[string]any) int {
	rd := jsonReader{&formErrors{}}
	if v, ok := rd.integer(raw, "delete_message_seconds", nil); ok && v > 0 {
		return int((v + 86399) / 86400)
	}
	if v, ok := rd.integer(raw, "delete_message_days", nil); ok && v > 0 {
		return int(v)
	}
	return 0
}

func (s *Server) botBanPut(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, perms.BanMembers)
	if srv == nil {
		return
	}
	uid, ok := pathID(r, "user")
	if !ok {
		writeBotError(w, dErrUnknownUser, nil)
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
	days := deleteDays(raw)
	if days > 7 {
		days = 7
	}
	body := map[string]any{"reason": auditReason(r), "delete_message_days": days}
	if ans := s.nexaGuild(r, s.handleBanPut, http.MethodPut, srv, map[string]string{"uid": uid.String()}, nil, body); !ans.ok() {
		writeBotError(w, ans.err(), dErrUnknownUser)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) botBanDelete(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, perms.BanMembers)
	if srv == nil {
		return
	}
	uid, ok := pathID(r, "user")
	if !ok {
		writeBotError(w, dErrUnknownBan, nil)
		return
	}
	if banned, _ := s.Store.IsBanned(r.Context(), srv.ID, uid); !banned {
		writeBotError(w, dErrUnknownBan, nil)
		return
	}
	if ans := s.nexaGuild(r, s.handleBanDelete, http.MethodDelete, srv, map[string]string{"uid": uid.String()}, nil, nil); !ans.ok() {
		writeBotError(w, ans.err(), dErrUnknownBan)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) botBulkBan(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, perms.BanMembers|perms.ManageServer)
	if srv == nil {
		return
	}
	raw, derr := readBotObject(w, r, 64<<10)
	if derr != nil {
		writeBotError(w, derr, nil)
		return
	}
	errs := &formErrors{}
	rd := jsonReader{errs}
	list, _ := rd.list(raw, "user_ids", nil)
	if len(list) < 1 || len(list) > 200 {
		writeBotError(w, dFormError("BASE_TYPE_BAD_LENGTH", "Must be between 1 and 200 in length.", "user_ids"), nil)
		return
	}
	users := []ids.ID{}
	for _, v := range list {
		sv := ""
		switch x := v.(type) {
		case string:
			sv = x
		case json.Number:
			sv = x.String()
		}
		if id, err := parseBotID(sv); err == nil {
			users = append(users, id)
		}
	}
	days := deleteDays(raw)
	if days > 7 {
		days = 7
	}
	ans := s.nexaGuild(r, s.handleBulkBan, http.MethodPost, srv, nil, nil, map[string]any{"user_ids": users, "reason": auditReason(r), "delete_message_days": days})
	if !ans.ok() {
		writeBotError(w, ans.err(), nil)
		return
	}
	var res struct {
		Banned []ids.ID `json:"banned"`
		Failed []ids.ID `json:"failed"`
	}
	_ = ans.decode(&res)
	if len(res.Banned) == 0 {
		writeBotError(w, dErr(http.StatusForbidden, 500000, "Failed to ban users"), nil)
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"banned_users": botproto.Snows(res.Banned), "failed_users": botproto.Snows(res.Failed)})
}

// --- roles ------------------------------------------------------------------------------------------

func (s *Server) botRoles(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, 0)
	if srv == nil {
		return
	}
	roles, err := s.Store.Roles(r.Context(), srv.ID)
	if err != nil {
		writeBotError(w, err, nil)
		return
	}
	WriteJSON(w, http.StatusOK, s.builderFor(r, botOf(r)).rolesObj(roles))
}

func (s *Server) botRoleGet(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, 0)
	if srv == nil {
		return
	}
	role := s.botRole(w, r, srv)
	if role != nil {
		WriteJSON(w, http.StatusOK, s.builderFor(r, botOf(r)).roleObj(role))
	}
}

func (s *Server) botRole(w http.ResponseWriter, r *http.Request, srv *store.Server) *store.Role {
	rid, ok := pathID(r, "role")
	if !ok {
		writeBotError(w, dErrUnknownRole, nil)
		return nil
	}
	role, err := s.Store.RoleByID(r.Context(), rid)
	if err != nil || role.ServerID != srv.ID {
		writeBotError(w, dErrUnknownRole, nil)
		return nil
	}
	return role
}

// botRoleCounts is how many members hold each role.
func (s *Server) botRoleCounts(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, 0)
	if srv == nil {
		return
	}
	ctx := r.Context()
	counts := map[string]int{}
	roles, err := s.Store.Roles(ctx, srv.ID)
	if err != nil {
		writeBotError(w, err, nil)
		return
	}
	for _, role := range roles {
		if role.ID != srv.ID {
			counts[botproto.OutString(role.ID)] = 0
		}
	}
	var after ids.ID
	for {
		page, err := s.Store.Members(ctx, srv.ID, after, 1000, "")
		if err != nil {
			writeBotError(w, err, nil)
			return
		}
		for _, m := range page {
			for _, id := range m.RoleIDs {
				counts[botproto.OutString(id)]++
			}
			after = m.UserID
		}
		if len(page) < 1000 {
			break
		}
	}
	WriteJSON(w, http.StatusOK, counts)
}

// roleBodyFrom turns the protocol's role fields into the app's.
func roleBodyFrom(rd jsonReader, raw map[string]any) map[string]any {
	body := map[string]any{}
	if v, ok := rd.str(raw, "name", nil); ok {
		body["name"] = v
	}
	if v, present := raw["permissions"]; present && v != nil {
		if sv, ok := rd.str(raw, "permissions", nil); ok {
			if n, err := strconv.ParseUint(strings.TrimSpace(sv), 10, 64); err == nil {
				body["permissions"] = strconv.FormatUint(n&uint64(perms.All), 10)
			} else {
				rd.errs.add(fieldPath("permissions"), "NUMBER_TYPE_COERCE", "Value is not a number.")
			}
		}
	}
	if v, ok := rd.integer(raw, "color", nil); ok {
		body["color"] = v
	}
	if colors, ok := rd.object(raw, "colors", nil); ok {
		if v, ok := rd.integer(colors, "primary_color", fieldPath("colors")); ok {
			body["color"] = v
		}
		for key, dst := range map[string]string{"secondary_color": "color2", "tertiary_color": "color3"} {
			if v, present := colors[key]; present {
				if v == nil {
					body[dst] = nil
				} else if n, ok := rd.integer(colors, key, fieldPath("colors")); ok {
					body[dst] = n
				}
			}
		}
	}
	if v, ok := rd.boolean(raw, "hoist", nil); ok {
		body["hoist"] = v
	}
	if v, ok := rd.boolean(raw, "mentionable", nil); ok {
		body["mentionable"] = v
	}
	if v, present := raw["unicode_emoji"]; present {
		if v == nil {
			body["icon_emoji"] = ""
		} else if sv, ok := rd.str(raw, "unicode_emoji", nil); ok {
			body["icon_emoji"] = sv
		}
	}
	return body
}

func (s *Server) botRoleCreate(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, perms.ManageRoles)
	if srv == nil {
		return
	}
	raw, derr := readBotObject(w, r, s.Cfg.MaxAvatarBytes*3+16<<10)
	if derr != nil {
		writeBotError(w, derr, nil)
		return
	}
	errs := &formErrors{}
	rd := jsonReader{errs}
	body := roleBodyFrom(rd, raw)
	var icon *rawImage
	if v, present := raw["icon"]; present && v != nil {
		icon = s.readImageField(rd, v, "icon")
	}
	if !errs.empty() {
		writeBotError(w, errs.err(), nil)
		return
	}
	// the app makes a role from its name, permissions and colour, and takes
	// the rest as a change
	create := map[string]any{}
	for _, k := range []string{"name", "permissions", "color", "hoist", "mentionable"} {
		if v, ok := body[k]; ok {
			create[k] = v
		}
	}
	ans := s.nexaGuild(r, s.handleRoleCreate, http.MethodPost, srv, nil, nil, create)
	if !ans.ok() {
		writeBotError(w, ans.err(), dErrUnknownRole)
		return
	}
	var role store.Role
	if err := ans.decode(&role); err != nil {
		writeBotError(w, err, nil)
		return
	}
	rest := map[string]any{}
	for _, k := range []string{"color2", "color3", "icon_emoji"} {
		if v, ok := body[k]; ok {
			rest[k] = v
		}
	}
	if len(rest) > 0 {
		if ans := s.nexaGuild(r, s.handleRolePatch, http.MethodPatch, srv, map[string]string{"rid": role.ID.String()}, nil, rest); !ans.ok() {
			writeBotError(w, ans.err(), dErrUnknownRole)
			return
		}
	}
	if icon != nil && icon.data != nil {
		if ans := s.nexaGuild(r, s.handleRoleIcon, http.MethodPut, srv, map[string]string{"rid": role.ID.String()}, nil, &rawBody{data: icon.data, contentType: "application/octet-stream"}); !ans.ok() {
			writeBotError(w, ans.err(), dErrUnknownRole)
			return
		}
	}
	fresh, err := s.Store.RoleByID(r.Context(), role.ID)
	if err != nil {
		writeBotError(w, err, dErrUnknownRole)
		return
	}
	WriteJSON(w, http.StatusOK, s.builderFor(r, botOf(r)).roleObj(fresh))
}

func (s *Server) botRolePatch(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, perms.ManageRoles)
	if srv == nil {
		return
	}
	role := s.botRole(w, r, srv)
	if role == nil {
		return
	}
	raw, derr := readBotObject(w, r, s.Cfg.MaxAvatarBytes*3+16<<10)
	if derr != nil {
		writeBotError(w, derr, nil)
		return
	}
	errs := &formErrors{}
	rd := jsonReader{errs}
	body := roleBodyFrom(rd, raw)
	var icon *rawImage
	if v, present := raw["icon"]; present {
		icon = s.readImageField(rd, v, "icon")
	}
	if !errs.empty() {
		writeBotError(w, errs.err(), nil)
		return
	}
	extra := map[string]string{"rid": role.ID.String()}
	if len(body) > 0 {
		if ans := s.nexaGuild(r, s.handleRolePatch, http.MethodPatch, srv, extra, nil, body); !ans.ok() {
			writeBotError(w, ans.err(), dErrUnknownRole)
			return
		}
	}
	if icon != nil {
		var ans *nexaAnswer
		if icon.data == nil {
			ans = s.nexaGuild(r, s.handleRoleIconDelete, http.MethodDelete, srv, extra, nil, nil)
		} else {
			ans = s.nexaGuild(r, s.handleRoleIcon, http.MethodPut, srv, extra, nil, &rawBody{data: icon.data, contentType: "application/octet-stream"})
		}
		if !ans.ok() {
			writeBotError(w, ans.err(), dErrUnknownRole)
			return
		}
	}
	fresh, err := s.Store.RoleByID(r.Context(), role.ID)
	if err != nil {
		writeBotError(w, err, dErrUnknownRole)
		return
	}
	WriteJSON(w, http.StatusOK, s.builderFor(r, botOf(r)).roleObj(fresh))
}

func (s *Server) botRolePositions(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, perms.ManageRoles)
	if srv == nil {
		return
	}
	var body []struct {
		ID       snow `json:"id"`
		Position *int `json:"position"`
	}
	if err := ReadJSON(w, r, &body, 32<<10); err != nil {
		writeBotError(w, dErrBadJSON, nil)
		return
	}
	moves := []map[string]any{}
	for _, item := range body {
		if item.Position == nil {
			continue
		}
		moves = append(moves, map[string]any{"id": ids.ID(item.ID), "position": *item.Position})
	}
	if ans := s.nexaGuild(r, s.handleRolePositions, http.MethodPatch, srv, nil, nil, moves); !ans.ok() {
		writeBotError(w, ans.err(), dErrUnknownRole)
		return
	}
	s.botRoles(w, r)
}

func (s *Server) botRoleDelete(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, perms.ManageRoles)
	if srv == nil {
		return
	}
	role := s.botRole(w, r, srv)
	if role == nil {
		return
	}
	if role.ManagedBy != nil {
		writeBotError(w, dErr(http.StatusBadRequest, 50028, "Invalid Role"), nil)
		return
	}
	if ans := s.nexaGuild(r, s.handleRoleDelete, http.MethodDelete, srv, map[string]string{"rid": role.ID.String()}, nil, nil); !ans.ok() {
		writeBotError(w, ans.err(), dErrUnknownRole)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// --- prune, invites, applications, the log ----------------------------------------------------------

func (s *Server) botPruneCount(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, perms.KickMembers|perms.ManageServer)
	if srv == nil {
		return
	}
	q := r.URL.Query()
	days := atoiDefault(q.Get("days"), 7)
	if days < 1 || days > 30 {
		writeBotError(w, dFormError("NUMBER_TYPE_MAX", "int value should be between 1 and 30.", "days"), nil)
		return
	}
	roles := []string{}
	for _, part := range strings.Split(q.Get("include_roles"), ",") {
		if id, err := parseBotID(strings.TrimSpace(part)); err == nil {
			roles = append(roles, id.String())
		}
	}
	ans := s.nexaGuild(r, s.handlePrunePreview, http.MethodGet, srv, nil, url.Values{"days": {strconv.Itoa(days)}, "roles": {strings.Join(roles, ",")}}, nil)
	if !ans.ok() {
		writeBotError(w, ans.err(), nil)
		return
	}
	var res struct {
		Count int `json:"count"`
	}
	_ = ans.decode(&res)
	WriteJSON(w, http.StatusOK, map[string]any{"pruned": res.Count})
}

func (s *Server) botPrune(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, perms.KickMembers|perms.ManageServer)
	if srv == nil {
		return
	}
	raw, derr := readBotObject(w, r, 16<<10)
	if derr != nil {
		writeBotError(w, derr, nil)
		return
	}
	rd := jsonReader{&formErrors{}}
	days := 7
	if v, ok := rd.integer(raw, "days", nil); ok {
		days = int(v)
	}
	if days < 1 || days > 30 {
		writeBotError(w, dFormError("NUMBER_TYPE_MAX", "int value should be between 1 and 30.", "days"), nil)
		return
	}
	roles := []ids.ID{}
	if list, ok := rd.list(raw, "include_roles", nil); ok {
		for _, v := range list {
			if sv, ok := v.(string); ok {
				if id, err := parseBotID(sv); err == nil {
					roles = append(roles, id)
				}
			}
		}
	}
	count := true
	if v, ok := rd.boolean(raw, "compute_prune_count", nil); ok {
		count = v
	}
	ans := s.nexaGuild(r, s.handlePrune, http.MethodPost, srv, nil, nil, map[string]any{"days": days, "roles": roles, "reason": auditReason(r)})
	if !ans.ok() {
		writeBotError(w, ans.err(), nil)
		return
	}
	var res struct {
		Pruned int `json:"pruned"`
	}
	_ = ans.decode(&res)
	if !count {
		WriteJSON(w, http.StatusOK, map[string]any{"pruned": nil})
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"pruned": res.Pruned})
}

func (s *Server) botGuildInvites(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, perms.ManageServer)
	if srv == nil {
		return
	}
	list, err := s.Store.InvitesForServer(r.Context(), srv.ID)
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

// botIntegrations lists a server's applications as the protocol's
// integrations.
func (s *Server) botIntegrations(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, perms.ManageServer)
	if srv == nil {
		return
	}
	ctx := r.Context()
	installs, err := s.Store.GuildInstalls(ctx, srv.ID)
	if err != nil {
		writeBotError(w, err, nil)
		return
	}
	b := s.builderFor(r, botOf(r))
	out := []map[string]any{}
	for _, in := range installs {
		app, err := s.Store.ApplicationByID(ctx, in.ApplicationID)
		if err != nil {
			continue
		}
		e := map[string]any{"id": snow(app.ID), "name": app.Name, "type": "discord", "enabled": true, "account": map[string]any{"id": botproto.OutString(app.ID), "name": app.Name},
			"application": map[string]any{"id": snow(app.ID), "name": app.Name, "icon": b.hash(app.IconID), "description": app.Description, "bot": b.userByID(app.ID)},
			"scopes":      in.Scopes}
		if in.InstalledBy != nil {
			e["user"] = b.userByID(*in.InstalledBy)
		}
		out = append(out, e)
	}
	WriteJSON(w, http.StatusOK, out)
}

func (s *Server) botIntegrationDelete(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, perms.ManageServer)
	if srv == nil {
		return
	}
	id, ok := pathID(r, "integration")
	if !ok {
		writeBotError(w, dErrUnknownIntegrate, nil)
		return
	}
	ans := s.callNexa(r, s.handleIntegrationRemove, http.MethodDelete, map[string]string{"id": srv.ID.String(), "app": id.String()}, nil, nil)
	if !ans.ok() {
		writeBotError(w, ans.err(), dErrUnknownIntegrate)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) botAuditLog(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, perms.ViewAuditLog)
	if srv == nil {
		return
	}
	ctx := r.Context()
	q := r.URL.Query()
	limit := 50
	if v := q.Get("limit"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 || n > 100 {
			writeBotError(w, dFormError("NUMBER_TYPE_MAX", "int value should be between 1 and 100.", "limit"), nil)
			return
		}
		limit = n
	}
	var f store.AuditFilter
	if v := q.Get("user_id"); v != "" {
		if id, err := parseBotID(v); err == nil {
			f.Actor = &id
		}
	}
	if v := q.Get("action_type"); v != "" {
		n, _ := strconv.Atoi(v)
		for action, t := range auditActions {
			if t == n {
				f.Actions = append(f.Actions, action)
			}
		}
		if len(f.Actions) == 0 {
			WriteJSON(w, http.StatusOK, map[string]any{"audit_log_entries": []any{}, "users": []any{}, "integrations": []any{}, "webhooks": []any{},
				"guild_scheduled_events": []any{}, "threads": []any{}, "application_commands": []any{}, "auto_moderation_rules": []any{}})
			return
		}
	}
	var before ids.ID
	if v := q.Get("before"); v != "" {
		if n, err := botproto.Parse(v); err == nil && n > 0 {
			before = ids.ID(n)
		}
	}
	var after ids.ID
	if v := q.Get("after"); v != "" {
		if n, err := botproto.Parse(v); err == nil && n > 0 {
			after = ids.ID(n)
		}
	}
	entries, err := s.Store.AuditEntriesFor(ctx, srv.ID, f, before, 1000)
	if err != nil {
		writeBotError(w, err, nil)
		return
	}
	b := s.builderFor(r, botOf(r))
	out := []dAuditEntry{}
	people := map[ids.ID]bool{}
	webhooks := map[ids.ID]bool{}
	for _, e := range entries {
		if after != 0 && e.ID <= after {
			continue
		}
		obj, ok := b.auditEntryObj(e)
		if !ok {
			continue
		}
		out = append(out, obj)
		people[e.ActorID] = true
		if e.TargetID != nil && e.TargetType == "user" {
			people[*e.TargetID] = true
		}
		if e.TargetID != nil && e.TargetType == "webhook" {
			webhooks[*e.TargetID] = true
		}
	}
	if after != 0 {
		// after reads upwards from it, oldest first, as the protocol pages
		sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	}
	if len(out) > limit {
		out = out[:limit]
	}
	users := []dUser{}
	for _, id := range sortedIDs(people) {
		users = append(users, b.userByID(id))
	}
	hooks := []dWebhook{}
	for _, id := range sortedIDs(webhooks) {
		if wh, err := s.Store.WebhookByID(ctx, id); err == nil {
			hooks = append(hooks, s.webhookObj(ctx, wh, false))
		}
	}
	rules := []dAMRule{}
	if list, err := s.Store.AutomodRules(ctx, srv.ID); err == nil {
		for _, rule := range list {
			if obj, ok := b.automodRuleObj(rule); ok {
				rules = append(rules, obj)
			}
		}
	}
	WriteJSON(w, http.StatusOK, map[string]any{"audit_log_entries": out, "users": users, "integrations": []any{}, "webhooks": hooks,
		"guild_scheduled_events": []any{}, "threads": []any{}, "application_commands": []any{}, "auto_moderation_rules": rules})
}

func (s *Server) botGuildWebhooks(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, perms.ManageWebhooks)
	if srv == nil {
		return
	}
	ctx := r.Context()
	list, err := s.Store.WebhooksByServer(ctx, srv.ID)
	if err != nil {
		writeBotError(w, err, nil)
		return
	}
	out := make([]dWebhook, 0, len(list))
	for _, wh := range list {
		out = append(out, s.webhookObj(ctx, wh, true))
	}
	WriteJSON(w, http.StatusOK, out)
}

// botVoiceStateGet is someone's voice state in a server, or the bot's own.
func (s *Server) botVoiceStateGet(w http.ResponseWriter, r *http.Request) {
	srv := s.botGuild(w, r, 0)
	if srv == nil {
		return
	}
	uid := botOf(r).user.ID
	if v := r.PathValue("user"); v != "" {
		id, ok := pathID(r, "user")
		if !ok {
			writeBotError(w, dErrUnknownVoiceState, nil)
			return
		}
		uid = id
	}
	st, ok := s.Voice.Get(uid)
	if !ok {
		writeBotError(w, dErrUnknownVoiceState, nil)
		return
	}
	ch, err := s.Store.ChannelByID(r.Context(), st.ChannelID)
	if err != nil || ch.ServerID == nil || *ch.ServerID != srv.ID {
		writeBotError(w, dErrUnknownVoiceState, nil)
		return
	}
	if p, err := s.Store.PermsIn(r.Context(), ch, botOf(r).user.ID); err != nil || !p.Has(perms.ViewChannel) {
		writeBotError(w, dErrUnknownVoiceState, nil)
		return
	}
	b := s.builderFor(r, botOf(r))
	gid := srv.ID
	WriteJSON(w, http.StatusOK, b.voiceStateObj(&gid, &st.ChannelID, uid, st.SessionID, st.SelfMute, st.SelfDeaf, st.Video, st.Streaming, st.ServerMute, st.ServerDeaf, true))
}
