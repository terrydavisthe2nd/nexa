package media

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"evilion.xyz/chat/internal/objstore"
	"evilion.xyz/chat/internal/store"
)

// End-to-end encrypted attachments live in a bucket (Cloudflare R2) instead
// of on this machine's disk. Uploads stream through to it as they arrive
// (PutEncrypted, relay.go). Files that did land here (from apps that send
// pieces, or from before the bucket) are copied there by the mover once
// something holds them (their message was sent): it checks the copy,
// records it in remote_blobs, and only then removes the file here. Reads
// prefer the file here while it exists. Only ciphertext goes:
// pictures, bug report, case and report files, and webhook files stay here,
// so the bucket holds nothing anyone there could read.

// ObjectStore is the bucket: an *objstore.Client, or a stand-in in tests.
type ObjectStore interface {
	objstore.Getter
	Put(ctx context.Context, key string, f io.ReaderAt, size int64, contentType string) error
	Head(ctx context.Context, key string) (int64, error)
	Delete(ctx context.Context, key string) error
	Presign(key string, ttl time.Duration) string
	PutStream(ctx context.Context, key string, r io.Reader, size int64, contentType string) error
	// for uploads relayed to the bucket (relay.go)
	CreateMultipart(ctx context.Context, key, contentType string) (string, error)
	PutPartStream(ctx context.Context, key, uploadID string, n int, r io.Reader, size int64) (objstore.Part, error)
	ListParts(ctx context.Context, key, uploadID string) ([]objstore.Part, error)
	CompleteMultipart(ctx context.Context, key, uploadID string, parts []objstore.Part) error
	AbortMultipart(ctx context.Context, key, uploadID string) error
}

// File is a blob's bytes, on disk (*os.File) or in the bucket.
type File interface {
	io.ReadSeekCloser
	io.ReaderAt
}

// moveAfter is how old a held attachment is before it moves: the people in
// a conversation fetch a new file at once, and do so from here.
var moveAfter = time.Minute

// remoteKey is where content name lives in the bucket.
func remoteKey(name string) string { return "attachments/" + name }

// billed tells the meter about a request the bucket bills.
func (m *Store) billed(class byte) {
	if m.Billed != nil {
		m.Billed(class)
	}
}

// openBytes opens a blob's bytes wherever they are.
func (m *Store) openBytes(ctx context.Context, b *store.Blob) (File, error) {
	name := shaOf(b)
	if f, err := os.Open(m.Path(name)); err == nil {
		return f, nil
	}
	if m.Remote != nil && b.Encrypted {
		if size, ok, err := m.st.RemoteBlobSize(ctx, name); err == nil && ok {
			return objstore.NewReader(ctx, m.Remote, remoteKey(name), size), nil
		}
	}
	return nil, store.ErrNotFound
}

// linkLife is how long a signed link to a file in the bucket works. A link
// cannot be taken back, and whoever holds one can fetch with it until it
// ends, each fetch a read the bucket bills, so it is short: apps ask again
// once one runs out (the web's download worker keeps one for a job's later
// stretches until a minute before its end; Android follows a fresh redirect
// for every request).
const linkLife = 2 * time.Minute

// LinkType marks the answer to a download asked for with X-Nexa-Link: 1
// whose bytes are in the bucket: {"url", "expires_in"} rather than the bytes.
const LinkType = "application/vnd.nexa.link+json"

// serveFromBucket answers a download whose bytes are in the bucket. While
// links are on (LinkOK: under the month's cap on reads, and within this
// account's pace) the app gets a signed link and fetches the bytes from the
// bucket without its token: the bucket refuses a request carrying both, and
// has no business seeing it. The web app asks for the link outright
// (X-Nexa-Link), because WebKit keeps the Authorization header across a
// redirect to another origin; Android's OkHttp and older apps follow a
// redirect, dropping the header as it leaves this server. Otherwise the
// bytes stream through here, ranges included, paced per account (ReadOK).
func (m *Store) serveFromBucket(w http.ResponseWriter, r *http.Request, b *store.Blob) bool {
	if m.Remote == nil || !b.Encrypted {
		return false
	}
	name := shaOf(b)
	size, ok, err := m.st.RemoteBlobSize(r.Context(), name)
	if err != nil || !ok {
		return false
	}
	h := w.Header()
	if m.LinkOK != nil && m.LinkOK(r) {
		m.billed(objstore.ClassB) // the read the app makes with it
		link := m.Remote.Presign(remoteKey(name), linkLife)
		h.Set("Cache-Control", "private, no-store")
		h.Set("Referrer-Policy", "no-referrer")
		if r.Header.Get("X-Nexa-Link") == "1" {
			h.Set("Content-Type", LinkType)
			h.Set("X-Content-Type-Options", "nosniff")
			_ = json.NewEncoder(w).Encode(map[string]any{"url": link, "expires_in": int(linkLife / time.Second)})
			return true
		}
		http.Redirect(w, r, link, http.StatusFound)
		return true
	}
	if m.ReadOK != nil && !m.ReadOK(r) {
		w.Header().Set("Retry-After", "10")
		http.Error(w, "too many downloads at once", http.StatusTooManyRequests)
		return true
	}
	f := objstore.NewReader(r.Context(), m.Remote, remoteKey(name), size)
	defer f.Close()
	h.Set("Content-Type", "application/octet-stream")
	h.Set("Cache-Control", "private, max-age=31536000, immutable")
	h.Set("X-Content-Type-Options", "nosniff")
	http.ServeContent(w, r, "", time.UnixMilli(b.CreatedAt), f)
	return true
}

