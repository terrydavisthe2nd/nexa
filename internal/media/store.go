package media

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sync"

	"evilion.xyz/chat/internal/db"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/store"
)

const sniffBytes = 1 << 20

// Store keeps blob bytes on disk (content-addressed) and metadata in the store.
type Store struct {
	Dir string
	st  *store.Store

	locksMu sync.Mutex
	locks   map[ids.ID]*uploadLock

	// contentLocks serialise everything that decides whether a stored file
	// is still needed: taking a new reference to existing bytes, and the
	// collector deleting the last row and then the file. Interleaved, an
	// upload could reference a row the collector had just deleted, or keep a
	// file the collector removed a moment later.
	contentMu    sync.Mutex
	contentLocks map[string]*uploadLock

	// Remote, when set, is the bucket end-to-end encrypted attachments move
	// to (remote.go); nil keeps every file on this disk.
	Remote ObjectStore
	// Billed is told about each bucket request that costs money, for the
	// monthly limit: the bucket client reports its own, and this store adds
	// the read an app makes with each signed link it is given.
	Billed func(class byte)
	// ReadOK, when set, says whether a download may read from the bucket
	// now; no answers 429 (api paces reads per account, each one billed).
	ReadOK func(r *http.Request) bool
	skipMu sync.Mutex
	skips  map[string]bool
}

// lockContent holds the lock for one file's bytes; call the result to let go.
func (m *Store) lockContent(sha string) func() {
	m.contentMu.Lock()
	if m.contentLocks == nil {
		m.contentLocks = map[string]*uploadLock{}
	}
	l := m.contentLocks[sha]
	if l == nil {
		l = &uploadLock{}
		m.contentLocks[sha] = l
	}
	l.users++
	m.contentMu.Unlock()
	l.mu.Lock()
	return func() {
		l.mu.Unlock()
		m.contentMu.Lock()
		l.users--
		if l.users == 0 {
			delete(m.contentLocks, sha)
		}
		m.contentMu.Unlock()
	}
}

type uploadLock struct {
	mu    sync.Mutex
	users int
}

// LockUpload serialises the requests that change one resumable upload: a
// retried piece can arrive while the request it replaced is still stuck on its
// body. It returns the unlock function.
func (m *Store) LockUpload(id ids.ID) func() {
	m.locksMu.Lock()
	if m.locks == nil {
		m.locks = map[ids.ID]*uploadLock{}
	}
	l := m.locks[id]
	if l == nil {
		l = &uploadLock{}
		m.locks[id] = l
	}
	l.users++
	m.locksMu.Unlock()
	l.mu.Lock()
	return func() {
		l.mu.Unlock()
		m.locksMu.Lock()
		if l.users--; l.users == 0 {
			delete(m.locks, id)
		}
		m.locksMu.Unlock()
	}
}

// New creates the directory layout under dir.
func New(dir string, st *store.Store) (*Store, error) {
	for _, sub := range []string{"tmp", "blobs"} {
		if err := os.MkdirAll(filepath.Join(dir, sub), 0o750); err != nil {
			return nil, err
		}
	}
	return &Store{Dir: dir, st: st}, nil
}

// Path returns where a blob's bytes live.
func (m *Store) Path(sha string) string {
	if len(sha) < 4 {
		return filepath.Join(m.Dir, "blobs", "xx", sha)
	}
	return filepath.Join(m.Dir, "blobs", sha[:2], sha[2:4], sha)
}

// spool copies r to a temp file (bounded by max+1 bytes) while hashing.
func (m *Store) spool(r io.Reader, max int64) (path string, sha string, size int64, err error) {
	f, err := os.CreateTemp(filepath.Join(m.Dir, "tmp"), "upload-*")
	if err != nil {
		return "", "", 0, err
	}
	path = f.Name()
	h := sha256.New()
	n, err := io.Copy(io.MultiWriter(f, h), io.LimitReader(r, max+1))
	// On disk before anything points at it: the row and the message naming
	// this file are committed durably, and a power cut that kept them but
	// lost the bytes would leave a file that never opens — and, since
	// uploads of the same picture share it, one no re-upload can mend.
	if err == nil {
		err = f.Sync()
	}
	cerr := f.Close()
	if err == nil {
		err = cerr
	}
	if err != nil {
		os.Remove(path)
		return "", "", 0, err
	}
	if n > max {
		os.Remove(path)
		return "", "", 0, ErrTooLarge
	}
	return path, hex.EncodeToString(h.Sum(nil)), n, nil
}

