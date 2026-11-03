package api

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"
	_ "time/tzdata" // local time widgets need every zone, whatever the host has
	"unicode"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/books"
	"evilion.xyz/chat/internal/games"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/media"
	"evilion.xyz/chat/internal/music"
	"evilion.xyz/chat/internal/screen"
	"evilion.xyz/chat/internal/store"
)

// Profile widgets: sections of a profile that show what someone is into. The
// games they love most, are playing, want to play and have played, found on
// IGDB (internal/games) or added by name with a picture of their own; the
// artists, albums and songs they love, found on Deezer (internal/music) or
// added by name the same way; and cards they make themselves: a title, a
// picture, a few words, a handful of numbers and a link. Shown to anyone who
// sees the profile, in the order they are put in.

const (
	maxWidgets     = 12
	maxCustomCards = 4
	maxWidgetNote  = 200
	maxPlaying     = 5
	maxGameList    = 20
	maxGameTags    = 3
	maxGameTag     = 20
	maxGameName    = 100
	maxWidgetTitle = 40
	maxWidgetText  = 1000
	maxCardLinks   = 3
	maxBookTitle   = 200
	maxBookAuthor  = 100
	// the packs a sticker showcase shows
	maxShowcasePacks = 2
	// a countdown's emoji: one emoji, which with skin tones and joins can be long
	maxCountdownEmoji = 32
	maxWidgetStats    = 6
	maxStatLabel      = 24
	maxStatValue      = 40
	maxLinkLabel      = 32
	maxLinkURL        = 512
	maxMusicName      = 100
	maxSongLength     = 24 * 60 * 60
	maxWidgetsBytes   = 64 << 10
	// how many of the artists, albums and songs saved at once Deezer is asked
	// about, when no search brought them: a few, so a save cannot spend
	// everyone's share of what Deezer allows
	maxMusicLookups = 6
	// how large a widget's picture is kept: square ones (a card's picture
	// beside its title), wide ones (across a card's top) and covers (a game
	// added by name, in the shape of a box)
	widgetSquareSide           = 256
	widgetWideW, widgetWideH   = 720, 300
	widgetCoverW, widgetCoverH = 300, 400
)

type widgetGame struct {
	// ID is IGDB's for the game; Cover the image id of its cover there.
	ID    string `json:"id,omitempty"`
	Cover string `json:"cover,omitempty"`
	// Name is the game's; Image a picture of the person's own for one the list does not have.
	Name  string   `json:"name"`
	Image string   `json:"image,omitempty"`
	Tags  []string `json:"tags,omitempty"`
}

// widgetMusic is an artist, an album or a song on a music widget.
type widgetMusic struct {
	// ID is Deezer's; Art the id of its picture there.
	ID   string `json:"id,omitempty"`
	Name string `json:"name"`
	// By is an album's or a song's artist.
	By  string `json:"by,omitempty"`
	Art string `json:"art,omitempty"`
	// Image is a picture of the person's own, for one added by name.
	Image string `json:"image,omitempty"`
	// Duration is a song's length in seconds.
	Duration int `json:"duration,omitempty"`
}

// widgetBook is a book on a books widget: found on Open Library (ID, Cover)
// or added by name with a picture of the person's own (Image).
type widgetBook struct {
	ID     string `json:"id,omitempty"`
	Title  string `json:"title"`
	Author string `json:"author,omitempty"`
	Cover  string `json:"cover,omitempty"`
	Image  string `json:"image,omitempty"`
	// Progress, only on Reading Now: a page reached or a percentage read
	Progress *widgetProgress `json:"progress,omitempty"`
}

type widgetProgress struct {
	// Kind is "page" or "percent"
	Kind  string `json:"kind"`
	Value int    `json:"value"`
}

// widgetScreen is a show or film: found on TMDB (ID, Kind, Poster) or added
// by name with a poster of the person's own (Image).
type widgetScreen struct {
	ID     string `json:"id,omitempty"`
	Kind   string `json:"kind"`
	Title  string `json:"title"`
	Year   int    `json:"year,omitempty"`
	Poster string `json:"poster,omitempty"`
	Image  string `json:"image,omitempty"`
	// Season and Episode, only for a show on Watching Now: where they are
	Season  int `json:"season,omitempty"`
	Episode int `json:"episode,omitempty"`
}

// mostScreen is how many each shows and films widget holds.
var mostScreen = map[string]int{"watching": 3, "screen": 12}

// mostBooks is how many each books widget holds.
var mostBooks = map[string]int{"reading": 3, "books": 12}

// musicKinds are the music widgets and what each holds, with how many.
var musicKinds = map[string]music.Kind{"artists": music.Artists, "albums": music.Albums, "songs": music.Songs}
var mostMusic = map[string]int{"artists": 12, "albums": 12, "songs": 10}

type widgetStat struct {
	Label string `json:"label"`
	// Value is what the stat says; for a bar, its percentage as words too,
	// which is all an app from before bars shows
	Value string `json:"value"`
	// Bar, from 0 to 100, draws the stat as a progress bar
	Bar *int `json:"bar,omitempty"`
}

type widgetLink struct {
	URL   string `json:"url"`
	Label string `json:"label,omitempty"`
}

type widget struct {
	ID    string        `json:"id"`
	Type  string        `json:"type"`
	Games []widgetGame  `json:"games,omitempty"`
	Music []widgetMusic `json:"music,omitempty"`
	Books []widgetBook  `json:"books,omitempty"`
	// Screen are a shows and films widget's titles
	Screen []widgetScreen `json:"screen,omitempty"`
	Note   string         `json:"note,omitempty"`
	Title  string         `json:"title,omitempty"`
	Image  string         `json:"image,omitempty"`
	Shape  string         `json:"shape,omitempty"`
	Text   string         `json:"text,omitempty"`
	Stats  []widgetStat   `json:"stats,omitempty"`
	// Links are a card's buttons, up to three; Link repeats the first for
	// apps from before there could be more than one
	Links []widgetLink `json:"links,omitempty"`
	Link  *widgetLink  `json:"link,omitempty"`
	// Color, and Color2 for a gradient, tint a card (0xRRGGBB)
	Color  *int `json:"color,omitempty"`
	Color2 *int `json:"color2,omitempty"`
	// a countdown: its emoji, and either a whole day (Date, YYYY-MM-DD, counted
	// in each viewer's own calendar) or a moment (At, Unix milliseconds)
	Emoji string `json:"emoji,omitempty"`
	Date  string `json:"date,omitempty"`
	At    int64  `json:"at,omitempty"`
	// Zone is a local time widget's IANA time zone ("America/Anchorage")
	Zone string `json:"zone,omitempty"`
	// Packs are a showcase's sticker or emoji packs: the person's own, public
	Packs []string `json:"packs,omitempty"`
}

