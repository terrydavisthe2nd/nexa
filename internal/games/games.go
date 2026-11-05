// Package games finds the games people put on their profile, from IGDB
// (https://www.igdb.com), and keeps their cover art. Searches go from this
// server, with its own credentials, and the covers are fetched once and kept
// here, so nobody using Nexa contacts IGDB themselves. IGDB asks to be
// credited where its data is shown; the app does so under its game search.
package games

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/time/rate"
)

// Game is a game IGDB knows.
type Game struct {
	// ID is IGDB's, as a string.
	ID   string `json:"id"`
	Name string `json:"name"`
	// Cover is the image id of its cover, or "".
	Cover string `json:"cover,omitempty"`
	// Year it first came out, or 0.
	Year int `json:"year,omitempty"`
}

var (
	// ErrOff means no IGDB credentials are set, so there is no search.
	ErrOff = errors.New("games: IGDB is not set up")
	// ErrUnknown is a game, or a cover, IGDB does not have.
	ErrUnknown = errors.New("games: not known")

	// IDPattern and CoverPattern are the shapes of IGDB's game and image ids.
	IDPattern    = regexp.MustCompile(`^[0-9]{1,10}$`)
	CoverPattern = regexp.MustCompile(`^[a-z0-9]{6,32}$`)
)

const (
	searchTTL    = 24 * time.Hour
	maxCached    = 2000
	maxCoverSize = 8 << 20
)

// CoverSizes are the sizes a cover is kept at: IGDB's small and big covers,
// at twice their size for sharp screens.
var CoverSizes = map[string]string{"s": "t_cover_small_2x", "l": "t_cover_big_2x"}

// PictureSizes are the sizes a game page's screenshots and artwork are kept
// at: a thumbnail for the strip and the full picture for the stage and the
// viewer. Only pictures a game page named (Details) are fetched at these.
var PictureSizes = map[string]string{"t": "t_screenshot_med_2x", "b": "t_1080p"}

// sizeKind is IGDB's name for one of the sizes kept, and whether it is a
// game page's picture size.
func sizeKind(size string) (kind string, picture bool, ok bool) {
	if k, ok := CoverSizes[size]; ok {
		return k, false, true
	}
	if k, ok := PictureSizes[size]; ok {
		return k, true, true
	}
	return "", false, false
}

// Client searches IGDB and keeps covers.
type Client struct {
	ClientID, ClientSecret string
	// Dir is where covers are kept.
	Dir    string
	HTTP   *http.Client
	API    string
	Auth   string
	Images string
	Log    *slog.Logger

	limit *rate.Limiter

	mu       sync.Mutex
	token    string
	tokenEnd time.Time
	searches map[string]searchHit
	known    map[string]Game

	coverMu  sync.Mutex
	inFlight map[string]chan struct{}

	detailMu sync.Mutex
	details  map[string]detailHit
	// pictures are the screenshots and artwork game pages named, which alone
	// are fetched at PictureSizes
	pictures map[string]bool
	matches  map[string]matchHit

	index nameIndexState
}

type searchHit struct {
	at    time.Time
	games []Game
}

// New is a client for these credentials, keeping covers in dir. Empty
// credentials make a client that is off.
func New(clientID, clientSecret, dir string) *Client {
	return &Client{
		ClientID:     strings.TrimSpace(clientID),
		ClientSecret: strings.TrimSpace(clientSecret),
		Dir:          dir,
		HTTP:         &http.Client{Timeout: 15 * time.Second},
		API:          "https://api.igdb.com/v4",
		Auth:         "https://id.twitch.tv/oauth2/token",
		Images:       "https://images.igdb.com/igdb/image/upload",
		Log:          slog.Default(),
		// IGDB lets each client make four requests a second
		limit:    rate.NewLimiter(4, 4),
		searches: map[string]searchHit{},
		known:    map[string]Game{},
		inFlight: map[string]chan struct{}{},
		details:  map[string]detailHit{},
		pictures: map[string]bool{},
		matches:  map[string]matchHit{},
	}
}

// On says whether there is a search to offer.
func (c *Client) On() bool {
	return c != nil && c.ClientID != "" && c.ClientSecret != ""
}

