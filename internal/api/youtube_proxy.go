package api

import (
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
)

// The YouTube player, played through this server (frame_proxy.go has how).
// YouTube sees Cloudflare's address and no cookies, never the viewer's.

const ytHome = "www.youtube-nocookie.com"

// ytHosts are the hosts the player may reach through /yt/: YouTube's pages and
// API, its pictures, its fonts and scripts on gstatic, and its video servers.
var ytHosts = map[string]bool{
	"www.youtube-nocookie.com": true, "www.youtube.com": true, "youtube.com": true, "m.youtube.com": true,
	"i.ytimg.com": true, "i1.ytimg.com": true, "i2.ytimg.com": true, "i3.ytimg.com": true, "i4.ytimg.com": true, "i9.ytimg.com": true, "s.ytimg.com": true,
	"yt3.ggpht.com": true, "yt4.ggpht.com": true, "lh3.googleusercontent.com": true,
	"www.gstatic.com": true, "ssl.gstatic.com": true, "fonts.gstatic.com": true, "fonts.googleapis.com": true,
	"jnn-pa.googleapis.com": true, "www.google.com": true,
}

func ytHostAllowed(host string) bool {
	host = strings.ToLower(host)
	return ytHosts[host] || strings.HasSuffix(host, ".googlevideo.com")
}

// ytTelemetry is what the player sends about the viewer rather than asks for:
// answered here with nothing, and never passed on.
var ytTelemetry = regexp.MustCompile(`^/(api/stats/|ptracking|youtubei/v1/log_event|generate_204|gen_204|csi_204|error_204|pagead/|log\b|youtubei/v1/att/log|api/jserror|videogoodput)`)

// ytVideoID is the shape of a YouTube video id.
var ytVideoID = regexp.MustCompile(`^[A-Za-z0-9_-]{11}$`)

var ytSite = &frameSite{
	root:    "yt",
	name:    "YouTube",
	signing: "youtube-proxy/v1",
	home:    ytHome,
	hosts:   ytHostAllowed,
	telemetry: func(host, path string) bool {
		return ytTelemetry.MatchString(path) || host == "www.google.com" && !strings.HasPrefix(path, "/js/")
	},
	shimHosts: `youtube-nocookie\.com|youtube\.com|ytimg\.com|ggpht\.com|googlevideo\.com|gstatic\.com|googleusercontent\.com`,
	// YouTube refuses a player that does not say which site embeds it
	// ("embedder identity denied"), so the page is asked for as an embed on
	// Nexa is: from Nexa's origin, never the viewer's page or address.
	pageReferer: func(s *Server, r *http.Request) string { return s.frameOrigin(r) + "/" },
}

func (s *Server) youtubeRoutes(mux *http.ServeMux) {
	mux.Handle("GET /api/v1/youtube/{id}", s.authed(s.handleYouTubePlayer))
	mux.Handle("GET /yt/{token}/embed/{id}", ipLimited(s.limYouTube, s.handleYouTubeEmbed))
	mux.Handle("/yt/{token}/p/{host}/{rest...}", s.frameProxyRoute(ytSite))
}

// ytSign is the token for a /yt/ address that stays good until expires.
func (s *Server) ytSign(expires int64) string { return s.frameSign(ytSite, expires) }

// ytTokenOK reports whether a /yt/ token is one this server signed and still good.
func (s *Server) ytTokenOK(token string) bool { return s.frameTokenOK(ytSite, token) }

// handleYouTubePlayer gives a signed-in person the address of a video's player.
func (s *Server) handleYouTubePlayer(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !ytVideoID.MatchString(id) {
		WriteError(w, ErrNotFound)
		return
	}
	q := url.Values{"autoplay": {"1"}, "rel": {"0"}}
	if start, err := strconv.Atoi(r.URL.Query().Get("start")); err == nil && start > 0 {
		q.Set("start", strconv.Itoa(start))
	}
	WriteJSON(w, http.StatusOK, map[string]any{"url": "/yt/" + s.frameToken(ytSite) + "/embed/" + id + "?" + q.Encode()})
}

// handleYouTubeEmbed is YouTube's embed page for a video, with every address
// in it pointed at /yt/ and the shim put in front of its scripts.
func (s *Server) handleYouTubeEmbed(w http.ResponseWriter, r *http.Request) {
	token, id := r.PathValue("token"), r.PathValue("id")
	if !s.ytTokenOK(token) || !ytVideoID.MatchString(id) {
		s.frameHeaders(w, r)
		http.Error(w, "This player link has expired. Press play again.", http.StatusForbidden)
		return
	}
	q := url.Values{"rel": {"0"}, "autoplay": {"1"}, "playsinline": {"1"}, "hl": {"en"}}
	if start, err := strconv.Atoi(r.URL.Query().Get("start")); err == nil && start > 0 {
		q.Set("start", strconv.Itoa(start))
	}
	if r.URL.Query().Get("autoplay") == "0" {
		q.Set("autoplay", "0")
	}
	target := &url.URL{Scheme: "https", Host: ytHome, Path: "/embed/" + id, RawQuery: q.Encode()}
	s.frameForward(ytSite, w, r, token, target, true)
}
