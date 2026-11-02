package api

import (
	"context"
	"crypto/tls"
	"encoding/base64"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"testing"
	"time"

	"evilion.xyz/chat/internal/unfurl"
)

// proxyResolver maps the one test hostname to a public address so the SSRF
// guard lets it through; the transport still dials the local test server.
type proxyResolver struct{ ips []net.IPAddr }

func (r proxyResolver) LookupIPAddr(_ context.Context, _ string) ([]net.IPAddr, error) {
	return r.ips, nil
}

func TestMediaProxy(t *testing.T) {
	srv, ts := newTestServer(t)
	// an upstream that serves one image and one script
	hits := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits++
		switch r.URL.Path {
		case "/pic.png":
			w.Header().Set("Content-Type", "image/png")
			_, _ = w.Write(pngBytes(8, 8))
		case "/evil.svg":
			w.Header().Set("Content-Type", "image/svg+xml")
			_, _ = io.WriteString(w, "<svg onload=alert(1)>")
		default:
			w.Header().Set("Content-Type", "text/html")
			_, _ = io.WriteString(w, "<html>")
		}
	}))
	defer upstream.Close()
	tr := &http.Transport{DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
		var d net.Dialer
		return d.DialContext(ctx, network, upstream.Listener.Addr().String())
	}}
	t.Cleanup(tr.CloseIdleConnections)
	srv.Unfurl = &unfurl.Service{Fetcher: &unfurl.Fetcher{Timeout: 5 * time.Second, Transport: tr, Resolver: proxyResolver{[]net.IPAddr{{IP: net.ParseIP("8.8.8.8")}}}}}
	const host = "http://images.test"

	signed := srv.ProxyURL(host + "/pic.png")
	if !strings.HasPrefix(signed, "/media/proxy/") {
		t.Fatalf("proxy url: %s", signed)
	}
	if got := srv.ProxyURL("/media/emoji/12"); got != "/media/emoji/12" {
		t.Errorf("relative urls pass through: %s", got)
	}
	res, err := http.Get(ts.URL + signed)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(res.Body)
	res.Body.Close()
	if res.StatusCode != 200 || res.Header.Get("Content-Type") != "image/png" || len(body) == 0 {
		t.Fatalf("fetch: %d %s %d bytes", res.StatusCode, res.Header.Get("Content-Type"), len(body))
	}
	if res.Header.Get("X-Content-Type-Options") != "nosniff" || res.Header.Get("Cache-Control") == "" {
		t.Errorf("hardening headers: %v", res.Header)
	}
	// a forged signature is refused, so this is not an open proxy
	parts := strings.SplitN(strings.TrimPrefix(signed, "/media/proxy/"), "/", 2)
	forged := ts.URL + "/media/proxy/AAAAAAAAAAAAAAAAAAAAAA/" + parts[1]
	if r2, err := http.Get(forged); err != nil || r2.StatusCode != 403 {
		t.Errorf("forged signature: %v %v", err, r2 != nil && r2.StatusCode == 403)
	}
	// SVG is an image, with downloads and a sandbox for direct navigation.
	r3, err := http.Get(ts.URL + srv.ProxyURL(host+"/evil.svg"))
	if err != nil || r3.StatusCode != http.StatusOK || r3.Header.Get("Content-Disposition") != `attachment; filename="preview.svg"` || r3.Header.Get("Content-Security-Policy") != "default-src 'none'; sandbox" {
		t.Errorf("svg image isolation: %v %d", err, r3.StatusCode)
	}
	// and neither does HTML
	r4, _ := http.Get(ts.URL + srv.ProxyURL(host+"/page"))
	if r4.StatusCode != http.StatusUnsupportedMediaType {
		t.Errorf("html blocked: %d", r4.StatusCode)
	}
	// unfurl results come back already rewritten
	if u := srv.ProxyURL("https://example.com/a.png"); !strings.HasPrefix(u, "/media/proxy/") || strings.Contains(u, "example.com/a.png") {
		t.Errorf("unfurl image rewrite: %s", u)
	}
}

