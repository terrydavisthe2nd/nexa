package gateway

import (
	"context"
	"encoding/json"
	"math"
	"net/url"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"

	"golang.org/x/time/rate"

	"evilion.xyz/chat/internal/games"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/textutil"
)

// Activity is something a user is doing, shown under their name: a game, a
// song, a video. It mirrors Discord's Rich Presence activity; the desktop app
// hears it from the programs on the user's computer. Images are already
// resolved to addresses on this server's media proxy.
type Activity struct {
	ApplicationID     string              `json:"application_id,omitempty"`
	Name              string              `json:"name"`
	Type              int                 `json:"type"`
	StatusDisplayType *int                `json:"status_display_type,omitempty"`
	Details           string              `json:"details,omitempty"`
	DetailsURL        string              `json:"details_url,omitempty"`
	State             string              `json:"state,omitempty"`
	StateURL          string              `json:"state_url,omitempty"`
	Timestamps        *ActivityTimestamps `json:"timestamps,omitempty"`
	Assets            *ActivityAssets     `json:"assets,omitempty"`
	Party             *ActivityParty      `json:"party,omitempty"`
	Buttons           []ActivityButton    `json:"buttons,omitempty"`
	Instance          bool                `json:"instance,omitempty"`
	IconURL           string              `json:"icon_url,omitempty"`
	// Detected marks a game the desktop app recognised running, which said
	// nothing about itself; GameID is IGDB's id for it when one was found, so
	// every viewer opens the same game's page without asking.
	Detected bool   `json:"detected,omitempty"`
	GameID   string `json:"game_id,omitempty"`
	// Source is "discord" for an activity heard from the person's linked
	// Discord account rather than from the desktop app.
	Source    string `json:"source,omitempty"`
	CreatedAt int64  `json:"created_at"`
}

// ActivityTimestamps are in milliseconds. A start alone counts up; a start and
// an end on a listening or watching activity draw a progress bar.
type ActivityTimestamps struct {
	Start *int64 `json:"start,omitempty"`
	End   *int64 `json:"end,omitempty"`
}

type ActivityAssets struct {
	LargeImage string `json:"large_image,omitempty"`
	LargeText  string `json:"large_text,omitempty"`
	LargeURL   string `json:"large_url,omitempty"`
	SmallImage string `json:"small_image,omitempty"`
	SmallText  string `json:"small_text,omitempty"`
	SmallURL   string `json:"small_url,omitempty"`
}

type ActivityParty struct {
	ID   string `json:"id,omitempty"`
	Size []int  `json:"size,omitempty"`
}

type ActivityButton struct {
	Label string `json:"label"`
	URL   string `json:"url"`
}

// activityInput is an activity as the desktop app sends it, before cleaning.
type activityInput struct {
	ApplicationID     string `json:"application_id"`
	Name              string `json:"name"`
	Type              int    `json:"type"`
	StatusDisplayType *int   `json:"status_display_type"`
	Details           string `json:"details"`
	DetailsURL        string `json:"details_url"`
	State             string `json:"state"`
	StateURL          string `json:"state_url"`
	Instance          bool   `json:"instance"`
	// A detected game: the store that knows it and its id there, or the IGDB
	// game someone chose for a game they added.
	Detected   bool   `json:"detected"`
	Store      string `json:"store"`
	StoreID    string `json:"store_id"`
	GameID     string `json:"game_id"`
	Timestamps *struct {
		Start *float64 `json:"start"`
		End   *float64 `json:"end"`
	} `json:"timestamps"`
	Assets *struct {
		LargeImage string `json:"large_image"`
		LargeText  string `json:"large_text"`
		LargeURL   string `json:"large_url"`
		SmallImage string `json:"small_image"`
		SmallText  string `json:"small_text"`
		SmallURL   string `json:"small_url"`
	} `json:"assets"`
	Party *struct {
		ID   string    `json:"id"`
		Size []float64 `json:"size"`
	} `json:"party"`
	Buttons []struct {
		Label string `json:"label"`
		URL   string `json:"url"`
	} `json:"buttons"`
}