var widgetID = regexp.MustCompile(`^[a-z0-9]{4,16}$`)

func (s *Server) widgetRoutes(mux *http.ServeMux) {
	mux.Handle("PUT /api/v1/users/@me/widgets", s.authed(s.handleWidgetsPut))
	mux.Handle("POST /api/v1/users/@me/widgets/image", s.userLimited(s.limUpload, s.handleWidgetImage))
	mux.Handle("GET /api/v1/games/search", s.userLimited(s.limSearch, s.handleGameSearch))
	// a game's page, from a profile's games or an activity naming the game (match)
	mux.Handle("GET /api/v1/games/match", s.userLimited(s.limSearch, s.handleGameMatch))
	mux.Handle("GET /api/v1/games/{id}", s.userLimited(s.limSearch, s.handleGameDetails))
	// kept pictures are served like avatars; only fetching one counts against a limit (fetchLimited)
	mux.HandleFunc("GET /media/game/{cover}", s.handleGameCover)
	mux.Handle("GET /api/v1/screen/search", s.userLimited(s.limSearch, s.handleScreenSearch))
	mux.HandleFunc("GET /media/screen/{poster}", s.handleScreenPoster)
	mux.Handle("GET /api/v1/books/search", s.userLimited(s.limSearch, s.handleBookSearch))
	mux.HandleFunc("GET /media/book/{cover}", s.handleBookCover)
	mux.Handle("GET /api/v1/music/search", s.userLimited(s.limMusic, s.handleMusicSearch))
	// a Spotify, Apple Music, Deezer or SoundCloud link read into a music widget, matched to Deezer
	mux.Handle("POST /api/v1/music/from-link", s.userLimited(s.limMusic, s.handleMusicFromLink))
	mux.HandleFunc("GET /media/music/{kind}/{art}", s.handleMusicArt)
	// a song's thirty seconds, played on a profile; a play asks for a few ranges of it
	mux.Handle("GET /media/music/preview/{id}", ipLimited(s.limPreview, s.handleMusicPreview))
}

// line is a single line of someone's own words, cleaned, at most max runes.
func line(raw string, max int) (string, bool) {
	return CleanText(strings.NewReplacer("\n", " ", "\t", " ").Replace(raw), max)
}

// cleanGame checks one game of a widget. A game found on IGDB is named and
// drawn as IGDB has it (known), whatever was sent; when IGDB cannot be asked,
// what was sent is kept, checked for shape.
func (s *Server) cleanGame(in widgetGame, tags bool, known map[string]games.Game) (widgetGame, string) {
	out := widgetGame{}
	if in.ID != "" {
		if !games.IDPattern.MatchString(in.ID) {
			return out, "That game isn't one from the games search."
		}
		out.ID = in.ID
		if g, ok := known[in.ID]; ok {
			out.Name, out.Cover = g.Name, g.Cover
		} else {
			name, ok := line(in.Name, maxGameName)
			if !ok || name == "" {
				return out, "Each game needs a name."
			}
			out.Name = name
			if games.CoverPattern.MatchString(in.Cover) {
				out.Cover = in.Cover
			}
		}
	} else {
		name, ok := line(in.Name, maxGameName)
		if !ok || name == "" {
			return out, "Each game needs a name of at most 100 characters."
		}
		out.Name = name
		if in.Image != "" {
			if _, err := ids.Parse(in.Image); err != nil {
				return out, "That game's picture has gone. Put it up again."
			}
			out.Image = in.Image
		}
	}
	if tags {
		if len(in.Tags) > maxGameTags {
			return out, "A game has at most three tags."
		}
		for _, raw := range in.Tags {
			tag, ok := line(raw, maxGameTag)
			if !ok {
				return out, "A tag is at most 20 characters."
			}
			if tag != "" {
				out.Tags = append(out.Tags, tag)
			}
		}
	}
	return out, ""
}

// cleanMusic checks one artist, album or song of a widget. One found on
// Deezer is named and drawn as Deezer has it (known), whatever was sent;
// when Deezer cannot be asked, what was sent is kept, checked for shape.
func cleanMusic(in widgetMusic, kind music.Kind, known map[string]music.Item) (widgetMusic, string) {
	out := widgetMusic{}
	if in.ID != "" {
		if !music.IDPattern.MatchString(in.ID) {
			return out, "That isn't one from the music search."
		}
		out.ID = in.ID
		if it, ok := known[string(kind)+":"+in.ID]; ok {
			if it.ID == "" {
				return out, "Deezer doesn't have one of those. Search for it again."
			}
			out.Name, out.By, out.Art = it.Name, it.By, it.Art
			if kind == music.Songs {
				out.Duration = it.Duration
			}
			return out, ""
		}
		if music.ArtPattern.MatchString(in.Art) {
			out.Art = in.Art
		}
		if kind == music.Songs && in.Duration > 0 && in.Duration <= maxSongLength {
			out.Duration = in.Duration
		}
	} else if in.Image != "" {
		if _, err := ids.Parse(in.Image); err != nil {
			return out, "That picture has gone. Put it up again."
		}
		out.Image = in.Image
	}
	name, ok := line(in.Name, maxMusicName)
	if !ok || name == "" {
		return out, "Each one needs a name of at most 100 characters."
	}
	out.Name = name
	if kind != music.Artists {
		by, ok := line(in.By, maxMusicName)
		if !ok {
			return out, "An artist's name is at most 100 characters."
		}
		out.By = by
	}
	return out, ""
}