func TestNarrowRange(t *testing.T) {
	const max = 100
	for in, want := range map[string]string{
		"bytes=0-":      "bytes=0-99",
		"bytes=500-":    "bytes=500-599",
		"bytes=0-49":    "bytes=0-49",
		"bytes=10-1000": "bytes=10-109",
		"bytes=-500":    "bytes=-500",
		"bytes=0-1,5-6": "bytes=0-1,5-6",
		"bytes=abc-":    "bytes=abc-",
		"bytes=50-10":   "bytes=50-10",
		"items=0-":      "items=0-",
		" bytes=0- ":    "bytes=0-99",
	} {
		if got := narrowRange(in, max); got != want {
			t.Errorf("narrowRange(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestMediaProxyPlaysLongVideoInPieces(t *testing.T) {
	srv, ts := newTestServer(t)
	const total = int64(maxProxyBytes) * 3
	var asked []string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		asked = append(asked, r.Header.Get("Range"))
		var start, end int64
		if _, err := fmt.Sscanf(r.Header.Get("Range"), "bytes=%d-%d", &start, &end); err != nil {
			// no range, or an open one: the whole file, which is too big to pass
			w.Header().Set("Content-Type", "video/mp4")
			w.Header().Set("Content-Length", strconv.FormatInt(total, 10))
			w.WriteHeader(http.StatusOK)
			return
		}
		w.Header().Set("Content-Type", "video/mp4")
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, total))
		w.Header().Set("Content-Length", "4")
		w.WriteHeader(http.StatusPartialContent)
		_, _ = w.Write([]byte("mp4!"))
	}))
	defer upstream.Close()
	tr := &http.Transport{DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
		var d net.Dialer
		return d.DialContext(ctx, network, upstream.Listener.Addr().String())
	}}
	t.Cleanup(tr.CloseIdleConnections)
	srv.Unfurl = &unfurl.Service{Fetcher: &unfurl.Fetcher{Timeout: 5 * time.Second, Transport: tr, Resolver: proxyResolver{[]net.IPAddr{{IP: net.ParseIP("8.8.8.8")}}}}}

	req, _ := http.NewRequest("GET", ts.URL+srv.ProxyURL("http://videos.test/long.mp4"), nil)
	req.Header.Set("Range", "bytes=0-")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	want := fmt.Sprintf("bytes=0-%d", maxProxyBytes-1)
	if res.StatusCode != http.StatusPartialContent || len(asked) != 1 || asked[0] != want {
		t.Fatalf("a player opening a long video gets its first part: %d, upstream asked %v", res.StatusCode, asked)
	}
	if cr := res.Header.Get("Content-Range"); cr != fmt.Sprintf("bytes 0-%d/%d", maxProxyBytes-1, total) {
		t.Errorf("Content-Range tells the player how long it is: %q", cr)
	}
	// without a range it is still too big to send whole
	res2, err := http.Get(ts.URL + srv.ProxyURL("http://videos.test/long.mp4"))
	if err != nil {
		t.Fatal(err)
	}
	res2.Body.Close()
	if res2.StatusCode != http.StatusRequestEntityTooLarge {
		t.Errorf("the whole file at once: %d", res2.StatusCode)
	}
}

// A signed media link used to be a capability that never expired: lift one out
// of any response and the server would keep fetching for you, for anyone, for
// good. The signature now covers a deadline, and a stale one is refused.
func TestProxyLinksExpire(t *testing.T) {
	s, ts := newTestServer(t)

	signed := s.ProxyURL("https://example.com/a.png")
	if !strings.HasPrefix(signed, "/media/proxy/") {
		t.Fatalf("not a proxy path: %q", signed)
	}
	// the path still has the shape clients read a filename out of
	if parts := strings.Split(strings.TrimPrefix(signed, "/"), "/"); len(parts) != 4 {
		t.Fatalf("want media/proxy/sig/url, got %q", signed)
	}

	// a deadline in the past, signed correctly, is still refused
	encoded := base64.RawURLEncoding.EncodeToString([]byte("https://example.com/a.png"))
	past := strconv.FormatInt(time.Now().Add(-time.Minute).Unix(), 10)
	stale := "/media/proxy/" + past + "-" + s.proxySignature(past, encoded) + "/" + encoded
	if res, _ := do(t, ts, "GET", stale, "", nil); res.StatusCode != http.StatusForbidden {
		t.Fatalf("an expired link should be refused, got %d", res.StatusCode)
	}

	// and so is a deadline moved forward without re-signing
	future := strconv.FormatInt(time.Now().Add(time.Hour).Unix(), 10)
	forged := "/media/proxy/" + future + "-" + s.proxySignature(past, encoded) + "/" + encoded
	if res, _ := do(t, ts, "GET", forged, "", nil); res.StatusCode != http.StatusForbidden {
		t.Fatalf("a moved deadline should be refused, got %d", res.StatusCode)
	}
}

