package api

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/store"
)

func (s *Server) adminRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /api/v1/instance", s.handleInstanceInfo)
	mux.Handle("GET /api/v1/admin/stats", s.staff(rankDeveloper, s.handleAdminStats))
	mux.Handle("GET /api/v1/admin/users", s.staff(rankDeveloper, s.handleAdminUsers))
	mux.Handle("PATCH /api/v1/admin/users/{id}", s.staff(rankMod, s.handleAdminUserPatch))
	mux.Handle("DELETE /api/v1/admin/users/{id}", s.staff(rankDeveloper, s.handleAdminUserDelete))
	mux.Handle("GET /api/v1/admin/settings", s.staff(rankDeveloper, s.handleAdminSettings))
	mux.Handle("PATCH /api/v1/admin/settings", s.staff(rankDeveloper, s.handleAdminSettingsPatch))
}

// handleInstanceInfo is public: the register page needs to know the policy.
// captchaInfo is what a browser needs to show the captcha: the provider and
// its public site key, never the secret. Nil when captchas are off. Apps from
// before the move back to Turnstile read only this field and understand only
// "hcaptcha", so it names hCaptcha while that fallback is configured;
// turnstileInfo is what current apps read first.
func (s *Server) captchaInfo() map[string]string {
	switch {
	case s.hcaptchaEnabled():
		return map[string]string{"provider": "hcaptcha", "site_key": s.Cfg.HCaptchaSiteKey}
	case s.turnstileEnabled():
		return map[string]string{"provider": "turnstile", "site_key": s.Cfg.TurnstileSiteKey}
	}
	return nil
}

// turnstileInfo is Turnstile's public site key, nil when it is not set up.
func (s *Server) turnstileInfo() map[string]string {
	if !s.turnstileEnabled() {
		return nil
	}
	return map[string]string{"site_key": s.Cfg.TurnstileSiteKey}
}

func (s *Server) handleInstanceInfo(w http.ResponseWriter, r *http.Request) {
	mode, key := s.registrationPolicy(r.Context())
	WriteJSON(w, http.StatusOK, map[string]any{
		"name":                "Nexa",
		"version":             s.Version,
		"registration":        mode,
		"needs_key":           mode == "invite" && key != "",
		"max_upload_bytes":    s.planLimitsFor(store.PlanFree).FileMax,
		"gif_search":          s.gifEnabled(),
		"public_url":          s.Cfg.PublicURL,
		"turn_configured":     len(s.Cfg.TurnURLs) > 0 || s.Cfg.TurnKeyID != "",
		"max_sticker_bytes":   s.Cfg.MaxStickerBytes,
		"upload_chunk_bytes":  MaxChunkBytes,
		"upload_single_bytes": SingleUploadBytes,
		"captcha":             s.captchaInfo(),
		"turnstile":           s.turnstileInfo(),
		"plans":               s.plansInfo(),
	})
}

func (s *Server) handleAdminStats(w http.ResponseWriter, r *http.Request) {
	st, err := s.Store.Stats(r.Context())
	if err != nil {
		WriteError(w, err)
		return
	}
	online := 0
	if h, ok := s.Bcast.(interface{ OnlineUsers() []ids.ID }); ok {
		online = len(h.OnlineUsers())
	}
	var calls any
	if s.Meter != nil {
		calls = s.Meter.Status(r.Context())
	}
	WriteJSON(w, http.StatusOK, map[string]any{"users": st.Users, "servers": st.Servers, "messages": st.Messages, "blobs": st.Blobs, "blob_bytes": st.BlobBytes, "online": online, "version": s.Version, "realtime": calls, "storage": s.storageStatus(r.Context())})
}

func (s *Server) handleAdminUsers(w http.ResponseWriter, r *http.Request) {
	before, _ := ids.Parse(r.URL.Query().Get("before"))
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	list, err := s.Store.AdminUsers(r.Context(), r.URL.Query().Get("q"), before, limit)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, list)
}

