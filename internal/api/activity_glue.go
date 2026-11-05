package api

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"

	"evilion.xyz/chat/internal/gateway"
	"evilion.xyz/chat/internal/rpcapps"
)

// WireActivities lets the gateway show Rich Presence activities: their images
// go through this server's media proxy, and their Discord applications are
// looked up (and cached) by this server rather than by each viewer.
func (s *Server) WireActivities(hub *gateway.Hub) {
	if s.Apps == nil {
		s.Apps = rpcapps.New()
	}
	if hub == nil {
		return
	}
	hub.ProxyURL = s.ProxyURL
	hub.Apps = s.Apps
	hub.IdentifyGame = s.identifyGame
	s.activityHub = hub
}

// identifyGame finds a game the desktop app recognised on IGDB. Its picture is
// the cover this server keeps, which viewers fetch from here (handleGameCover).
// A failure only means the card goes without; viewers can still ask by name.
func (s *Server) identifyGame(ctx context.Context, name, store, storeID, gameID string) (string, string) {
	if !s.Games.On() {
		return "", ""
	}
	g, err := s.Games.Identify(ctx, name, store, storeID, gameID)
	if err != nil {
		s.Log.Warn("igdb identify", "err", err)
		return "", ""
	}
	if g == nil {
		return "", ""
	}
	if g.Cover == "" {
		return g.ID, ""
	}
	return g.ID, "/media/game/" + url.PathEscape(g.Cover) + "?size=s"
}

func (s *Server) activityRoutes(mux *http.ServeMux) {
	mux.Handle("POST /api/v1/activities/preview", s.userLimited(s.limSearch, s.handleActivityPreview))
}

// handleActivityPreview shows the desktop app how activities will look to
// others (named, with their art resolved) without showing them to anyone.
func (s *Server) handleActivityPreview(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Activities []json.RawMessage `json:"activities"`
	}
	if err := ReadJSON(w, r, &body, 64<<10); err != nil {
		WriteError(w, err)
		return
	}
	out := []gateway.Activity{}
	if s.activityHub != nil {
		out = append(out, s.activityHub.ResolveActivities(body.Activities)...)
	}
	WriteJSON(w, http.StatusOK, out)
}
