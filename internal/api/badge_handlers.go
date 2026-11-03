package api

import (
	"net/http"
	"strings"
	"unicode/utf8"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/store"
	"evilion.xyz/chat/internal/textutil"
)

// Profile badges (internal/store/badges.go): a person's own list with the
// order and hidden set they chose, and what staff do: make event badges and
// give them, and Contributor, by hand. Everyone else sees badges in a
// profile's answer (handleUserProfile, handleSharedProfile).
func (s *Server) badgeRoutes(mux *http.ServeMux) {
	mux.Handle("GET /api/v1/users/@me/badges", s.authed(s.handleMyBadges))
	mux.Handle("PUT /api/v1/users/@me/badges", s.authed(s.handleMyBadgesPut))
	mux.Handle("GET /api/v1/moderation/badges", s.staff(rankAdmin, s.handleModBadges))
	mux.Handle("POST /api/v1/moderation/badges", s.staff(rankAdmin, s.handleModBadgeCreate))
	mux.Handle("PATCH /api/v1/moderation/badges/{id}", s.staff(rankAdmin, s.handleModBadgeEdit))
	mux.Handle("DELETE /api/v1/moderation/badges/{id}", s.staff(rankAdmin, s.handleModBadgeDelete))
	mux.Handle("GET /api/v1/moderation/users/{id}/badges", s.staff(rankAdmin, s.handleModUserBadges))
	mux.Handle("PUT /api/v1/moderation/users/{id}/badges", s.staff(rankAdmin, s.handleModUserBadgesPut))
}

// profileBadges is what a profile's answer carries; a failure shows none
// rather than failing the profile.
func (s *Server) profileBadges(r *http.Request, u *store.User) []store.Badge {
	list, err := s.Store.ProfileBadges(r.Context(), u)
	if err != nil {
		s.Log.Warn("badges: profile", "user", u.ID.String(), "err", err)
		return []store.Badge{}
	}
	return list
}

func (s *Server) handleMyBadges(w http.ResponseWriter, r *http.Request) {
	list, err := s.Store.MyBadges(r.Context(), auth.UserFrom(r.Context()))
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"badges": list})
}

func (s *Server) handleMyBadgesPut(w http.ResponseWriter, r *http.Request) {
	u := auth.UserFrom(r.Context())
	if ok, retry := s.limProfile.Allow("badges:" + u.ID.String()); !ok {
		w.Header().Set("Retry-After", retryAfter(retry))
		WriteError(w, ErrRateLimited)
		return
	}
	var p store.BadgePrefs
	if err := ReadJSON(w, r, &p, 16<<10); err != nil {
		WriteError(w, err)
		return
	}
	if err := s.Store.SetBadgePrefs(r.Context(), u.ID, p); err != nil {
		WriteError(w, err)
		return
	}
	s.handleMyBadges(w, r)
}

// --- staff ------------------------------------------------------------------

func (s *Server) handleModBadges(w http.ResponseWriter, r *http.Request) {
	defs, err := s.Store.BadgeDefs(r.Context())
	if err != nil {
		WriteError(w, err)
		return
	}
	contributors, err := s.Store.ContributorCount(r.Context())
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"badges": defs, "contributors": contributors})
}

type badgeDefBody struct {
	Name  string `json:"name"`
	Icon  string `json:"icon"`
	Color int    `json:"color"`
}

// readBadgeDef reads and checks an event badge's name, icon and colour.
func readBadgeDef(w http.ResponseWriter, r *http.Request) (*store.BadgeDef, bool) {
	var b badgeDefBody
	if err := ReadJSON(w, r, &b, 4<<10); err != nil {
		WriteError(w, err)
		return nil, false
	}
	name, ok := textutil.CleanName(b.Name, 4*store.MaxBadgeName)
	if !ok || name == "" || utf8.RuneCountInString(name) > store.MaxBadgeName {
		WriteError(w, E(http.StatusBadRequest, "bad_name", "A badge needs a name of 1 to 32 characters."))
		return nil, false
	}
	if !store.ValidBadgeIcon(b.Icon) {
		WriteError(w, E(http.StatusBadRequest, "bad_icon", "Pick an icon for the badge."))
		return nil, false
	}
	if b.Color < 0 || b.Color > 0xFFFFFF {
		WriteError(w, E(http.StatusBadRequest, "bad_color", "That colour is not valid."))
		return nil, false
	}
	return &store.BadgeDef{Name: name, Icon: b.Icon, Color: b.Color}, true
}

func (s *Server) handleModBadgeCreate(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	d, ok := readBadgeDef(w, r)
	if !ok {
		return
	}
	d.CreatedBy = me.ID
	if err := s.Store.CreateBadgeDef(r.Context(), d); err != nil {
		WriteError(w, err)
		return
	}
	s.logBadge(r, store.LogBadgeCreate, nil, map[string]any{"name": d.Name})
	WriteJSON(w, http.StatusOK, d)
}

