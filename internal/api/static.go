package api

import (
	"fmt"
	"io"
	"mime"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"evilion.xyz/chat/internal/auth"
)

// CSP is the content security policy for the web app. hash-wasm needs
// 'wasm-unsafe-eval'; inline style attributes (role colours) need
// 'unsafe-inline' for styles; embeds may load https images/media directly;
// only the privacy-enhanced YouTube host may be framed, and Cloudflare
// Turnstile may load its captcha script and frame when captchas are on.
//
// A person's own look (custom CSS, and themes loaded from links) may pull in
// https stylesheets and web fonts with @import and @font-face, so style-src
// and font-src allow https: as img-src already did. Plugins are ES modules
// the app imports from blob: URLs it makes itself, out of code the person
// approved and whose hash it checked (web/src/lib/look/plugins), so
// script-src allows blob:. Only script already running in this page can
// make a blob: URL, so that lets no new script in from anywhere else, and
// script-src still refuses https: sources and eval, so a plugin cannot
// fetch more code past the hash it was pinned to.
const CSP = "default-src 'self'; script-src 'self' 'wasm-unsafe-eval' blob: https://challenges.cloudflare.com; style-src 'self' 'unsafe-inline' https:; img-src 'self' https: data: blob:; media-src 'self' https: blob:; connect-src 'self' wss: https: blob:; frame-src 'self' https://challenges.cloudflare.com; worker-src 'self' blob:; font-src 'self' data: https:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'"

func init() {
	_ = mime.AddExtensionType(".webmanifest", "application/manifest+json")
	_ = mime.AddExtensionType(".wasm", "application/wasm")
	_ = mime.AddExtensionType(".woff2", "font/woff2")
	_ = mime.AddExtensionType(".js", "text/javascript; charset=utf-8")
	_ = mime.AddExtensionType(".mjs", "text/javascript; charset=utf-8")
}

func (s *Server) staticRoutes(mux *http.ServeMux) {
	mux.Handle("/", s.staticHandler())
}

// allowPublicPage meters the pages the static handler renders for a shared
// link. It answers false having already written the refusal.
func (s *Server) allowPublicPage(w http.ResponseWriter, r *http.Request) bool {
	ok, retry := s.limGeneric.Allow("page:" + auth.RateKey(r))
	if ok {
		return true
	}
	secs := int(retry.Seconds() + 0.999)
	if secs < 1 {
		secs = 1
	}
	w.Header().Set("Retry-After", strconv.Itoa(secs))
	w.Header().Set("Cache-Control", "no-store")
	WriteError(w, ErrRateLimited)
	return false
}