// musicKnown is what Deezer says about every artist, album and song in
// widgets being saved, by kind and id: as a search or a lookup already said,
// as the profile had them before (before), or asked about now, a few at
// most. One Deezer says it does not have is there with no id.
func (s *Server) musicKnown(ctx context.Context, in []widget, before string) map[string]music.Item {
	out := map[string]music.Item{}
	var saved []widget
	if before != "" && json.Unmarshal([]byte(before), &saved) != nil {
		saved = nil
	}
	for _, w := range saved {
		kind, ok := musicKinds[w.Type]
		if !ok {
			continue
		}
		for _, m := range w.Music {
			if m.ID != "" {
				out[string(kind)+":"+m.ID] = music.Item{ID: m.ID, Name: m.Name, By: m.By, Art: m.Art, Duration: m.Duration}
			}
		}
	}
	type ask struct {
		kind music.Kind
		id   string
	}
	var missing []ask
	asking := map[string]bool{}
	for _, w := range in {
		kind, ok := musicKinds[w.Type]
		if !ok {
			continue
		}
		for _, m := range w.Music {
			key := string(kind) + ":" + m.ID
			if !music.IDPattern.MatchString(m.ID) || asking[key] {
				continue
			}
			if _, ok := out[key]; ok {
				continue
			}
			if it, ok := s.Music.Known(kind, m.ID); ok {
				out[key] = it
				continue
			}
			asking[key] = true
			missing = append(missing, ask{kind, m.ID})
		}
	}
	if len(missing) == 0 || !s.Music.On() {
		return out
	}
	lctx, cancel := context.WithTimeout(ctx, 8*time.Second)
	defer cancel()
	var mu sync.Mutex
	var wg sync.WaitGroup
	for _, a := range missing[:min(len(missing), maxMusicLookups)] {
		wg.Add(1)
		go func() {
			defer wg.Done()
			it, err := s.Music.Lookup(lctx, a.kind, a.id)
			mu.Lock()
			defer mu.Unlock()
			switch {
			case err == nil:
				out[string(a.kind)+":"+a.id] = it
			case errors.Is(err, music.ErrUnknown):
				out[string(a.kind)+":"+a.id] = music.Item{}
			default:
				s.Log.Warn("deezer lookup", "err", err)
			}
		}()
	}
	wg.Wait()
	return out
}