// handleAdminUserPatch changes an account's rank and whether it can sign in.
// Only the owner gives or takes ranks, and only the owner disables the sign-in
// of staff; disabling anyone else's takes a Developer.
func (s *Server) handleAdminUserPatch(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	me := auth.UserFrom(ctx)
	id, err := ids.Parse(r.PathValue("id"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	u, err := s.Store.UserByID(ctx, id)
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	var req struct {
		Disabled *bool `json:"disabled"`
		// Rank is "developer", "admin", "mod" or "" for none.
		Rank *string `json:"rank"`
		// Admin is what apps before ranks send: true for Developer, false for none.
		Admin  *bool  `json:"admin"`
		Reason string `json:"reason"`
	}
	if err := ReadJSON(w, r, &req, 4<<10); err != nil {
		WriteError(w, err)
		return
	}
	if req.Rank == nil && req.Admin != nil {
		name := ""
		if *req.Admin {
			name = rankNames[rankDeveloper]
		}
		req.Rank = &name
	}
	var want staffRank
	if req.Rank != nil {
		found := false
		for r, name := range rankNames {
			if name == *req.Rank && r != rankOwner {
				want, found = r, true
			}
		}
		if !found {
			WriteError(w, E(http.StatusBadRequest, "invalid_value", "rank must be developer, admin, mod or empty."))
			return
		}
	}
	if id == me.ID && ((req.Disabled != nil && *req.Disabled) || req.Rank != nil) {
		WriteError(w, E(http.StatusBadRequest, "self", "You cannot disable yourself or change your own rank."))
		return
	}
	if notAPerson(u) {
		WriteError(w, blockError(blockSystem))
		return
	}
	// Nobody can take the owner's access away: without this, the owner could
	// be demoted and then acted on like anyone else.
	owner, err := s.Store.InstanceOwnerID(ctx)
	if err != nil {
		WriteError(w, err)
		return
	}
	if owner == id && id != me.ID {
		WriteError(w, E(http.StatusForbidden, "owner_protected", "The owner's access cannot be changed."))
		return
	}
	mine := s.rankOf(ctx, me)
	if req.Rank != nil && mine < rankOwner {
		WriteError(w, staffRankError(rankOwner))
		return
	}
	if req.Disabled != nil {
		need := rankDeveloper
		if u.Flags&store.StaffFlags != 0 {
			need = rankOwner
		}
		if mine < need {
			WriteError(w, staffRankError(need))
			return
		}
	}
	reason, ok := CleanText(req.Reason, maxModReason)
	if !ok {
		WriteError(w, E(http.StatusBadRequest, "reason_too_long", "Reasons are at most 1000 characters."))
		return
	}
	// Only the bits this request names are touched, so someone changing a
	// different bit at the same moment does not have their change undone.
	var set, clear int64
	if req.Disabled != nil {
		if *req.Disabled {
			set |= store.FlagDisabled
		} else {
			clear |= store.FlagDisabled
		}
	}
	if req.Rank != nil {
		clear |= store.StaffFlags
		set |= rankBits[want]
		clear &^= set
	}
	// Developers see every private channel of the servers they are in, so
	// gaining or losing that changes their view (and who holds the keys)
	isDev := func(f int64) bool { return f&store.FlagAdmin != 0 && f&(store.FlagDisabled|store.FlagDeleted) == 0 }
	var servers []*store.Server
	var before []visibility
	if isDev(u.Flags) != isDev((u.Flags|set)&^clear) {
		// The snapshots come first: taken after the write they would show the
		// new view as though it had always been there, and a failed one would
		// make every channel look newly visible to every member.
		if servers, err = s.Store.ServersForUser(ctx, id); err != nil {
			WriteError(w, err)
			return
		}
		for _, srv := range servers {
			vis, err := s.snapshotVisibility(ctx, srv.ID)
			if err != nil {
				WriteError(w, err)
				return
			}
			before = append(before, vis)
		}
	}
	flags, err := s.Store.ChangeUserFlags(ctx, id, set, clear)
	if err != nil {
		WriteError(w, err)
		return
	}
	// what actually changed goes in the log, one line per change
	var entries []*store.ModLogEntry
	if was, now := rankFromFlags(u.Flags, false), rankFromFlags(flags, false); was != now {
		entries = append(entries, &store.ModLogEntry{Action: store.LogRankChange, Details: mustJSON(map[string]string{"from": rankNames[was], "to": rankNames[now]})})
	}
	if was, now := u.Flags&store.FlagDisabled != 0, flags&store.FlagDisabled != 0; was != now {
		action := store.LogAccountEnable
		if now {
			action = store.LogAccountDisable
		}
		entries = append(entries, &store.ModLogEntry{Action: action})
	}
	for _, entry := range entries {
		entry.ModeratorID, entry.ModeratorUsername, entry.TargetID, entry.TargetUsername, entry.Reason = me.ID, me.Username, &u.ID, u.Username, reason
		if err := s.Store.AppendModLog(ctx, entry); err != nil {
			s.Log.Warn("moderation: log access change", "user", id.String(), "err", err)
			continue
		}
		s.emitLog(ctx, entry)
	}
	for i, srv := range servers {
		s.syncVisibility(ctx, srv.ID, before[i])
	}
	if flags != u.Flags {
		// everyone who sees them sees the new rank on their profile
		if updated, err := s.Store.UserByID(ctx, id); err == nil {
			s.Bcast.UserUpdated(updated)
		}
	}
	if req.Disabled != nil && *req.Disabled {
		// out of any call first: closing its sockets only detaches a call,
		// which would go on carrying its voice for the grace period
		s.dropVoice(ctx, id, func(*store.Channel) bool { return true })
		// a disabled account loses every session immediately
		if err := s.Store.DeleteUserSessions(ctx, id, 0); err != nil {
			// the account is already disabled — the middleware re-checks the
			// flag per request — but the rows should have gone with it
			s.Log.Warn("disable: session sweep", "user", id.String(), "err", err)
		}
		s.Bcast.EmitUsers([]ids.ID{id}, "session_expired", map[string]any{"reason": "disabled"})
		s.Bcast.CloseUserSessions(id, 0)
	}
	WriteJSON(w, http.StatusOK, store.AdminUser{ID: u.ID, Username: u.Username, DisplayName: u.DisplayName, Flags: flags, CreatedAt: u.CreatedAt, Admin: flags&store.FlagAdmin != 0, Disabled: flags&store.FlagDisabled != 0})
}

// handleAdminUserDelete deletes an account the way the moderation panel's
// Delete does, without the case: everything it made goes with it, everywhere,
// including the servers and group chats it owns. Its one-to-one conversations
// stay, for the people on the other side of them.
func (s *Server) handleAdminUserDelete(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	ctx := r.Context()
	id, err := ids.Parse(r.PathValue("id"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	u, err := s.Store.UserByID(ctx, id)
	if err != nil || u.Flags&store.FlagDeleted != 0 {
		WriteError(w, ErrNotFound)
		return
	}
	if id == me.ID {
		WriteError(w, E(http.StatusBadRequest, "self", "Delete your own account from your settings instead."))
		return
	}
	if notAPerson(u) {
		WriteError(w, blockError(blockSystem))
		return
	}
	if u.Flags&store.StaffFlags != 0 {
		WriteError(w, E(http.StatusBadRequest, "is_admin", "Take away their rank before deleting their account."))
		return
	}
	counts, err := s.Store.PurgePreview(ctx, id)
	if err != nil {
		WriteError(w, err)
		return
	}
	entry := &store.ModLogEntry{Action: store.ActionDelete, ModeratorID: me.ID, ModeratorUsername: me.Username, TargetID: &u.ID, TargetUsername: u.Username,
		Details: mustJSON(map[string]any{"counts": counts})}
	if err := s.Store.AppendModLog(ctx, entry); err != nil {
		WriteError(w, err)
		return
	}
	s.emitLog(ctx, entry)
	// Signing in stops now, and any tab still open for them signs out, so
	// nothing new is added while the rest goes.
	if _, err := s.Store.ChangeUserFlags(ctx, id, store.FlagDisabled|store.FlagDeleted, 0); err != nil {
		WriteError(w, err)
		return
	}
	if err := s.Store.DeleteUserSessions(ctx, id, 0); err != nil {
		s.Log.Warn("admin: delete sessions", "user", id.String(), "err", err)
	}
	s.Bcast.EmitUsers([]ids.ID{id}, "session_expired", map[string]any{"reason": "deleted"})
	s.Bcast.CloseUserSessions(id, 0)
	pctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), purgeTimeout)
	defer cancel()
	s.endSubscription(pctx, id)
	if err := s.purgeAccount(r.WithContext(pctx), id, purgeOptions{}); err != nil {
		s.Log.Error("admin: delete account", "user", id.String(), "err", err)
		WriteError(w, E(http.StatusInternalServerError, "delete_incomplete", "The account is closed, but not everything is gone yet. The rest goes by itself shortly."))
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) adminSettingsView(r *http.Request) map[string]any {
	mode, key := s.registrationPolicy(r.Context())
	overrides, _ := s.Store.InstanceSettings(r.Context())
	var budget any
	if cents, set, err := s.storageLimit(r.Context()); err == nil && set {
		budget = cents
	}
	return map[string]any{
		"storage_remote":       s.bucketOn(),
		"storage_budget_cents": budget,
		"registration":         mode,
		"registration_key_set": key != "",
		"signup_ip_limit":      s.signupIPLimitOn(r.Context()),
		"config_registration":  s.Cfg.Registration,
		"overrides":            overrides,
		"max_upload_bytes":     s.Cfg.MaxUploadBytes,
		"max_emoji_bytes":      s.Cfg.MaxEmojiBytes,
		"max_sticker_bytes":    s.Cfg.MaxStickerBytes,
		"max_avatar_bytes":     s.Cfg.MaxAvatarBytes,
		"gif_search":           s.gifEnabled(),
		"turn":                 len(s.Cfg.TurnURLs) > 0,
	}
}

func (s *Server) handleAdminSettings(w http.ResponseWriter, r *http.Request) {
	WriteJSON(w, http.StatusOK, s.adminSettingsView(r))
}

func (s *Server) handleAdminSettingsPatch(w http.ResponseWriter, r *http.Request) {
	var raw map[string]json.RawMessage
	if err := ReadJSON(w, r, &raw, 4<<10); err != nil {
		WriteError(w, err)
		return
	}
	// Every field is checked before any of them is written. Validating and
	// writing one at a time meant a request rejected for its second field had
	// already applied its first: an admin who read "registration_key is too
	// long" and concluded nothing had changed had in fact closed registration.
	var pending [][2]string
	if v, ok := raw["registration"]; ok {
		var mode string
		if json.Unmarshal(v, &mode) != nil || !OneOf(mode, "open", "invite", "closed", "") {
			WriteError(w, E(http.StatusBadRequest, "invalid_value", "registration must be open, invite or closed."))
			return
		}
		pending = append(pending, [2]string{"registration", mode})
	}
	if v, ok := raw["registration_key"]; ok {
		var key string
		if string(v) != "null" && json.Unmarshal(v, &key) != nil {
			WriteError(w, E(http.StatusBadRequest, "invalid_value", "registration_key must be a string."))
			return
		}
		key = strings.TrimSpace(key)
		if len(key) > 128 {
			WriteError(w, E(http.StatusBadRequest, "invalid_value", "registration_key is too long."))
			return
		}
		pending = append(pending, [2]string{"registration_key", key})
	}
	if v, ok := raw["signup_ip_limit"]; ok {
		var on bool
		if json.Unmarshal(v, &on) != nil {
			WriteError(w, E(http.StatusBadRequest, "invalid_value", "signup_ip_limit must be true or false."))
			return
		}
		// on is the default, so turning it back on clears the override
		value := ""
		if !on {
			value = "off"
		}
		pending = append(pending, [2]string{"signup_ip_limit", value})
	}
	if v, ok := raw["storage_budget"]; ok {
		// cents a month; null takes the limit away
		value := ""
		if string(v) != "null" {
			var cents int64
			if json.Unmarshal(v, &cents) != nil || cents < 0 || cents > maxStorageBudget {
				WriteError(w, E(http.StatusBadRequest, "invalid_value", "storage_budget must be a whole number of cents from 0 to 10000000, or null."))
				return
			}
			value = strconv.FormatInt(cents, 10)
		}
		pending = append(pending, [2]string{"storage_budget", value})
	}
	if err := s.Store.SetInstanceSettings(r.Context(), pending); err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, s.adminSettingsView(r))
}
