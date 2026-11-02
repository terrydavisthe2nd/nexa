package api

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"mime"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// Other sites' players, played through this server. A player's embed page and
// everything it loads (its scripts, its pictures, the audio or video itself)
// are fetched here, out through the egress proxy, and handed to a frame that
// can talk to nothing else. The site sees Cloudflare's address and no cookies,
// never the viewer's; the viewer's browser contacts only this server.
//
// YouTube was the first (youtube_proxy.go); the music players whose terms ask
// for their own player rather than one Nexa draws (Spotify, SoundCloud,
// Bandcamp: music_players.go) go the same way. Each is a frameSite: the path
// it is served under, the hosts it may reach, and what of its traffic is
// reporting about the viewer and so goes nowhere.
//
// The frame is locked down three ways:
//   - the app opens it sandboxed without allow-same-origin, so the player's
//     code runs in an origin of its own and cannot read the app's storage,
//     cookies or page;
//   - every page here carries a Content-Security-Policy that lets it load and
//     connect to this server and nowhere else, so anything the address
//     rewriting below misses is refused rather than fetched from the site;
//   - the shim injected first into the page rewrites the addresses the player
//     builds as it runs, gives it a cookie jar and storage that live only in
//     memory, and drops its beacons.
//
// None of it is an open proxy: addresses carry a token this server signs for
// people who are signed in, each site answers only for its own hosts, and
// none forwards its statistics and logging calls.

const (
	frameTokenTTL    = 6 * time.Hour
	frameMaxPostBody = 1 << 20
	frameMaxRewrite  = 8 << 20
)

// frameSite is one player served through this server.
type frameSite struct {
	// root is the first part of its paths: /<root>/<token>/...
	root string
	// name is the site's, for errors and the log.
	name string
	// signing is what a token for it is signed over, so one site's token
	// opens no other's.
	signing string
	// home is the host its embed page is on: the page the player's own
	// requests say they come from.
	home string
	// hosts says whether the player may reach a host through here.
	hosts func(host string) bool
	// telemetry says whether a request reports on the viewer rather than
	// asks for something; those are answered with nothing.
	telemetry func(host, path string) bool
	// shimHosts is a JavaScript pattern of the hosts' domains, for the
	// addresses the shim finds written in markup the player builds.
	shimHosts string
	// pageReferer, when set, is sent as the embed page's Referer instead of
	// Nexa's own origin (YouTube refuses a player that names no embedder;
	// the others are told nothing of who embeds them).
	pageReferer func(s *Server, r *http.Request) string
}

func (fs *frameSite) prefix(token string) string { return "/" + fs.root + "/" + token }

// sign is the token for one of the site's addresses that stays good until expires.
func (s *Server) frameSign(fs *frameSite, expires int64) string {
	exp := strconv.FormatInt(expires, 36)
	mac := hmac.New(sha256.New, s.Secret)
	mac.Write([]byte(fs.signing))
	mac.Write([]byte(exp))
	return exp + "." + base64.RawURLEncoding.EncodeToString(mac.Sum(nil)[:16])
}

// frameTokenOK reports whether a token is one this server signed for the site and still good.
func (s *Server) frameTokenOK(fs *frameSite, token string) bool {
	exp, _, ok := strings.Cut(token, ".")
	if !ok {
		return false
	}
	n, err := strconv.ParseInt(exp, 36, 64)
	if err != nil || time.Now().Unix() > n {
		return false
	}
	return hmac.Equal([]byte(s.frameSign(fs, n)), []byte(token))
}

// frameToken is a fresh token for the site.
func (s *Server) frameToken(fs *frameSite) string {
	return s.frameSign(fs, time.Now().Add(frameTokenTTL).Unix())
}