// appInfo is what is known about an activity's Discord application.
type appInfo struct {
	Name    string
	IconURL string
	Asset   func(key string) string // an art asset's CDN address by name or id, or ""
}

// Discord's limits for Rich Presence.
const (
	maxActivities    = 5
	maxActivityText  = 128
	maxActivityLink  = 256
	maxActivityImage = 1024
	maxButtonLabel   = 32
	maxButtonURL     = 512
	maxPartySize     = 1_000_000
	activityWindow   = 10 * 365 * 24 * time.Hour
)

// newGameLimit lets a connection have a few games looked up at once, as a
// computer that has just started does, and few after: nobody starts a new game
// every minute for long.
func newGameLimit() *rate.Limiter { return rate.NewLimiter(rate.Every(time.Minute), 8) }

var (
	appIDPattern     = regexp.MustCompile(`^[0-9]{17,20}$`)
	spotifyIDPattern = regexp.MustCompile(`^[A-Za-z0-9]{1,64}$`)
	youtubeIDPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{6,20}$`)
	twitchPattern    = regexp.MustCompile(`^[A-Za-z0-9_]{1,25}$`)
)

// normalizeActivity cleans one activity to Discord's rules and resolves its
// images through proxy. It reports false when nothing showable is left.
func normalizeActivity(in activityInput, app *appInfo, now time.Time, proxy func(string) string) (Activity, bool) {
	out := Activity{CreatedAt: now.UnixMilli()}
	if appIDPattern.MatchString(in.ApplicationID) {
		out.ApplicationID = in.ApplicationID
	}
	// Discord shows a Rich Presence activity under its application's name; a
	// name the program sends only stands in when the application is unknown
	if app != nil {
		out.Name = cleanLine(app.Name, maxActivityText)
	}
	if out.Name == "" {
		out.Name = cleanLine(in.Name, maxActivityText)
	}
	if out.Name == "" {
		return Activity{}, false
	}
	switch in.Type {
	case 0, 1, 2, 3, 5:
		out.Type = in.Type
	}
	if d := in.StatusDisplayType; d != nil && *d >= 0 && *d <= 2 {
		v := *d
		out.StatusDisplayType = &v
	}
	out.Details = cleanLine(in.Details, maxActivityText)
	out.State = cleanLine(in.State, maxActivityText)
	out.DetailsURL = cleanLink(in.DetailsURL, maxActivityLink)
	out.StateURL = cleanLink(in.StateURL, maxActivityLink)
	out.Instance = in.Instance
	out.Detected = in.Detected

	if ts := in.Timestamps; ts != nil {
		start, end := activityTime(ts.Start, now), activityTime(ts.End, now)
		if start != nil && end != nil && *end <= *start {
			end = nil
		}
		if start != nil || end != nil {
			out.Timestamps = &ActivityTimestamps{Start: start, End: end}
		}
	}

	var asset func(string) string
	if app != nil {
		asset = app.Asset
	}
	if as := in.Assets; as != nil {
		a := ActivityAssets{
			LargeImage: proxyImage(activityImage(as.LargeImage, asset), proxy),
			LargeText:  cleanLine(as.LargeText, maxActivityText),
			LargeURL:   cleanLink(as.LargeURL, maxActivityLink),
			SmallImage: proxyImage(activityImage(as.SmallImage, asset), proxy),
			SmallText:  cleanLine(as.SmallText, maxActivityText),
			SmallURL:   cleanLink(as.SmallURL, maxActivityLink),
		}
		if a != (ActivityAssets{}) {
			out.Assets = &a
		}
	}

	if p := in.Party; p != nil {
		party := ActivityParty{ID: cleanLine(p.ID, maxActivityText)}
		if len(p.Size) == 2 && wholeNumber(p.Size[0]) && wholeNumber(p.Size[1]) && p.Size[0] >= 0 && p.Size[1] >= 1 && p.Size[0] <= p.Size[1] && p.Size[1] <= maxPartySize {
			party.Size = []int{int(p.Size[0]), int(p.Size[1])}
		}
		if party.ID != "" || party.Size != nil {
			out.Party = &party
		}
	}

	// Discord only ever shows the first two buttons, so a third never stands in for a broken one
	for i, b := range in.Buttons {
		if i == 2 {
			break
		}
		label, link := cleanLine(b.Label, maxButtonLabel), cleanLink(b.URL, maxButtonURL)
		if label != "" && link != "" {
			out.Buttons = append(out.Buttons, ActivityButton{Label: label, URL: link})
		}
	}

	if app != nil && app.IconURL != "" {
		out.IconURL = proxyImage(app.IconURL, proxy)
	}
	return out, true
}

// cleanLine is text for one line: control characters and direction overrides
// removed, line breaks turned into spaces, and cut to max characters.
func cleanLine(s string, max int) string {
	s = strings.NewReplacer("\r\n", " ", "\n", " ", "\r", " ", "\t", " ").Replace(s)
	out, ok := textutil.CleanText(s, max)
	if !ok && out != "" {
		out = strings.TrimSpace(string([]rune(out)[:max]))
	}
	if !utf8.ValidString(out) {
		return ""
	}
	return out
}

// cleanLink keeps an absolute http(s) address of at most max bytes.
func cleanLink(s string, max int) string {
	s = strings.TrimSpace(s)
	if s == "" || len(s) > max {
		return ""
	}
	u, err := url.Parse(s)
	if err != nil || (u.Scheme != "https" && u.Scheme != "http") || u.Host == "" || u.User != nil {
		return ""
	}
	return u.String()
}

// activityTime reads a timestamp in seconds or milliseconds as milliseconds,
// or nil when it is not a time within ten years of now.
func activityTime(v *float64, now time.Time) *int64 {
	if v == nil || math.IsNaN(*v) || math.IsInf(*v, 0) || *v <= 0 {
		return nil
	}
	ms := *v
	// Discord's libraries send seconds as often as milliseconds; nothing in milliseconds is below 1e12 (2001)
	if ms < 1e12 {
		ms *= 1000
	}
	if ms > float64(math.MaxInt64/2) {
		return nil
	}
	t := int64(ms)
	if d := time.Duration(t-now.UnixMilli()) * time.Millisecond; d > activityWindow || d < -activityWindow {
		return nil
	}
	return &t
}

// activityImage turns an activity's image value into the address it names:
// an https URL, one of Discord's media proxy forms, a Spotify, YouTube or
// Twitch picture, or one of the application's art assets.
func activityImage(key string, asset func(string) string) string {
	key = strings.TrimSpace(key)
	if key == "" || len(key) > maxActivityImage {
		return ""
	}
	switch {
	case strings.HasPrefix(key, "https://"):
		return cleanLink(key, maxActivityImage)
	case strings.HasPrefix(key, "mp:external/"):
		// mp:external/<hash>/<scheme>/<host and path>
		parts := strings.SplitN(strings.TrimPrefix(key, "mp:external/"), "/", 3)
		if len(parts) == 3 && (parts[1] == "https" || parts[1] == "http") {
			return cleanLink(parts[1]+"://"+parts[2], maxActivityImage)
		}
		return ""
	case strings.HasPrefix(key, "mp:"):
		return cleanLink("https://media.discordapp.net/"+strings.TrimPrefix(key, "mp:"), maxActivityImage)
	case strings.HasPrefix(key, "spotify:"):
		if id := strings.TrimPrefix(key, "spotify:"); spotifyIDPattern.MatchString(id) {
			return "https://i.scdn.co/image/" + id
		}
		return ""
	case strings.HasPrefix(key, "youtube:"):
		if id := strings.TrimPrefix(key, "youtube:"); youtubeIDPattern.MatchString(id) {
			return "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg"
		}
		return ""
	case strings.HasPrefix(key, "twitch:"):
		if name := strings.TrimPrefix(key, "twitch:"); twitchPattern.MatchString(name) {
			return "https://static-cdn.jtvnw.net/previews-ttv/live_user_" + strings.ToLower(name) + "-640x360.jpg"
		}
		return ""
	}
	if asset == nil {
		return ""
	}
	return asset(key)
}

func proxyImage(u string, proxy func(string) string) string {
	if u == "" {
		return ""
	}
	if proxy == nil {
		return u
	}
	return proxy(u)
}

func wholeNumber(f float64) bool { return f == math.Trunc(f) && !math.IsInf(f, 0) }

// queueActivities hands a new set of activities to the resolver. Only the
// latest set matters, so one arriving while another is resolved replaces
// whatever was still waiting.
func (c *conn) queueActivities(raw []json.RawMessage) {
	c.actMu.Lock()
	c.actPending = &raw
	if c.actBusy {
		c.actMu.Unlock()
		return
	}
	c.actBusy = true
	c.actMu.Unlock()
	// A panic here must not leave actBusy set, or this connection would never
	// resolve another activity; it is not worth ending the connection over.
	// Only a panic clears it — on the ordinary way out resolveLoop has already
	// cleared it, and clearing it again could let a second resolver start
	// beside one that had just begun.
	c.hub.safely("activity resolver", func() {
		defer func() {
			if r := recover(); r != nil {
				c.actMu.Lock()
				c.actBusy = false
				c.actMu.Unlock()
				panic(r) // safely logs it
			}
		}()
		c.resolveLoop()
	})
}

func (c *conn) resolveLoop() {
	for {
		c.actMu.Lock()
		p := c.actPending
		c.actPending = nil
		if p == nil {
			c.actBusy = false
			c.actMu.Unlock()
			return
		}
		prev := c.acts
		c.actMu.Unlock()

		list := c.hub.resolveActivities(*p, prev, c.gameLimit.Allow)

		select {
		case <-c.closed:
			c.actMu.Lock()
			c.actBusy = false
			c.actMu.Unlock()
			return
		default:
		}
		c.actMu.Lock()
		c.acts, c.actsAt = list, time.Now()
		c.actMu.Unlock()
		c.hub.activitiesChanged(c)
	}
}

// dropActivities forgets a closed connection's activities and reports whether it had any.
func (c *conn) dropActivities() bool {
	c.actMu.Lock()
	defer c.actMu.Unlock()
	had := len(c.acts) > 0
	c.acts, c.actPending = nil, nil
	if c.actTimer != nil {
		c.actTimer.Stop()
		c.actTimer = nil
	}
	return had
}

// ResolveActivities cleans and resolves activities exactly as the activities
// op would, without showing them to anyone: the desktop settings page previews
// what others will see.
func (h *Hub) ResolveActivities(raw []json.RawMessage) []Activity {
	if len(raw) > maxActivities {
		raw = raw[:maxActivities]
	}
	return h.resolveActivities(raw, nil, nil)
}

// resolveActivities cleans a set of activities, naming their applications and
// resolving their art. Something that carries on (the same application and
// name) keeps the time it began. A detected game gives way to a program
// reporting that game itself, and what is left of them is looked up on IGDB
// for its page and cover; identify says whether one more may be, nil meaning
// always (the caller holds its own limit).
func (h *Hub) resolveActivities(raw []json.RawMessage, prev []Activity, identify func() bool) []Activity {
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	now := time.Now()
	out := make([]Activity, 0, len(raw))
	wanted := map[int]activityInput{}
	for _, r := range raw {
		if len(r) > maxActivityBytes {
			continue
		}
		var in activityInput
		if json.Unmarshal(r, &in) != nil {
			continue
		}
		var info *appInfo
		if h.Apps != nil && appIDPattern.MatchString(in.ApplicationID) {
			if app, err := h.Apps.Lookup(ctx, in.ApplicationID); err == nil && app != nil {
				info = &appInfo{Name: app.Name, IconURL: app.IconURL, Asset: app.AssetURL}
			}
		}
		a, ok := normalizeActivity(in, info, now, h.ProxyURL)
		if !ok {
			continue
		}
		for _, p := range prev {
			if p.ApplicationID == a.ApplicationID && p.Name == a.Name {
				a.CreatedAt = p.CreatedAt
				break
			}
		}
		if a.Detected {
			wanted[len(out)] = in
		}
		out = append(out, a)
		if len(out) == maxActivities {
			break
		}
	}
	return h.identifyDetected(ctx, out, wanted, prev, identify)
}

// selfReported says whether an activity is a program telling us about the game itself.
func selfReported(a Activity) bool {
	return !a.Detected && (a.Type == 0 || a.Type == 5)
}

// identifyDetected drops the detected games a program already reports, and
// finds the rest on IGDB. Only the server knows what an application is called,
// so this is where a game's own Rich Presence wins by name; the desktop app
// has already let it win where it could tell by process.
func (h *Hub) identifyDetected(ctx context.Context, list []Activity, wanted map[int]activityInput, prev []Activity, identify func() bool) []Activity {
	if len(wanted) == 0 {
		return list
	}
	reported := map[string]bool{}
	for _, a := range list {
		if selfReported(a) {
			reported[games.FoldName(a.Name)] = true
		}
	}
	out := make([]Activity, 0, len(list))
	for i, a := range list {
		in, detected := wanted[i]
		if !detected {
			out = append(out, a)
			continue
		}
		if reported[games.FoldName(a.Name)] {
			continue
		}
		a.Type = 0
		if p, ok := sameDetected(prev, a); ok && (in.GameID == "" || in.GameID == p.GameID) {
			// a game carrying on is not looked up again every time something else changes
			a.GameID, a.IconURL = p.GameID, p.IconURL
		} else if h.IdentifyGame != nil && (identify == nil || identify()) {
			// its own allowance of time, so a slow answer never holds the rest back for long
			idCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
			a.GameID, a.IconURL = h.IdentifyGame(idCtx, a.Name, in.Store, in.StoreID, in.GameID)
			cancel()
		}
		out = append(out, a)
	}
	return out
}

func sameDetected(prev []Activity, a Activity) (Activity, bool) {
	for _, p := range prev {
		if p.Detected && p.Name == a.Name && p.GameID != "" {
			return p, true
		}
	}
	return Activity{}, false
}

// activitiesChanged tells the user's co-members, no more often than the
// limiter lets through; a change that has to wait goes out with the latest set.
func (h *Hub) activitiesChanged(c *conn) {
	c.actMu.Lock()
	if c.actTimer != nil {
		c.actMu.Unlock()
		return
	}
	delay := c.actLimit.Reserve().Delay()
	if delay == 0 {
		c.actMu.Unlock()
		ctx, cancel := background()
		defer cancel()
		h.broadcastUser(ctx, c)
		return
	}
	c.actTimer = time.AfterFunc(delay, func() {
		defer h.recoverTimer("activity flush")
		c.actMu.Lock()
		c.actTimer = nil
		c.actMu.Unlock()
		ctx, cancel := background()
		defer cancel()
		h.broadcastUser(ctx, c)
	})
	c.actMu.Unlock()
}

// activitiesOf is what the user is doing: as reported by the connection that
// spoke most recently, then whatever their linked Discord account adds.
func (h *Hub) activitiesOf(uid ids.ID) []Activity {
	h.mu.RLock()
	var best []Activity
	var at time.Time
	for c := range h.conns[uid] {
		c.actMu.Lock()
		if len(c.acts) > 0 && c.actsAt.After(at) {
			best, at = c.acts, c.actsAt
		}
		c.actMu.Unlock()
	}
	h.mu.RUnlock()
	h.linkedMu.Lock()
	linked := h.linked[uid]
	h.linkedMu.Unlock()
	return mergeActivities(best, linked)
}

// mergeActivities puts a linked Discord account's activities after the
// desktop app's, less what the desktop app already shows: the same
// application, or the same name and kind. A program the desktop app hears
// also reaches Discord through its relay, so both report it; the desktop
// app's copy is kept while it lasts, and Discord's shows once it is gone. The
// one exception is a game the desktop app only recognised running, which gives
// way to Discord's copy when that says more about what is going on.
func mergeActivities(own, linked []Activity) []Activity {
	if len(linked) == 0 {
		return own
	}
	kept := make([]Activity, 0, len(own))
	for _, o := range own {
		if !o.Detected || !saysMore(linked, o) {
			kept = append(kept, o)
		}
	}
	own = kept
	out := make([]Activity, 0, min(maxActivities, len(own)+len(linked)))
	out = append(out, own...)
	for _, l := range linked {
		if len(out) >= maxActivities {
			break
		}
		dup := false
		for _, o := range own {
			if (l.ApplicationID != "" && l.ApplicationID == o.ApplicationID) || (l.Type == o.Type && strings.EqualFold(l.Name, o.Name)) {
				dup = true
				break
			}
		}
		if !dup {
			out = append(out, l)
		}
	}
	return out
}

// saysMore finds a linked activity for the same game that carries more than the game's name.
func saysMore(linked []Activity, o Activity) bool {
	for _, l := range linked {
		if l.Type == o.Type && games.FoldName(l.Name) == games.FoldName(o.Name) && (l.Details != "" || l.State != "" || l.Assets != nil) {
			return true
		}
	}
	return false
}

// SetLinkedActivities replaces what a person's linked Discord account says
// they are doing. They are cleaned and resolved exactly as the desktop app's
// are, and everyone who sees the person hears of a change, no more than once
// a second.
func (h *Hub) SetLinkedActivities(uid ids.ID, raw []json.RawMessage) {
	if len(raw) > maxActivities {
		raw = raw[:maxActivities]
	}
	h.linkedMu.Lock()
	prev := h.linked[uid]
	h.linkedMu.Unlock()
	var list []Activity
	if len(raw) > 0 {
		list = h.resolveActivities(raw, prev, nil)
		for i := range list {
			list[i].Source = "discord"
		}
	}
	h.linkedMu.Lock()
	same := sameActivities(prev, list)
	if len(list) == 0 {
		delete(h.linked, uid)
	} else {
		h.linked[uid] = list
	}
	h.linkedMu.Unlock()
	if !same {
		h.linkedChanged(uid)
	}
}

// LinkedActivities is what a person's linked Discord account is showing, for tests and the settings page.
func (h *Hub) LinkedActivities(uid ids.ID) []Activity {
	h.linkedMu.Lock()
	defer h.linkedMu.Unlock()
	return append([]Activity(nil), h.linked[uid]...)
}

func sameActivities(a, b []Activity) bool {
	if len(a) != len(b) {
		return false
	}
	x, _ := json.Marshal(a)
	y, _ := json.Marshal(b)
	return string(x) == string(y)
}

// linkedChanged tells everyone who sees the person, at most once a second: a
// change that has to wait goes out with whatever is newest by then.
func (h *Hub) linkedChanged(uid ids.ID) {
	h.linkedMu.Lock()
	if h.linkedTimer[uid] != nil {
		h.linkedMu.Unlock()
		return
	}
	wait := time.Second - time.Since(h.linkedAt[uid])
	if wait <= 0 {
		h.linkedAt[uid] = time.Now()
		h.linkedMu.Unlock()
		h.PresenceChanged(uid)
		return
	}
	h.linkedTimer[uid] = time.AfterFunc(wait, func() {
		defer h.recoverTimer("linked activity flush")
		h.linkedMu.Lock()
		delete(h.linkedTimer, uid)
		h.linkedAt[uid] = time.Now()
		h.linkedMu.Unlock()
		h.PresenceChanged(uid)
	})
	h.linkedMu.Unlock()
}

// broadcastUser sends a user's presence as it stands now, from the account as
// the connection holds it: every change to the account reaches every one of
// its connections (Hub.refreshUser), so there is nothing to read again — and a
// Rich Presence flush every few seconds used to read it every time.
func (h *Hub) broadcastUser(ctx context.Context, c *conn) {
	if u := c.currentUser(); u != nil {
		h.broadcastPresence(ctx, u)
	}
}
