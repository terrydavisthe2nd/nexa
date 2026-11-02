package api

import (
	"bufio"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"io"
	"math"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/unfurl"
)

// The media proxy fetches remote images on the user's behalf so that opening a
// chat never reveals their address to whoever hosts the picture. Only URLs this
// server itself signed can be fetched, so it is not an open proxy: the client
// never learns the key and cannot mint links of its own.

const (
	maxProxyBytes  = unfurl.MaxMediaBytes
	proxyCacheCtl  = "public, max-age=604800, immutable"
	proxySigLength = 16 // bytes of HMAC kept, base64url encoded
)

// proxyTTL is how long a signed media link stays usable. Long enough for a
// page someone left open overnight, short enough that a link lifted from a
// response does not become a permanent way to make this server fetch things.
const proxyTTL = 24 * time.Hour

func (s *Server) proxyRoutes(mux *http.ServeMux) {
	// Fetching on a caller's behalf costs this server bandwidth in both
	// directions, so it is metered per address whether or not anyone is
	// signed in.
	mux.Handle("GET /media/proxy/{sig}/{url}", ipLimited(s.limProxy, s.handleMediaProxy))
	mux.Handle("HEAD /media/proxy/{sig}/{url}", ipLimited(s.limProxy, s.handleMediaProxy))
	// under /proxy, not /media: both clients send /media/... paths unversioned
	mux.Handle("POST /api/v1/proxy/refresh", s.authed(s.handleMediaRefresh))
}

func (s *Server) proxySignature(expires, encoded string) string {
	mac := hmac.New(sha256.New, s.Secret)
	mac.Write([]byte("media-proxy/v2"))
	mac.Write([]byte(expires))
	mac.Write([]byte{0})
	mac.Write([]byte(encoded))
	return base64.RawURLEncoding.EncodeToString(mac.Sum(nil)[:proxySigLength])
}

// proxySignatureV1 is how links were signed before they carried a deadline:
// over the URL alone. Nothing is fetched with one any more. They are only
// recognised so they can be signed again, because every message sent before
// the deadline arrived carries them in its encrypted embeds.
func (s *Server) proxySignatureV1(encoded string) string {
	mac := hmac.New(sha256.New, s.Secret)
	mac.Write([]byte("media-proxy/v1"))
	mac.Write([]byte(encoded))
	return base64.RawURLEncoding.EncodeToString(mac.Sum(nil)[:proxySigLength])
}

// signedTarget is the remote address inside a link this server signed, whatever
// its deadline and in either signing scheme: "/media/proxy/{sig}/{encoded}", or
// the same behind this server's public origin. ok is false for anything this
// server did not sign.
func (s *Server) signedTarget(link string) (string, bool) {
	if len(s.Secret) == 0 {
		return "", false
	}
	path := link
	if base := strings.TrimRight(s.Cfg.PublicURL, "/"); base != "" {
		path = strings.TrimPrefix(path, base)
	}
	rest, ok := strings.CutPrefix(path, "/media/proxy/")
	if !ok {
		return "", false
	}
	sig, encoded, ok := strings.Cut(rest, "/")
	if !ok || encoded == "" || strings.ContainsAny(encoded, "/?#") {
		return "", false
	}
	valid := false
	if expires, mac, found := strings.Cut(sig, "-"); found && hmac.Equal([]byte(mac), []byte(s.proxySignature(expires, encoded))) {
		valid = true
	} else if hmac.Equal([]byte(sig), []byte(s.proxySignatureV1(encoded))) {
		// a v1 signature is base64url and may itself contain a "-"
		valid = true
	}
	if !valid {
		return "", false
	}
	raw, err := base64.RawURLEncoding.DecodeString(encoded)
	if err != nil {
		return "", false
	}
	u, err := url.Parse(string(raw))
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return "", false
	}
	return string(raw), true
}

type mediaRefreshReq struct {
	URLs []string `json:"urls"`
}

