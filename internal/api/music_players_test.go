package api

import (
	"io"
	"net/http"
	"strings"
	"testing"
	"time"
)

func TestMusicPlayerAddresses(t *testing.T) {
	_, ts := newTestServer(t)
	if res, _ := do(t, ts, "GET", "/api/v1/music/player/spotify/track/4cOdK2wGLETKBW3PvgPWqT", "", nil); res.StatusCode != http.StatusUnauthorized {
		t.Fatalf("signed out: %d", res.StatusCode)
	}
	tok, _ := register(t, ts, "listener")
	for _, bad := range []string{"spotify/track/short", "spotify/user/4cOdK2wGLETKBW3PvgPWqT", "soundcloud/track/abc", "napster/track/1", "apple/track/1440857786"} {
		if res, _ := do(t, ts, "GET", "/api/v1/music/player/"+bad, tok, nil); res.StatusCode != http.StatusNotFound {
			t.Errorf("%s: %d", bad, res.StatusCode)
		}
	}
	res, body := do(t, ts, "GET", "/api/v1/music/player/spotify/album/4LH4d3cOWNNsVw41Gqt2kv", tok, nil)
	if u, _ := body["url"].(string); res.StatusCode != http.StatusOK || !strings.HasPrefix(u, "/sp/") || !strings.HasSuffix(u, "/embed/album/4LH4d3cOWNNsVw41Gqt2kv") {
		t.Fatalf("spotify: %d %v", res.StatusCode, body)
	}
	res, body = do(t, ts, "GET", "/api/v1/music/player/soundcloud/playlist/18", tok, nil)
	if u, _ := body["url"].(string); res.StatusCode != http.StatusOK || !strings.HasPrefix(u, "/sc/") || !strings.Contains(u, "api.soundcloud.com%2Fplaylists%2F18") {
		t.Fatalf("soundcloud: %d %v", res.StatusCode, body)
	}
}

// Each player answers only with its own token, only for its own hosts, and
// sends what it reports about the listener nowhere. None of these reach the network.
func TestMusicPlayerProxyRefuses(t *testing.T) {
	srv, ts := newTestServer(t)
	srv.Secret = []byte("k")
	sp := srv.frameSign(spotifySite, time.Now().Add(time.Hour).Unix())
	sc := srv.frameSign(soundcloudSite, time.Now().Add(time.Hour).Unix())
	yt := srv.ytSign(time.Now().Add(time.Hour).Unix())
	cases := []struct {
		method, path string
		want         int
	}{
		{"GET", "/sp/" + sp + "/p/evil.example/x.js", http.StatusForbidden},
		{"GET", "/sp/" + sp + "/p/spotify.com.evil.example/x", http.StatusForbidden},
		{"GET", "/sp/" + yt + "/p/open.spotify.com/x", http.StatusForbidden},
		{"GET", "/sp/" + sc + "/embed/track/4cOdK2wGLETKBW3PvgPWqT", http.StatusForbidden},
		{"GET", "/sp/" + sp + "/embed/user/4cOdK2wGLETKBW3PvgPWqT", http.StatusForbidden},
		{"POST", "/sp/" + sp + "/p/guc3-spclient.spotify.com/gabo-receiver-service/public/v3/events", http.StatusNoContent},
		{"GET", "/sc/" + sp + "/p/w.soundcloud.com/player/", http.StatusForbidden},
		{"GET", "/sc/" + sc + "/p/dwt.evil.example/tags.js", http.StatusForbidden},
		{"GET", "/sc/" + sc + "/player/?url=https%3A%2F%2Fevil.example%2Ftracks%2F1", http.StatusForbidden},
		{"POST", "/sc/" + sc + "/p/api-v2.soundcloud.com/event-logging/batch", http.StatusNoContent},
		{"GET", "/yt/" + sp + "/p/www.youtube.com/s/x.js", http.StatusForbidden},
	}
	for _, c := range cases {
		req, _ := http.NewRequest(c.method, ts.URL+c.path, nil)
		req.Header.Set("Origin", "null")
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		io.Copy(io.Discard, res.Body)
		res.Body.Close()
		if res.StatusCode != c.want {
			t.Errorf("%s %s: %d, want %d", c.method, c.path, res.StatusCode, c.want)
		}
		if csp := res.Header.Get("Content-Security-Policy"); !strings.Contains(csp, "sandbox allow-scripts allow-presentation") || strings.Contains(csp, "allow-same-origin") {
			t.Errorf("%s: policy %q", c.path, csp)
		}
	}
}