// MoveResult is what one pass of the mover did.
type MoveResult struct {
	Moved int
	Bytes int64
	// Held says the pass stopped because the bucket had no room under the
	// monthly limit.
	Held bool
}

// MoveToRemote copies up to max held attachments to the bucket, oldest
// first, and removes their files here. room says whether the bucket may
// take size more bytes; a no stops the pass, leaving the rest here.
func (m *Store) MoveToRemote(ctx context.Context, max int, room func(size int64) bool) (MoveResult, error) {
	var res MoveResult
	if m.Remote == nil {
		return res, nil
	}
	list, err := m.st.BlobsToMove(ctx, time.Now().Add(-moveAfter).UnixMilli(), max+m.skipCount())
	if err != nil {
		return res, err
	}
	for _, c := range list {
		if res.Moved >= max {
			break
		}
		if m.skipped(c.Name) {
			continue
		}
		if !room(c.Size) {
			res.Held = true
			return res, nil
		}
		size, err := m.moveOne(ctx, c.Name)
		if err != nil {
			return res, err
		}
		if size > 0 {
			res.Moved++
			res.Bytes += size
		}
	}
	return res, nil
}

// moveOne copies one file to the bucket and removes it here.
func (m *Store) moveOne(ctx context.Context, name string) (int64, error) {
	path := m.Path(name)
	f, err := os.Open(path)
	if errors.Is(err, os.ErrNotExist) {
		// a row whose file is gone has nothing to move; asking again each
		// pass would stall the queue behind it
		m.skip(name)
		return 0, nil
	}
	if err != nil {
		return 0, err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return 0, err
	}
	key := remoteKey(name)
	if err := m.Remote.Put(ctx, key, f, info.Size(), "application/octet-stream"); err != nil {
		return 0, fmt.Errorf("move %s: %w", name, err)
	}
	if got, err := m.Remote.Head(ctx, key); err != nil || got != info.Size() {
		return 0, fmt.Errorf("move %s: the bucket holds %d of %d bytes (%v)", name, got, info.Size(), err)
	}
	unlock := m.lockContent(name)
	defer unlock()
	if err := m.st.AddRemoteBlob(ctx, name, info.Size()); err != nil {
		return 0, err
	}
	// Everything that held it may have gone while it was on its way (Remove
	// takes this lock too, and has already deleted the file here): then the
	// copy is nobody's, and goes the way any such copy does.
	if n, err := m.st.CountBlobsByContent(ctx, name); err != nil {
		return 0, err
	} else if n == 0 {
		return 0, m.st.MarkRemoteGone(ctx, name)
	}
	// Only now may the file here go: every read from here on finds the copy.
	if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
		return 0, err
	}
	if dir, err := os.Open(filepath.Dir(path)); err == nil {
		_ = dir.Sync()
		dir.Close()
	}
	return info.Size(), nil
}

// SweepRemote deletes bucket copies that nothing holds any more (marked
// gone by Remove), up to max, and returns how many went. Each is checked
// again first: the same bytes may have been uploaded again since.
func (m *Store) SweepRemote(ctx context.Context, max int) (int, error) {
	if m.Remote == nil {
		return 0, nil
	}
	names, err := m.st.GoneRemoteBlobs(ctx, max)
	if err != nil {
		return 0, err
	}
	done := 0
	for _, name := range names {
		if err := m.sweepOne(ctx, name); err != nil {
			return done, err
		}
		done++
	}
	return done, nil
}

func (m *Store) sweepOne(ctx context.Context, name string) error {
	unlock := m.lockContent(name)
	defer unlock()
	if n, err := m.st.CountBlobsByContent(ctx, name); err != nil {
		return err
	} else if n > 0 {
		return m.st.KeepRemoteBlob(ctx, name)
	}
	if err := m.Remote.Delete(ctx, remoteKey(name)); err != nil {
		return err
	}
	return m.st.DeleteRemoteBlob(ctx, name)
}

func (m *Store) skip(name string) {
	m.skipMu.Lock()
	defer m.skipMu.Unlock()
	if m.skips == nil {
		m.skips = map[string]bool{}
	}
	m.skips[name] = true
}

func (m *Store) skipped(name string) bool {
	m.skipMu.Lock()
	defer m.skipMu.Unlock()
	return m.skips[name]
}

func (m *Store) skipCount() int {
	m.skipMu.Lock()
	defer m.skipMu.Unlock()
	return len(m.skips)
}