func (s *Server) frameClient(fs *frameSite) *http.Client {
	tr := &http.Transport{
		Proxy:                 nil,
		ForceAttemptHTTP2:     true,
		MaxIdleConnsPerHost:   8,
		IdleConnTimeout:       60 * time.Second,
		TLSHandshakeTimeout:   8 * time.Second,
		ResponseHeaderTimeout: 20 * time.Second,
	}
	if s.Egress != nil {
		tr.DialContext = s.Egress.DialContext
	} else {
		tr.DialContext = (&net.Dialer{Timeout: 8 * time.Second}).DialContext
	}
	return &http.Client{
		Transport: tr,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= 3 || req.URL.Scheme != "https" || !fs.hosts(strings.ToLower(req.URL.Hostname())) {
				return http.ErrUseLastResponse
			}
			return nil
		},
	}
}

// frameOrigin is where this server is reached, as the frame's policy names it:
// the address the page holding the player is at, when that is one of Nexa's
// own, and its main address for anything else.
func (s *Server) frameOrigin(r *http.Request) string {
	if addr, kind := s.requestAddress(r); kind != notOurs {
		return addr
	}
	if s.Cfg.PublicURL != "" {
		if u, err := url.Parse(s.Cfg.PublicURL); err == nil && u.Host != "" {
			return u.Scheme + "://" + u.Host
		}
	}
	scheme := "https"
	if r.TLS == nil && !strings.EqualFold(r.Header.Get("X-Forwarded-Proto"), "https") {
		scheme = "http"
	}
	return scheme + "://" + r.Host
}

// frameHeaders are the headers every answer carries: the frame's policy, open
// CORS (the frame's origin is opaque, and nothing here takes credentials), and
// none of what the site said about cookies or framing.
func (s *Server) frameHeaders(w http.ResponseWriter, r *http.Request) {
	h := w.Header()
	self := s.frameOrigin(r)
	h.Set("Content-Security-Policy", strings.Join([]string{
		"default-src 'none'",
		"script-src " + self + " 'self' 'unsafe-inline' 'unsafe-eval' blob:",
		"style-src " + self + " 'self' 'unsafe-inline'",
		"img-src " + self + " 'self' data: blob:",
		"media-src " + self + " 'self' data: blob:",
		"connect-src " + self + " 'self' data: blob:",
		"font-src " + self + " 'self' data:",
		"worker-src " + self + " 'self' blob:",
		"frame-src 'none'",
		"base-uri 'none'",
		"form-action 'none'",
		"frame-ancestors " + self + " 'self'",
		// belt and braces: even opened on its own, the page gets an origin of its own
		"sandbox allow-scripts allow-presentation",
	}, "; "))
	h.Set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), display-capture=(), usb=(), serial=(), hid=(), payment=(), clipboard-read=(), clipboard-write=(), browsing-topics=()")
	h.Set("Referrer-Policy", "no-referrer")
	h.Del("X-Frame-Options")
	h.Set("Cross-Origin-Resource-Policy", "cross-origin")
	// The frame's origin is opaque ("null"). Its requests that ask to carry
	// credentials need that origin named back; they carry none here anyway:
	// the frame has no cookies of this server's, and this sets none.
	origin := r.Header.Get("Origin")
	if origin == "" {
		origin = "*"
	}
	h.Set("Access-Control-Allow-Origin", origin)
	if origin != "*" {
		h.Set("Access-Control-Allow-Credentials", "true")
		h.Add("Vary", "Origin")
	}
	h.Set("Access-Control-Allow-Methods", "GET, HEAD, POST, OPTIONS")
	if want := r.Header.Get("Access-Control-Request-Headers"); want != "" {
		h.Set("Access-Control-Allow-Headers", want)
	} else {
		h.Set("Access-Control-Allow-Headers", "*")
	}
	h.Set("Access-Control-Expose-Headers", "*")
	h.Set("Access-Control-Max-Age", "600")
}

