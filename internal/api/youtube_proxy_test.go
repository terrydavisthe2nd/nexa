package api

import (
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestYouTubePlayerNeedsAnAccount(t *testing.T) {
	_, ts := newTestServer(t)
	if res, _ := do(t, ts, "GET", "/api/v1/youtube/jNQXAC9IVRw", "", nil); res.StatusCode != http.StatusUnauthorized {
		t.Fatalf("signed out: %d", res.StatusCode)
	}
	tok, _ := register(t, ts, "ytviewer")
	if res, _ := do(t, ts, "GET", "/api/v1/youtube/not-an-id", tok, nil); res.StatusCode != http.StatusNotFound {
		t.Fatalf("bad id: %d", res.StatusCode)
	}
	res, body := do(t, ts, "GET", "/api/v1/youtube/jNQXAC9IVRw?start=42", tok, nil)
	u, _ := body["url"].(string)
	if res.StatusCode != http.StatusOK || !strings.HasPrefix(u, "/yt/") || !strings.Contains(u, "/embed/jNQXAC9IVRw?") || !strings.Contains(u, "start=42") {
		t.Fatalf("player url: %d %v", res.StatusCode, body)
	}
}

func TestYouTubeTokens(t *testing.T) {
	srv, _ := newTestServer(t)
	srv.Secret = []byte("secret one")
	good := srv.ytSign(time.Now().Add(time.Hour).Unix())
	if !srv.ytTokenOK(good) {
		t.Fatal("a fresh token refused")
	}
	if srv.ytTokenOK(srv.ytSign(time.Now().Add(-time.Minute).Unix())) {
		t.Fatal("an expired token taken")
	}
	exp, _, _ := strings.Cut(good, ".")
	n, _ := strconv.ParseInt(exp, 36, 64)
	if srv.ytTokenOK(strconv.FormatInt(n+3600, 36) + good[len(exp):]) {
		t.Fatal("a token with its expiry pushed back taken")
	}
	for _, bad := range []string{"", "x", "abc.def", good + "x"} {
		if srv.ytTokenOK(bad) {
			t.Fatalf("%q taken", bad)
		}
	}
	srv.Secret = []byte("secret two")
	if srv.ytTokenOK(good) {
		t.Fatal("a token signed with another secret taken")
	}
}

// The proxy answers only for YouTube's hosts, only with a token, and never
// passes on what the player reports about the viewer. None of these reach
// the network: they are refused or answered before anything is fetched.
func TestYouTubeProxyRefuses(t *testing.T) {
	srv, ts := newTestServer(t)
	srv.Secret = []byte("k")
	tok := srv.ytSign(time.Now().Add(time.Hour).Unix())
	cases := []struct {
		method, path string
		want         int
	}{
		{"GET", "/yt/" + tok + "/p/evil.example/x.js", http.StatusForbidden},
		{"GET", "/yt/" + tok + "/p/127.0.0.1/admin", http.StatusForbidden},
		{"GET", "/yt/" + tok + "/p/googlevideo.com.evil.example/videoplayback", http.StatusForbidden},
		{"GET", "/yt/bogus.token/p/www.youtube.com/s/x.js", http.StatusForbidden},
		{"GET", "/yt/bogus.token/embed/jNQXAC9IVRw", http.StatusForbidden},
		{"GET", "/yt/" + tok + "/embed/not-an-id", http.StatusForbidden},
		{"DELETE", "/yt/" + tok + "/p/www.youtube.com/x", http.StatusMethodNotAllowed},
		{"POST", "/yt/" + tok + "/p/www.youtube.com/api/stats/watchtime?docid=x", http.StatusNoContent},
		{"GET", "/yt/" + tok + "/p/www.youtube.com/ptracking?video_id=x", http.StatusNoContent},
		{"POST", "/yt/" + tok + "/p/www.youtube-nocookie.com/youtubei/v1/log_event?alt=json", http.StatusNoContent},
		{"GET", "/yt/" + tok + "/p/www.google.com/search?q=x", http.StatusNoContent},
		{"OPTIONS", "/yt/" + tok + "/p/www.youtube.com/youtubei/v1/player", http.StatusNoContent},
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
		if strings.HasPrefix(c.path, "/yt/"+tok+"/p/") {
			csp := res.Header.Get("Content-Security-Policy")
			if !strings.Contains(csp, "default-src 'none'") || !strings.Contains(csp, "sandbox allow-scripts allow-presentation") || strings.Contains(csp, "allow-same-origin") {
				t.Errorf("%s: policy %q", c.path, csp)
			}
			if res.Header.Get("Set-Cookie") != "" || res.Header.Get("X-Frame-Options") != "" {
				t.Errorf("%s: cookie or frame header passed on", c.path)
			}
		}
	}
}

func TestYouTubeRewrite(t *testing.T) {
	base, _ := url.Parse("https://www.youtube-nocookie.com/embed/abc")
	page := `<html><head><link href="/s/player/x/www-player.css" rel="stylesheet"><script nonce="n" src="/s/player/x/base.js"></script>` +
		`<style>@font-face{src:url(//fonts.gstatic.com/s/roboto/a.woff2)}</style></head>` +
		`<body><img src="https://i.ytimg.com/vi/abc/hq.jpg"><a href="https://evil.example/">x</a>` +
		`<script>var c={"u":"https:\/\/www.youtube.com\/youtubei"}</script></body></html>`
	out := string(ytSite.injectShim(ytSite.rewriteHTML([]byte(page), "/yt/T", base), "/yt/T", "https://chat.example/"))
	for _, want := range []string{
		`href="/yt/T/p/www.youtube-nocookie.com/s/player/x/www-player.css"`,
		`<script crossorigin="anonymous" nonce="n" src="/yt/T/p/www.youtube-nocookie.com/s/player/x/base.js"`,
		`url(/yt/T/p/fonts.gstatic.com/s/roboto/a.woff2)`,
		`src="/yt/T/p/i.ytimg.com/vi/abc/hq.jpg"`,
		`href="https://evil.example/"`,
		`\/yt\/T\/p\/www.youtube.com\/youtubei`,
	} {
		if !strings.Contains(out, want) {
			t.Errorf("rewritten page lacks %s\n%s", want, out)
		}
	}
	// the shim comes before anything of YouTube's runs
	if head := strings.Index(out, "<head>"); !strings.HasPrefix(out[head+len("<head>"):], "<script>(function(C){") {
		t.Errorf("shim not first in head:\n%s", out)
	}
	css := string(ytSite.rewriteCSS([]byte(`a{background:url("https://i.ytimg.com/a.png")} b{background:url(/img/b.png)} c{background:url(data:image/png;base64,AA)}`), "/yt/T", base))
	for _, want := range []string{`url("/yt/T/p/i.ytimg.com/a.png")`, `url(/yt/T/p/www.youtube-nocookie.com/img/b.png)`, `url(data:image/png;base64,AA)`} {
		if !strings.Contains(css, want) {
			t.Errorf("rewritten css lacks %s: %s", want, css)
		}
	}
}
