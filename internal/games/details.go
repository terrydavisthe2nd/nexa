package games

import (
	"context"
	"encoding/json"
	"math"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode"
)

// Details is what a game's page shows: everything IGDB says about the game
// that is worth reading, its pictures and trailers, similar games and where
// it is sold.
type Details struct {
	Game
	Summary   string `json:"summary,omitempty"`
	Storyline string `json:"storyline,omitempty"`
	// Released is when it first came out, in Unix milliseconds, or 0.
	Released   int64       `json:"released,omitempty"`
	Genres     []string    `json:"genres"`
	Themes     []string    `json:"themes"`
	Modes      []string    `json:"modes"`
	Platforms  []string    `json:"platforms"`
	Developers []string    `json:"developers"`
	Publishers []string    `json:"publishers"`
	Rating     int         `json:"rating,omitempty"`
	Ratings    int         `json:"rating_count,omitempty"`
	AgeRatings []AgeRating `json:"age_ratings"`
	Shots      []Picture   `json:"screenshots"`
	Artworks   []Picture   `json:"artworks"`
	Videos     []Video     `json:"videos"`
	Similar    []Game      `json:"similar"`
	Links      []Link      `json:"links"`
}

// AgeRating is one board's rating ("PEGI", "18").
type AgeRating struct {
	Board  string `json:"board"`
	Rating string `json:"rating"`
}

// Picture is a screenshot or artwork: IGDB's image id and its shape.
type Picture struct {
	ID string `json:"id"`
	W  int    `json:"w,omitempty"`
	H  int    `json:"h,omitempty"`
}

// Video is a trailer or other video on YouTube.
type Video struct {
	ID   string `json:"id"`
	Name string `json:"name,omitempty"`
}

// Link is where the game is sold, or its own site: Kind names the store.
type Link struct {
	Kind string `json:"kind"`
	URL  string `json:"url"`
}

const (
	detailTTL   = 24 * time.Hour
	maxDetails  = 500
	maxPictures = 20000
	maxList     = 12
	maxText     = 4000
)

type detailHit struct {
	at time.Time
	d  *Details
}

type matchHit struct {
	at time.Time
	id string
}

var youTubeID = regexp.MustCompile(`^[A-Za-z0-9_-]{11}$`)

// linkKinds are IGDB's website types a game page offers, by the name the
// apps draw them with.
var linkKinds = map[int]string{
	1: "official", 3: "wikipedia", 10: "apple", 11: "apple", 12: "googleplay", 13: "steam", 15: "itch",
	16: "epic", 17: "gog", 22: "xbox", 23: "playstation", 24: "nintendo", 26: "gamejolt",
}

// linkOrder is the order links are shown in: the game's own site, then stores, then Wikipedia.
var linkOrder = map[string]int{
	"official": 0, "steam": 1, "epic": 2, "gog": 3, "itch": 4, "playstation": 5, "xbox": 6, "nintendo": 7,
	"apple": 8, "googleplay": 9, "gamejolt": 10, "wikipedia": 11,
}

const detailFields = "fields name,summary,storyline,first_release_date,cover.image_id,genres.name,themes.name," +
	"game_modes.name,platforms.name,involved_companies.company.name,involved_companies.developer," +
	"involved_companies.publisher,total_rating,total_rating_count,age_ratings.organization.name," +
	"age_ratings.rating_category.rating,screenshots.image_id,screenshots.width,screenshots.height," +
	"artworks.image_id,artworks.width,artworks.height,videos.video_id,videos.name,similar_games.name," +
	"similar_games.cover.image_id,similar_games.first_release_date,websites.url,websites.type;"

type named struct {
	Name string `json:"name"`
}

type rawPicture struct {
	ImageID string `json:"image_id"`
	Width   int    `json:"width"`
	Height  int    `json:"height"`
}

