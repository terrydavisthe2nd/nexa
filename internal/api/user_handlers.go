package api

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"reflect"
	"strings"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/store"
)

const (
	// About me is shown on every profile card and travels with every profile
	// change to everyone who shares a server with the person, so it stays
	// short enough that opening a profile is never slow.
	maxAboutMe  = 1000
	maxPronouns = 40
	maxStatus   = 128
	maxSettings = 1 << 20
)

func (s *Server) userRoutes(mux *http.ServeMux) {
	mux.Handle("GET /api/v1/users/@me", s.authed(s.handleMe))
	mux.Handle("PATCH /api/v1/users/@me", s.authed(s.handleMePatch))
	mux.Handle("POST /api/v1/users/@me/delete", s.authed(s.handleMeDelete))
	mux.Handle("GET /api/v1/users/@me/delete-preview", s.authed(s.handleMeDeletePreview))
	mux.Handle("GET /api/v1/users/@me/settings", s.authed(s.handleSettingsGet))
	mux.Handle("PUT /api/v1/users/@me/settings", s.authed(s.handleSettingsPut))
	mux.Handle("GET /api/v1/users/@me/keys", s.authed(s.handleMyKeys))
	mux.Handle("GET /api/v1/users/search", s.userLimited(s.limSearch, s.handleUserSearch))
	mux.Handle("GET /api/v1/users/{id}", s.authed(s.handleUserGet))
	mux.Handle("GET /api/v1/users/{id}/profile", s.authed(s.handleUserProfile))
	mux.Handle("GET /api/v1/users/{id}/public-key", s.authed(s.handlePublicKey))
}

func (s *Server) handleMe(w http.ResponseWriter, r *http.Request) {
	WriteJSON(w, http.StatusOK, s.meView(r.Context(), auth.UserFrom(r.Context())))
}

// parseUserID resolves "@me" or a snowflake from the path.
func parseUserID(r *http.Request) (ids.ID, bool) {
	raw := r.PathValue("id")
	if raw == "@me" {
		return auth.UserFrom(r.Context()).ID, true
	}
	id, err := ids.Parse(raw)
	return id, err == nil
}