// spoolClean is spool for a file kept readable: a picture's or a video's
// metadata is taken out first (scrub.go), so the hash and size are those of
// the bytes kept, and the same photo sent twice still shares one file.
func (m *Store) spoolClean(r io.Reader, max int64) (path string, sha string, size int64, err error) {
	path, sha, size, err = m.spool(r, max)
	if err != nil {
		return "", "", 0, err
	}
	changed, err := scrubFile(path)
	if err == nil && changed {
		sha, size, err = hashFile(path)
	}
	if err != nil {
		os.Remove(path)
		return "", "", 0, err
	}
	return path, sha, size, nil
}

// PutPublic validates an image upload and stores it under kind, without its
// metadata.
func (m *Store) PutPublic(ctx context.Context, kind string, r io.Reader, lim Limits, owner ids.ID) (*store.Blob, error) {
	tmp, sha, size, err := m.spoolClean(r, lim.MaxBytes)
	if err != nil {
		return nil, err
	}
	defer os.Remove(tmp)
	if size == 0 {
		return nil, ErrUnsupported
	}
	f, err := os.Open(tmp)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	head := make([]byte, sniffBytes)
	n, _ := io.ReadFull(f, head)
	head = head[:n]
	mime, animated, ok := Sniff(head)
	if !ok {
		return nil, ErrUnsupported
	}
	// Animated and video stickers are sticker-pack members only: nowhere else
	// takes anything but pictures. Their dimensions come from their own
	// metadata (Lottie JSON) or stay empty (video), never from an image
	// header, which neither of them has.
	var width, height *int
	if mime == MimeTGS || mime == MimeWebM {
		if kind != "sticker" {
			return nil, ErrUnsupported
		}
		if mime == MimeTGS {
			if _, err := f.Seek(0, io.SeekStart); err != nil {
				return nil, err
			}
			w, h, err := TgsDims(f)
			if err != nil {
				return nil, err
			}
			if err := lim.Check(w, h); err != nil {
				return nil, err
			}
			width, height = &w, &h
		}
	} else {
		if _, err := f.Seek(0, io.SeekStart); err != nil {
			return nil, err
		}
		w, h, err := ImageDims(f)
		if err != nil {
			return nil, err
		}
		if err := lim.Check(w, h); err != nil {
			return nil, err
		}
		width, height = &w, &h
	}
	// a picture the staff took down stays down, whatever it is uploaded as
	if blocked, err := m.st.ImageBlocked(ctx, sha); err != nil {
		return nil, err
	} else if blocked {
		return nil, ErrBlocked
	}
	unlock := m.lockContent(sha)
	defer unlock()
	if existing, err := m.st.BlobBySHA(ctx, sha, kind); err == nil {
		if err := m.st.AddBlobRef(ctx, existing.ID, 1); err == nil {
			existing.RefCount++
			return existing, nil
		} else if !errors.Is(err, store.ErrNotFound) {
			return nil, err
		}
		// the row went before it could be referenced: stored afresh below
	} else if !errors.Is(err, store.ErrNotFound) {
		return nil, err
	}
	if err := m.commit(tmp, sha); err != nil {
		return nil, err
	}
	ow := owner
	b := &store.Blob{SHA256: sha, Size: size, Mime: mime, Kind: kind, Encrypted: false, RefCount: 1, OwnerID: &ow, Width: width, Height: height, Animated: animated}
	if err := m.st.InsertBlob(ctx, b); err != nil {
		// Two people setting the same picture within the same moment both miss
		// the lookup above and both insert; there is a unique index on
		// (sha256, kind), so the second one lost and the person saw a 500 for
		// an upload that had in fact already arrived. The row the other
		// transaction wrote is the same bytes, so take a reference on it.
		if !db.IsUniqueViolation(err) {
			return nil, err
		}
		existing, reErr := m.st.BlobBySHA(ctx, sha, kind)
		if reErr != nil {
			return nil, err
		}
		if err := m.st.AddBlobRef(ctx, existing.ID, 1); err != nil {
			return nil, err
		}
		existing.RefCount++
		return existing, nil
	}
	return b, nil
}

// PutEncrypted stores opaque ciphertext as an unattached attachment (ref 0).
// declared is the request's Content-Length, or -1 when it did not say:
// with a bucket and a declared size the bytes stream straight there as they
// arrive and never touch this disk; otherwise they land here, for the mover.
func (m *Store) PutEncrypted(ctx context.Context, r io.Reader, max, declared int64, owner ids.ID) (*store.Blob, error) {
	if m.Remote != nil && declared > 0 && declared <= max {
		return m.putEncryptedRemote(ctx, r, declared, owner)
	}
	tmp, sha, size, err := m.spool(r, max)
	if err != nil {
		return nil, err
	}
	defer os.Remove(tmp)
	if size == 0 {
		return nil, ErrUnsupported
	}
	// the same ciphertext sent twice (a retried upload) shares one file
	unlock := m.lockContent(sha)
	defer unlock()
	if err := m.commit(tmp, sha); err != nil {
		return nil, err
	}
	ow := owner
	// Random IVs make every ciphertext unique, so no dedupe: give each upload its own row.
	b := &store.Blob{SHA256: sha + "-" + ids.New().String(), Size: size, Mime: "application/octet-stream", Kind: "attachment", Encrypted: true, RefCount: 0, OwnerID: &ow}
	if err := m.st.InsertBlob(ctx, b); err != nil {
		return nil, err
	}
	return b, nil
}