// accessToken is the app's token, fetched again a day before it runs out.
func (c *Client) accessToken(ctx context.Context, fresh bool) (string, error) {
	c.mu.Lock()
	if !fresh && c.token != "" && time.Until(c.tokenEnd) > 24*time.Hour {
		t := c.token
		c.mu.Unlock()
		return t, nil
	}
	c.mu.Unlock()
	q := url.Values{"client_id": {c.ClientID}, "client_secret": {c.ClientSecret}, "grant_type": {"client_credentials"}}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.Auth+"?"+q.Encode(), nil)
	if err != nil {
		return "", err
	}
	res, err := c.HTTP.Do(req)
	if err != nil {
		return "", err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return "", fmt.Errorf("games: Twitch refused the IGDB credentials (%d)", res.StatusCode)
	}
	var tok struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int64  `json:"expires_in"`
	}
	if err := json.NewDecoder(io.LimitReader(res.Body, 64<<10)).Decode(&tok); err != nil || tok.AccessToken == "" {
		return "", errors.New("games: no access token in Twitch's answer")
	}
	c.mu.Lock()
	c.token, c.tokenEnd = tok.AccessToken, time.Now().Add(time.Duration(tok.ExpiresIn)*time.Second)
	c.mu.Unlock()
	return tok.AccessToken, nil
}

// post asks one of IGDB's endpoints, once more with a fresh token if the
// one kept was refused, and answers the raw JSON.
func (c *Client) post(ctx context.Context, endpoint, body string) ([]byte, error) {
	if !c.On() {
		return nil, ErrOff
	}
	for attempt := 0; attempt < 2; attempt++ {
		if err := c.limit.Wait(ctx); err != nil {
			return nil, err
		}
		token, err := c.accessToken(ctx, attempt > 0)
		if err != nil {
			return nil, err
		}
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.API+"/"+endpoint, strings.NewReader(body))
		if err != nil {
			return nil, err
		}
		req.Header.Set("Client-ID", c.ClientID)
		req.Header.Set("Authorization", "Bearer "+token)
		req.Header.Set("Accept", "application/json")
		res, err := c.HTTP.Do(req)
		if err != nil {
			return nil, err
		}
		raw, err := io.ReadAll(io.LimitReader(res.Body, 4<<20))
		res.Body.Close()
		if err != nil {
			return nil, err
		}
		if res.StatusCode == http.StatusUnauthorized && attempt == 0 {
			continue
		}
		if res.StatusCode != http.StatusOK {
			return nil, fmt.Errorf("games: IGDB answered %d", res.StatusCode)
		}
		return raw, nil
	}
	return nil, errors.New("games: IGDB refused the token twice")
}

// query asks IGDB's games endpoint for games in the shape searches use.
func (c *Client) query(ctx context.Context, body string) ([]Game, error) {
	raw, err := c.post(ctx, "games", body)
	if err != nil {
		return nil, err
	}
	{
		var in []struct {
			ID    int64  `json:"id"`
			Name  string `json:"name"`
			Cover *struct {
				ImageID string `json:"image_id"`
			} `json:"cover"`
			FirstRelease int64 `json:"first_release_date"`
		}
		if err := json.Unmarshal(raw, &in); err != nil {
			return nil, err
		}
		out := make([]Game, 0, len(in))
		for _, g := range in {
			name := strings.TrimSpace(g.Name)
			if g.ID <= 0 || name == "" || len(name) > 120 {
				continue
			}
			game := Game{ID: strconv.FormatInt(g.ID, 10), Name: name}
			if g.Cover != nil && CoverPattern.MatchString(g.Cover.ImageID) {
				game.Cover = g.Cover.ImageID
			}
			if g.FirstRelease > 0 {
				game.Year = time.Unix(g.FirstRelease, 0).UTC().Year()
			}
			out = append(out, game)
		}
		c.remember(out)
		return out, nil
	}
}

func (c *Client) remember(list []Game) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.known) > maxCached {
		c.known = map[string]Game{}
	}
	for _, g := range list {
		c.known[g.ID] = g
	}
}