func (s *Server) handleModBadgeEdit(w http.ResponseWriter, r *http.Request) {
	id, err := ids.Parse(r.PathValue("id"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	was, err := s.Store.BadgeDef(r.Context(), id)
	if err != nil {
		WriteError(w, err)
		return
	}
	d, ok := readBadgeDef(w, r)
	if !ok {
		return
	}
	d.ID, d.CreatedBy, d.CreatedAt = id, was.CreatedBy, was.CreatedAt
	if err := s.Store.UpdateBadgeDef(r.Context(), d); err != nil {
		WriteError(w, err)
		return
	}
	s.logBadge(r, store.LogBadgeEdit, nil, map[string]any{"name": d.Name, "from": was.Name})
	WriteJSON(w, http.StatusOK, d)
}

func (s *Server) handleModBadgeDelete(w http.ResponseWriter, r *http.Request) {
	id, err := ids.Parse(r.PathValue("id"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	was, err := s.Store.BadgeDef(r.Context(), id)
	if err != nil {
		WriteError(w, err)
		return
	}
	if err := s.Store.DeleteBadgeDef(r.Context(), id); err != nil {
		WriteError(w, err)
		return
	}
	s.logBadge(r, store.LogBadgeDelete, nil, map[string]any{"name": was.Name})
	w.WriteHeader(http.StatusNoContent)
}

// badgeTarget is the account a staff badge route names, refusing bots,
// Nexa's own accounts and deleted ones, which hold no badges.
func (s *Server) badgeTarget(w http.ResponseWriter, r *http.Request) *store.User {
	id, err := ids.Parse(r.PathValue("id"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil
	}
	u, err := s.Store.UserByID(r.Context(), id)
	if err != nil {
		WriteError(w, err)
		return nil
	}
	if u.Flags&(store.NotPeople|store.FlagBot|store.FlagDeleted) != 0 {
		WriteError(w, E(http.StatusBadRequest, "no_badges", "This account cannot hold badges."))
		return nil
	}
	return u
}

func (s *Server) handleModUserBadges(w http.ResponseWriter, r *http.Request) {
	u := s.badgeTarget(w, r)
	if u == nil {
		return
	}
	granted, err := s.Store.GrantedBadges(r.Context(), u.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"granted": granted})
}

func (s *Server) handleModUserBadgesPut(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	u := s.badgeTarget(w, r)
	if u == nil {
		return
	}
	var body struct {
		Badges []string `json:"badges"`
	}
	if err := ReadJSON(w, r, &body, 16<<10); err != nil {
		WriteError(w, err)
		return
	}
	if len(body.Badges) > 64 {
		WriteError(w, E(http.StatusBadRequest, "too_many_badges", "That is too many badges."))
		return
	}
	for _, b := range body.Badges {
		if !store.IsGrantableBadge(b) {
			WriteError(w, E(http.StatusBadRequest, "bad_badge", "Staff cannot give that badge."))
			return
		}
	}
	added, removed, err := s.Store.SetGrantedBadges(r.Context(), u.ID, me.ID, body.Badges)
	if err != nil {
		WriteError(w, err)
		return
	}
	if len(added)+len(removed) > 0 {
		s.logBadge(r, store.LogBadgeChange, u, map[string]any{"added": s.badgeNames(r, added), "removed": s.badgeNames(r, removed)})
	}
	granted, err := s.Store.GrantedBadges(r.Context(), u.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"granted": granted})
}

// badgeNames names given badges for the log: Contributor by its id (the
// panel translates it), an event badge by its name now, since it can go.
func (s *Server) badgeNames(r *http.Request, list []string) []string {
	out := []string{}
	for _, b := range list {
		if n, ok := strings.CutPrefix(b, store.EventBadgePrefix); ok {
			if id, err := ids.Parse(n); err == nil {
				if d, err := s.Store.BadgeDef(r.Context(), id); err == nil {
					out = append(out, d.Name)
					continue
				}
			}
		}
		out = append(out, b)
	}
	return out
}

func (s *Server) logBadge(r *http.Request, action string, target *store.User, details map[string]any) {
	me := auth.UserFrom(r.Context())
	e := &store.ModLogEntry{Action: action, ModeratorID: me.ID, ModeratorUsername: me.Username, Details: mustJSON(details)}
	if target != nil {
		e.TargetID, e.TargetUsername = &target.ID, target.Username
	}
	if err := s.Store.AppendModLog(r.Context(), e); err != nil {
		s.Log.Warn("badges: log", "action", action, "err", err)
		return
	}
	s.emitLog(r.Context(), e)
}