// A link inside a message is end-to-end encrypted, so the server cannot renew
// it when its deadline passes; a reader who is signed in can have it signed
// again. Expired links, links from before deadlines existed, and links written
// out with the public origin in front all come back fresh. Anything this
// server did not sign does not come back at all.
func TestMediaRefresh(t *testing.T) {
	s, ts := newTestServer(t)
	tok, _ := register(t, ts, "alice")
	const target = "https://example.com/a.png"
	encoded := base64.RawURLEncoding.EncodeToString([]byte(target))
	past := strconv.FormatInt(time.Now().Add(-48*time.Hour).Unix(), 10)
	expired := "/media/proxy/" + past + "-" + s.proxySignature(past, encoded) + "/" + encoded
	legacy := "/media/proxy/" + s.proxySignatureV1(encoded) + "/" + encoded
	absolute := strings.TrimRight(s.Cfg.PublicURL, "/") + expired
	forged := "/media/proxy/" + past + "-AAAAAAAAAAAAAAAAAAAAAA/" + encoded
	otherTarget := "/media/proxy/" + past + "-" + s.proxySignature(past, encoded) + "/" + base64.RawURLEncoding.EncodeToString([]byte("https://example.com/b.png"))
	notOurs := target

	if res, _ := do(t, ts, "POST", "/api/v1/proxy/refresh", "", map[string]any{"urls": []string{expired}}); res.StatusCode != http.StatusUnauthorized {
		t.Fatalf("only for someone signed in: %d", res.StatusCode)
	}
	res, body := do(t, ts, "POST", "/api/v1/proxy/refresh", tok, map[string]any{"urls": []string{expired, legacy, absolute, forged, otherTarget, notOurs}})
	if res.StatusCode != http.StatusOK {
		t.Fatalf("refresh: %d %v", res.StatusCode, body)
	}
	urls, _ := body["urls"].(map[string]any)
	for _, link := range []string{expired, legacy, absolute} {
		fresh, _ := urls[link].(string)
		if fresh == "" {
			t.Errorf("%s was not signed again", link)
			continue
		}
		if got, ok := s.signedTarget(fresh); !ok || got != target {
			t.Errorf("%s came back as %s, pointing at %q", link, fresh, got)
		}
		sig := strings.SplitN(strings.TrimPrefix(fresh, "/media/proxy/"), "/", 2)[0]
		expires, _, _ := strings.Cut(sig, "-")
		if at, _ := strconv.ParseInt(expires, 10, 64); at <= time.Now().Unix() {
			t.Errorf("%s is not fresh: %s", link, fresh)
		}
		// Past the signature and the deadline, which is all this checks:
		// with no fetcher configured the proxy answers 503, not 403.
		if r, _ := do(t, ts, "GET", fresh, "", nil); r.StatusCode == http.StatusForbidden {
			t.Errorf("the proxy refuses the fresh link %s", fresh)
		}
	}
	for _, link := range []string{forged, otherTarget, notOurs} {
		if got, ok := urls[link]; ok {
			t.Errorf("%s should not have been signed, got %v", link, got)
		}
	}
	// an empty batch and an oversized one are both refused
	if r, _ := do(t, ts, "POST", "/api/v1/proxy/refresh", tok, map[string]any{"urls": []string{}}); r.StatusCode != http.StatusBadRequest {
		t.Errorf("empty batch: %d", r.StatusCode)
	}
	many := make([]string, 101)
	for i := range many {
		many[i] = expired
	}
	if r, _ := do(t, ts, "POST", "/api/v1/proxy/refresh", tok, map[string]any{"urls": many}); r.StatusCode != http.StatusBadRequest {
		t.Errorf("oversized batch: %d", r.StatusCode)
	}
}

