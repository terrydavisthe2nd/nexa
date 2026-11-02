package api

import (
	"context"
	"errors"
	"net/http"
	"net/url"
	"strings"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/unfurl"
)

func (s *Server) unfurlRoutes(mux *http.ServeMux) {
	mux.Handle("GET /api/v1/unfurl", s.authed(s.handleUnfurl))
}

// handleUnfurl fetches a link preview on behalf of the sender. Anything that
// cannot be embedded (including blocked addresses) is a 204 so the client
// simply shows no preview.
func (s *Server) handleUnfurl(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	if ok, retry := s.limUnfurl.Allow("unfurl:" + me.ID.String()); !ok {
		w.Header().Set("Retry-After", retryAfter(retry))
		WriteError(w, ErrRateLimited)
		return
	}
	raw := strings.TrimSpace(r.URL.Query().Get("url"))
	if raw == "" || len(raw) > 2048 {
		WriteError(w, E(http.StatusBadRequest, "bad_url", "url is required and must be under 2048 characters."))
		return
	}
	// An invite to Nexa is answered from our own records: no fetch,
	// and the reader gets a card they can join from.
	if res := s.inviteUnfurl(r, raw); res != nil {
		WriteJSON(w, http.StatusOK, res)
		return
	}
	// So is a profile link: the reader's app draws it as the person's card.
	if res := s.profileUnfurl(r.Context(), raw); res != nil {
		WriteJSON(w, http.StatusOK, res)
		return
	}
	// A note's share link gets no preview from here: what it leads to can
	// only be read with the key in the part after the #, which never reaches
	// the server, so the app draws its card itself.
	if s.ownPath(raw, "/note/") != "" || s.isAppInstallLink(raw) {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if s.Unfurl == nil {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	res, err := s.Unfurl.Lookup(r.Context(), raw)
	if err != nil {
		if errors.Is(err, unfurl.ErrUnsupported) || errors.Is(err, unfurl.ErrBlocked) || errors.Is(err, unfurl.ErrTooLarge) {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		WriteError(w, E(http.StatusBadGateway, "fetch_failed", "That link could not be fetched."))
		return
	}
	// A short link that redirects to one of our invites gets the invite's
	// own card, not a copy of the invite page's preview tags.
	if res.URL != raw {
		if inv := s.inviteUnfurl(r, res.URL); inv != nil {
			WriteJSON(w, http.StatusOK, inv)
			return
		}
	}
	w.Header().Set("Cache-Control", "private, max-age=3600")
	WriteJSON(w, http.StatusOK, s.proxied(res))
}

// proxied is a preview with every address the client loads from it rewritten
// to go through our proxy, so opening a chat never hands the reader's address
// to whoever hosts the picture: the image, the site's own icon, a file it
// plays inline, and everything a social post shows. The preview given is the
// cache's own and is left as it is.
func (s *Server) proxied(res *unfurl.Result) *unfurl.Result {
	out := *res
	if out.Image == "" && (out.Type == "image" || out.Type == "gif" || out.Type == "video") {
		out.Image = out.URL
	}
	out.Image = s.ProxyURL(out.Image)
	out.Icon = s.ProxyURL(out.Icon)
	out.Media = s.ProxyURL(out.Media)
	out.Post = s.proxiedPost(out.Post)
	out.Music = s.proxiedMusic(out.Music)
	return &out
}

// proxiedMusic is a music card with its cover and previews through the proxy.
// Deezer's previews already come through this server (/media/music/preview).
func (s *Server) proxiedMusic(m *unfurl.Music) *unfurl.Music {
	if m == nil {
		return nil
	}
	out := *m
	out.Art = s.ProxyURL(out.Art)
	out.Preview = s.ProxyURL(out.Preview)
	if len(m.Tracks) > 0 {
		out.Tracks = make([]unfurl.MusicTrack, len(m.Tracks))
		for i, t := range m.Tracks {
			t.Preview = s.ProxyURL(t.Preview)
			out.Tracks[i] = t
		}
	}
	return &out
}

func (s *Server) proxiedPost(p *unfurl.Post) *unfurl.Post {
	if p == nil {
		return nil
	}
	out := *p
	out.Avatar = s.ProxyURL(out.Avatar)
	if len(p.Media) > 0 {
		out.Media = make([]unfurl.PostMedia, len(p.Media))
		for i, m := range p.Media {
			m.URL, m.Thumb, m.Audio = s.ProxyURL(m.URL), s.ProxyURL(m.Thumb), s.ProxyURL(m.Audio)
			out.Media[i] = m
		}
	}
	out.Quote = s.proxiedPost(p.Quote)
	return &out
}

// inviteCodeOf returns the code when raw is an invite link to Nexa.
func (s *Server) inviteCodeOf(raw string) string {
	return s.ownPath(raw, "/invite/")
}

// ownPath returns what follows prefix when raw is a link to one of Nexa's
// own pages under it ("/invite/CODE", "/note/TOKEN", "/@NAME"), at any of
// its addresses, or "".
// isAppInstallLink reports whether raw is one of our own pages for adding an
// app (/oauth2/authorize), which the apps draw as the app's own card: no
// preview of the page is fetched for it.
func (s *Server) isAppInstallLink(raw string) bool {
	return s.ownPath(raw, "/oauth2/") == "authorize"
}

func (s *Server) ownPath(raw, prefix string) string {
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" {
		return ""
	}
	if _, kind := s.addressOf(u.Host); kind == notOurs {
		return ""
	}
	rest, ok := strings.CutPrefix(u.Path, prefix)
	// the app takes the page with a slash at its end too
	rest = strings.TrimSuffix(rest, "/")
	if !ok || rest == "" || strings.Contains(rest, "/") {
		return ""
	}
	return rest
}

// inviteUnfurl builds the preview card for one of our own invite links.
func (s *Server) inviteUnfurl(r *http.Request, raw string) *unfurl.Result {
	return s.inviteUnfurlLink(r.Context(), raw)
}

// inviteUnfurlLink is inviteUnfurl outside a request: for the links a webhook
// posts, whose previews the server fetches after the message is in.
func (s *Server) inviteUnfurlLink(ctx context.Context, raw string) *unfurl.Result {
	code := s.inviteCodeOf(raw)
	if code == "" {
		return nil
	}
	in, err := s.Store.InviteByCode(ctx, code)
	if err != nil {
		return nil
	}
	out := &unfurl.Result{URL: raw, Type: "invite", InviteCode: in.Code, SiteName: s.issuer()}
	if in.ServerID == nil {
		if in.ChannelID == nil {
			return nil
		}
		ch, err := s.Store.ChannelByID(ctx, *in.ChannelID)
		if err != nil {
			return nil
		}
		out.Title = ch.Name
		if out.Title == "" {
			out.Title = "Group chat"
		}
		out.Description = "You have been invited to a group chat."
		out.MemberCount = len(ch.Recipients)
		if ch.IconID != nil {
			out.Image = s.Cfg.PublicURL + "/media/icon/" + ch.IconID.String()
		}
		return out
	}
	srv, err := s.Store.ServerByID(ctx, *in.ServerID)
	if err != nil {
		return nil
	}
	if srv.NSFW {
		out.Title = "Age-Restricted Server"
		out.Description = "This server contains NSFW content. You must be 18 or older to view this invite."
		return out
	}
	count, online := s.counts(ctx, srv.ID)
	out.Title = srv.Name
	out.Description = srv.Description
	if out.Description == "" {
		out.Description = "You have been invited to join this server."
	}
	out.MemberCount = count
	out.OnlineCount = online
	if srv.IconID != nil {
		out.Image = s.Cfg.PublicURL + "/media/icon/" + srv.IconID.String()
	}
	if srv.BannerID != nil {
		out.Banner = s.Cfg.PublicURL + "/media/banner/" + srv.BannerID.String()
	}
	return out
}