// cleanWidgets checks widgets their owner sent and writes them the one way
// they are kept; before is how the profile had them. The second result is
// why they were refused.
func (s *Server) cleanWidgets(ctx context.Context, raw json.RawMessage, before string, owner ids.ID) (string, string) {
	var in []widget
	if err := json.Unmarshal(raw, &in); err != nil {
		return "", "widgets must be a list."
	}
	if len(in) > maxWidgets {
		return "", "A profile has at most twelve widgets."
	}
	// every IGDB game named, looked up in one go (most are known from the search that found them)
	var gameIDs []string
	for _, w := range in {
		for _, g := range w.Games {
			if games.IDPattern.MatchString(g.ID) {
				gameIDs = append(gameIDs, g.ID)
			}
		}
	}
	known := map[string]games.Game{}
	if len(gameIDs) > 0 && s.Games.On() {
		lctx, cancel := context.WithTimeout(ctx, 8*time.Second)
		found, err := s.Games.Lookup(lctx, gameIDs)
		cancel()
		if err != nil {
			s.Log.Warn("igdb lookup", "err", err)
		}
		known = found
	}
	knownMusic := s.musicKnown(ctx, in, before)
	// cards as they were, for apps from before cards had several links, a
	// colour and bars, which send a card without "links" and would drop them
	savedCards := map[string]widget{}
	if before != "" {
		var saved []widget
		if json.Unmarshal([]byte(before), &saved) == nil {
			for _, sw := range saved {
				if sw.Type == "custom" {
					savedCards[sw.ID] = sw
				}
			}
		}
	}
	out := make([]widget, 0, len(in))
	seenType := map[string]bool{}
	seenID := map[string]bool{}
	cards := 0
	for i, w := range in {
		next := widget{ID: w.ID, Type: w.Type}
		if !widgetID.MatchString(next.ID) || seenID[next.ID] {
			next.ID = "w" + strconv.FormatInt(time.Now().UnixMilli()%1_000_000_000, 36) + strconv.Itoa(i)
		}
		seenID[next.ID] = true
		switch w.Type {
		case "favorite", "playing", "wishlist", "played":
			if seenType[w.Type] {
				return "", "A profile has one of each kind of games widget."
			}
			seenType[w.Type] = true
			most := map[string]int{"favorite": 1, "playing": maxPlaying, "wishlist": maxGameList, "played": maxGameList}[w.Type]
			if len(w.Games) == 0 || len(w.Games) > most {
				return "", "A games widget holds between one game and its most."
			}
			for _, g := range w.Games {
				clean, problem := s.cleanGame(g, w.Type == "playing", known)
				if problem != "" {
					return "", problem
				}
				next.Games = append(next.Games, clean)
			}
			if w.Type == "favorite" {
				note, ok := CleanText(w.Note, maxWidgetNote)
				if !ok {
					return "", "What you say about your favourite is at most 200 characters."
				}
				next.Note = note
			}
		case "artists", "albums", "songs":
			if seenType[w.Type] {
				return "", "A profile has one of each kind of music widget."
			}
			seenType[w.Type] = true
			if len(w.Music) == 0 || len(w.Music) > mostMusic[w.Type] {
				return "", "A music widget holds between one and its most."
			}
			kind := musicKinds[w.Type]
			seen := map[string]bool{}
			for _, m := range w.Music {
				clean, problem := cleanMusic(m, kind, knownMusic)
				if problem != "" {
					return "", problem
				}
				// the same one twice is there once
				key := "id:" + clean.ID
				if clean.ID == "" {
					key = "name:" + strings.ToLower(clean.Name) + "\x00" + strings.ToLower(clean.By)
				}
				if seen[key] {
					continue
				}
				seen[key] = true
				next.Music = append(next.Music, clean)
			}
		case "watching", "screen":
			if seenType[w.Type] {
				return "", "A profile has one of each kind of shows and films widget."
			}
			seenType[w.Type] = true
			if len(w.Screen) == 0 || len(w.Screen) > mostScreen[w.Type] {
				return "", "A shows and films widget holds between one and its most."
			}
			seenTitle := map[string]bool{}
			for _, t := range w.Screen {
				clean, problem := cleanScreen(t, w.Type == "watching")
				if problem != "" {
					return "", problem
				}
				key := clean.Kind + ":" + clean.ID
				if clean.ID == "" {
					key = "title:" + strings.ToLower(clean.Title)
				}
				if seenTitle[key] {
					continue
				}
				seenTitle[key] = true
				next.Screen = append(next.Screen, clean)
			}
		case "reading", "books":
			if seenType[w.Type] {
				return "", "A profile has one of each kind of books widget."
			}
			seenType[w.Type] = true
			if len(w.Books) == 0 || len(w.Books) > mostBooks[w.Type] {
				return "", "A books widget holds between one book and its most."
			}
			seenBook := map[string]bool{}
			for _, b := range w.Books {
				clean, problem := cleanBook(b, w.Type == "reading")
				if problem != "" {
					return "", problem
				}
				key := "id:" + clean.ID
				if clean.ID == "" {
					key = "title:" + strings.ToLower(clean.Title) + "\x00" + strings.ToLower(clean.Author)
				}
				if seenBook[key] {
					continue
				}
				seenBook[key] = true
				next.Books = append(next.Books, clean)
			}
		case "countdown":
			if seenType[w.Type] {
				return "", "A profile has one countdown."
			}
			seenType[w.Type] = true
			title, ok := line(w.Title, maxWidgetTitle)
			if !ok || title == "" {
				return "", "A countdown needs a title of at most 40 characters."
			}
			next.Title = title
			emoji, ok := line(w.Emoji, maxCountdownEmoji)
			if !ok || strings.ContainsAny(emoji, "<>:") {
				return "", "A countdown's emoji is one emoji."
			}
			next.Emoji = emoji
			switch {
			case w.Date != "" && w.At == 0:
				day, err := time.Parse("2006-01-02", w.Date)
				if err != nil || day.Year() < 1970 || day.Year() > 2200 {
					return "", "A countdown needs a date."
				}
				next.Date = day.Format("2006-01-02")
			case w.Date == "" && w.At != 0:
				if w.At < 0 || w.At > time.Date(2200, 1, 1, 0, 0, 0, 0, time.UTC).UnixMilli() {
					return "", "A countdown needs a date."
				}
				next.At = w.At
			default:
				return "", "A countdown needs a date."
			}
		case "stickers":
			if seenType[w.Type] {
				return "", "A profile has one sticker showcase."
			}
			seenType[w.Type] = true
			if len(w.Packs) == 0 || len(w.Packs) > maxShowcasePacks {
				return "", "A showcase holds one or two of your packs."
			}
			for _, raw := range w.Packs {
				id, err := ids.Parse(raw)
				if err != nil {
					return "", "That pack isn't one of yours."
				}
				p, err := s.Store.PackByID(ctx, id)
				if err != nil || p.OwnerUserID == nil || *p.OwnerUserID != owner {
					return "", "That pack isn't one of yours."
				}
				if !p.Public {
					return "", "Only a public pack can be shown on your profile."
				}
				if !slices.Contains(next.Packs, id.String()) {
					next.Packs = append(next.Packs, id.String())
				}
			}
		case "localtime":
			if seenType[w.Type] {
				return "", "A profile has one local time."
			}
			seenType[w.Type] = true
			zone := strings.TrimSpace(w.Zone)
			if zone == "" || len(zone) > 64 || zone == "Local" {
				return "", "That isn't a time zone."
			}
			if _, err := time.LoadLocation(zone); err != nil {
				return "", "That isn't a time zone."
			}
			next.Zone = zone
		case "custom":
			if w.Links == nil {
				w = carryCardExtras(w, savedCards[w.ID])
			}
			cards++
			if cards > maxCustomCards {
				return "", "A profile has at most four cards of your own."
			}
			title, ok := line(w.Title, maxWidgetTitle)
			if !ok || title == "" {
				return "", "A card needs a title of at most 40 characters."
			}
			next.Title = title
			text, ok := CleanText(w.Text, maxWidgetText)
			if !ok {
				return "", "A card's words are at most 1,000 characters."
			}
			next.Text = text
			if w.Image != "" {
				if _, err := ids.Parse(w.Image); err != nil {
					return "", "That card's picture has gone. Put it up again."
				}
				next.Image = w.Image
				next.Shape = "wide"
				if w.Shape == "square" {
					next.Shape = "square"
				}
			}
			if len(w.Stats) > maxWidgetStats {
				return "", "A card has at most six stats."
			}
			for _, st := range w.Stats {
				label, ok1 := line(st.Label, maxStatLabel)
				value, ok2 := line(st.Value, maxStatValue)
				if !ok1 || !ok2 {
					return "", "A card's labels are at most 24 characters, and their values at most 40."
				}
				var bar *int
				if st.Bar != nil {
					if *st.Bar < 0 || *st.Bar > 100 {
						return "", "A bar on a card goes from 0 to 100."
					}
					b := *st.Bar
					bar = &b
					if label != "" {
						value = strconv.Itoa(b) + "%"
					}
				}
				if label == "" && value == "" {
					continue
				}
				if label == "" || value == "" {
					return "", "Each stat on a card needs a label and a value."
				}
				next.Stats = append(next.Stats, widgetStat{Label: label, Value: value, Bar: bar})
			}
			links := w.Links
			if links == nil && w.Link != nil {
				links = []widgetLink{*w.Link}
			}
			for _, l := range links {
				raw := strings.TrimSpace(l.URL)
				if raw == "" {
					continue
				}
				if len(next.Links) == maxCardLinks {
					return "", "A card has at most three links."
				}
				u, err := url.Parse(raw)
				if err != nil || len(raw) > maxLinkURL || (u.Scheme != "https" && u.Scheme != "http") || u.Host == "" || u.User != nil {
					return "", "A card's link must be a web address starting with https://."
				}
				label, ok := line(l.Label, maxLinkLabel)
				if !ok {
					return "", "A link's words are at most 32 characters."
				}
				next.Links = append(next.Links, widgetLink{URL: u.String(), Label: label})
			}
			if len(next.Links) > 0 {
				first := next.Links[0]
				next.Link = &first
			}
			for _, c := range []*int{w.Color, w.Color2} {
				if c != nil && (*c < 0 || *c > 0xFFFFFF) {
					return "", "A card's colour must be a colour."
				}
			}
			if w.Color != nil {
				c := *w.Color
				next.Color = &c
				if w.Color2 != nil {
					c2 := *w.Color2
					next.Color2 = &c2
				}
			}
			if next.Text == "" && next.Image == "" && len(next.Stats) == 0 && next.Link == nil {
				return "", "A card needs something on it besides its title."
			}
		default:
			return "", "That isn't a kind of widget."
		}
		out = append(out, next)
	}
	if len(out) == 0 {
		return "", ""
	}
	var b bytes.Buffer
	enc := json.NewEncoder(&b)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(out); err != nil {
		return "", "widgets could not be read."
	}
	text := strings.TrimSuffix(b.String(), "\n")
	if len(text) > maxWidgetsBytes {
		return "", "That's more than a profile can hold. Take a few things out."
	}
	return text, ""
}

