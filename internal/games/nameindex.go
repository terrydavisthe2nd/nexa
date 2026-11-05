package games

import (
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/time/rate"
)

// The name index lets a desktop app ask "is there a game called this?" about a
// program running full screen without telling anyone what the program is. It
// holds a short hash of every game's name, never a name, and the app downloads
// it and looks on the device. So a program that turns out not to be a game
// never leaves the computer, and neither does the fact of asking.
//
// The format is the magic "NXGI", a version byte, then two big-endian uint32
// counts and that many sorted hashes of HashBytes each: first games people have
// rated, then the rest. Fewer ratings ask for more evidence (desktop
// games/candidates.ts), since an obscure game can be called anything.

const (
	nameIndexMagic   = "NXGI"
	nameIndexVersion = 1
	// HashBytes of SHA-256 leave false matches about one in a million across half a million names.
	HashBytes     = 5
	nameIndexPage = 500
	// nameIndexEvery is how long an index is good for: new games come slowly.
	nameIndexEvery = 7 * 24 * time.Hour
	nameIndexRetry = time.Hour
)

// gameTypesForIndex are IGDB's game types that are something someone plays
// under their own name: main games, bundles, standalone expansions, remakes,
// remasters, expanded games and ports. DLC, mods, episodes and updates are not.
const gameTypesForIndex = "(0,3,4,8,9,10,11)"

// NameHash is the part of a name the index keeps. Desktop games/candidates.ts
// computes the same; testdata/fold_vectors.json holds the two to it.
func NameHash(name string) []byte {
	sum := sha256.Sum256([]byte(FoldName(name)))
	return sum[:HashBytes]
}

type nameIndexState struct {
	mu   sync.RWMutex
	data []byte
	etag string
	at   time.Time
}

// NameIndex is the current index and its ETag, or false before one is built.
func (c *Client) NameIndex() ([]byte, string, time.Time, bool) {
	c.index.mu.RLock()
	defer c.index.mu.RUnlock()
	return c.index.data, c.index.etag, c.index.at, len(c.index.data) > 0
}

func (c *Client) setNameIndex(data []byte, at time.Time) {
	sum := sha256.Sum256(data)
	c.index.mu.Lock()
	c.index.data, c.index.etag, c.index.at = data, `"`+hex.EncodeToString(sum[:8])+`"`, at
	c.index.mu.Unlock()
}

func (c *Client) nameIndexPath() string { return filepath.Join(c.Dir, "name-index.bin") }

// StartNameIndex loads the index kept on disk and builds a new one when it is
// missing or old, until ctx is done. A failed build keeps the old index.
func (c *Client) StartNameIndex(ctx context.Context) {
	if !c.On() {
		return
	}
	if data, err := os.ReadFile(c.nameIndexPath()); err == nil && validNameIndex(data) {
		if fi, err := os.Stat(c.nameIndexPath()); err == nil {
			c.setNameIndex(data, fi.ModTime())
		}
	}
	go func() {
		for {
			wait := time.Duration(0)
			_, _, at, ok := c.NameIndex()
			if ok {
				wait = time.Until(at.Add(nameIndexEvery))
			}
			if wait > 0 {
				select {
				case <-ctx.Done():
					return
				case <-time.After(wait):
				}
			}
			data, err := c.buildNameIndex(ctx)
			if ctx.Err() != nil {
				return
			}
			if err == nil {
				err = c.keepNameIndex(data)
			}
			if err != nil {
				c.Log.Warn("igdb name index", "err", err)
				select {
				case <-ctx.Done():
					return
				case <-time.After(nameIndexRetry):
				}
				continue
			}
			c.setNameIndex(data, time.Now())
			c.Log.Info("igdb name index built", "bytes", len(data))
		}
	}()
}

func (c *Client) keepNameIndex(data []byte) error {
	if err := os.MkdirAll(c.Dir, 0o750); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(c.Dir, ".name-index-*")
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
	return os.Rename(tmp.Name(), c.nameIndexPath())
}

// buildNameIndex walks every game by id, slowly enough to leave most of IGDB's
// allowance to people searching. Alternative names count: they are what a game
// is called in other places, and what a window may say.
func (c *Client) buildNameIndex(ctx context.Context) ([]byte, error) {
	pace := rate.NewLimiter(rate.Limit(1.5), 1)
	rated := map[[HashBytes]byte]bool{}
	other := map[[HashBytes]byte]bool{}
	add := func(set map[[HashBytes]byte]bool, name string) {
		if FoldName(name) == "" {
			return
		}
		var key [HashBytes]byte
		copy(key[:], NameHash(name))
		set[key] = true
	}
	var last int64
	for {
		if err := pace.Wait(ctx); err != nil {
			return nil, err
		}
		raw, err := c.post(ctx, "games", "fields name,alternative_names.name,total_rating_count; where id > "+strconv.FormatInt(last, 10)+
			" & version_parent = null & game_type = "+gameTypesForIndex+"; sort id asc; limit "+strconv.Itoa(nameIndexPage)+";")
		if err != nil {
			return nil, err
		}
		var page []struct {
			ID    int64  `json:"id"`
			Name  string `json:"name"`
			Rated int    `json:"total_rating_count"`
			Alt   []struct {
				Name string `json:"name"`
			} `json:"alternative_names"`
		}
		if err := json.Unmarshal(raw, &page); err != nil {
			return nil, err
		}
		for _, g := range page {
			set := other
			if g.Rated > 0 {
				set = rated
			}
			add(set, g.Name)
			for _, a := range g.Alt {
				add(set, a.Name)
			}
			last = g.ID
		}
		if len(page) < nameIndexPage {
			break
		}
	}
	if len(rated) == 0 {
		return nil, errors.New("games: IGDB named no rated games")
	}
	for key := range rated {
		delete(other, key)
	}
	return encodeNameIndex(rated, other), nil
}

func encodeNameIndex(rated, other map[[HashBytes]byte]bool) []byte {
	sorted := func(set map[[HashBytes]byte]bool) [][HashBytes]byte {
		list := make([][HashBytes]byte, 0, len(set))
		for k := range set {
			list = append(list, k)
		}
		sort.Slice(list, func(i, j int) bool { return strings.Compare(string(list[i][:]), string(list[j][:])) < 0 })
		return list
	}
	a, b := sorted(rated), sorted(other)
	out := make([]byte, 0, 13+(len(a)+len(b))*HashBytes)
	out = append(out, nameIndexMagic...)
	out = append(out, nameIndexVersion)
	out = binary.BigEndian.AppendUint32(out, uint32(len(a)))
	out = binary.BigEndian.AppendUint32(out, uint32(len(b)))
	for _, k := range a {
		out = append(out, k[:]...)
	}
	for _, k := range b {
		out = append(out, k[:]...)
	}
	return out
}

func validNameIndex(data []byte) bool {
	if len(data) < 13 || string(data[:4]) != nameIndexMagic || data[4] != nameIndexVersion {
		return false
	}
	a, b := binary.BigEndian.Uint32(data[5:]), binary.BigEndian.Uint32(data[9:])
	return uint64(len(data)) == 13+(uint64(a)+uint64(b))*HashBytes
}