// handleMediaRefresh signs again, for someone signed in, links this server
// signed before. A link in a message lives inside its end-to-end encryption,
// where the server cannot renew it, so with a deadline of a day every picture,
// GIF and video in a conversation stopped loading the day after it was sent,
// and everything sent before deadlines existed stopped at once. Anyone signed
// in can have any address signed through /unfurl already, so this grants
// nothing new; an anonymous copy of a link still dies on its deadline.
func (s *Server) handleMediaRefresh(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	if ok, retry := s.limRefresh.Allow("media-refresh:" + me.ID.String()); !ok {
		w.Header().Set("Retry-After", retryAfter(retry))
		WriteError(w, ErrRateLimited)
		return
	}
	var req mediaRefreshReq
	if err := ReadJSON(w, r, &req, 512<<10); err != nil {
		WriteError(w, err)
		return
	}
	if len(req.URLs) == 0 || len(req.URLs) > 100 {
		WriteError(w, E(http.StatusBadRequest, "bad_request", "Send between 1 and 100 links."))
		return
	}
	// only what this server signed comes back; anything else is left out
	out := make(map[string]string, len(req.URLs))
	for _, link := range req.URLs {
		if len(link) > 8192 {
			continue
		}
		if target, ok := s.signedTarget(link); ok {
			out[link] = s.ProxyURL(target)
		}
	}
	WriteJSON(w, http.StatusOK, map[string]any{"urls": out})
}

// ProxyURL rewrites a remote URL into a signed path on this server. Anything
// that is not an absolute http(s) URL (including our own paths) is returned
// unchanged so callers can apply it blindly.
func (s *Server) ProxyURL(raw string) string {
	if raw == "" || len(s.Secret) == 0 {
		return raw
	}
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return raw
	}
	encoded := base64.RawURLEncoding.EncodeToString([]byte(raw))
	// The expiry rides in the signature segment rather than a segment of its
	// own, so the path keeps the shape clients already read a filename out of.
	expires := strconv.FormatInt(time.Now().Add(proxyTTL).Unix(), 10)
	return "/media/proxy/" + expires + "-" + s.proxySignature(expires, encoded) + "/" + encoded
}