// cleanScreen checks one show or film, with where they are in a show on Watching Now.
func cleanScreen(in widgetScreen, episode bool) (widgetScreen, string) {
	out := widgetScreen{Kind: in.Kind}
	if in.Kind != "tv" && in.Kind != "movie" {
		return out, "A title is a show or a film."
	}
	title, ok := line(in.Title, maxBookTitle)
	if !ok || title == "" {
		return out, "Each show or film needs a title of at most 200 characters."
	}
	out.Title = title
	if in.Year != 0 {
		if in.Year < 1870 || in.Year > 2200 {
			return out, "That year doesn't look right."
		}
		out.Year = in.Year
	}
	if in.ID != "" {
		if !screen.IDPattern.MatchString(in.ID) || (in.Poster != "" && !screen.PosterPattern.MatchString(in.Poster)) {
			return out, "That title isn't one from the search."
		}
		out.ID, out.Poster = in.ID, in.Poster
	} else if in.Image != "" {
		if _, err := ids.Parse(in.Image); err != nil {
			return out, "That poster has gone. Put it up again."
		}
		out.Image = in.Image
	}
	if episode && in.Kind == "tv" && (in.Season != 0 || in.Episode != 0) {
		if in.Season < 0 || in.Season > 200 || in.Episode < 0 || in.Episode > 10000 {
			return out, "That season or episode doesn't look right."
		}
		out.Season, out.Episode = in.Season, in.Episode
	}
	return out, ""
}

// cleanBook checks one book of a widget, with how far into it they are when
// it is on Reading Now.
func cleanBook(in widgetBook, progress bool) (widgetBook, string) {
	out := widgetBook{}
	title, ok := line(in.Title, maxBookTitle)
	if !ok || title == "" {
		return out, "Each book needs a title of at most 200 characters."
	}
	out.Title = title
	author, ok := line(in.Author, maxBookAuthor)
	if !ok {
		return out, "A book's author is at most 100 characters."
	}
	out.Author = author
	if in.ID != "" {
		if !books.IDPattern.MatchString(in.ID) {
			return out, "That book isn't one from the books search."
		}
		out.ID = in.ID
		if in.Cover != "" {
			if !books.CoverPattern.MatchString(in.Cover) {
				return out, "That book isn't one from the books search."
			}
			out.Cover = in.Cover
		}
	} else if in.Image != "" {
		if _, err := ids.Parse(in.Image); err != nil {
			return out, "That book's picture has gone. Put it up again."
		}
		out.Image = in.Image
	}
	if progress && in.Progress != nil {
		p := *in.Progress
		switch {
		case p.Kind == "percent" && p.Value >= 0 && p.Value <= 100:
		case p.Kind == "page" && p.Value >= 1 && p.Value <= 100000:
		default:
			return out, "How far you are is a page or a percentage."
		}
		out.Progress = &p
	}
	return out, ""
}

// carryCardExtras keeps what an app from before could not show or send of a
// saved card it sent back: the links after the first (while the first is
// still the same), the colours, and the bars of stats it left as they were.
// Current apps always send "links", even empty, so their changes stand.
func carryCardExtras(w, saved widget) widget {
	if saved.ID == "" {
		return w
	}
	if w.Link != nil && len(saved.Links) > 0 && strings.TrimSpace(w.Link.URL) == saved.Links[0].URL {
		w.Links = append([]widgetLink{*w.Link}, saved.Links[1:]...)
	}
	if w.Color == nil {
		w.Color, w.Color2 = saved.Color, saved.Color2
	}
	for i, st := range w.Stats {
		for _, old := range saved.Stats {
			if old.Bar != nil && st.Bar == nil && old.Label == st.Label && old.Value == st.Value {
				w.Stats[i].Bar = old.Bar
			}
		}
	}
	return w
}

// widgetsJSON is widgets as they go out: a list, empty for none.
func widgetsJSON(raw string) json.RawMessage {
	if raw == "" {
		return json.RawMessage("[]")
	}
	return json.RawMessage(raw)
}

func (s *Server) handleWidgetsPut(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	// every save is shown to everyone who opens the profile
	if ok, retry := s.limProfile.Allow("profile:" + me.ID.String()); !ok {
		w.Header().Set("Retry-After", retryAfter(retry))
		WriteError(w, ErrRateLimited)
		return
	}
	var req struct {
		Widgets json.RawMessage `json:"widgets"`
	}
	if err := ReadJSON(w, r, &req, 64<<10); err != nil {
		WriteError(w, err)
		return
	}
	if len(req.Widgets) == 0 || string(req.Widgets) == "null" {
		req.Widgets = json.RawMessage("[]")
	}
	before, err := s.Store.UserWidgets(r.Context(), me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	clean, problem := s.cleanWidgets(r.Context(), req.Widgets, before, me.ID)
	if problem != "" {
		WriteError(w, E(http.StatusBadRequest, "invalid_widgets", problem))
		return
	}
	if err := s.Store.SetUserWidgets(r.Context(), me.ID, clean); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			WriteError(w, E(http.StatusBadRequest, "invalid_widgets", "One of those pictures has gone. Put it up again."))
			return
		}
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"widgets": widgetsJSON(clean)})
}

// handleWidgetImage takes a picture for a widget with the part framed, cut
// here square or wide and kept small. It is held for a day: saving widgets
// that name it keeps it.
func (s *Server) handleWidgetImage(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	lim := s.limitsFor("widget")
	data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, lim.MaxBytes+1))
	if err != nil || int64(len(data)) > lim.MaxBytes {
		WriteError(w, media.ErrTooLarge)
		return
	}
	if width, height, err := media.ImageDims(bytes.NewReader(data)); err != nil {
		WriteError(w, media.ErrUnsupported)
		return
	} else if err := lim.Check(width, height); err != nil {
		WriteError(w, err)
		return
	}
	shape := r.URL.Query().Get("shape")
	spec := media.CropSpec{Rect: cropFromQuery(r), MaxW: widgetSquareSide, MaxH: widgetSquareSide}
	switch shape {
	case "wide":
		spec.MaxW, spec.MaxH = widgetWideW, widgetWideH
	case "cover":
		spec.MaxW, spec.MaxH = widgetCoverW, widgetCoverH
	default:
		shape = "square"
	}
	out, _, err := media.Crop(data, spec)
	if err != nil {
		WriteError(w, cropError(err))
		return
	}
	b, err := s.Media.PutPublic(r.Context(), "widget", bytes.NewReader(out), media.Limits{MaxBytes: int64(len(out)) + 1, MaxW: spec.MaxW, MaxH: spec.MaxH}, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	s.releaseBlob(r.Context(), b.ID)
	WriteJSON(w, http.StatusOK, map[string]any{"id": b.ID, "shape": shape, "animated": b.Animated})
}

// handleGameSearch finds games on IGDB by name.
func (s *Server) handleGameSearch(w http.ResponseWriter, r *http.Request) {
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	list := []games.Game{}
	on := s.Games.On()
	if on && q != "" && len(q) <= 100 {
		ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
		found, err := s.Games.Search(ctx, q, 24)
		cancel()
		if err != nil {
			s.Log.Warn("igdb search", "err", err)
			WriteError(w, E(http.StatusBadGateway, "games_unavailable", "Game search isn't answering right now. Try again in a moment, or add the game by name."))
			return
		}
		list = found
	}
	WriteJSON(w, http.StatusOK, map[string]any{"games": list, "ready": on})
}