// Everything a card shows loads through the proxy: its picture, the site's own
// icon and a video the page offers to play inline. The icon and the video used
// to go out as the page gave them, so opening a chat with a link card in it
// told that site the reader's address.
func TestUnfurlProxiesEverythingOnTheCard(t *testing.T) {
	srv, ts := newTestServer(t)
	tok, _ := register(t, ts, "alice")
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = io.WriteString(w, `<html><head>
<title>A page</title>
<link rel="icon" href="/favicon.png">
<meta property="og:image" content="/pic.png">
<meta property="og:video" content="/clip.mp4">
<meta property="og:video:type" content="video/mp4">
</head><body></body></html>`)
	}))
	defer upstream.Close()
	tr := &http.Transport{DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
		var d net.Dialer
		return d.DialContext(ctx, network, upstream.Listener.Addr().String())
	}}
	t.Cleanup(tr.CloseIdleConnections)
	srv.Unfurl = &unfurl.Service{Fetcher: &unfurl.Fetcher{Timeout: 5 * time.Second, Transport: tr, Resolver: proxyResolver{[]net.IPAddr{{IP: net.ParseIP("8.8.8.8")}}}}}

	res, body := do(t, ts, "GET", "/api/v1/unfurl?url=http://site.test/page", tok, nil)
	if res.StatusCode != http.StatusOK {
		t.Fatalf("unfurl: %d %v", res.StatusCode, body)
	}
	for _, field := range []string{"image", "icon", "media"} {
		v, _ := body[field].(string)
		if !strings.HasPrefix(v, "/media/proxy/") {
			t.Errorf("%s should load through the proxy, got %q", field, v)
			continue
		}
		if got, ok := srv.signedTarget(v); !ok || !strings.HasPrefix(got, "http://site.test/") {
			t.Errorf("%s points at %q", field, got)
		}
	}
}

// postUpstream is a pretend internet for the social post sources: each host
// answers with its own handler, over TLS as the real ones do.
func postUpstream(t *testing.T, hosts map[string]http.Handler) *unfurl.Service {
	t.Helper()
	upstream := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if h := hosts[r.Host]; h != nil {
			h.ServeHTTP(w, r)
			return
		}
		http.NotFound(w, r)
	}))
	t.Cleanup(upstream.Close)
	tr := &http.Transport{
		TLSClientConfig: &tls.Config{InsecureSkipVerify: true},
		DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
			var d net.Dialer
			return d.DialContext(ctx, network, upstream.Listener.Addr().String())
		},
	}
	t.Cleanup(tr.CloseIdleConnections)
	return &unfurl.Service{
		Fetcher: &unfurl.Fetcher{Timeout: 5 * time.Second, Transport: tr, Resolver: proxyResolver{[]net.IPAddr{{IP: net.ParseIP("8.8.8.8")}}}},
		Cache:   unfurl.NewCache(10, time.Hour),
	}
}

