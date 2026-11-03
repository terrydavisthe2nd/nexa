// Package books finds the books people put on their profile, on Open Library
// (https://openlibrary.org), and keeps their covers. Searches go from this
// server, and covers are fetched once and kept here, so nobody using Nexa
// contacts Open Library themselves. Open Library needs no key; it asks to be
// credited where its data is shown, which the app does under its search.
package books

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

// Book is a book Open Library knows: one work, whatever its editions.
type Book struct {
	// ID is Open Library's work key ("OL27482W").
	ID     string `json:"id"`
	Title  string `json:"title"`
	Author string `json:"author,omitempty"`
	// Cover is Open Library's cover id, or "".
	Cover string `json:"cover,omitempty"`
	Year  int    `json:"year,omitempty"`
}

var (
	// ErrUnknown is a cover Open Library does not have.
	ErrUnknown = errors.New("books: not known")

	// IDPattern and CoverPattern are the shapes of a work key and a cover id.
	IDPattern    = regexp.MustCompile(`^OL[0-9]{1,12}W$`)
	CoverPattern = regexp.MustCompile(`^[0-9]{1,12}$`)
)

const (
	searchTTL    = 24 * time.Hour
	maxCached    = 2000
	maxCoverSize = 4 << 20
)

// CoverSizes are the sizes a cover is kept at: Open Library's medium and large.
var CoverSizes = map[string]string{"s": "M", "l": "L"}

// Client searches Open Library and keeps covers.
type Client struct {
	// Dir is where covers are kept.
	Dir    string
	HTTP   *http.Client
	API    string
	Covers string
	Agent  string
	Log    *slog.Logger

	limit *rate.Limiter

	mu       sync.Mutex
	searches map[string]searchHit

	coverMu  sync.Mutex
	inFlight map[string]chan struct{}
}

type searchHit struct {
	at    time.Time
	books []Book
}

// New is a client keeping covers in dir.
func New(dir string) *Client {
	return &Client{
		Dir:    dir,
		HTTP:   &http.Client{Timeout: 25 * time.Second},
		API:    "https://openlibrary.org",
		Covers: "https://covers.openlibrary.org",
		// Open Library asks heavy users to say who they are
		Agent:    "Nexa (https://nexa.fish)",
		Log:      slog.Default(),
		limit:    rate.NewLimiter(3, 3),
		searches: map[string]searchHit{},
		inFlight: map[string]chan struct{}{},
	}
}

// Search finds books by title or author, Open Library's best first. Answers
// are kept for a day.
func (c *Client) Search(ctx context.Context, q string, limit int) ([]Book, error) {
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
		return trim(hit.books, limit), nil
	}
	c.mu.Unlock()
	if err := c.limit.Wait(ctx); err != nil {
		return nil, err
	}
	u := c.API + "/search.json?" + url.Values{
		"q":      {q},
		"fields": {"key,title,author_name,cover_i,first_publish_year"},
		"limit":  {"24"},
	}.Encode()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", c.Agent)
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
		return nil, fmt.Errorf("books: Open Library answered %d", res.StatusCode)
	}
	var in struct {
		Docs []struct {
			Key     string   `json:"key"`
			Title   string   `json:"title"`
			Authors []string `json:"author_name"`
			Cover   int64    `json:"cover_i"`
			Year    int      `json:"first_publish_year"`
		} `json:"docs"`
	}
	if err := json.Unmarshal(raw, &in); err != nil {
		return nil, err
	}
	out := []Book{}
	for _, d := range in.Docs {
		id := strings.TrimPrefix(d.Key, "/works/")
		title := strings.TrimSpace(d.Title)
		if !IDPattern.MatchString(id) || title == "" || len(title) > 200 {
			continue
		}
		b := Book{ID: id, Title: title, Year: d.Year}
		if len(d.Authors) > 0 {
			b.Author = strings.TrimSpace(d.Authors[0])
		}
		if d.Cover > 0 {
			b.Cover = strconv.FormatInt(d.Cover, 10)
		}
		out = append(out, b)
	}
	c.mu.Lock()
	if len(c.searches) > maxCached {
		c.searches = map[string]searchHit{}
	}
	c.searches[key] = searchHit{at: time.Now(), books: out}
	c.mu.Unlock()
	return trim(out, limit), nil
}

func trim(list []Book, limit int) []Book {
	if len(list) > limit {
		return list[:limit]
	}
	return list
}

func (c *Client) coverPath(id, size string) string {
	return filepath.Join(c.Dir, "covers", id+"-"+size+".jpg")
}

// Kept is where a cover is kept at a size, if it has been fetched already.
func (c *Client) Kept(id, size string) (string, bool) {
	if _, ok := CoverSizes[size]; !ok || !CoverPattern.MatchString(id) {
		return "", false
	}
	path := c.coverPath(id, size)
	if fi, err := os.Stat(path); err == nil && fi.Size() > 0 {
		return path, true
	}
	return "", false
}

// CoverFile is a book's cover kept at a size ("s" or "l"), fetched the first
// time it is asked for. Only Open Library's own cover addresses are fetched.
func (c *Client) CoverFile(ctx context.Context, id, size string) (string, error) {
	letter, ok := CoverSizes[size]
	if !ok || !CoverPattern.MatchString(id) {
		return "", ErrUnknown
	}
	path := c.coverPath(id, size)
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
	fctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 20*time.Second)
	err := c.fetchCover(fctx, id, letter, path)
	cancel()
	c.coverMu.Lock()
	delete(c.inFlight, path)
	c.coverMu.Unlock()
	close(done)
	return path, err
}

func (c *Client) fetchCover(ctx context.Context, id, letter, path string) error {
	// default=false answers 404 for a cover Open Library has not got, rather than a blank picture
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.Covers+"/b/id/"+id+"-"+letter+".jpg?default=false", nil)
	if err != nil {
		return err
	}
	req.Header.Set("User-Agent", c.Agent)
	res, err := c.HTTP.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	if res.StatusCode == http.StatusNotFound {
		return ErrUnknown
	}
	if res.StatusCode != http.StatusOK {
		return fmt.Errorf("books: cover answered %d", res.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(res.Body, maxCoverSize+1))
	if err != nil {
		return err
	}
	if len(data) > maxCoverSize || len(data) < 8 {
		return errors.New("books: cover of the wrong size")
	}
	if ct := http.DetectContentType(data); ct != "image/jpeg" && ct != "image/png" && ct != "image/gif" {
		return fmt.Errorf("books: cover is %s", ct)
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