// handleGameCover serves a game's cover, fetched from IGDB once and kept. It
// only ever fetches IGDB's own picture addresses.
func (s *Server) handleGameCover(w http.ResponseWriter, r *http.Request) {
	cover := r.PathValue("cover")
	size := r.URL.Query().Get("size")
	if _, shot := games.PictureSizes[size]; !shot && size != "s" {
		size = "l"
	}
	if s.Games == nil || !games.CoverPattern.MatchString(cover) {
		http.NotFound(w, r)
		return
	}
	if path, ok := s.Games.Kept(cover, size); ok {
		serveKept(w, r, path)
		return
	}
	if !s.fetchLimited(w, r) {
		return
	}
	path, err := s.Games.CoverFile(r.Context(), cover, size)
	if err != nil {
		if errors.Is(err, games.ErrUnknown) {
			http.NotFound(w, r)
			return
		}
		http.Error(w, "could not fetch the cover", http.StatusBadGateway)
		return
	}
	serveKept(w, r, path)
}

// handleScreenSearch finds shows and films on TMDB by name.
func (s *Server) handleScreenSearch(w http.ResponseWriter, r *http.Request) {
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	list := []screen.Title{}
	on := s.Screen.On()
	if on && q != "" && len(q) <= 200 {
		ctx, cancel := context.WithTimeout(r.Context(), 12*time.Second)
		found, err := s.Screen.Search(ctx, q, 24)
		cancel()
		if err != nil {
			s.Log.Warn("tmdb search", "err", err)
			WriteError(w, E(http.StatusBadGateway, "screen_unavailable", "Show and film search isn't answering right now. Try again in a moment, or add it by name."))
			return
		}
		list = found
	}
	WriteJSON(w, http.StatusOK, map[string]any{"titles": list, "ready": on})
}

// handleScreenPoster serves a show's or film's poster, fetched from TMDB once and kept.
func (s *Server) handleScreenPoster(w http.ResponseWriter, r *http.Request) {
	file := r.PathValue("poster")
	size := r.URL.Query().Get("size")
	if size != "s" {
		size = "l"
	}
	if s.Screen == nil || !screen.PosterPattern.MatchString(file) {
		http.NotFound(w, r)
		return
	}
	if path, ok := s.Screen.Kept(file, size); ok {
		serveKept(w, r, path)
		return
	}
	if !s.Screen.On() || !s.fetchLimited(w, r) {
		if !s.Screen.On() {
			http.NotFound(w, r)
		}
		return
	}
	path, err := s.Screen.PosterFile(r.Context(), file, size)
	if err != nil {
		if errors.Is(err, screen.ErrUnknown) {
			http.NotFound(w, r)
			return
		}
		http.Error(w, "could not fetch the poster", http.StatusBadGateway)
		return
	}
	serveKept(w, r, path)
}

// handleBookSearch finds books on Open Library by title or author.
func (s *Server) handleBookSearch(w http.ResponseWriter, r *http.Request) {
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	list := []books.Book{}
	on := s.Books != nil
	if on && q != "" && len(q) <= 200 {
		// Open Library can take a while over a search it has not seen lately
		ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
		found, err := s.Books.Search(ctx, q, 24)
		cancel()
		if err != nil {
			s.Log.Warn("open library search", "err", err)
			WriteError(w, E(http.StatusBadGateway, "books_unavailable", "Book search isn't answering right now. Try again in a moment, or add the book by name."))
			return
		}
		list = found
	}
	WriteJSON(w, http.StatusOK, map[string]any{"books": list, "ready": on})
}

// handleBookCover serves a book's cover, fetched from Open Library once and kept.
func (s *Server) handleBookCover(w http.ResponseWriter, r *http.Request) {
	cover := r.PathValue("cover")
	size := r.URL.Query().Get("size")
	if size != "s" {
		size = "l"
	}
	if s.Books == nil || !books.CoverPattern.MatchString(cover) {
		http.NotFound(w, r)
		return
	}
	if path, ok := s.Books.Kept(cover, size); ok {
		serveKept(w, r, path)
		return
	}
	if !s.fetchLimited(w, r) {
		return
	}
	path, err := s.Books.CoverFile(r.Context(), cover, size)
	if err != nil {
		if errors.Is(err, books.ErrUnknown) {
			http.NotFound(w, r)
			return
		}
		http.Error(w, "could not fetch the cover", http.StatusBadGateway)
		return
	}
	serveKept(w, r, path)
}

