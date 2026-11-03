package api

import (
	"context"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"evilion.xyz/chat/internal/store"
	"evilion.xyz/chat/internal/unfurl"
)

// Profile links: https://nexa.fish/@username. Anyone may open one, signed in
// or not. The app shows the person's own profile card (web/src/pages/
// ProfilePage.tsx), or the profile inside Nexa for someone signed in; pasted
// in a chat it is drawn as that card, and pasted anywhere else it unfurls to
// their name, a line of their about me and their picture.
//
// A link shows what any signed-in account can already see of someone, less
// what says where they are right now (their status) and their encryption key.

// profilePath is what a profile link's path starts with.
const profilePath = "/@"

func (s *Server) profileLinkRoutes(mux *http.ServeMux) {
	// public, so metered per address like an invite's preview
	mux.Handle("GET /api/v1/profiles/{username}", s.stepUpIP(s.stepLookup, s.handleSharedProfile))
}

// sharedProfile is the profile a link shows, or nil when it shows none: no
// such account, one no person holds (Nexa's own, the webhooks'), or one that
// is disabled, deleted or banned.
func (s *Server) sharedProfile(ctx context.Context, username string) *store.PublicUser {
	username = strings.ToLower(strings.TrimSpace(username))
	if !ValidUsername(username) {
		return nil
	}
	u, err := s.Store.UserByUsername(ctx, username)
	if err != nil || u == nil || u.Flags&(store.NotPeople|store.FlagDisabled|store.FlagDeleted) != 0 {
		return nil
	}
	if _, ban, err := s.Store.ActiveRestrictions(ctx, u.ID, time.Now().UnixMilli()); err != nil || ban != nil {
		return nil
	}
	p := u.Public()
	p.PublicKey = nil
	p.GroupLinkOnly = false
	p.StatusText, p.StatusEmoji, p.StatusExpiresAt = "", "", nil
	return &p
}