// staticHandler serves the built SPA: real files from WebDist (hashed assets
// immutable, everything else no-cache) and index.html for any other path.
func (s *Server) staticHandler() http.Handler {
	dist, _ := filepath.Abs(s.Cfg.WebDist)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			WriteError(w, E(http.StatusMethodNotAllowed, "method_not_allowed", "Method not allowed."))
			return
		}
		p := path.Clean("/" + r.URL.Path)
		if strings.HasPrefix(p, "/api/") || p == "/api" || p == "/gateway" || strings.HasPrefix(p, "/media/") {
			WriteError(w, ErrNotFound)
			return
		}
		// Nothing beginning with a dot is part of the app. A build directory
		// picks up .DS_Store, .vite and the like, and this handler served them
		// with a sniffed content type — /desktop/ and /android/ refuse dotfiles
		// through their filename patterns, and this is the same rule.
		// `.well-known` is the exception every site has: those paths are
		// answered by routes of their own, and one that is not belongs to
		// nobody either.
		if hiddenPath(p) {
			WriteError(w, ErrNotFound)
			return
		}
		moved := s.movedTag(r)
		if p != "/" {
			fp := filepath.Join(dist, filepath.FromSlash(p))
			// at an address Nexa moved from, every page carries the way on (below)
			if strings.HasPrefix(fp, dist+string(filepath.Separator)) && (moved == "" || filepath.Ext(fp) != ".html") {
				if st, err := os.Stat(fp); err == nil && !st.IsDir() {
					serveStaticFile(w, r, fp, p, st.ModTime())
					return
				}
			}
		}
		// A build's own files either exist or they do not. Answering with the
		// app's HTML instead turns a missing script into a page the browser
		// refuses to run, and any cache in front of this, the browser's own or
		// a CDN's, then keeps that HTML under the script's address.
		if strings.HasPrefix(p, "/models/") || strings.HasPrefix(p, "/assets/") || strings.HasPrefix(p, "/twemoji/") || p == "/sw.js" || p == "/download-sw.js" || p == "/version.json" {
			w.Header().Set("Cache-Control", "no-store")
			WriteError(w, ErrNotFound)
			return
		}
		// Downloads are answered by the service worker. One that reaches here
		// has no worker behind it, and must not load a second copy of the app
		// into the hidden frame that asked.
		if strings.HasPrefix(p, "/__download/") {
			w.Header().Set("Cache-Control", "no-store")
			WriteError(w, ErrNotFound)
			return
		}
		// An invite link gets its own page head, so pasting it anywhere else
		// shows what it leads to rather than a bare address. These two pages do
		// real work per hit — they read the invite and the server behind it —
		// and both are public, so they are metered per address while the rest
		// of the SPA's assets stay unmetered.
		if code, ok := strings.CutPrefix(p, "/invite/"); ok && code != "" && !strings.Contains(code, "/") {
			if !s.allowPublicPage(w, r) {
				return
			}
			if s.serveInvitePage(w, r, dist, code) {
				return
			}
		}
		// A guest link says what it is when pasted elsewhere, and nothing about whose call it is.
		if code, ok := strings.CutPrefix(p, "/call/"); ok && code != "" && !strings.Contains(code, "/") {
			if !s.allowPublicPage(w, r) {
				return
			}
			if s.servePlainPage(w, r, dist, "Join a Voice Call", "You're invited to a voice call. No account needed: pick a name and join.") {
				return
			}
		}
		// So does a note's share link. The server cannot read the note, and the
		// link's key is in the part after the # that never reaches it, so the
		// preview says only what the link is.
		if token, ok := strings.CutPrefix(p, "/note/"); ok && token != "" && !strings.Contains(token, "/") {
			if !s.allowPublicPage(w, r) {
				return
			}
			if s.servePlainPage(w, r, dist, "A Shared Note", "Someone shared a note with you on Nexa. Open the link to read it.") {
				return
			}
		}
		// A profile link shows whose profile it is when pasted elsewhere
		// (profile_links.go). Like an invite's page it reads the database per
		// hit, so it is metered the same way.
		if name, ok := strings.CutPrefix(p, profilePath); ok && name != "" && !strings.Contains(name, "/") {
			if !s.allowPublicPage(w, r) {
				return
			}
			if s.serveProfilePage(w, r, dist, name) {
				return
			}
		}
		// The front page is what gets shared when someone recommends Nexa, and
		// it was the one public page that unfurled as a bare address.
		if p == "/" && s.serveHomePage(w, r, dist) {
			return
		}
		index := filepath.Join(dist, "index.html")
		f, err := os.Open(index)
		if err != nil {
			w.Header().Set("Content-Type", "text/plain; charset=utf-8")
			w.Header().Set("Cache-Control", "no-store")
			w.WriteHeader(http.StatusServiceUnavailable)
			_, _ = w.Write([]byte("The web app has not been built yet. Run `make web` in the chat directory.\n"))
			return
		}
		defer f.Close()
		st, _ := f.Stat()
		h := w.Header()
		h.Set("Content-Type", "text/html; charset=utf-8")
		// This page names the files of one particular build. A cache that
		// holds on to it hands out a build whose files have moved on.
		h.Set("Cache-Control", "no-cache, must-revalidate")
		h.Set("CDN-Cache-Control", "no-store")
		h.Set("Content-Security-Policy", CSP)
		if moved != "" {
			raw, err := io.ReadAll(f)
			if err != nil {
				WriteError(w, err)
				return
			}
			body := string(raw)
			if i := strings.Index(body, "</head>"); i >= 0 {
				body = body[:i] + moved + "\n" + body[i:]
			}
			if r.Method == http.MethodHead {
				w.WriteHeader(http.StatusOK)
				return
			}
			_, _ = w.Write([]byte(body))
			return
		}
		var mod time.Time
		if st != nil {
			mod = st.ModTime()
		}
		http.ServeContent(w, r, "index.html", mod, f)
	})
}

// movedTag is what a page carries at an address Nexa moved away from: where
// Nexa is now. The app sends the browser on to the same page there before it
// draws anything (web/src/lib/move.ts), where it signs in again, except inside
// a desktop app from before the move, which goes on working at the old address
// until it updates. Elsewhere it is nothing.
func (s *Server) movedTag(r *http.Request) string {
	if _, kind := s.requestAddress(r); kind != ourMoved {
		return ""
	}
	return "\n<meta name=\"nexa-moved-to\" content=\"" + escapeAttr(strings.TrimRight(s.Cfg.PublicURL, "/")) + "\">"
}