type rawDetails struct {
	ID        int64  `json:"id"`
	Name      string `json:"name"`
	Summary   string `json:"summary"`
	Storyline string `json:"storyline"`
	Released  int64  `json:"first_release_date"`
	Cover     *struct {
		ImageID string `json:"image_id"`
	} `json:"cover"`
	Genres    []named `json:"genres"`
	Themes    []named `json:"themes"`
	Modes     []named `json:"game_modes"`
	Platforms []named `json:"platforms"`
	Companies []struct {
		Company   *named `json:"company"`
		Developer bool   `json:"developer"`
		Publisher bool   `json:"publisher"`
	} `json:"involved_companies"`
	Rating      float64 `json:"total_rating"`
	RatingCount int     `json:"total_rating_count"`
	AgeRatings  []struct {
		Organization *named `json:"organization"`
		Category     *struct {
			Rating string `json:"rating"`
		} `json:"rating_category"`
	} `json:"age_ratings"`
	Screenshots []rawPicture `json:"screenshots"`
	Artworks    []rawPicture `json:"artworks"`
	Videos      []struct {
		VideoID string `json:"video_id"`
		Name    string `json:"name"`
	} `json:"videos"`
	Similar []struct {
		ID    int64  `json:"id"`
		Name  string `json:"name"`
		Cover *struct {
			ImageID string `json:"image_id"`
		} `json:"cover"`
		Released int64 `json:"first_release_date"`
	} `json:"similar_games"`
	Websites []struct {
		URL  string `json:"url"`
		Type int    `json:"type"`
	} `json:"websites"`
}

// Details is a game's page, kept for a day. ErrUnknown when IGDB has no such game.
func (c *Client) Details(ctx context.Context, id string) (*Details, error) {
	if !IDPattern.MatchString(id) {
		return nil, ErrUnknown
	}
	c.detailMu.Lock()
	if hit, ok := c.details[id]; ok && time.Since(hit.at) < detailTTL {
		c.detailMu.Unlock()
		return hit.d, nil
	}
	c.detailMu.Unlock()
	raw, err := c.post(ctx, "games", detailFields+" where id = "+id+"; limit 1;")
	if err != nil {
		return nil, err
	}
	var in []rawDetails
	if err := json.Unmarshal(raw, &in); err != nil {
		return nil, err
	}
	if len(in) == 0 {
		return nil, ErrUnknown
	}
	d := cleanDetails(in[0])
	if d == nil {
		return nil, ErrUnknown
	}
	c.detailMu.Lock()
	if len(c.details) >= maxDetails {
		c.details = map[string]detailHit{}
	}
	if len(c.pictures) >= maxPictures {
		c.pictures = map[string]bool{}
	}
	c.details[id] = detailHit{at: time.Now(), d: d}
	for _, p := range d.Shots {
		c.pictures[p.ID] = true
	}
	for _, p := range d.Artworks {
		c.pictures[p.ID] = true
	}
	c.detailMu.Unlock()
	c.remember(append([]Game{d.Game}, d.Similar...))
	return d, nil
}

// text is IGDB's text, trimmed and held to n bytes on a rune boundary.
func text(s string, n int) string {
	s = strings.TrimSpace(s)
	if len(s) <= n {
		return s
	}
	cut := n
	for cut > 0 && !utfStart(s[cut]) {
		cut--
	}
	return strings.TrimSpace(s[:cut]) + "…"
}

func utfStart(b byte) bool { return b&0xC0 != 0x80 }

func names(list []named) []string {
	out := []string{}
	seen := map[string]bool{}
	for _, n := range list {
		name := text(n.Name, 80)
		if name == "" || seen[name] {
			continue
		}
		seen[name] = true
		out = append(out, name)
		if len(out) == maxList {
			break
		}
	}
	return out
}

func pictures(list []rawPicture, most int) []Picture {
	out := []Picture{}
	for _, p := range list {
		if !CoverPattern.MatchString(p.ImageID) {
			continue
		}
		out = append(out, Picture{ID: p.ImageID, W: p.Width, H: p.Height})
		if len(out) == most {
			break
		}
	}
	return out
}

// cleanLink is a website's address when it is a plain web address worth opening.
func cleanLink(raw string) string {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || (u.Scheme != "https" && u.Scheme != "http") || u.Host == "" || u.User != nil || len(raw) > 1024 {
		return ""
	}
	return u.String()
}