func (s *Server) handleMediaProxy(w http.ResponseWriter, r *http.Request) {
	encoded := r.PathValue("url")
	expires, mac, ok := strings.Cut(r.PathValue("sig"), "-")
	if !ok || !hmac.Equal([]byte(mac), []byte(s.proxySignature(expires, encoded))) {
		http.Error(w, "bad signature", http.StatusForbidden)
		return
	}
	// A signature with no expiry is a capability that never goes away: lift one
	// out of any response and this server fetches for you, for good.
	if at, err := strconv.ParseInt(expires, 10, 64); err != nil || time.Now().Unix() > at {
		http.Error(w, "expired", http.StatusForbidden)
		return
	}
	rawBytes, err := base64.RawURLEncoding.DecodeString(encoded)
	if err != nil {
		http.Error(w, "bad url", http.StatusBadRequest)
		return
	}
	target, err := url.Parse(string(rawBytes))
	if err != nil || (target.Scheme != "http" && target.Scheme != "https") {
		http.Error(w, "bad url", http.StatusBadRequest)
		return
	}
	if s.Unfurl == nil {
		http.Error(w, "proxy unavailable", http.StatusServiceUnavailable)
		return
	}
	// A social post's files move (their sites sign each address for a few
	// hours), so a card names them instead; the name is traded here for
	// where the file is today. See internal/unfurl/assets.go.
	var extra map[string]string
	if asset, named, err := s.Unfurl.Asset(r.Context(), target); named {
		if err != nil {
			http.Error(w, "upstream failed", http.StatusBadGateway)
			return
		}
		target, extra = asset.URL, asset.Header
	}
	f := s.Unfurl.MediaFetcher()
	if err := f.Validate(r.Context(), target); err != nil {
		http.Error(w, "blocked", http.StatusForbidden)
		return
	}
	// No deadline on the whole transfer, which a slow phone can stretch past any
	// sensible one; the request is dropped only once it stops making progress.
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	watch := f.Watch(cancel)
	defer watch.Stop()
	req, err := http.NewRequestWithContext(ctx, r.Method, target.String(), nil)
	if err != nil {
		http.Error(w, "bad url", http.StatusBadRequest)
		return
	}
	req.Header.Set("User-Agent", f.UA())
	req.Header.Set("Accept", "image/*,video/*,audio/*;q=0.8,*/*;q=0.1")
	for k, v := range extra {
		req.Header.Set(k, v)
	}
	// a Range request lets the browser seek inside proxied video, and a long
	// one comes through a piece at a time instead of being refused
	if rng := r.Header.Get("Range"); rng != "" {
		req.Header.Set("Range", narrowRange(rng, maxProxyBytes))
	}
	res, err := f.StreamClient().Do(req)
	if err != nil {
		http.Error(w, "upstream failed", http.StatusBadGateway)
		return
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK && res.StatusCode != http.StatusPartialContent {
		http.Error(w, "upstream said "+strconv.Itoa(res.StatusCode), http.StatusBadGateway)
		return
	}
	ct := strings.ToLower(strings.TrimSpace(strings.Split(res.Header.Get("Content-Type"), ";")[0]))
	body := io.Reader(res.Body)
	ct = unfurl.ImageAlias(ct)
	if unfurl.NeedsSniff(ct) {
		ct, body = sniffedType(target.String(), res)
	}
	if !unfurl.PlayableMediaType(ct) && !unfurl.ProxyImageTypes[ct] {
		http.Error(w, "unsupported media type", http.StatusUnsupportedMediaType)
		return
	}
	if n, err := strconv.ParseInt(res.Header.Get("Content-Length"), 10, 64); err == nil && n > maxProxyBytes {
		http.Error(w, "too large", http.StatusRequestEntityTooLarge)
		return
	}
	h := w.Header()
	h.Set("Content-Type", ct)
	if ct == "image/svg+xml" {
		// SVG is inert in image elements; direct navigation downloads it.
		h.Set("Content-Disposition", `attachment; filename="preview.svg"`)
	}
	h.Set("Cache-Control", proxyCacheCtl)
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("Content-Security-Policy", "default-src 'none'; sandbox")
	h.Set("Cross-Origin-Resource-Policy", "same-origin")
	if v := res.Header.Get("Content-Length"); v != "" {
		h.Set("Content-Length", v)
	}
	if v := res.Header.Get("Content-Range"); v != "" {
		h.Set("Content-Range", v)
	}
	if v := res.Header.Get("Accept-Ranges"); v != "" {
		h.Set("Accept-Ranges", v)
	}
	w.WriteHeader(res.StatusCode)
	if r.Method == http.MethodHead {
		return
	}
	_, _ = io.Copy(w, watch.Body(io.LimitReader(body, maxProxyBytes)))
}

// sniffedTypes remembers what untyped files turned out to be, by address.
// Only the start of a file says what it is, and a video is fetched in pieces:
// the first piece is read for its type and the later ones go by it.
var sniffedTypes = struct {
	sync.Mutex
	m map[string]string
}{m: map[string]string{}}

// sniffedType is the type of an untyped file, judged by what the file is, and
// the body to send on with the bytes that were read to tell. The answer is
// only used if it is a type the proxy passes anyway, so a page or a script
// served without a type is still refused.
func sniffedType(address string, res *http.Response) (string, io.Reader) {
	fromStart := res.StatusCode == http.StatusOK || strings.HasPrefix(strings.TrimSpace(res.Header.Get("Content-Range")), "bytes 0-")
	if !fromStart {
		sniffedTypes.Lock()
		defer sniffedTypes.Unlock()
		return sniffedTypes.m[address], res.Body
	}
	head := bufio.NewReaderSize(res.Body, 512)
	peek, _ := head.Peek(512)
	ct := unfurl.SniffMedia(peek)
	if ct != "" {
		sniffedTypes.Lock()
		if len(sniffedTypes.m) >= 2000 {
			clear(sniffedTypes.m)
		}
		sniffedTypes.m[address] = ct
		sniffedTypes.Unlock()
	}
	return ct, head
}

// narrowRange limits a single "bytes=start-[end]" range to at most max bytes.
// A video player opens with "bytes=0-", the whole file; answered with the first
// part, it asks for the rest as it plays, so a video over max still plays
// while no one response is larger than max. Suffix and multi-part ranges pass
// unchanged.
func narrowRange(h string, max int64) string {
	spec, ok := strings.CutPrefix(strings.TrimSpace(h), "bytes=")
	if !ok || strings.Contains(spec, ",") {
		return h
	}
	startS, endS, ok := strings.Cut(spec, "-")
	start, err := strconv.ParseInt(strings.TrimSpace(startS), 10, 64)
	if !ok || err != nil || start < 0 {
		return h
	}
	if start > math.MaxInt64-max {
		// start + max would wrap negative and the malformed range would be
		// forwarded upstream as written
		return h
	}
	last := start + max - 1
	if endS = strings.TrimSpace(endS); endS != "" {
		end, err := strconv.ParseInt(endS, 10, 64)
		if err != nil || end < start {
			return h
		}
		last = min(last, end)
	}
	return "bytes=" + strconv.FormatInt(start, 10) + "-" + strconv.FormatInt(last, 10)
}