// frameProxyRoute serves /<root>/{token}/p/{host}/{rest...}: everything the
// player asks for after its page.
func (s *Server) frameProxyRoute(fs *frameSite) http.Handler {
	return ipLimited(s.limYouTube, func(w http.ResponseWriter, r *http.Request) {
		token, host := r.PathValue("token"), strings.ToLower(r.PathValue("host"))
		s.frameHeaders(w, r)
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		if !s.frameTokenOK(fs, token) {
			http.Error(w, "expired", http.StatusForbidden)
			return
		}
		if r.Method != http.MethodGet && r.Method != http.MethodHead && r.Method != http.MethodPost {
			http.Error(w, "method", http.StatusMethodNotAllowed)
			return
		}
		if !fs.hosts(host) {
			http.Error(w, "host", http.StatusForbidden)
			return
		}
		path := "/" + r.PathValue("rest")
		if fs.telemetry(host, path) {
			// what the player reports about the viewer goes nowhere
			w.WriteHeader(http.StatusNoContent)
			return
		}
		target := &url.URL{Scheme: "https", Host: host, Path: path, RawQuery: r.URL.RawQuery}
		s.frameForward(fs, w, r, token, target, false)
	})
}

// frameForward fetches target for the frame and passes the answer on,
// rewritten where it is a page or a stylesheet. page is the player's own page,
// which gets the shim.
func (s *Server) frameForward(fs *frameSite, w http.ResponseWriter, r *http.Request, token string, target *url.URL, page bool) {
	s.frameHeaders(w, r)
	var body io.Reader
	if r.Method == http.MethodPost && !page {
		buf, err := io.ReadAll(io.LimitReader(r.Body, frameMaxPostBody+1))
		if err != nil || len(buf) > frameMaxPostBody {
			http.Error(w, "too large", http.StatusRequestEntityTooLarge)
			return
		}
		body = bytes.NewReader(buf)
	}
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	method := r.Method
	if page {
		method = http.MethodGet
	}
	req, err := http.NewRequestWithContext(ctx, method, target.String(), body)
	if err != nil {
		http.Error(w, "bad address", http.StatusBadRequest)
		return
	}
	// Only what the player needs to be answered properly goes on: no cookies,
	// no address, no referrer but the site's own embed page.
	ua := r.Header.Get("User-Agent")
	if ua == "" {
		ua = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
	}
	req.Header.Set("User-Agent", ua)
	req.Header.Set("Accept-Language", "en-US,en;q=0.9")
	for _, k := range []string{"Accept", "Content-Type", "Content-Encoding", "Range", "If-None-Match", "If-Modified-Since", "Authorization", "Client-Token", "App-Platform", "Spotify-App-Version"} {
		if v := r.Header.Get(k); v != "" {
			req.Header.Set(k, v)
		}
	}
	for k, v := range r.Header {
		lk := strings.ToLower(k)
		if strings.HasPrefix(lk, "x-goog-") || strings.HasPrefix(lk, "x-youtube-") {
			req.Header[k] = v
		}
	}
	if page {
		if fs.pageReferer != nil {
			req.Header.Set("Referer", fs.pageReferer(s, r))
		}
	} else {
		req.Header.Set("Origin", "https://"+fs.home)
		req.Header.Set("Referer", "https://"+fs.home+"/")
	}
	res, err := s.frameClient(fs).Do(req)
	if err != nil {
		if s.Log != nil && !errors.Is(err, context.Canceled) {
			s.Log.Warn("player proxy", "site", fs.name, "host", target.Host, "err", err)
		}
		http.Error(w, fs.name+" could not be reached.", http.StatusBadGateway)
		return
	}
	defer res.Body.Close()

	h := w.Header()
	ct := res.Header.Get("Content-Type")
	mt, _, _ := mime.ParseMediaType(ct)
	rewrite := page || mt == "text/html" || mt == "text/css"
	if ct != "" {
		h.Set("Content-Type", ct)
	}
	for _, k := range []string{"Content-Range", "Accept-Ranges", "Last-Modified", "ETag", "Expires", "Cache-Control"} {
		if v := res.Header.Get(k); v != "" {
			h.Set(k, v)
		}
	}
	prefix := fs.prefix(token)
	// a redirect the client would follow is pointed back through here too
	if loc := res.Header.Get("Location"); loc != "" {
		if u, err := target.Parse(loc); err == nil && u.Scheme == "https" && fs.hosts(strings.ToLower(u.Hostname())) {
			h.Set("Location", frameLocal(prefix, u))
		}
	}
	if !rewrite {
		if res.Header.Get("Content-Encoding") == "" && res.ContentLength >= 0 {
			h.Set("Content-Length", strconv.FormatInt(res.ContentLength, 10))
		}
		w.WriteHeader(res.StatusCode)
		if r.Method != http.MethodHead {
			io.Copy(w, res.Body)
		}
		return
	}
	raw, err := io.ReadAll(io.LimitReader(res.Body, frameMaxRewrite+1))
	if err != nil || len(raw) > frameMaxRewrite {
		http.Error(w, fs.name+"'s answer was too large.", http.StatusBadGateway)
		return
	}
	var out []byte
	if mt == "text/css" {
		out = fs.rewriteCSS(raw, prefix, target)
	} else {
		out = fs.rewriteHTML(raw, prefix, target)
		if page {
			out = fs.injectShim(out, prefix, s.frameOrigin(r)+"/")
			h.Set("Content-Type", "text/html; charset=utf-8")
			h.Set("Cache-Control", "no-store")
		}
	}
	h.Set("Content-Length", strconv.Itoa(len(out)))
	w.WriteHeader(res.StatusCode)
	if r.Method != http.MethodHead {
		w.Write(out)
	}
}