func serveStaticFile(w http.ResponseWriter, r *http.Request, fp, urlPath string, mod time.Time) {
	f, err := os.Open(fp)
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	defer f.Close()
	h := w.Header()
	if ct := mime.TypeByExtension(filepath.Ext(fp)); ct != "" {
		h.Set("Content-Type", ct)
	}
	// Hashed bundles and the emoji artwork are named after their content, so
	// they never change under the same URL.
	if strings.HasPrefix(urlPath, "/assets/") || strings.HasPrefix(urlPath, "/twemoji/") {
		h.Set("Cache-Control", "public, max-age=31536000, immutable")
		// The worker response carries the CSP that governs its fetches. A CDN
		// may otherwise retain an old policy under an unchanged asset URL.
		if strings.HasPrefix(filepath.Base(fp), "transcribe.worker-") {
			h.Set("Cache-Control", "no-cache, must-revalidate")
			h.Set("CDN-Cache-Control", "no-store")
		}
	} else if urlPath == "/version.json" {
		// Names the build being served, which open pages ask for to learn of
		// a deploy (web/src/lib/appUpdate.ts): a copy kept anywhere, a CDN's
		// included, would hide the deploy from them.
		h.Set("Cache-Control", "no-store")
		h.Set("CDN-Cache-Control", "no-store")
	} else {
		h.Set("Cache-Control", "no-cache")
	}
	// A worker enforces the policy on its script response. Trusted app scripts
	// need the app's fetch and WASM permissions too; the default deny policy
	// would prevent the transcription worker from downloading its model.
	if ext := filepath.Ext(fp); ext == ".html" || ext == ".js" || ext == ".mjs" {
		policy := CSP
		if urlPath == "/demo.html" {
			// Only the isolated demo may be embedded by our own homepage.
			policy = strings.Replace(policy, "frame-ancestors 'none'", "frame-ancestors 'self'", 1)
		}
		h.Set("Content-Security-Policy", policy)
	}
	if filepath.Base(fp) == "sw.js" {
		h.Set("Service-Worker-Allowed", "/")
	}
	http.ServeContent(w, r, filepath.Base(fp), mod, f)
}