func (s *Server) handleSharedProfile(w http.ResponseWriter, r *http.Request) {
	p := s.sharedProfile(r.Context(), r.PathValue("username"))
	if p == nil {
		WriteError(w, E(http.StatusNotFound, "profile_not_found", "There is no profile at this link."))
		return
	}
	// the widgets show on the card a link opens, as they do in the app
	widgets, err := s.Store.UserWidgets(r.Context(), p.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	// badges too, worked out from the account behind the link
	badges := []store.Badge{}
	if u, err := s.Store.UserByID(r.Context(), p.ID); err == nil {
		badges = s.profileBadges(r, u)
	}
	w.Header().Set("Cache-Control", "no-cache")
	WriteJSON(w, http.StatusOK, struct {
		*store.PublicUser
		Widgets json.RawMessage `json:"widgets"`
		Badges  []store.Badge   `json:"badges"`
	}{p, widgetsJSON(widgets), badges})
}

// profileLinkName is the username a link to one of our profiles names, at any
// of our addresses, or "".
func (s *Server) profileLinkName(raw string) string {
	name := strings.ToLower(s.ownPath(raw, profilePath))
	if !ValidUsername(name) {
		return ""
	}
	return name
}

// profileUnfurl is the preview of a profile link, answered from our own
// records like an invite's: no fetch, and the app draws it as the person's
// card. Nil when the link is not one of ours or leads to no profile.
func (s *Server) profileUnfurl(ctx context.Context, raw string) *unfurl.Result {
	name := s.profileLinkName(raw)
	if name == "" {
		return nil
	}
	p := s.sharedProfile(ctx, name)
	if p == nil {
		return nil
	}
	out := &unfurl.Result{URL: raw, Type: "profile", Username: p.Username, SiteName: s.issuer(), Title: profileTitle(p), Description: aboutLine(p.AboutMe, 200)}
	if p.AvatarID != nil {
		out.Image = s.Cfg.PublicURL + "/media/avatar/" + p.AvatarID.String()
	}
	if p.BannerID != nil {
		out.Banner = s.Cfg.PublicURL + "/media/banner/" + p.BannerID.String()
	}
	if c := accentHex(p.AccentColor); c != "" {
		out.Color = c
	}
	return out
}

// profileTitle is how a preview names someone: "Maya (@maya)", or the
// username alone when their name is written the same.
func profileTitle(p *store.PublicUser) string {
	name := strings.TrimSpace(p.DisplayName)
	if name == "" || name == p.Username {
		return "@" + p.Username
	}
	return name + " (@" + p.Username + ")"
}

var (
	// a spoiler stays hidden in a preview as in the app
	spoilerRe = regexp.MustCompile(`\|\|[\s\S]*?\|\|`)
	// custom emoji keep their name; mentions of people, roles and channels go
	customEmojiRe = regexp.MustCompile(`<a?:([A-Za-z0-9_]{1,32}):\d+>`)
	mentionRe     = regexp.MustCompile(`<(?:@[!&]?|#)\d+>`)
	// emphasis marks, and the marks that start a heading, a quote or small
	// text; a single underscore is left, being part of so many names
	markRe  = regexp.MustCompile("[*~`]+|__|^#{1,3} |^> |^-# ")
	spaceRe = regexp.MustCompile(`\s+`)
)

// aboutLine is the start of an about me as one plain line for a preview: no
// markdown, no spoilers, no mentions, at most max characters.
func aboutLine(about string, max int) string {
	about = spoilerRe.ReplaceAllString(about, "")
	about = customEmojiRe.ReplaceAllString(about, ":$1:")
	about = mentionRe.ReplaceAllString(about, "")
	lines := strings.Split(about, "\n")
	for i, l := range lines {
		lines[i] = markRe.ReplaceAllString(strings.TrimSpace(l), "")
	}
	line := strings.TrimSpace(spaceRe.ReplaceAllString(strings.Join(lines, " "), " "))
	if utf8.RuneCountInString(line) <= max {
		return line
	}
	r := []rune(line)[:max-1]
	return strings.TrimRight(string(r), " ,.;:") + "…"
}

// accentHex is a profile colour as #rrggbb, or "".
func accentHex(c *int) string {
	if c == nil || *c < 0 || *c > 0xffffff {
		return ""
	}
	h := strconv.FormatInt(int64(*c), 16)
	return "#" + strings.Repeat("0", 6-len(h)) + h
}

var themeColorRe = regexp.MustCompile(`<meta name="theme-color" content="[^"]*"\s*/?>`)

// serveProfilePage renders index.html with a profile's own preview tags. It
// reports whether it answered: a link to no profile gets the app's page as it
// is, which says so.
func (s *Server) serveProfilePage(w http.ResponseWriter, r *http.Request, dist, username string) bool {
	p := s.sharedProfile(r.Context(), username)
	if p == nil {
		return false
	}
	raw, err := os.ReadFile(filepath.Join(dist, "index.html"))
	if err != nil {
		return false
	}
	base := strings.TrimRight(s.Cfg.PublicURL, "/")
	title := profileTitle(p)
	desc := aboutLine(p.AboutMe, 200)
	if desc == "" {
		name := strings.TrimSpace(p.DisplayName)
		if name == "" {
			name = p.Username
		}
		desc = name + " is on Nexa."
	}
	var tags strings.Builder
	tags.WriteString("\n<title>" + escapeAttr(title+" on Nexa") + "</title>")
	meta := func(prop, content string) {
		if content == "" {
			return
		}
		tags.WriteString("\n<meta property=\"" + prop + "\" content=\"" + escapeAttr(content) + "\">")
	}
	meta("og:type", "profile")
	meta("og:site_name", s.issuer())
	meta("og:title", title)
	meta("og:description", desc)
	meta("profile:username", p.Username)
	if base != "" {
		meta("og:url", base+profilePath+p.Username)
		// their picture beside the words; without one, Nexa's own mark
		if p.AvatarID != nil {
			meta("og:image", base+"/media/avatar/"+p.AvatarID.String()+"?size=256")
		} else {
			meta("og:image", base+"/icons/icon-512.png")
		}
	}
	tags.WriteString("\n<meta name=\"description\" content=\"" + escapeAttr(desc) + "\">")
	tags.WriteString("\n<meta name=\"twitter:card\" content=\"summary\">")
	// a link is shared on purpose; a search engine's listing of everyone is not
	tags.WriteString("\n<meta name=\"robots\" content=\"noindex\">")
	tags.WriteString(s.movedTag(r))

	body := string(raw)
	// their colour, where a preview has a bar or a tint to put it in
	if c := accentHex(p.AccentColor); c != "" {
		body = themeColorRe.ReplaceAllLiteralString(body, `<meta name="theme-color" content="`+c+`" />`)
	}
	if i := strings.Index(body, "</head>"); i >= 0 {
		body = body[:i] + tags.String() + "\n" + body[i:]
	}
	h := w.Header()
	h.Set("Content-Type", "text/html; charset=utf-8")
	h.Set("Cache-Control", "no-cache, must-revalidate")
	h.Set("CDN-Cache-Control", "no-store")
	h.Set("Content-Security-Policy", CSP)
	h.Set("X-Robots-Tag", "noindex")
	if r.Method == http.MethodHead {
		w.WriteHeader(http.StatusOK)
		return true
	}
	_, _ = w.Write([]byte(body))
	return true
}