// frameLocal is u's address through the proxy under prefix.
func frameLocal(prefix string, u *url.URL) string {
	p := prefix + "/p/" + strings.ToLower(u.Host) + u.EscapedPath()
	if u.RawQuery != "" {
		p += "?" + u.RawQuery
	}
	return p
}

var (
	frameAttrRe   = regexp.MustCompile(`(?i)(\s(?:src|href|poster|action)\s*=\s*)("([^"]*)"|'([^']*)')`)
	frameAbsRe    = regexp.MustCompile(`https?:(?:\\?/){2}([a-zA-Z0-9.-]+)((?:\\?/)[^"'\s<>)\\]*)?`)
	frameCSSURLRe = regexp.MustCompile(`url\(\s*(['"]?)([^'")]+)(['"]?)\s*\)`)
	frameScriptRe = regexp.MustCompile(`(?i)<script(\s[^>]*\bsrc=)`)
	frameHeadRe   = regexp.MustCompile(`(?i)<head[^>]*>`)
)

// rewriteRef points one address written in a page or stylesheet at the proxy,
// or leaves it be when it is not the site's (the policy refuses it then).
func (fs *frameSite) rewriteRef(ref, prefix string, base *url.URL) string {
	ref = strings.TrimSpace(ref)
	if ref == "" || strings.HasPrefix(ref, "#") || strings.HasPrefix(ref, "data:") || strings.HasPrefix(ref, "blob:") || strings.HasPrefix(ref, prefix+"/") {
		return ref
	}
	u, err := base.Parse(strings.ReplaceAll(ref, "&amp;", "&"))
	if err != nil || (u.Scheme != "https" && u.Scheme != "http") || !fs.hosts(strings.ToLower(u.Hostname())) {
		return ref
	}
	u.Scheme = "https"
	return strings.ReplaceAll(frameLocal(prefix, u), "&", "&amp;")
}

// rewriteHTML points the page's attributes, and the plain https:// addresses
// in its inline scripts, at the proxy.
func (fs *frameSite) rewriteHTML(raw []byte, prefix string, base *url.URL) []byte {
	out := frameAttrRe.ReplaceAllFunc(raw, func(m []byte) []byte {
		sub := frameAttrRe.FindSubmatch(m)
		val, quote := string(sub[3]), `"`
		if len(sub[4]) > 0 || (len(sub[3]) == 0 && bytes.HasPrefix(sub[2], []byte("'"))) {
			val, quote = string(sub[4]), `'`
		}
		return []byte(string(sub[1]) + quote + fs.rewriteRef(val, prefix, base) + quote)
	})
	// inline styles name their fonts as //fonts.gstatic.com/..., which only a CSS reading catches
	out = fs.rewriteCSS(out, prefix, base)
	// the frame's origin is opaque, so its scripts are all cross-origin to it:
	// fetched with CORS, an error in one says what it was instead of "Script error."
	out = frameScriptRe.ReplaceAll(out, []byte(`<script crossorigin="anonymous"$1`))
	return fs.rewriteAbsolute(out, prefix)
}