func (s *Server) handleMePatch(w http.ResponseWriter, r *http.Request) {
	u := auth.UserFrom(r.Context())
	// Every change goes out to everyone who shares a server with the person.
	if ok, retry := s.limProfile.Allow("profile:" + u.ID.String()); !ok {
		w.Header().Set("Retry-After", retryAfter(retry))
		WriteError(w, ErrRateLimited)
		return
	}
	var raw map[string]json.RawMessage
	if err := ReadJSON(w, r, &raw, 512<<10); err != nil {
		WriteError(w, err)
		return
	}
	var p store.ProfilePatch
	bad := func(code, msg string) { WriteError(w, E(http.StatusBadRequest, code, msg)) }
	// A value of the wrong type is its own answer, not the same as null. They
	// used to be one: for the fields where null means "clear it", a
	// {"about_me": 42} from a mis-typed client silently emptied the person's
	// bio and returned 200.
	badType := ""
	getString := func(key string) (string, bool, bool) { // value, present, isNull
		v, ok := raw[key]
		if !ok {
			return "", false, false
		}
		if string(v) == "null" {
			return "", true, true
		}
		var s string
		if err := json.Unmarshal(v, &s); err != nil {
			if badType == "" {
				badType = key
			}
			return "", true, true
		}
		return s, true, false
	}
	if v, present, null := getString("username"); present {
		v = strings.ToLower(strings.TrimSpace(v))
		if null || !ValidUsername(v) {
			bad("invalid_username", "Usernames are 2–32 characters of a-z, 0-9, _ and . (no leading, trailing or double dots).")
			return
		}
		if v != u.Username && reservedDisplayName(v) && !isSystem(u) {
			bad("reserved_username", "That username belongs to the official Nexa account.")
			return
		}
		// a new name is a captcha away, so nobody can cycle through names or
		// snap up a stream of them with a script
		if v != u.Username && !s.requireCaptcha(w, r, "username") {
			return
		}
		p.Username = &v
	}
	if v, present, null := getString("display_name"); present {
		clean, ok := CleanDisplayName(v)
		if null || !ok {
			bad("invalid_display_name", "Display names are 1–32 characters.")
			return
		}
		if reservedDisplayName(clean) && !isSystem(u) {
			bad("reserved_display_name", "That name belongs to the official Nexa account.")
			return
		}
		p.DisplayName = &clean
	}
	if v, present, null := getString("about_me"); present {
		if null {
			v = ""
		}
		clean, ok := CleanText(v, maxAboutMe)
		if !ok {
			bad("invalid_about_me", "About me is too long.")
			return
		}
		p.AboutMe = &clean
	}
	if v, present, null := getString("pronouns"); present {
		if null {
			v = ""
		}
		clean, ok := CleanText(strings.ReplaceAll(v, "\n", " "), maxPronouns)
		if !ok {
			bad("invalid_pronouns", "Pronouns are at most 40 characters.")
			return
		}
		p.Pronouns = &clean
	}
	if v, present, null := getString("status_text"); present {
		if null {
			v = ""
		}
		clean, ok := CleanText(strings.ReplaceAll(v, "\n", " "), maxStatus)
		if !ok {
			bad("invalid_status", "Status text is at most 128 characters.")
			return
		}
		p.StatusText = &clean
	}
	if v, present, null := getString("status_emoji"); present {
		if null {
			v = ""
		}
		clean, ok := CleanText(v, 64)
		if !ok || strings.ContainsAny(clean, " \n") {
			bad("invalid_status", "Status emoji is malformed.")
			return
		}
		p.StatusEmoji = &clean
	}
	if badType != "" {
		bad("invalid_"+badType, "The value for "+badType+" must be a string or null.")
		return
	}
	if v, ok := raw["status_expires_at"]; ok {
		if string(v) == "null" {
			var nilP *int64
			p.StatusExpiresAt = &nilP
		} else {
			var n int64
			if err := json.Unmarshal(v, &n); err != nil || n < 0 {
				bad("invalid_status", "status_expires_at must be a timestamp or null.")
				return
			}
			np := &n
			p.StatusExpiresAt = &np
		}
	}
	if v, ok := raw["accent_color"]; ok {
		if string(v) == "null" {
			var nilP *int
			p.AccentColor = &nilP
		} else {
			var n int
			if err := json.Unmarshal(v, &n); err != nil || n < 0 || n > 0xFFFFFF {
				bad("invalid_color", "accent_color must be 0–16777215 or null.")
				return
			}
			np := &n
			p.AccentColor = &np
		}
	}
	if v, ok := raw["accent_color_2"]; ok {
		if string(v) == "null" {
			var nilP *int
			p.AccentColor2 = &nilP
		} else {
			var n int
			if err := json.Unmarshal(v, &n); err != nil || n < 0 || n > 0xFFFFFF {
				bad("invalid_color", "accent_color_2 must be 0–16777215 or null.")
				return
			}
			np := &n
			p.AccentColor2 = &np
		}
	}
	if v, present, null := getString("presence_pref"); present {
		if null || !OneOf(v, "online", "idle", "dnd", "invisible") {
			bad("invalid_presence", "presence_pref must be online, idle, dnd or invisible.")
			return
		}
		p.PresencePref = &v
	}
	if v, present, null := getString("dm_privacy"); present {
		if null || !OneOf(v, store.DMEveryone, store.DMFriendsServers, store.DMFriends, store.DMNobody) {
			bad("invalid_dm_privacy", "dm_privacy must be everyone, friends_servers, friends or nobody.")
			return
		}
		p.DMPrivacy = &v
	}
	if v, ok := raw["typing_style"]; ok {
		style, problem := cleanTypingStyle(v)
		if problem != "" {
			bad("invalid_typing_style", problem)
			return
		}
		p.TypingStyle = &style
	}
	if v, ok := raw["name_style"]; ok {
		style, problem := cleanNameStyle(v)
		if problem != "" {
			bad("invalid_name_style", problem)
			return
		}
		// a style already worn may stay after a plan ends; a new one may not
		if store.PaidNameEffects[nameStyleEffect(style)] && !s.planLimitsFor(u.Plan).NameEffects && style != u.NameStyle {
			WriteError(w, E(http.StatusForbidden, "plan_required", "That effect comes with Nexa Supporter."))
			return
		}
		p.NameStyle = &style
	}
	if v, ok := raw["group_link_only"]; ok {
		var b bool
		if err := json.Unmarshal(v, &b); err != nil || string(v) == "null" {
			bad("invalid_group_link_only", "group_link_only must be true or false.")
			return
		}
		p.GroupLinkOnly = &b
	}
	// Muted, someone can still set their presence, who may message them and
	// who may add them to groups, and take things off their profile, but not
	// put anything new on it.
	filled := func(v *string) bool { return v != nil && *v != "" }
	public := p.Username != nil || p.DisplayName != nil || p.AccentColor != nil || p.AccentColor2 != nil ||
		filled(p.AboutMe) || filled(p.Pronouns) || filled(p.StatusText) || filled(p.StatusEmoji) || filled(p.TypingStyle) || filled(p.NameStyle)
	if public && mutedFor(w, r) {
		return
	}
	if err := s.Store.UpdateProfile(r.Context(), u.ID, p); err != nil {
		if errors.Is(err, store.ErrTypingImageGone) {
			bad("invalid_typing_style", "One of your typing pictures has gone. Put it up again.")
			return
		}
		WriteError(w, err)
		return
	}
	updated, err := s.Store.UserByID(r.Context(), u.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	// A save that changed nothing anyone else sees goes nowhere.
	if !reflect.DeepEqual(u.Public(), updated.Public()) || u.PresencePref != updated.PresencePref {
		s.Bcast.UserUpdated(updated)
	}
	WriteJSON(w, http.StatusOK, s.meView(r.Context(), updated))
}

func (s *Server) handleMeDelete(w http.ResponseWriter, r *http.Request) {
	u := auth.UserFrom(r.Context())
	var req struct {
		AuthKey []byte `json:"auth_key"`
	}
	if err := ReadJSON(w, r, &req, 4<<10); err != nil {
		WriteError(w, err)
		return
	}
	if !s.requireCaptcha(w, r, "delete_account") {
		return
	}
	if err := s.requirePassword(w, u, req.AuthKey, "The password is wrong."); err != nil {
		WriteError(w, err)
		return
	}
	ctx := r.Context()
	// From here the account is deleted: nobody can sign in as it and its open
	// tabs close, so nothing new is added while the rest goes. A wipe that
	// stops part way is finished by the sweep (finishDeletedAccounts).
	if _, err := s.Store.ChangeUserFlags(ctx, u.ID, store.FlagDisabled|store.FlagDeleted, 0); err != nil {
		WriteError(w, err)
		return
	}
	if err := s.Store.DeleteUserSessions(ctx, u.ID, 0); err != nil {
		s.Log.Warn("delete account: sessions", "user", u.ID.String(), "err", err)
	}
	s.Bcast.CloseUserSessions(u.ID, 0)
	// Everything it made goes, and its one-to-one conversations with it, for
	// both people in them. It runs on a context of its own, so closing the tab
	// does not stop it half way.
	pctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), purgeTimeout)
	defer cancel()
	// a closed account is not charged again
	s.endSubscription(pctx, u.ID)
	if err := s.purgeAccount(r.WithContext(pctx), u.ID, purgeOptions{DMs: true}); err != nil {
		s.Log.Error("delete account", "user", u.ID.String(), "err", err)
		WriteError(w, E(http.StatusInternalServerError, "delete_incomplete", "Your account is closed, but not everything is gone yet. The rest goes by itself shortly."))
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// handleMeDeletePreview says what deleting the account would take with it,
// for the confirmation that asks.
func (s *Server) handleMeDeletePreview(w http.ResponseWriter, r *http.Request) {
	counts, err := s.Store.PurgePreview(r.Context(), auth.UserFrom(r.Context()).ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, counts)
}

func (s *Server) handleSettingsGet(w http.ResponseWriter, r *http.Request) {
	st, err := s.Store.GetSettings(r.Context(), auth.UserFrom(r.Context()).ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	if st == nil {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	WriteJSON(w, http.StatusOK, st)
}

func (s *Server) handleSettingsPut(w http.ResponseWriter, r *http.Request) {
	u := auth.UserFrom(r.Context())
	var req struct {
		Blob    []byte `json:"blob"`
		Version int64  `json:"version"`
	}
	if err := ReadJSON(w, r, &req, maxSettings+4096); err != nil {
		WriteError(w, err)
		return
	}
	if len(req.Blob) == 0 || len(req.Blob) > maxSettings || req.Version < 0 {
		WriteError(w, E(http.StatusBadRequest, "invalid_settings", "Settings blob must be 1 byte to 1 MiB."))
		return
	}
	v, err := s.Store.PutSettings(r.Context(), u.ID, req.Blob, req.Version)
	if err != nil {
		if errors.Is(err, store.ErrConflict) {
			WriteError(w, E(http.StatusConflict, "settings_conflict", "Settings were changed on another device; reload them first."))
			return
		}
		WriteError(w, err)
		return
	}
	s.Bcast.EmitUsers([]ids.ID{u.ID}, "settings_update", map[string]any{"version": v, "session_id": auth.SessionFrom(r.Context()).ID})
	WriteJSON(w, http.StatusOK, map[string]any{"version": v})
}

func (s *Server) handleMyKeys(w http.ResponseWriter, r *http.Request) {
	WriteJSON(w, http.StatusOK, keyBundle(auth.UserFrom(r.Context())))
}

func (s *Server) handleUserSearch(w http.ResponseWriter, r *http.Request) {
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	if len(q) < 2 {
		WriteJSON(w, http.StatusOK, []store.PublicUser{})
		return
	}
	users, err := s.Store.SearchUsers(r.Context(), q, 10)
	if err != nil {
		WriteError(w, err)
		return
	}
	out := make([]store.PublicUser, 0, len(users))
	for _, u := range users {
		out = append(out, u.Public())
	}
	WriteJSON(w, http.StatusOK, out)
}

func (s *Server) handleUserGet(w http.ResponseWriter, r *http.Request) {
	id, ok := parseUserID(r)
	if !ok {
		WriteError(w, ErrNotFound)
		return
	}
	u, err := s.Store.UserByID(r.Context(), id)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, u.Public())
}

// ProfileExtras is filled by the relationship layer (mutual friends) and the
// server layer (mutual servers). Nil functions yield empty lists.
type ProfileExtras struct {
	MutualFriends func(r *http.Request, me, other ids.ID) ([]ids.ID, error)
	MutualServers func(r *http.Request, me, other ids.ID) ([]ids.ID, error)
}

func (s *Server) handleUserProfile(w http.ResponseWriter, r *http.Request) {
	id, ok := parseUserID(r)
	if !ok {
		WriteError(w, ErrNotFound)
		return
	}
	me := auth.UserFrom(r.Context())
	u, err := s.Store.UserByID(r.Context(), id)
	if err != nil {
		WriteError(w, err)
		return
	}
	friends, servers := []ids.ID{}, []ids.ID{}
	if s.Profile.MutualFriends != nil && id != me.ID {
		if friends, err = s.Profile.MutualFriends(r, me.ID, id); err != nil {
			WriteError(w, err)
			return
		}
	}
	if s.Profile.MutualServers != nil && id != me.ID {
		if servers, err = s.Profile.MutualServers(r, me.ID, id); err != nil {
			WriteError(w, err)
			return
		}
	}
	if friends == nil {
		friends = []ids.ID{}
	}
	if servers == nil {
		servers = []ids.ID{}
	}
	// the profile's widgets come with the whole profile, not with every mention of the person
	widgets, err := s.Store.UserWidgets(r.Context(), u.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	// opened in a server both are in, the ones they chose for it, if they did
	own := false
	if sid, err := ids.Parse(r.URL.Query().Get("server_id")); err == nil && r.URL.Query().Get("server_id") != "" {
		if in, err := s.Store.IsMember(r.Context(), sid, me.ID); err == nil && in {
			if there, err := s.Store.MemberWidgets(r.Context(), sid, u.ID); err == nil && there != nil {
				widgets, own = *there, true
			}
		}
	}
	WriteJSON(w, http.StatusOK, map[string]any{"user": u.Public(), "mutual_friends": friends, "mutual_servers": servers, "widgets": widgetsJSON(widgets), "server_widgets": own, "badges": s.profileBadges(r, u)})
}

func (s *Server) handlePublicKey(w http.ResponseWriter, r *http.Request) {
	id, ok := parseUserID(r)
	if !ok {
		WriteError(w, ErrNotFound)
		return
	}
	u, err := s.Store.UserByID(r.Context(), id)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"user_id": u.ID, "public_key": u.PublicKey})
}
