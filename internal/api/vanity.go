package api

import (
	"errors"
	"net/http"
	"regexp"
	"strings"

	"evilion.xyz/chat/internal/perms"
	"evilion.xyz/chat/internal/store"
)

// A server's vanity link is an invite whose code its managers chose
// (/invite/<code>): lowercase letters, digits and hyphens, 3 to 32 of them,
// never starting or ending with a hyphen. It never expires or runs out, is
// found in any case, lands people in the server's landing channel, and stays
// out of the invite list and Revoke All. Joins through it are join method
// "vanity".
func (s *Server) vanityRoutes(mux *http.ServeMux) {
	mux.Handle("GET /api/v1/servers/{id}/vanity-url", s.authed(s.handleVanityGet))
	mux.Handle("PATCH /api/v1/servers/{id}/vanity-url", s.stepUpUser(s.stepBuild, s.handleVanitySet))
}

var vanityCode = regexp.MustCompile(`^[a-z0-9](?:[a-z0-9-]{1,30})[a-z0-9]$`)

// reservedVanity are codes that would read as Nexa itself speaking.
var reservedVanity = map[string]bool{
	"nexa": true, "nexa-fish": true, "nexafish": true, "official": true, "admin": true, "admins": true,
	"staff": true, "mod": true, "mods": true, "moderator": true, "moderators": true, "moderation": true,
	"support": true, "help": true, "safety": true, "trust": true, "security": true, "system": true,
	"everyone": true, "here": true, "invite": true, "invites": true, "discover": true, "discovery": true,
	"api": true, "app": true, "download": true, "login": true, "register": true,
}

// cleanVanity lowercases and checks a wanted code.
func cleanVanity(raw string) (string, error) {
	code := strings.ToLower(strings.TrimSpace(raw))
	if !vanityCode.MatchString(code) {
		return "", E(http.StatusBadRequest, "invalid_vanity", "Use 3 to 32 letters, numbers or hyphens, starting and ending with a letter or number.")
	}
	if reservedVanity[code] {
		return "", E(http.StatusBadRequest, "vanity_reserved", "That link is reserved. Try another.")
	}
	return code, nil
}

func vanityView(in *store.Invite) map[string]any {
	if in == nil {
		return map[string]any{"code": nil, "uses": 0, "channel_id": nil}
	}
	return map[string]any{"code": in.Code, "uses": in.Uses, "channel_id": in.ChannelID}
}

func (s *Server) handleVanityGet(w http.ResponseWriter, r *http.Request) {
	srv, _, _ := s.serverCtx(w, r, perms.ManageServer)
	if srv == nil {
		return
	}
	in, err := s.Store.VanityInvite(r.Context(), srv.ID)
	if err != nil && !errors.Is(err, store.ErrNotFound) {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, vanityView(in))
}

func (s *Server) handleVanitySet(w http.ResponseWriter, r *http.Request) {
	srv, me, _ := s.serverCtx(w, r, perms.ManageServer)
	if srv == nil {
		return
	}
	var req struct {
		Code *string `json:"code"`
	}
	if err := ReadJSON(w, r, &req, 1<<10); err != nil {
		WriteError(w, err)
		return
	}
	if req.Code == nil || strings.TrimSpace(*req.Code) == "" {
		old, err := s.Store.ClearVanityInvite(r.Context(), srv.ID)
		if err != nil {
			WriteError(w, err)
			return
		}
		if old != "" {
			s.audit(r, srv.ID, me.ID, "vanity_url_update", "server", &srv.ID, map[string]any{"from": old, "to": nil}, "")
		}
		WriteJSON(w, http.StatusOK, vanityView(nil))
		return
	}
	code, err := cleanVanity(*req.Code)
	if err != nil {
		WriteError(w, err)
		return
	}
	target, err := s.landingChannel(r.Context(), srv)
	if err != nil {
		WriteError(w, err)
		return
	}
	if target == nil {
		WriteError(w, E(http.StatusBadRequest, "no_channel", "Add a text channel first: people who join through the link land in one."))
		return
	}
	in, old, err := s.Store.SetVanityInvite(r.Context(), srv.ID, *target, me.ID, code)
	if errors.Is(err, store.ErrVanityTaken) {
		WriteError(w, E(http.StatusConflict, "vanity_taken", "That link is already taken."))
		return
	}
	if err != nil {
		WriteError(w, err)
		return
	}
	if old != code {
		changes := map[string]any{"from": nil, "to": code}
		if old != "" {
			changes["from"] = old
		}
		s.audit(r, srv.ID, me.ID, "vanity_url_update", "server", &srv.ID, changes, "")
	}
	WriteJSON(w, http.StatusOK, vanityView(in))
}