// rewriteAbsolute points every https:// address of the site's in text at the
// proxy, the escaped "https:\/\/" kind in JSON included.
func (fs *frameSite) rewriteAbsolute(raw []byte, prefix string) []byte {
	return frameAbsRe.ReplaceAllFunc(raw, func(m []byte) []byte {
		sub := frameAbsRe.FindSubmatch(m)
		host := strings.ToLower(string(sub[1]))
		if !fs.hosts(host) {
			return m
		}
		escaped := bytes.Contains(m, []byte(`\/`))
		path := string(sub[2])
		local := prefix + "/p/" + host
		if escaped {
			return []byte(strings.ReplaceAll(local, "/", `\/`) + path)
		}
		return []byte(local + path)
	})
}

func (fs *frameSite) rewriteCSS(raw []byte, prefix string, base *url.URL) []byte {
	return frameCSSURLRe.ReplaceAllFunc(raw, func(m []byte) []byte {
		sub := frameCSSURLRe.FindSubmatch(m)
		return []byte("url(" + string(sub[1]) + fs.rewriteRef(string(sub[2]), prefix, base) + string(sub[3]) + ")")
	})
}

// injectShim puts the shim before anything else on the page runs.
func (fs *frameSite) injectShim(page []byte, prefix, embedder string) []byte {
	cfg, _ := json.Marshal(map[string]string{"prefix": prefix, "home": fs.home, "embedder": embedder, "hosts": fs.shimHosts})
	tag := []byte("<script>(function(C){" + frameShim + "})(" + string(cfg) + ");</script>")
	if loc := frameHeadRe.FindIndex(page); loc != nil {
		return append(append(append([]byte{}, page[:loc[1]]...), tag...), page[loc[1]:]...)
	}
	return append(tag, page...)
}

