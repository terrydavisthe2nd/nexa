// Package screen finds the shows and films people put on their profile, on
// TMDB (https://www.themoviedb.org), and keeps their posters. Searches go from
// this server with its own key, and posters are fetched once and kept here, so
// nobody using Nexa contacts TMDB themselves. TMDB requires this notice where
// its data is shown, which the apps carry: "This product uses the TMDB API but
// is not endorsed or certified by TMDB."
package screen

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

// Title is a show or a film TMDB knows.
type Title struct {
	// ID is TMDB's, as a string; Kind says which list it is in ("tv" or "movie").
	ID    string `json:"id"`
	Kind  string `json:"kind"`
	Title string `json:"title"`
	Year  int    `json:"year,omitempty"`
	// Poster is TMDB's poster path without its slash ("pPHpeI2X1qEd1CS1SeyrdhZ4qnT.jpg"), or "".
	Poster string `json:"poster,omitempty"`
}

var (
	// ErrOff means no TMDB key is set, so there is no search.
	ErrOff = errors.New("screen: TMDB is not set up")
	// ErrUnknown is a poster TMDB does not have.
	ErrUnknown = errors.New("screen: not known")

	// IDPattern and PosterPattern are the shapes of TMDB's ids and poster files.
	IDPattern     = regexp.MustCompile(`^[0-9]{1,10}$`)
	PosterPattern = regexp.MustCompile(`^[A-Za-z0-9]{10,40}\.(jpg|png)$`)
)

const (
	searchTTL     = 24 * time.Hour
	maxCached     = 2000
	maxPosterSize = 4 << 20
)

// PosterSizes are the sizes a poster is kept at: TMDB's small and large widths.
var PosterSizes = map[string]string{"s": "w185", "l": "w500"}

// Client searches TMDB and keeps posters.
type Client struct {
	// Key is a TMDB v4 Read Access Token.
	Key string
	// Dir is where posters are kept.
	Dir    string
	HTTP   *http.Client
	API    string
	Images string
	Log    *slog.Logger

	limit *rate.Limiter

	mu       sync.Mutex
	searches map[string]searchHit

	posterMu sync.Mutex
	inFlight map[string]chan struct{}
}

type searchHit struct {
	at     time.Time
	titles []Title
}

// New is a client for this key, keeping posters in dir. An empty key makes a client that is off.
func New(key, dir string) *Client {
	return &Client{
		Key:      strings.TrimSpace(key),
		Dir:      dir,
		HTTP:     &http.Client{Timeout: 15 * time.Second},
		API:      "https://api.themoviedb.org/3",
		Images:   "https://image.tmdb.org/t/p",
		Log:      slog.Default(),
		limit:    rate.NewLimiter(20, 20),
		searches: map[string]searchHit{},
		inFlight: map[string]chan struct{}{},
	}
}

// On says whether there is a search to offer.
func (c *Client) On() bool { return c != nil && c.Key != "" }