// A social post's card shows more than a link's: the author's picture, every
// photo and video, the quoted post's. All of it loads through the proxy.
func TestUnfurlProxiesEverythingOnAPost(t *testing.T) {
	srv, ts := newTestServer(t)
	tok, _ := register(t, ts, "alice")
	srv.Unfurl = postUpstream(t, map[string]http.Handler{
		"api.fxtwitter.com": http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "application/json")
			_, _ = io.WriteString(w, `{"code":200,"status":{"url":"https://x.com/jack/status/20","text":"just setting up my twttr",
 "author":{"name":"jack","screen_name":"jack","avatar_url":"https://pbs.twimg.com/profile_images/1/a.jpg","url":"https://x.com/jack"},
 "likes":5,"created_timestamp":1142974214,
 "media":{"all":[{"type":"photo","url":"https://pbs.twimg.com/media/p.jpg","width":100,"height":50},
                 {"type":"video","url":"https://video.twimg.com/v.mp4","thumbnail_url":"https://pbs.twimg.com/media/t.jpg","width":100,"height":50,"duration":3,"formats":[]}]},
 "quote":{"url":"https://x.com/biz/status/7","text":"quoted","author":{"name":"Biz","screen_name":"biz","avatar_url":"https://pbs.twimg.com/profile_images/2/b.jpg"},
   "media":{"all":[{"type":"photo","url":"https://pbs.twimg.com/media/q.jpg","width":10,"height":10}]}}}}`)
		}),
	})
	link := "https://x.com/jack/status/20"
	res, body := do(t, ts, "GET", "/api/v1/unfurl?url="+url.QueryEscape(link), tok, nil)
	if res.StatusCode != http.StatusOK {
		t.Fatalf("unfurl: %d %v", res.StatusCode, body)
	}
	post, _ := body["post"].(map[string]any)
	if post == nil {
		t.Fatalf("no post in %v", body)
	}
	if post["platform"] != "twitter" || post["handle"] != "jack" || post["text"] != "just setting up my twttr" || post["url"] != link {
		t.Errorf("post: %v", post)
	}
	proxied := func(what string, v any, want string) {
		t.Helper()
		s, _ := v.(string)
		if !strings.HasPrefix(s, "/media/proxy/") {
			t.Errorf("%s should load through the proxy, got %q", what, s)
			return
		}
		if got, ok := srv.signedTarget(s); !ok || got != want {
			t.Errorf("%s points at %q, want %q", what, got, want)
		}
	}
	proxied("the avatar", post["avatar"], "https://pbs.twimg.com/profile_images/1/a.jpg")
	media, _ := post["media"].([]any)
	if len(media) != 2 {
		t.Fatalf("media: %v", post["media"])
	}
	proxied("the photo", media[0].(map[string]any)["url"], "https://pbs.twimg.com/media/p.jpg")
	proxied("the video", media[1].(map[string]any)["url"], "https://video.twimg.com/v.mp4")
	proxied("the video's poster", media[1].(map[string]any)["thumb"], "https://pbs.twimg.com/media/t.jpg")
	quote, _ := post["quote"].(map[string]any)
	if quote == nil {
		t.Fatalf("no quote in %v", post)
	}
	proxied("the quoted avatar", quote["avatar"], "https://pbs.twimg.com/profile_images/2/b.jpg")
	proxied("the quoted photo", quote["media"].([]any)[0].(map[string]any)["url"], "https://pbs.twimg.com/media/q.jpg")
	// and the plain card's fields, which an older app draws
	proxied("the card's picture", body["image"], "https://pbs.twimg.com/media/p.jpg")
	proxied("the card's icon", body["icon"], "https://abs.twimg.com/favicons/twitter.3.ico")

	// The same post again comes from the cache, and the cache's copy was not
	// rewritten in place: proxying twice would sign a proxy path.
	_, again := do(t, ts, "GET", "/api/v1/unfurl?url="+url.QueryEscape(link), tok, nil)
	proxied("the avatar, asked again", again["post"].(map[string]any)["avatar"], "https://pbs.twimg.com/profile_images/1/a.jpg")
}