// frameShim runs first in the player's page. It points every address the
// player builds at the proxy as it asks for it, gives it cookies and storage
// that live only in memory (an opaque origin has neither, and players expect
// both), and sends its beacons nowhere. What it misses, the page's policy
// refuses.
const frameShim = `
var P=C.prefix,HOME="https://"+C.home,VIRT=HOME+(location.pathname.indexOf(P+"/")===0?location.pathname.slice(P.length):location.pathname);
function rw(u){
  if(u==null)return u;var s=String(u);
  if(/^(blob:|data:|about:|javascript:|#)/i.test(s)||s.indexOf(P+"/")===0)return s;
  var a;try{
    if(s.indexOf(location.origin+P+"/")===0)return s.slice(location.origin.length);
    if(s.indexOf(location.origin+"/")===0)s=s.slice(location.origin.length);
    a=new URL(s.slice(0,2)==="//"?"https:"+s:s,VIRT);
  }catch(e){return s}
  if(a.protocol!=="https:"&&a.protocol!=="http:")return s;
  return P+"/p/"+a.host+a.pathname+a.search+a.hash;
}
function rwCSS(v){return v==null?v:String(v).replace(/url\(\s*(['"]?)([^'")]+)\1\s*\)/g,function(_,q,u){return "url("+q+rw(u)+q+")"})}
var HOSTS=new RegExp("(?:https?:)?\\/\\/((?:[a-z0-9-]+\\.)*(?:"+C.hosts+"))(?=[\\/\"'\\s?#)]|$)","gi");
function rwHTML(v){return v==null?v:String(v).replace(HOSTS,function(_,h){return P+"/p/"+h.toLowerCase()})}
[["innerHTML",Element],["outerHTML",Element]].forEach(function(x){var d=Object.getOwnPropertyDescriptor(x[1].prototype,x[0]);
  if(d&&d.set)Object.defineProperty(x[1].prototype,x[0],{configurable:true,get:d.get,set:function(v){d.set.call(this,rwHTML(v))}})});
var IAH=Element.prototype.insertAdjacentHTML;Element.prototype.insertAdjacentHTML=function(w,v){return IAH.call(this,w,rwHTML(v))};
var F=window.fetch;window.fetch=function(i,o){
  var init=Object.assign({},o||{},{credentials:"omit",referrerPolicy:"no-referrer"});
  if(i&&typeof i==="object"&&"url" in i){
    // read a Request's body whole: passing the Request on would stream it, which only HTTP/2 carries
    var r=i,m=(init.method||r.method||"GET").toUpperCase();
    return (m==="GET"||m==="HEAD"||init.body!==undefined?Promise.resolve(init.body):r.arrayBuffer()).then(function(b){
      var h=new Headers(r.headers);if(init.headers)new Headers(init.headers).forEach(function(v,k){h.set(k,v)});
      return F.call(window,rw(r.url),Object.assign({method:m,cache:r.cache,redirect:r.redirect,signal:r.signal},init,{headers:h,body:m==="GET"||m==="HEAD"?undefined:b}))})}
  return F.call(window,rw(i),init)};
var XC=Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype,"withCredentials");
if(XC&&XC.set)Object.defineProperty(XMLHttpRequest.prototype,"withCredentials",{configurable:true,get:XC.get,set:function(){XC.set.call(this,false)}});
if(window.FontFace){var FF=window.FontFace;window.FontFace=function(n,src,d){return new FF(n,typeof src==="string"?rwCSS(src):src,d)};window.FontFace.prototype=FF.prototype}
var XO=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){arguments[1]=rw(u);return XO.apply(this,arguments)};
try{navigator.sendBeacon=function(){return true}}catch(e){}
window.open=function(){return null};
function hook(T,p,f){if(!T)return;var d=Object.getOwnPropertyDescriptor(T.prototype,p);if(!d||!d.set)return;
  Object.defineProperty(T.prototype,p,{configurable:true,enumerable:d.enumerable,get:d.get,set:function(v){d.set.call(this,f(v))}})}
[[window.HTMLScriptElement,"src"],[window.HTMLImageElement,"src"],[window.HTMLLinkElement,"href"],[window.HTMLIFrameElement,"src"],
 [window.HTMLMediaElement,"src"],[window.HTMLSourceElement,"src"],[window.HTMLVideoElement,"poster"],[window.HTMLTrackElement,"src"]].forEach(function(x){hook(x[0],x[1],rw)});
hook(window.HTMLImageElement,"srcset",function(v){return String(v).split(",").map(function(p){p=p.trim().split(/\s+/);p[0]=rw(p[0]);return p.join(" ")}).join(", ")});
function rwAttr(n,v){var l=String(n).toLowerCase();
  if(l==="src"||l==="href"||l==="poster"||l==="xlink:href")return rw(v);if(l==="style")return rwCSS(v);if(l==="srcset")return rwHTML(v);return v}
var SA=Element.prototype.setAttribute;Element.prototype.setAttribute=function(n,v){return SA.call(this,n,rwAttr(n,v))};
var SAN=Element.prototype.setAttributeNS;Element.prototype.setAttributeNS=function(ns,n,v){return SAN.call(this,ns,n,rwAttr(n,v))};
var HS=Object.getOwnPropertyDescriptor(HTMLElement.prototype,"style");
if(HS&&HS.set)Object.defineProperty(HTMLElement.prototype,"style",{configurable:true,get:HS.get,set:function(v){HS.set.call(this,typeof v==="string"?rwCSS(v):v)}});
if(window.CSSStyleSheet){var SS=CSSStyleSheet.prototype,IR=SS.insertRule,RS=SS.replaceSync,RP=SS.replace;
  SS.insertRule=function(r,i){return IR.call(this,rwCSS(rwHTML(r)),i)};if(RS)SS.replaceSync=function(t){return RS.call(this,rwCSS(rwHTML(t)))};if(RP)SS.replace=function(t){return RP.call(this,rwCSS(rwHTML(t)))}}
var AC=Node.prototype.appendChild;Node.prototype.appendChild=function(c){if(this.nodeName==="STYLE"&&c&&c.nodeType===3)c.data=rwHTML(c.data);return AC.call(this,c)};
var TC=Object.getOwnPropertyDescriptor(Node.prototype,"textContent");
if(TC&&TC.set)Object.defineProperty(Node.prototype,"textContent",{configurable:true,get:TC.get,set:function(v){TC.set.call(this,this.nodeName==="STYLE"?rwHTML(v):v)}});
var CS=window.CSSStyleDeclaration&&CSSStyleDeclaration.prototype;
if(CS){var SP=CS.setProperty;CS.setProperty=function(n,v,p){return SP.call(this,n,rwCSS(v),p)};
  ["backgroundImage","background","cssText","content","listStyleImage","borderImage","maskImage"].forEach(function(p){
    var d=Object.getOwnPropertyDescriptor(CS,p);if(d&&d.set)Object.defineProperty(CS,p,{configurable:true,get:d.get,set:function(v){d.set.call(this,rwCSS(v))}})})}
if(window.MediaMetadata){var MM=window.MediaMetadata;var art=function(a){return Array.isArray(a)?a.map(function(x){return Object.assign({},x,{src:rw(x.src)})}):a};
  window.MediaMetadata=function(o){return new MM(o&&o.artwork?Object.assign({},o,{artwork:art(o.artwork)}):o)};window.MediaMetadata.prototype=MM.prototype;
  var AD=Object.getOwnPropertyDescriptor(MM.prototype,"artwork");if(AD&&AD.set)Object.defineProperty(MM.prototype,"artwork",{configurable:true,get:AD.get,set:function(v){AD.set.call(this,art(v))}})}
["Worker","SharedWorker"].forEach(function(n){var W=window[n];if(!W)return;window[n]=function(u,o){return new W(rw(u),o)};window[n].prototype=W.prototype});
var jar={};try{Object.defineProperty(Document.prototype,"cookie",{configurable:true,
  get:function(){return Object.keys(jar).map(function(k){return k+"="+jar[k]}).join("; ")},
  set:function(v){var kv=String(v).split(";")[0],i=kv.indexOf("=");if(i>0)jar[kv.slice(0,i).trim()]=kv.slice(i+1).trim()}})}catch(e){}
function mem(){var d={};return{get length(){return Object.keys(d).length},key:function(i){return Object.keys(d)[i]||null},
  getItem:function(k){return Object.prototype.hasOwnProperty.call(d,k)?d[k]:null},setItem:function(k,v){d[k]=String(v)},
  removeItem:function(k){delete d[k]},clear:function(){d={}}}}
["localStorage","sessionStorage"].forEach(function(n){try{Object.defineProperty(window,n,{configurable:true,value:mem()})}catch(e){}});
["indexedDB","caches","cookieStore"].forEach(function(n){try{Object.defineProperty(window,n,{configurable:true,value:undefined})}catch(e){}});
try{Object.defineProperty(navigator,"serviceWorker",{configurable:true,value:undefined})}catch(e){}
// the page it is embedded in, as a player may need to be told: Nexa's origin, nothing of the viewer's
try{Object.defineProperty(Document.prototype,"referrer",{configurable:true,get:function(){return C.embedder}})}catch(e){}
try{var AO=[C.embedder.replace(/\/$/,"")];AO.contains=function(o){return AO.indexOf(o)>=0};AO.item=function(i){return AO[i]||null};
  Object.defineProperty(location,"ancestorOrigins",{configurable:true,get:function(){return AO}})}catch(e){}
`