// escapeAttr keeps text safe inside a double-quoted HTML attribute.
var escapeAttr = strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;", `"`, "&quot;").Replace

// serveInvitePage renders index.html with the invite's own preview tags in the
// head. It reports whether it answered the request.
func (s *Server) serveInvitePage(w http.ResponseWriter, r *http.Request, dist, code string) bool {
	res := s.inviteUnfurl(r, s.Cfg.PublicURL+"/invite/"+code)
	if res == nil {
		return false
	}
	raw, err := os.ReadFile(filepath.Join(dist, "index.html"))
	if err != nil {
		return false
	}
	title := res.Title
	if title == "" {
		title = "An invitation"
	}
	desc := res.Description
	if res.MemberCount > 0 {
		desc = fmt.Sprintf("%s · %d member%s", desc, res.MemberCount, plural(res.MemberCount))
	}
	var tags strings.Builder
	tags.WriteString("\n<title>" + escapeAttr("Join "+title) + "</title>")
	meta := func(prop, content string) {
		if content == "" {
			return
		}
		tags.WriteString("\n<meta property=\"" + prop + "\" content=\"" + escapeAttr(content) + "\">")
	}
	meta("og:type", "website")
	meta("og:site_name", res.SiteName)
	meta("og:title", "Join "+title)
	meta("og:description", desc)
	meta("og:url", res.URL)
	// A server's banner is the wide picture a preview can show across its
	// top; without one, its icon is the small square beside the words.
	card := "summary"
	if res.Banner != "" {
		meta("og:image", res.Banner+"?size=1024")
		card = "summary_large_image"
	} else {
		meta("og:image", res.Image)
	}
	tags.WriteString("\n<meta name=\"description\" content=\"" + escapeAttr(desc) + "\">")
	tags.WriteString("\n<meta name=\"twitter:card\" content=\"" + card + "\">")
	tags.WriteString(s.movedTag(r))

	body := string(raw)
	if i := strings.Index(body, "</head>"); i >= 0 {
		body = body[:i] + tags.String() + "\n" + body[i:]
	}
	h := w.Header()
	h.Set("Content-Type", "text/html; charset=utf-8")
	h.Set("Cache-Control", "no-cache, must-revalidate")
	h.Set("CDN-Cache-Control", "no-store")
	h.Set("Content-Security-Policy", CSP)
	if r.Method == http.MethodHead {
		w.WriteHeader(http.StatusOK)
		return true
	}
	_, _ = w.Write([]byte(body))
	return true
}

// serveHomePage renders the front page with preview tags of its own: a title,
// what Nexa is, and a picture of the app. They are written here rather than
// into index.html, which every page shares — an invite page carrying the
// front page's tags ahead of its own would unfurl as the front page.
func (s *Server) serveHomePage(w http.ResponseWriter, r *http.Request, dist string) bool {
	raw, err := os.ReadFile(filepath.Join(dist, "index.html"))
	if err != nil {
		return false
	}
	const title = "Nexa: private chat with all the fun left in"
	const desc = "A free chat app with servers, voice and video calls, and custom emoji and stickers. Messages, files and calls are encrypted on your device, and signing up needs no email or phone number."
	var tags strings.Builder
	// The front page is drawn on the app's darker canvas; until the page's
	// code has arrived the loading screen would show the app's grey instead.
	// This paints the page's background from the first frame, and the page
	// removes it once it is drawn (HomePage.tsx, useFrontDocument).
	tags.WriteString("\n<style id=\"front-paint\">:root{--bg-base:#120f18;background:#120f18;color-scheme:dark}</style>")
	meta := func(prop, content string) {
		tags.WriteString("\n<meta property=\"" + prop + "\" content=\"" + escapeAttr(content) + "\">")
	}
	meta("og:type", "website")
	meta("og:site_name", s.issuer())
	meta("og:title", title)
	meta("og:description", desc)
	// A preview's picture has to be a whole address; without the public one
	// there is no picture rather than a broken one.
	if base := strings.TrimRight(s.Cfg.PublicURL, "/"); base != "" {
		meta("og:url", base+"/")
		meta("og:image", base+"/home/app.png")
		meta("og:image:width", "1440")
		meta("og:image:height", "900")
		meta("og:image:alt", "The Nexa app: a server's channels, a conversation and who is online")
		tags.WriteString("\n<meta name=\"twitter:card\" content=\"summary_large_image\">")
		// the same page at every address: search engines are told which one is Nexa's own
		tags.WriteString("\n<link rel=\"canonical\" href=\"" + escapeAttr(base+"/") + "\">")
	} else {
		tags.WriteString("\n<meta name=\"twitter:card\" content=\"summary\">")
	}
	tags.WriteString(s.movedTag(r))
	body := string(raw)
	if i := strings.Index(body, "</head>"); i >= 0 {
		body = body[:i] + tags.String() + "\n" + body[i:]
	}
	h := w.Header()
	h.Set("Content-Type", "text/html; charset=utf-8")
	h.Set("Cache-Control", "no-cache, must-revalidate")
	h.Set("CDN-Cache-Control", "no-store")
	h.Set("Content-Security-Policy", CSP)
	if r.Method == http.MethodHead {
		w.WriteHeader(http.StatusOK)
		return true
	}
	_, _ = w.Write([]byte(body))
	return true
}

// servePlainPage renders index.html with preview tags that only say what
// kind of link it is: a guest link to a voice call, with no name, server or
// people in it, or a note's share link. Neither link says anything about
// where it leads to whoever does not open it.
func (s *Server) servePlainPage(w http.ResponseWriter, r *http.Request, dist, title, desc string) bool {
	raw, err := os.ReadFile(filepath.Join(dist, "index.html"))
	if err != nil {
		return false
	}
	var tags strings.Builder
	tags.WriteString("\n<title>" + escapeAttr(title) + "</title>")
	for _, m := range [][2]string{{"og:type", "website"}, {"og:site_name", s.issuer()}, {"og:title", title}, {"og:description", desc}} {
		tags.WriteString("\n<meta property=\"" + m[0] + "\" content=\"" + escapeAttr(m[1]) + "\">")
	}
	tags.WriteString("\n<meta name=\"description\" content=\"" + escapeAttr(desc) + "\">")
	tags.WriteString("\n<meta name=\"twitter:card\" content=\"summary\">")
	tags.WriteString(s.movedTag(r))
	body := string(raw)
	if i := strings.Index(body, "</head>"); i >= 0 {
		body = body[:i] + tags.String() + "\n" + body[i:]
	}
	h := w.Header()
	h.Set("Content-Type", "text/html; charset=utf-8")
	h.Set("Cache-Control", "no-cache, must-revalidate")
	h.Set("CDN-Cache-Control", "no-store")
	h.Set("Content-Security-Policy", CSP)
	if r.Method == http.MethodHead {
		w.WriteHeader(http.StatusOK)
		return true
	}
	_, _ = w.Write([]byte(body))
	return true
}

func plural(n int) string {
	if n == 1 {
		return ""
	}
	return "s"
}

// hiddenPath reports whether any segment of a cleaned path starts with a dot,
// which is how a build directory's own leftovers are told from the app's
// files. `.well-known` is excepted: it is a real, public convention, and its
// paths have routes of their own.
func hiddenPath(p string) bool {
	for _, seg := range strings.Split(strings.TrimPrefix(p, "/"), "/") {
		if strings.HasPrefix(seg, ".") && seg != ".well-known" {
			return true
		}
	}
	return false
}