// TikTok signs a file's address for a few hours, so a card holds a name for
// the file and the proxy finds where it is when asked.
func TestMediaProxyFindsNamedFiles(t *testing.T) {
	srv, ts := newTestServer(t)
	tok, _ := register(t, ts, "alice")
	pageReads := 0
	video := []byte("not really an mp4, but served as one")
	page := `<html><body><script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">{"__DEFAULT_SCOPE__":{"webapp.video-detail":{"itemInfo":{"itemStruct":{
 "id":"6718335390845095173","desc":"Guess my name","createTime":"1564234358","author":{"uniqueId":"scout2015","nickname":"Scout","avatarMedium":"https://p16.tiktokcdn.com/avatar.jpeg"},
 "video":{"width":576,"height":1024,"duration":10,"originCover":"https://p16.tiktokcdn.com/cover.jpeg",
  "bitrateInfo":[{"CodecType":"h264","Bitrate":900000,"PlayAddr":{"UrlList":["https://www.tiktok.com/aweme/v1/play/?file_id=h264"]}}]}}}}}}</script></body></html>`
	srv.Unfurl = postUpstream(t, map[string]http.Handler{
		"www.tiktok.com": http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			switch r.URL.Path {
			case "/@scout2015/video/6718335390845095173":
				pageReads++
				w.Header().Set("Content-Type", "text/html; charset=utf-8")
				_, _ = io.WriteString(w, page)
			case "/aweme/v1/play/":
				// the way TikTok does it: only for a browser that came from
				// TikTok, and by sending it on to the file server
				if !strings.Contains(r.UserAgent(), "Chrome/") || !strings.HasPrefix(r.Referer(), "https://www.tiktok.com") {
					http.Error(w, "no", http.StatusForbidden)
					return
				}
				http.Redirect(w, r, "https://v16.tiktokcdn.com/file.mp4", http.StatusFound)
			default:
				http.NotFound(w, r)
			}
		}),
		"v16.tiktokcdn.com": http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "video/mp4")
			w.Header().Set("Accept-Ranges", "bytes")
			if r.Header.Get("Range") == "bytes=0-3" {
				w.Header().Set("Content-Range", fmt.Sprintf("bytes 0-3/%d", len(video)))
				w.WriteHeader(http.StatusPartialContent)
				_, _ = w.Write(video[:4])
				return
			}
			_, _ = w.Write(video)
		}),
	})
	link := "https://www.tiktok.com/@scout2015/video/6718335390845095173"
	res, body := do(t, ts, "GET", "/api/v1/unfurl?url="+url.QueryEscape(link), tok, nil)
	if res.StatusCode != http.StatusOK {
		t.Fatalf("unfurl: %d %v", res.StatusCode, body)
	}
	media := body["post"].(map[string]any)["media"].([]any)[0].(map[string]any)
	signed, _ := media["url"].(string)
	// the card holds the file's name under the post, signed like any address
	if target, ok := srv.signedTarget(signed); !ok || target != link+"#nexa-asset=m0" {
		t.Fatalf("the video is named %q", target)
	}
	got, err := http.Get(ts.URL + signed)
	if err != nil {
		t.Fatal(err)
	}
	data, _ := io.ReadAll(got.Body)
	got.Body.Close()
	if got.StatusCode != http.StatusOK || got.Header.Get("Content-Type") != "video/mp4" || string(data) != string(video) {
		t.Fatalf("named file: %d %s %q", got.StatusCode, got.Header.Get("Content-Type"), data)
	}
	// a player asks for a video in pieces; each piece is the same name again
	req, _ := http.NewRequest("GET", ts.URL+signed, nil)
	req.Header.Set("Range", "bytes=0-3")
	piece, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	data, _ = io.ReadAll(piece.Body)
	piece.Body.Close()
	if piece.StatusCode != http.StatusPartialContent || string(data) != string(video[:4]) {
		t.Errorf("a piece: %d %q", piece.StatusCode, data)
	}
	if pageReads != 1 {
		t.Errorf("the post was read %d times for a card and two fetches of its video", pageReads)
	}
	// a file the post does not have is not found, rather than fetched from anywhere
	gone, err := http.Get(ts.URL + srv.ProxyURL(link+"#nexa-asset=m7"))
	if err != nil {
		t.Fatal(err)
	}
	gone.Body.Close()
	if gone.StatusCode != http.StatusBadGateway {
		t.Errorf("a name with no file: %d", gone.StatusCode)
	}
	// signing a link again keeps it a name
	_, refreshed := do(t, ts, "POST", "/api/v1/proxy/refresh", tok, map[string]any{"urls": []string{signed}})
	again, _ := refreshed["urls"].(map[string]any)[signed].(string)
	if target, ok := srv.signedTarget(again); !ok || target != link+"#nexa-asset=m0" {
		t.Errorf("refreshed name: %q", target)
	}
}