func cleanDetails(g rawDetails) *Details {
	name := text(g.Name, 120)
	if g.ID <= 0 || name == "" {
		return nil
	}
	d := &Details{
		Game:       Game{ID: strconv.FormatInt(g.ID, 10), Name: name},
		Summary:    text(g.Summary, maxText),
		Storyline:  text(g.Storyline, maxText),
		Genres:     names(g.Genres),
		Themes:     names(g.Themes),
		Modes:      names(g.Modes),
		Platforms:  names(g.Platforms),
		Shots:      pictures(g.Screenshots, 20),
		Artworks:   pictures(g.Artworks, 10),
		Videos:     []Video{},
		Similar:    []Game{},
		Links:      []Link{},
		AgeRatings: []AgeRating{},
		Developers: []string{},
		Publishers: []string{},
	}
	if g.Cover != nil && CoverPattern.MatchString(g.Cover.ImageID) {
		d.Cover = g.Cover.ImageID
	}
	if g.Released > 0 {
		d.Released = g.Released * 1000
		d.Year = time.Unix(g.Released, 0).UTC().Year()
	}
	for _, ic := range g.Companies {
		if ic.Company == nil {
			continue
		}
		n := text(ic.Company.Name, 80)
		if n == "" {
			continue
		}
		if ic.Developer && len(d.Developers) < maxList {
			d.Developers = append(d.Developers, n)
		}
		if ic.Publisher && len(d.Publishers) < maxList {
			d.Publishers = append(d.Publishers, n)
		}
	}
	// a rating from a handful of votes says little; IGDB's own pages hide those too
	if g.RatingCount >= 3 && g.Rating > 0 && g.Rating <= 100 {
		d.Rating = int(math.Round(g.Rating))
		d.Ratings = g.RatingCount
	}
	seenBoard := map[string]bool{}
	for _, a := range g.AgeRatings {
		if a.Organization == nil || a.Category == nil {
			continue
		}
		board := strings.ReplaceAll(text(a.Organization.Name, 20), "_", " ")
		rating := text(a.Category.Rating, 20)
		if board == "" || rating == "" || seenBoard[board] {
			continue
		}
		seenBoard[board] = true
		d.AgeRatings = append(d.AgeRatings, AgeRating{Board: board, Rating: rating})
	}
	for _, v := range g.Videos {
		if !youTubeID.MatchString(v.VideoID) {
			continue
		}
		d.Videos = append(d.Videos, Video{ID: v.VideoID, Name: text(v.Name, 100)})
		if len(d.Videos) == 10 {
			break
		}
	}
	for _, s := range g.Similar {
		sn := text(s.Name, 120)
		if s.ID <= 0 || sn == "" {
			continue
		}
		sg := Game{ID: strconv.FormatInt(s.ID, 10), Name: sn}
		if s.Cover != nil && CoverPattern.MatchString(s.Cover.ImageID) {
			sg.Cover = s.Cover.ImageID
		}
		if s.Released > 0 {
			sg.Year = time.Unix(s.Released, 0).UTC().Year()
		}
		d.Similar = append(d.Similar, sg)
		if len(d.Similar) == maxList {
			break
		}
	}
	seenKind := map[string]bool{}
	for _, w := range g.Websites {
		kind, ok := linkKinds[w.Type]
		addr := cleanLink(w.URL)
		if !ok || addr == "" || seenKind[kind] {
			continue
		}
		seenKind[kind] = true
		d.Links = append(d.Links, Link{Kind: kind, URL: addr})
	}
	sortLinks(d.Links)
	return d
}

func sortLinks(l []Link) {
	for i := 1; i < len(l); i++ {
		for j := i; j > 0 && linkOrder[l[j].Kind] < linkOrder[l[j-1].Kind]; j-- {
			l[j], l[j-1] = l[j-1], l[j]
		}
	}
}

// foldName is a game's name as names are compared: case folded, without
// trademark signs, punctuation or extra spaces.
func foldName(s string) string {
	var b strings.Builder
	space := false
	for _, r := range strings.ToLower(s) {
		switch {
		case unicode.IsLetter(r) || unicode.IsNumber(r):
			if space && b.Len() > 0 {
				b.WriteByte(' ')
			}
			space = false
			b.WriteRune(r)
		case unicode.IsSpace(r) || r == ':' || r == '-' || r == '_':
			space = true
		}
	}
	return b.String()
}

// Match is IGDB's id for the game with exactly this name (case, trademark
// signs and punctuation aside), or "" when none is. Activities name their
// game but carry no IGDB id. Answers, misses too, are kept for a day.
func (c *Client) Match(ctx context.Context, name string) (string, error) {
	key := foldName(name)
	if key == "" || len(name) > 120 {
		return "", nil
	}
	c.detailMu.Lock()
	if hit, ok := c.matches[key]; ok && time.Since(hit.at) < detailTTL {
		c.detailMu.Unlock()
		return hit.id, nil
	}
	c.detailMu.Unlock()
	list, err := c.Search(ctx, name, 30)
	if err != nil {
		return "", err
	}
	id := ""
	for _, g := range list {
		if foldName(g.Name) == key {
			id = g.ID
			break
		}
	}
	c.detailMu.Lock()
	if len(c.matches) >= maxDetails*4 {
		c.matches = map[string]matchHit{}
	}
	c.matches[key] = matchHit{at: time.Now(), id: id}
	c.detailMu.Unlock()
	return id, nil
}