// quote writes text as a string in IGDB's query language.
func quote(s string) string {
	return `"` + strings.NewReplacer(`\`, ``, `"`, ``, "\n", " ", ";", " ").Replace(s) + `"`
}

// Search finds games by name, IGDB's best first, leaving out editions and
// versions of a game already in the list. Answers are kept for a day.
func (c *Client) Search(ctx context.Context, q string, limit int) ([]Game, error) {
	q = strings.Join(strings.Fields(q), " ")
	if q == "" {
		return nil, nil
	}
	if len(q) > 80 {
		q = q[:80]
	}
	key := strings.ToLower(q)
	c.mu.Lock()
	if hit, ok := c.searches[key]; ok && time.Since(hit.at) < searchTTL {
		c.mu.Unlock()
		return trim(hit.games, limit), nil
	}
	c.mu.Unlock()
	list, err := c.query(ctx, "search "+quote(q)+"; fields name,cover.image_id,first_release_date; where version_parent = null; limit 30;")
	if err != nil {
		return nil, err
	}
	c.mu.Lock()
	if len(c.searches) > maxCached {
		c.searches = map[string]searchHit{}
	}
	c.searches[key] = searchHit{at: time.Now(), games: list}
	c.mu.Unlock()
	return trim(list, limit), nil
}

func trim(list []Game, limit int) []Game {
	if len(list) > limit {
		return list[:limit]
	}
	return list
}

// Lookup is what IGDB says about these games, from what a search already
// brought when it can be.
func (c *Client) Lookup(ctx context.Context, ids []string) (map[string]Game, error) {
	out := map[string]Game{}
	var missing []string
	c.mu.Lock()
	for _, id := range ids {
		if g, ok := c.known[id]; ok {
			out[id] = g
		} else if IDPattern.MatchString(id) {
			missing = append(missing, id)
		}
	}
	c.mu.Unlock()
	if len(missing) == 0 {
		return out, nil
	}
	list, err := c.query(ctx, "fields name,cover.image_id,first_release_date; where id = ("+strings.Join(missing, ",")+"); limit 500;")
	if err != nil {
		return out, err
	}
	for _, g := range list {
		out[g.ID] = g
	}
	return out, nil
}

// Kept is where a cover is kept at a size, if it has been fetched already.
func (c *Client) Kept(imageID, size string) (string, bool) {
	if _, _, ok := sizeKind(size); !ok || !CoverPattern.MatchString(imageID) {
		return "", false
	}
	path := filepath.Join(c.Dir, "covers", imageID+"-"+size+".jpg")
	if fi, err := os.Stat(path); err == nil && fi.Size() > 0 {
		return path, true
	}
	return "", false
}

// CoverFile is a game's cover kept at a size ("s" or "l"), or a game page's
// screenshot or artwork ("t" or "b"), fetched the first time it is asked for. Only IGDB's own picture addresses are ever fetched.
func (c *Client) CoverFile(ctx context.Context, imageID, size string) (string, error) {
	kind, picture, ok := sizeKind(size)
	if !ok || !CoverPattern.MatchString(imageID) {
		return "", ErrUnknown
	}
	if picture {
		c.detailMu.Lock()
		named := c.pictures[imageID]
		c.detailMu.Unlock()
		if !named {
			return "", ErrUnknown
		}
	}
	path := filepath.Join(c.Dir, "covers", imageID+"-"+size+".jpg")
	if fi, err := os.Stat(path); err == nil && fi.Size() > 0 {
		return path, nil
	}
	c.coverMu.Lock()
	if wait, ok := c.inFlight[path]; ok {
		c.coverMu.Unlock()
		select {
		case <-wait:
		case <-ctx.Done():
			return "", ctx.Err()
		}
		if fi, err := os.Stat(path); err == nil && fi.Size() > 0 {
			return path, nil
		}
		return "", ErrUnknown
	}
	done := make(chan struct{})
	c.inFlight[path] = done
	c.coverMu.Unlock()
	// the fetch outlives a viewer who gave up, so the next one finds it kept
	fctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 15*time.Second)
	err := c.fetchCover(fctx, kind, imageID, path)
	cancel()
	c.coverMu.Lock()
	delete(c.inFlight, path)
	c.coverMu.Unlock()
	close(done)
	return path, err
}

func (c *Client) fetchCover(ctx context.Context, kind, imageID, path string) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.Images+"/"+kind+"/"+imageID+".jpg", nil)
	if err != nil {
		return err
	}
	res, err := c.HTTP.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	if res.StatusCode == http.StatusNotFound {
		return ErrUnknown
	}
	if res.StatusCode != http.StatusOK {
		return fmt.Errorf("games: cover answered %d", res.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(res.Body, maxCoverSize+1))
	if err != nil {
		return err
	}
	if len(data) > maxCoverSize || len(data) < 8 {
		return errors.New("games: cover of the wrong size")
	}
	if ct := http.DetectContentType(data); ct != "image/jpeg" && ct != "image/png" && ct != "image/webp" {
		return fmt.Errorf("games: cover is %s", ct)
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o750); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(path), ".cover-*")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmp.Name(), path)
}