// Search finds shows and films by name, TMDB's best first, leaving out people.
// Answers are kept for a day.
func (c *Client) Search(ctx context.Context, q string, limit int) ([]Title, error) {
	if !c.On() {
		return nil, ErrOff
	}
	q = strings.Join(strings.Fields(q), " ")
	if q == "" {
		return nil, nil
	}
	if len(q) > 100 {
		q = q[:100]
	}
	key := strings.ToLower(q)
	c.mu.Lock()
	if hit, ok := c.searches[key]; ok && time.Since(hit.at) < searchTTL {
		c.mu.Unlock()
		return trim(hit.titles, limit), nil
	}
	c.mu.Unlock()
	if err := c.limit.Wait(ctx); err != nil {
		return nil, err
	}
	u := c.API + "/search/multi?" + url.Values{"query": {q}, "include_adult": {"false"}, "page": {"1"}}.Encode()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+c.Key)
	req.Header.Set("Accept", "application/json")
	res, err := c.HTTP.Do(req)
	if err != nil {
		return nil, err
	}
	raw, err := io.ReadAll(io.LimitReader(res.Body, 2<<20))
	res.Body.Close()
	if err != nil {
		return nil, err
	}
	if res.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("screen: TMDB answered %d", res.StatusCode)
	}
	var in struct {
		Results []struct {
			ID        int64  `json:"id"`
			MediaType string `json:"media_type"`
			Title     string `json:"title"`
			Name      string `json:"name"`
			Release   string `json:"release_date"`
			FirstAir  string `json:"first_air_date"`
			Poster    string `json:"poster_path"`
			Adult     bool   `json:"adult"`
		} `json:"results"`
	}
	if err := json.Unmarshal(raw, &in); err != nil {
		return nil, err
	}
	out := []Title{}
	for _, r := range in.Results {
		if (r.MediaType != "tv" && r.MediaType != "movie") || r.ID <= 0 || r.Adult {
			continue
		}
		t := Title{ID: strconv.FormatInt(r.ID, 10), Kind: r.MediaType, Title: strings.TrimSpace(r.Title)}
		date := r.Release
		if r.MediaType == "tv" {
			t.Title, date = strings.TrimSpace(r.Name), r.FirstAir
		}
		if t.Title == "" || len(t.Title) > 200 {
			continue
		}
		if len(date) >= 4 {
			t.Year, _ = strconv.Atoi(date[:4])
		}
		if p := strings.TrimPrefix(r.Poster, "/"); PosterPattern.MatchString(p) {
			t.Poster = p
		}
		out = append(out, t)
	}
	c.mu.Lock()
	if len(c.searches) > maxCached {
		c.searches = map[string]searchHit{}
	}
	c.searches[key] = searchHit{at: time.Now(), titles: out}
	c.mu.Unlock()
	return trim(out, limit), nil
}

func trim(list []Title, limit int) []Title {
	if len(list) > limit {
		return list[:limit]
	}
	return list
}

func (c *Client) posterPath(file, size string) string {
	return filepath.Join(c.Dir, "posters", size+"-"+file)
}

// Kept is where a poster is kept at a size, if it has been fetched already.
func (c *Client) Kept(file, size string) (string, bool) {
	if _, ok := PosterSizes[size]; !ok || !PosterPattern.MatchString(file) {
		return "", false
	}
	path := c.posterPath(file, size)
	if fi, err := os.Stat(path); err == nil && fi.Size() > 0 {
		return path, true
	}
	return "", false
}

// PosterFile is a poster kept at a size ("s" or "l"), fetched the first time
// it is asked for. Only TMDB's own image addresses are fetched.
func (c *Client) PosterFile(ctx context.Context, file, size string) (string, error) {
	width, ok := PosterSizes[size]
	if !ok || !PosterPattern.MatchString(file) {
		return "", ErrUnknown
	}
	path := c.posterPath(file, size)
	if fi, err := os.Stat(path); err == nil && fi.Size() > 0 {
		return path, nil
	}
	c.posterMu.Lock()
	if wait, ok := c.inFlight[path]; ok {
		c.posterMu.Unlock()
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
	c.posterMu.Unlock()
	fctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 15*time.Second)
	err := c.fetchPoster(fctx, file, width, path)
	cancel()
	c.posterMu.Lock()
	delete(c.inFlight, path)
	c.posterMu.Unlock()
	close(done)
	return path, err
}

func (c *Client) fetchPoster(ctx context.Context, file, width, path string) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.Images+"/"+width+"/"+file, nil)
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
		return fmt.Errorf("screen: poster answered %d", res.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(res.Body, maxPosterSize+1))
	if err != nil {
		return err
	}
	if len(data) > maxPosterSize || len(data) < 8 {
		return errors.New("screen: poster of the wrong size")
	}
	if ct := http.DetectContentType(data); ct != "image/jpeg" && ct != "image/png" {
		return fmt.Errorf("screen: poster is %s", ct)
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o750); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(path), ".poster-*")
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
