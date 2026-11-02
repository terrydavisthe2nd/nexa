package api

import (
	"net/http"
	"net/url"
	"regexp"
	"strings"

	"evilion.xyz/chat/internal/unfurl"
)

// The music players whose services ask that their audio be played only in
// their own player: Spotify's embed and SoundCloud's widget, each played
// through this server as YouTube's is (frame_proxy.go), so neither learns who
// is listening. The card around them is the app's own (internal/unfurl/music.go);
// the player opens only when someone presses play.

var spotifySite = &frameSite{
	root:    "sp",
	name:    "Spotify",
	signing: "spotify-proxy/v1",
	home:    "open.spotify.com",
	hosts: func(h string) bool {
		switch h {
		case "open.spotify.com", "api.spotify.com", "api-partner.spotify.com", "apresolve.spotify.com", "clienttoken.spotify.com", "i.scdn.co", "p.scdn.co", "encore.scdn.co":
			return true
		}
		return strings.HasSuffix(h, ".spotifycdn.com") || strings.HasSuffix(h, ".scdn.co") || spotifyClientHost.MatchString(h)
	},
	telemetry: func(host, path string) bool {
		return spotifyTelemetry.MatchString(path)
	},
	shimHosts: `spotify\.com|spotifycdn\.com|scdn\.co`,
}

// spotifyClientHost is one of the API hosts apresolve hands out ("guc3-spclient.spotify.com").
var spotifyClientHost = regexp.MustCompile(`^[a-z0-9]{1,10}-(?:spclient|dealer)\.spotify\.com$`)

var spotifyTelemetry = regexp.MustCompile(`^/(gabo-receiver-service|v1/events|events|melody|pathfinder/v1/query\?operationName=(?:playbackLogging)|ess-proxy|v1/stats|log)\b`)

var soundcloudSite = &frameSite{
	root:    "sc",
	name:    "SoundCloud",
	signing: "soundcloud-proxy/v1",
	home:    "w.soundcloud.com",
	hosts: func(h string) bool {
		switch h {
		case "w.soundcloud.com", "api-widget.soundcloud.com", "api-v2.soundcloud.com", "api.soundcloud.com", "soundcloud.com":
			return true
		}
		return strings.HasSuffix(h, ".sndcdn.com") || strings.HasSuffix(h, ".soundcloud.cloud")
	},
	telemetry: func(host, path string) bool {
		return soundcloudTelemetry.MatchString(path)
	},
	shimHosts: `soundcloud\.com|sndcdn\.com|soundcloud\.cloud`,
}

var soundcloudTelemetry = regexp.MustCompile(`^/(event-logging|events?|telemetry|me/play-history|audio-ads|promoted|ads|stats)\b`)

var (
	spotifyKinds    = map[string]bool{"track": true, "album": true, "playlist": true, "artist": true, "episode": true, "show": true}
	soundcloudIDRe  = regexp.MustCompile(`^\d{1,15}$`)
)

func (s *Server) musicPlayerRoutes(mux *http.ServeMux) {
	mux.Handle("GET /api/v1/music/player/{platform}/{kind}/{id}", s.authed(s.handleMusicPlayer))
	mux.Handle("GET /sp/{token}/embed/{kind}/{id}", ipLimited(s.limYouTube, s.handleSpotifyEmbed))
	mux.Handle("/sp/{token}/p/{host}/{rest...}", s.frameProxyRoute(spotifySite))
	mux.Handle("GET /sc/{token}/player/{$}", ipLimited(s.limYouTube, s.handleSoundCloudEmbed))
	mux.Handle("/sc/{token}/p/{host}/{rest...}", s.frameProxyRoute(soundcloudSite))
}

// handleMusicPlayer gives a signed-in person the address of a service's own
// player for one song, album or playlist.
func (s *Server) handleMusicPlayer(w http.ResponseWriter, r *http.Request) {
	platform, kind, id := r.PathValue("platform"), r.PathValue("kind"), r.PathValue("id")
	switch {
	case platform == "spotify" && spotifyKinds[kind] && unfurl.SpotifyIDRe.MatchString(id):
		WriteJSON(w, http.StatusOK, map[string]any{"url": "/sp/" + s.frameToken(spotifySite) + "/embed/" + kind + "/" + id})
	case platform == "soundcloud" && (kind == "track" || kind == "playlist") && soundcloudIDRe.MatchString(id):
		WriteJSON(w, http.StatusOK, map[string]any{"url": "/sc/" + s.frameToken(soundcloudSite) + "/player/?" + soundcloudQuery("https://api.soundcloud.com/"+kind+"s/"+id).Encode()})
	default:
		WriteError(w, ErrNotFound)
	}
}

func (s *Server) handleSpotifyEmbed(w http.ResponseWriter, r *http.Request) {
	token, kind, id := r.PathValue("token"), r.PathValue("kind"), r.PathValue("id")
	if !s.frameTokenOK(spotifySite, token) || !spotifyKinds[kind] || !unfurl.SpotifyIDRe.MatchString(id) {
		s.frameHeaders(w, r)
		http.Error(w, "This player link has expired. Press play again.", http.StatusForbidden)
		return
	}
	// theme 0 is Spotify's dark player, which sits in a dark card on every theme
	target := &url.URL{Scheme: "https", Host: "open.spotify.com", Path: "/embed/" + kind + "/" + id, RawQuery: "utm_source=generator&theme=0"}
	s.frameForward(spotifySite, w, r, token, target, true)
}

// soundcloudQuery is the widget's settings, which its own script reads from
// the frame's address: the same here as on SoundCloud's.
func soundcloudQuery(api string) url.Values {
	return url.Values{
		"url": {api}, "auto_play": {"true"}, "visual": {"false"}, "color": {"#ff5500"},
		"show_comments": {"false"}, "hide_related": {"true"}, "show_teaser": {"false"}, "show_reposts": {"false"}, "show_user": {"true"},
	}
}

var soundcloudAPIURL = regexp.MustCompile(`^https://api\.soundcloud\.com/(?:tracks|playlists)/\d{1,15}$`)

func (s *Server) handleSoundCloudEmbed(w http.ResponseWriter, r *http.Request) {
	token, api := r.PathValue("token"), r.URL.Query().Get("url")
	if !s.frameTokenOK(soundcloudSite, token) || !soundcloudAPIURL.MatchString(api) {
		s.frameHeaders(w, r)
		http.Error(w, "This player link has expired. Press play again.", http.StatusForbidden)
		return
	}
	target := &url.URL{Scheme: "https", Host: "w.soundcloud.com", Path: "/player/", RawQuery: soundcloudQuery(api).Encode()}
	s.frameForward(soundcloudSite, w, r, token, target, true)
}