// Some file servers do not say what a file is (a Bluesky video's poster comes
// as "application/octet-stream"). Such a file is judged by what it is, and
// still only passed when that is a picture or a video the proxy passes anyway.
func TestMediaProxyJudgesUntypedFilesByWhatTheyAre(t *testing.T) {
	srv, ts := newTestServer(t)
	// the start of an MP4: a "ftyp" box, then enough to make two pieces
	mp4 := append([]byte{0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2', 0, 0, 0, 0, 'm', 'p', '4', '2', 'i', 's', 'o', 'm'}, make([]byte, 1000)...)
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/octet-stream")
		switch r.URL.Path {
		case "/poster.jpg":
			_, _ = w.Write(pngBytes(8, 8))
		case "/page.jpg":
			_, _ = io.WriteString(w, "<html><script>alert(1)</script></html>")
		case "/drawing.jpg":
			_, _ = io.WriteString(w, `<?xml version="1.0"?><svg xmlns="http://www.w3.org/2000/svg" onload="alert(1)"></svg>`)
		case "/clip", "/unseen":
			var start, end int
			if _, err := fmt.Sscanf(r.Header.Get("Range"), "bytes=%d-%d", &start, &end); err != nil || end >= len(mp4) || start > end {
				_, _ = w.Write(mp4)
				return
			}
			w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, len(mp4)))
			w.WriteHeader(http.StatusPartialContent)
			_, _ = w.Write(mp4[start : end+1])
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()
	tr := &http.Transport{DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
		var d net.Dialer
		return d.DialContext(ctx, network, upstream.Listener.Addr().String())
	}}
	t.Cleanup(tr.CloseIdleConnections)
	srv.Unfurl = &unfurl.Service{Fetcher: &unfurl.Fetcher{Timeout: 5 * time.Second, Transport: tr, Resolver: proxyResolver{[]net.IPAddr{{IP: net.ParseIP("8.8.8.8")}}}}}
	const host = "http://files.test"
	get := func(path, rng string) (int, string, []byte) {
		t.Helper()
		req, _ := http.NewRequest("GET", ts.URL+srv.ProxyURL(host+path), nil)
		if rng != "" {
			req.Header.Set("Range", rng)
		}
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		data, _ := io.ReadAll(res.Body)
		return res.StatusCode, res.Header.Get("Content-Type"), data
	}
	if code, ct, data := get("/poster.jpg", ""); code != http.StatusOK || ct != "image/png" || string(data) != string(pngBytes(8, 8)) {
		t.Errorf("an untyped picture: %d %s, %d bytes", code, ct, len(data))
	}
	// a page or a drawing that can script, served without a type, is still refused
	for _, path := range []string{"/page.jpg", "/drawing.jpg"} {
		if code, ct, _ := get(path, ""); code != http.StatusUnsupportedMediaType {
			t.Errorf("%s got through as %d %s", path, code, ct)
		}
	}
	// A later piece of a file says nothing about what the file is, so one
	// asked for before the start was ever seen is refused...
	if code, _, _ := get("/unseen", "bytes=100-199"); code != http.StatusUnsupportedMediaType {
		t.Errorf("a later piece of a file never seen: %d", code)
	}
	// ...and once the start has been seen, which is what a player asks for
	// first, the pieces after it go by what the start was.
	if code, ct, data := get("/clip", "bytes=0-99"); code != http.StatusPartialContent || ct != "video/mp4" || string(data) != string(mp4[:100]) {
		t.Errorf("the first piece: %d %s, %d bytes", code, ct, len(data))
	}
	if code, ct, data := get("/clip", "bytes=100-199"); code != http.StatusPartialContent || ct != "video/mp4" || string(data) != string(mp4[100:200]) {
		t.Errorf("a later piece: %d %s, %d bytes", code, ct, len(data))
	}
}