// handleGameDetails is a game's page: what IGDB says about it, its pictures,
// trailers, similar games and stores.
func (s *Server) handleGameDetails(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !games.IDPattern.MatchString(id) {
		WriteError(w, E(http.StatusNotFound, "unknown_game", "That game isn't known."))
		return
	}
	if !s.Games.On() {
		WriteError(w, E(http.StatusServiceUnavailable, "games_off", "Game details aren't available here."))
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
	d, err := s.Games.Details(ctx, id)
	cancel()
	if errors.Is(err, games.ErrUnknown) {
		WriteError(w, E(http.StatusNotFound, "unknown_game", "That game isn't known."))
		return
	}
	if err != nil {
		s.Log.Warn("igdb details", "err", err)
		WriteError(w, E(http.StatusBadGateway, "games_unavailable", "Game details aren't loading right now. Try again in a moment."))
		return
	}
	// trailers' pictures come through the media proxy, as a chat's YouTube links' do
	type video struct {
		games.Video
		// Thumb is YouTube's 16:9 picture for the strip; Poster its larger
		// one, a 4:3 frame with the 16:9 picture across its middle
		Thumb  string `json:"thumb,omitempty"`
		Poster string `json:"poster,omitempty"`
	}
	videos := make([]video, 0, len(d.Videos))
	for _, v := range d.Videos {
		base := "https://i.ytimg.com/vi/" + v.ID + "/"
		videos = append(videos, video{Video: v, Thumb: s.ProxyURL(base + "mqdefault.jpg"), Poster: s.ProxyURL(base + "hqdefault.jpg")})
	}
	WriteJSON(w, http.StatusOK, struct {
		*games.Details
		Videos []video `json:"videos"`
	}{d, videos})
}

// handleGameMatch is IGDB's id for the game an activity names, if one has
// exactly that name: {"id": "..."} or {"id": null}.
func (s *Server) handleGameMatch(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimSpace(r.URL.Query().Get("name"))
	var id any
	if s.Games.On() && name != "" && len(name) <= 120 {
		ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
		found, err := s.Games.Match(ctx, name)
		cancel()
		if err != nil {
			s.Log.Warn("igdb match", "err", err)
			WriteError(w, E(http.StatusBadGateway, "games_unavailable", "Game details aren't loading right now. Try again in a moment."))
			return
		}
		if found != "" {
			id = found
		}
	}
	WriteJSON(w, http.StatusOK, map[string]any{"id": id})
}

// musicKey is a title or a name as two services' copies of it are compared:
// case folded, without what follows a dash or sits in brackets ("Remastered",
// "feat. ..."), and without punctuation.
func musicKey(s string) string {
	s = strings.ToLower(s)
	if i := strings.Index(s, " - "); i > 0 {
		s = s[:i]
	}
	for _, open := range []string{"(", "["} {
		if i := strings.Index(s, open); i > 0 {
			s = s[:i]
		}
	}
	var b strings.Builder
	for _, r := range s {
		if unicode.IsLetter(r) || unicode.IsNumber(r) {
			b.WriteRune(r)
		}
	}
	return b.String()
}

// sameMusic says whether a Deezer result is the piece a link named: the same
// name, and an artist that shares the lead artist's name.
func sameMusic(name, by string, it music.Item) bool {
	if musicKey(name) == "" || musicKey(name) != musicKey(it.Name) {
		return false
	}
	lead := by
	for _, sep := range []string{",", "&", " feat", " x "} {
		if i := strings.Index(strings.ToLower(lead), sep); i > 0 {
			lead = lead[:i]
		}
	}
	a, b := musicKey(lead), musicKey(it.By)
	return a == "" || b == "" || strings.Contains(b, a) || strings.Contains(a, b)
}

// handleMusicFromLink reads a music link into a music widget: an album's or
// playlist's songs (up to the widget's ten), an album, or an artist, each
// matched to Deezer so previews play. What could not be matched is named in
// "missed" for the person to add by hand.
func (s *Server) handleMusicFromLink(w http.ResponseWriter, r *http.Request) {
	var req struct {
		URL  string `json:"url"`
		Kind string `json:"kind"`
	}
	if err := ReadJSON(w, r, &req, 4096); err != nil {
		WriteError(w, err)
		return
	}
	kind, ok := musicKinds[req.Kind]
	if !ok {
		WriteError(w, E(http.StatusBadRequest, "invalid_kind", "kind is artists, albums or songs."))
		return
	}
	if s.Unfurl == nil || s.Unfurl.Fetcher == nil || !s.Music.On() {
		WriteError(w, E(http.StatusServiceUnavailable, "music_unavailable", "Music links can't be read here. Add them by searching instead."))
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 25*time.Second)
	defer cancel()
	m := s.Unfurl.Fetcher.MusicList(ctx, req.URL, mostMusic["songs"])
	if m == nil {
		WriteError(w, E(http.StatusUnprocessableEntity, "not_music", "That isn't a Spotify, Apple Music, Deezer or SoundCloud link Nexa can read."))
		return
	}
	// what to look for on Deezer, by the widget's kind
	type want struct{ name, by string }
	var wants []want
	switch kind {
	case music.Songs:
		if len(m.Tracks) > 0 && m.Kind != "track" {
			for _, t := range m.Tracks {
				wants = append(wants, want{t.Title, t.Artist})
			}
		} else if m.Kind == "track" {
			wants = append(wants, want{m.Title, m.Artist})
		}
	case music.Albums:
		switch m.Kind {
		case "album":
			wants = append(wants, want{m.Title, m.Artist})
		case "track":
			if m.Album != "" {
				wants = append(wants, want{m.Album, m.Artist})
			}
		}
	case music.Artists:
		if m.Kind == "artist" {
			wants = append(wants, want{m.Title, ""})
		} else if m.Artist != "" {
			wants = append(wants, want{m.Artist, ""})
		}
	}
	if len(wants) == 0 {
		WriteError(w, E(http.StatusUnprocessableEntity, "wrong_kind", "That link has nothing for this widget."))
		return
	}
	found := make([]*music.Item, len(wants))
	var wg sync.WaitGroup
	sem := make(chan struct{}, 3)
	for i, wt := range wants {
		wg.Add(1)
		go func() {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			q := strings.TrimSpace(wt.name + " " + wt.by)
			list, err := s.Music.Search(ctx, kind, q)
			if err != nil {
				return
			}
			for _, it := range list {
				if kind == music.Artists && musicKey(it.Name) == musicKey(wt.name) || kind != music.Artists && sameMusic(wt.name, wt.by, it) {
					hit := it
					found[i] = &hit
					return
				}
			}
		}()
	}
	wg.Wait()
	items := []music.Item{}
	missed := []string{}
	seen := map[string]bool{}
	for i, f := range found {
		if f == nil {
			name := wants[i].name
			if wants[i].by != "" {
				name += " (" + wants[i].by + ")"
			}
			missed = append(missed, name)
			continue
		}
		if seen[f.ID] {
			continue
		}
		seen[f.ID] = true
		items = append(items, *f)
	}
	WriteJSON(w, http.StatusOK, map[string]any{"kind": m.Kind, "title": m.Title, "items": items, "missed": missed})
}

// handleMusicSearch finds artists, albums or songs on Deezer, from a name,
// a song or an album (internal/music).
func (s *Server) handleMusicSearch(w http.ResponseWriter, r *http.Request) {
	kind, ok := musicKinds[r.URL.Query().Get("kind")]
	if !ok {
		WriteError(w, E(http.StatusBadRequest, "invalid_kind", "kind is artists, albums or songs."))
		return
	}
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	list := []music.Item{}
	on := s.Music.On()
	if on && q != "" && len(q) <= 200 {
		ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
		found, err := s.Music.Search(ctx, kind, q)
		cancel()
		if err != nil {
			s.Log.Warn("deezer search", "err", err)
			WriteError(w, E(http.StatusBadGateway, "music_unavailable", "Music search isn't answering right now. Try again in a moment, or add it by name."))
			return
		}
		list = found
	}
	WriteJSON(w, http.StatusOK, map[string]any{"results": list, "ready": on})
}

// handleMusicArt serves an artist's photo or a cover, fetched from Deezer
// once and kept. It only ever fetches Deezer's own picture addresses.
func (s *Server) handleMusicArt(w http.ResponseWriter, r *http.Request) {
	kind, art := r.PathValue("kind"), r.PathValue("art")
	size := r.URL.Query().Get("size")
	if size != "s" {
		size = "l"
	}
	if !s.Music.On() || !music.ArtKinds[kind] || !music.ArtPattern.MatchString(art) {
		http.NotFound(w, r)
		return
	}
	if path, ok := s.Music.Kept(kind, art, size); ok {
		serveKept(w, r, path)
		return
	}
	if !s.fetchLimited(w, r) {
		return
	}
	path, err := s.Music.ArtFile(r.Context(), kind, art, size)
	if err != nil {
		if errors.Is(err, music.ErrUnknown) {
			http.NotFound(w, r)
			return
		}
		http.Error(w, "could not fetch the picture", http.StatusBadGateway)
		return
	}
	serveKept(w, r, path)
}

// byteRange is the one kind of Range a preview passes on to Deezer: a single span of bytes.
var byteRange = regexp.MustCompile(`^bytes=\d{0,12}-\d{0,12}$`)

// maxSongPreview is more than thirty seconds of any song Deezer has.
const maxSongPreview = 4 << 20

// handleMusicPreview passes on thirty seconds of a song from Deezer as the
// listener's player asks for it, ranges included (Safari plays nothing
// without them), so the listener never contacts Deezer. None of it is kept,
// here or by any cache on the way: Deezer's terms forbid keeping its audio.
func (s *Server) handleMusicPreview(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !s.Music.On() || !music.IDPattern.MatchString(id) {
		http.NotFound(w, r)
		return
	}
	span := r.Header.Get("Range")
	if !byteRange.MatchString(span) {
		span = ""
	}
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Minute)
	defer cancel()
	res, err := s.Music.OpenPreview(ctx, id, span)
	if err != nil {
		if errors.Is(err, music.ErrUnknown) {
			http.NotFound(w, r)
			return
		}
		if r.Context().Err() == nil {
			s.Log.Warn("deezer preview", "err", err)
		}
		http.Error(w, "could not fetch the preview", http.StatusBadGateway)
		return
	}
	defer res.Body.Close()
	h := w.Header()
	h.Set("Cache-Control", "no-store")
	h.Set("Content-Security-Policy", "default-src 'none'; sandbox")
	h.Set("X-Content-Type-Options", "nosniff")
	switch {
	case res.StatusCode == http.StatusRequestedRangeNotSatisfiable:
		h.Set("Content-Range", res.Header.Get("Content-Range"))
		w.WriteHeader(res.StatusCode)
		return
	case res.StatusCode == http.StatusNotFound:
		http.NotFound(w, r)
		return
	case (res.StatusCode != http.StatusOK && res.StatusCode != http.StatusPartialContent) || !strings.HasPrefix(res.Header.Get("Content-Type"), "audio/"):
		s.Log.Warn("deezer preview", "status", res.StatusCode, "type", res.Header.Get("Content-Type"))
		http.Error(w, "could not fetch the preview", http.StatusBadGateway)
		return
	}
	h.Set("Content-Type", res.Header.Get("Content-Type"))
	for _, k := range []string{"Content-Length", "Content-Range", "Accept-Ranges"} {
		if v := res.Header.Get(k); v != "" {
			h.Set(k, v)
		}
	}
	w.WriteHeader(res.StatusCode)
	_, _ = io.Copy(w, io.LimitReader(res.Body, maxSongPreview))
}