// SpoolPath is where a resumable upload's bytes accumulate.
func (m *Store) SpoolPath(id ids.ID) string {
	return filepath.Join(m.Dir, "tmp", "resume-"+id.String())
}

// StagePiece reads one piece of a resumable upload into a file of its own, so
// a body that never finishes arriving leaves the spool untouched. The caller
// removes the returned file.
func (m *Store) StagePiece(r io.Reader, limit int64) (string, int64, error) {
	f, err := os.CreateTemp(filepath.Join(m.Dir, "tmp"), "piece-*")
	if err != nil {
		return "", 0, err
	}
	path := f.Name()
	n, err := io.Copy(f, io.LimitReader(r, limit+1))
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err == nil && n > limit {
		err = ErrTooLarge
	}
	if err != nil {
		os.Remove(path)
		return "", 0, err
	}
	return path, n, nil
}

// AppendSpool adds a staged piece to a resumable upload at exactly `at`,
// refusing anything that would leave a hole. It returns the new length. The
// caller holds LockUpload and has checked `at` against the database.
func (m *Store) AppendSpool(id ids.ID, at int64, piece string) (int64, error) {
	src, err := os.Open(piece)
	if err != nil {
		return at, err
	}
	defer src.Close()
	f, err := os.OpenFile(m.SpoolPath(id), os.O_CREATE|os.O_WRONLY, 0o640)
	if err != nil {
		return 0, err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return 0, err
	}
	if info.Size() < at {
		// a hole: bytes the database counts are missing from the file
		return info.Size(), ErrBadOffset
	}
	if info.Size() > at {
		// The database only advances once a piece is written and synced, so
		// anything past it was written by a request that never got to record it.
		if err := f.Truncate(at); err != nil {
			return info.Size(), err
		}
	}
	if _, err := f.Seek(at, io.SeekStart); err != nil {
		return at, err
	}
	n, err := io.Copy(f, src)
	if err != nil {
		_ = f.Truncate(at)
		return at, err
	}
	if err := f.Sync(); err != nil {
		return at + n, err
	}
	return at + n, nil
}

// DiscardSpool removes a half-finished upload.
func (m *Store) DiscardSpool(id ids.ID) {
	_ = os.Remove(m.SpoolPath(id))
}

// FinishSpool turns a completed upload of `size` bytes into a blob. The bytes
// are already encrypted by the sender, so they are stored under a name of their
// own rather than hashed for sharing: the same file uploaded twice is two blobs.
//
// Finishing is idempotent. The name comes from the upload's id alone, and the
// blob row and the upload's pointer to it are written together, so every way
// this can stop halfway leaves a state a retry can carry on from. It used to
// carry a random suffix and write the two rows separately, which meant a
// database error after the bytes were committed was unrecoverable: the spool
// had been renamed away, so every retry answered 404 and the person had to
// send the whole file again.
func (m *Store) FinishSpool(ctx context.Context, id ids.ID, owner ids.ID, size int64) (*store.Blob, error) {
	name := uploadName(id)
	if info, err := os.Stat(m.SpoolPath(id)); err == nil {
		if info.Size() == 0 {
			m.DiscardSpool(id)
			return nil, ErrUnsupported
		}
		if info.Size() != size {
			// the file on disk is not what the database counted; it would not decrypt
			m.DiscardSpool(id)
			return nil, ErrBadOffset
		}
		if err := m.commit(m.SpoolPath(id), name); err != nil {
			return nil, err
		}
	} else if committed, err := os.Stat(m.Path(name)); err != nil || committed.Size() != size {
		// no spool and nothing committed under this upload's name, here or
		// in the bucket: there is nothing here to finish
		if moved, ok, rerr := m.st.RemoteBlobSize(ctx, name); rerr != nil || !ok || moved != size {
			return nil, ErrNotFound
		}
	}
	ow := owner
	b := &store.Blob{SHA256: name, Size: size, Mime: "application/octet-stream", Kind: "attachment", Encrypted: true, RefCount: 0, OwnerID: &ow}
	if err := m.st.FinishUploadWithBlob(ctx, id, b); err != nil {
		return nil, err
	}
	return b, nil
}