// File links go from the sender's unfurl request to signed proxy playback,
// including seeking. The original URL remains only the message's link.
func TestMediaFileLinksAlwaysUseProxy(t *testing.T) {
	srv, ts := newTestServer(t)
	tok, _ := register(t, ts, "alice")
	for _, ct := range []string{"video/mp4", "video/webm", "video/ogg", "video/quicktime", "audio/mpeg", "audio/mp4", "audio/ogg", "audio/wav", "audio/x-wav", "audio/webm", "audio/aac", "audio/flac", "audio/x-flac", "application/octet-stream"} {
		t.Run(ct, func(t *testing.T) {
			data := "ID3\x03\x00\x00\x00\x00\x00\x00audio bytes"
			srv.Unfurl = postUpstream(t, map[string]http.Handler{
				"files.test": http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
					w.Header().Set("Content-Type", ct)
					if r.Header.Get("Range") != "" {
						w.Header().Set("Accept-Ranges", "bytes")
						w.Header().Set("Content-Range", fmt.Sprintf("bytes 0-%d/%d", len(data)-1, len(data)))
						w.WriteHeader(http.StatusPartialContent)
					}
					io.WriteString(w, data)
				}),
			})
			res, preview := do(t, ts, "GET", "/api/v1/unfurl?url=https://files.test/media", tok, nil)
			if res.StatusCode != http.StatusOK {
				t.Fatalf("unfurl: %d %v", res.StatusCode, preview)
			}
			field := "image"
			if preview["type"] == "audio" {
				field = "media"
			}
			link, _ := preview[field].(string)
			if target, ok := srv.signedTarget(link); !ok || target != "https://files.test/media" {
				t.Fatalf("unsigned playback: %v", preview)
			}
			req, _ := http.NewRequest(http.MethodGet, ts.URL+link, nil)
			req.Header.Set("Range", "bytes=0-")
			play, err := http.DefaultClient.Do(req)
			if err != nil {
				t.Fatal(err)
			}
			defer play.Body.Close()
			got, _ := io.ReadAll(play.Body)
			wantType := ct
			if ct == "application/octet-stream" {
				wantType = "audio/mpeg"
			}
			if play.StatusCode != http.StatusPartialContent || play.Header.Get("Content-Type") != wantType || string(got) != data {
				t.Fatalf("playback: %d %s %q", play.StatusCode, play.Header.Get("Content-Type"), got)
			}
		})
	}
}

// Servers that name a picture wrongly (image/jpg, text/plain) still get it
// shown, judged by its bytes; what those bytes do not show to be a picture is
// still refused.
func TestMediaProxyPassesMislabelledPictures(t *testing.T) {
	srv, ts := newTestServer(t)
	avif := append([]byte{0, 0, 0, 0x1c, 'f', 't', 'y', 'p', 'a', 'v', 'i', 'f'}, make([]byte, 64)...)
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/jpg":
			w.Header().Set("Content-Type", "image/jpg")
			_, _ = w.Write(pngBytes(8, 8))
		case "/plain":
			w.Header().Set("Content-Type", "text/plain; charset=utf-8")
			_, _ = w.Write(pngBytes(8, 8))
		case "/avif":
			w.Header().Set("Content-Type", "image/unknown")
			_, _ = w.Write(avif)
		case "/script":
			w.Header().Set("Content-Type", "text/plain")
			_, _ = io.WriteString(w, "<html><script>alert(1)</script></html>")
		case "/svg":
			w.Header().Set("Content-Type", "image/svg")
			_, _ = io.WriteString(w, `<svg xmlns="http://www.w3.org/2000/svg"></svg>`)
		case "/tiff":
			w.Header().Set("Content-Type", "image/tiff")
			_, _ = w.Write([]byte("II*\x00junkjunkjunk"))
		}
	}))
	defer upstream.Close()
	tr := &http.Transport{DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
		var d net.Dialer
		return d.DialContext(ctx, network, upstream.Listener.Addr().String())
	}}
	t.Cleanup(tr.CloseIdleConnections)
	srv.Unfurl = &unfurl.Service{Fetcher: &unfurl.Fetcher{Timeout: 5 * time.Second, Transport: tr, Resolver: proxyResolver{[]net.IPAddr{{IP: net.ParseIP("8.8.8.8")}}}}}
	get := func(path string) (int, string) {
		t.Helper()
		res, err := http.Get(ts.URL + srv.ProxyURL("http://files.test"+path))
		if err != nil {
			t.Fatal(err)
		}
		res.Body.Close()
		return res.StatusCode, res.Header.Get("Content-Type")
	}
	for path, want := range map[string]string{"/jpg": "image/jpeg", "/plain": "image/png", "/avif": "image/avif", "/svg": "image/svg+xml"} {
		if code, ct := get(path); code != http.StatusOK || ct != want {
			t.Errorf("%s: %d %s, want %s", path, code, ct, want)
		}
	}
	for _, path := range []string{"/script", "/tiff"} {
		if code, ct := get(path); code != http.StatusUnsupportedMediaType {
			t.Errorf("%s got through as %d %s", path, code, ct)
		}
	}
}