// fetchLimited counts a picture this server has yet to fetch from IGDB or
// Deezer against the address asking, as the media proxy's fetches are; the
// ones already kept are not counted, or a page of search results would run
// out. It answers for itself when the limit is reached.
func (s *Server) fetchLimited(w http.ResponseWriter, r *http.Request) bool {
	ok, retry := s.limProxy.Allow(auth.RateKey(r))
	if !ok {
		w.Header().Set("Retry-After", retryAfter(retry))
		WriteError(w, ErrRateLimited)
	}
	return ok
}

// serveKept serves a picture kept from IGDB or Deezer, whose id names it for good.
func serveKept(w http.ResponseWriter, r *http.Request, path string) {
	f, err := os.Open(path)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	defer f.Close()
	head := make([]byte, 512)
	n, _ := io.ReadFull(f, head)
	if _, err := f.Seek(0, io.SeekStart); err != nil {
		http.NotFound(w, r)
		return
	}
	h := w.Header()
	h.Set("Content-Type", http.DetectContentType(head[:n]))
	// an image id names one picture for good
	h.Set("Cache-Control", "public, max-age=31536000, immutable")
	h.Set("Content-Security-Policy", "default-src 'none'; sandbox")
	h.Set("X-Content-Type-Options", "nosniff")
	http.ServeContent(w, r, "", time.Time{}, f)
}

// widgetsText is widgets as words a moderation case can keep: a line a
// widget, with its games and what was said about them, or a card's title,
// words, stats and link.
func widgetsText(raw string) string {
	var list []widget
	if raw == "" || json.Unmarshal([]byte(raw), &list) != nil {
		return raw
	}
	lines := make([]string, 0, len(list))
	for _, w := range list {
		var parts []string
		if _, ok := musicKinds[w.Type]; ok {
			for _, m := range w.Music {
				name := m.Name
				if m.By != "" {
					name += " (" + m.By + ")"
				}
				parts = append(parts, name)
			}
		} else if w.Type == "countdown" {
			parts = append(parts, strings.TrimSpace(w.Emoji+" "+w.Title))
		} else if w.Type == "localtime" {
			parts = append(parts, w.Zone)
		} else if w.Type == "watching" || w.Type == "screen" {
			for _, t := range w.Screen {
				parts = append(parts, t.Title)
			}
		} else if w.Type == "reading" || w.Type == "books" {
			for _, b := range w.Books {
				name := b.Title
				if b.Author != "" {
					name += " (" + b.Author + ")"
				}
				parts = append(parts, name)
			}
		} else if w.Type == "stickers" {
			parts = append(parts, strings.Join(w.Packs, ", "))
		} else if w.Type == "custom" {
			parts = append(parts, w.Title)
			if w.Text != "" {
				parts = append(parts, w.Text)
			}
			for _, st := range w.Stats {
				parts = append(parts, st.Label+": "+st.Value)
			}
			links := w.Links
			if links == nil && w.Link != nil {
				links = []widgetLink{*w.Link}
			}
			for _, l := range links {
				parts = append(parts, strings.TrimSpace(l.Label+" "+l.URL))
			}
		} else {
			for _, g := range w.Games {
				name := g.Name
				if len(g.Tags) > 0 {
					name += " [" + strings.Join(g.Tags, ", ") + "]"
				}
				parts = append(parts, name)
			}
			if w.Note != "" {
				parts = append(parts, w.Note)
			}
		}
		lines = append(lines, w.Type+": "+strings.Join(parts, " / "))
	}
	return strings.Join(lines, "\n")
}