// putEncryptedRemote streams size bytes of ciphertext to the bucket under a
// name of their own, checks what the bucket holds, and only then records it.
func (m *Store) putEncryptedRemote(ctx context.Context, r io.Reader, size int64, owner ids.ID) (*store.Blob, error) {
	id := ids.New()
	name := uploadName(id)
	key := remoteKey(name)
	if err := m.Remote.PutStream(ctx, key, r, size, "application/octet-stream"); err != nil {
		return nil, fmt.Errorf("put %s: %w", name, err)
	}
	if got, err := m.Remote.Head(ctx, key); err != nil || got != size {
		_ = m.Remote.Delete(context.WithoutCancel(ctx), key)
		return nil, fmt.Errorf("put %s: the bucket holds %d of %d bytes (%v)", name, got, size, err)
	}
	unlock := m.lockContent(name)
	defer unlock()
	if err := m.st.AddRemoteBlob(ctx, name, size); err != nil {
		return nil, err
	}
	ow := owner
	b := &store.Blob{ID: id, SHA256: name, Size: size, Mime: "application/octet-stream", Kind: "attachment", Encrypted: true, RefCount: 0, OwnerID: &ow}
	if err := m.st.InsertBlob(ctx, b); err != nil {
		_ = m.st.MarkRemoteGone(context.WithoutCancel(ctx), name)
		return nil, err
	}
	return b, nil
}

// uploadName is the content name of the file a transfer becomes, here or in
// the bucket: from the upload's id alone, so finishing again finds it.
func uploadName(id ids.ID) string { return hex.EncodeToString([]byte(id.String())) }

// commit moves a spooled file into its content-addressed place.
func (m *Store) commit(tmp, sha string) error {
	dst := m.Path(sha)
	if err := os.MkdirAll(filepath.Dir(dst), 0o750); err != nil {
		return err
	}
	if _, err := os.Stat(dst); err == nil {
		return nil // already present (same bytes)
	}
	if err := os.Rename(tmp, dst); err != nil {
		return fmt.Errorf("commit blob: %w", err)
	}
	// The directory entry has to reach the disk as well as the bytes: a blob
	// row pointing at a file whose name never landed is an attachment that
	// cannot be downloaded and that the collector will not clean up.
	if dir, err := os.Open(filepath.Dir(dst)); err == nil {
		_ = dir.Sync()
		dir.Close()
	}
	return nil
}

// Open returns a blob's bytes, from this disk or from the bucket, and its row.
func (m *Store) Open(ctx context.Context, id ids.ID) (File, *store.Blob, error) {
	b, err := m.st.BlobByID(ctx, id)
	if err != nil {
		return nil, nil, err
	}
	f, err := m.openBytes(ctx, b)
	if err != nil {
		return nil, nil, err
	}
	return f, b, nil
}

// shaOf strips the uniqueness suffix used by encrypted blobs.
func shaOf(b *store.Blob) string {
	if len(b.SHA256) > 64 {
		return b.SHA256[:64]
	}
	return b.SHA256
}

// Remove deletes a blob's metadata and, when no other row shares the same
// bytes, the file.
func (m *Store) Remove(ctx context.Context, b *store.Blob) error {
	unlock := m.lockContent(shaOf(b))
	defer unlock()
	deleted, err := m.st.DeleteBlob(ctx, b.ID)
	if err != nil {
		return err
	}
	if !deleted {
		return errReferenced // picked up again since it was listed
	}
	n, err := m.st.CountBlobsByContent(ctx, shaOf(b))
	if err != nil {
		return err
	}
	if n == 0 {
		_ = os.Remove(m.Path(shaOf(b)))
		m.removeVariants(shaOf(b))
		// The bucket's copy, if it has one, goes with the mover's next pass
		// (SweepRemote), so a long list of removals never waits on it.
		if err := m.st.MarkRemoteGone(ctx, shaOf(b)); err != nil {
			return err
		}
	}
	return nil
}

// errReferenced tells GC a blob it listed is in use again and was kept.
var errReferenced = errors.New("blob is referenced again")

// GC removes unreferenced blobs older than olderThan (unix ms). Returns the count.
func (m *Store) GC(ctx context.Context, olderThan int64) (int, error) {
	list, err := m.st.UnreferencedBlobs(ctx, olderThan)
	if err != nil {
		return 0, err
	}
	n := 0
	for _, b := range list {
		if err := m.Remove(ctx, b); err == nil {
			n++
		}
	}
	return n, nil
}
