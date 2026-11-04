package media

import (
	"bytes"
	"context"
	"crypto/rand"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/objstore"
)

// memBucket stands in for R2.
type memBucket struct {
	mu   sync.Mutex
	objs map[string][]byte
}

func (b *memBucket) Put(_ context.Context, key string, f io.ReaderAt, size int64, _ string) error {
	data, err := io.ReadAll(io.NewSectionReader(f, 0, size))
	if err != nil {
		return err
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.objs == nil {
		b.objs = map[string][]byte{}
	}
	b.objs[key] = data
	return nil
}

func (b *memBucket) Head(_ context.Context, key string) (int64, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if d, ok := b.objs[key]; ok {
		return int64(len(d)), nil
	}
	return 0, objstore.ErrNotFound
}

func (b *memBucket) Delete(_ context.Context, key string) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	delete(b.objs, key)
	return nil
}

func (b *memBucket) Get(_ context.Context, key string, from, n int64) (io.ReadCloser, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	d, ok := b.objs[key]
	if !ok {
		return nil, objstore.ErrNotFound
	}
	d = d[from:]
	if n > 0 {
		d = d[:n]
	}
	return io.NopCloser(bytes.NewReader(d)), nil
}

func (b *memBucket) Presign(key string, _ time.Duration) string {
	return "https://bucket.test/" + key + "?X-Amz-Signature=x"
}

func (b *memBucket) has(key string) bool {
	_, err := b.Head(context.Background(), key)
	return err == nil
}

// A held attachment moves to the bucket and its file here goes only once the
// copy is there; reads and downloads find it there; one nothing holds is
// never moved; the monthly limit holds files back; and a removed file's copy
// is deleted from the bucket.
func TestAttachmentsMoveToTheBucket(t *testing.T) {
	m, st := newMedia(t)
	ctx := context.Background()
	bucket := &memBucket{}
	m.Remote = bucket
	var reads int
	m.Billed = func(class byte) {
		if class == objstore.ClassB {
			reads++
		}
	}
	defer func(was time.Duration) { moveAfter = was }(moveAfter)
	moveAfter = -time.Second

	put := func() ([]byte, ids.ID) {
		time.Sleep(3 * time.Millisecond) // oldest first needs times apart
		data := make([]byte, 3000)
		rand.Read(data)
		b, err := m.PutEncrypted(ctx, bytes.NewReader(data), 1<<20, ids.New())
		if err != nil {
			t.Fatal(err)
		}
		return data, b.ID
	}
	sent, sentID := put()
	_, unsentID := put()
	held, heldID := put()
	for _, id := range []ids.ID{sentID, heldID} {
		if err := st.AddBlobRef(ctx, id, 1); err != nil {
			t.Fatal(err)
		}
	}
	nameOf := func(id ids.ID) string {
		b, err := st.BlobByID(ctx, id)
		if err != nil {
			t.Fatal(err)
		}
		return shaOf(b)
	}

	// the limit has room for one file only
	room := 1
	res, err := m.MoveToRemote(ctx, 10, func(int64) bool { room--; return room >= 0 })
	if err != nil || res.Moved != 1 || !res.Held {
		t.Fatalf("first pass: %+v %v", res, err)
	}
	if !bucket.has(remoteKey(nameOf(sentID))) {
		t.Fatal("the older held file did not reach the bucket")
	}
	if _, err := os.Stat(m.Path(nameOf(sentID))); !os.IsNotExist(err) {
		t.Fatalf("its file here was kept: %v", err)
	}
	for _, id := range []ids.ID{unsentID, heldID} {
		if _, err := os.Stat(m.Path(nameOf(id))); err != nil {
			t.Fatalf("a file that was not moved went: %v", err)
		}
	}

	// reads come from the bucket now
	f, _, err := m.Open(ctx, sentID)
	if err != nil {
		t.Fatal(err)
	}
	got, err := io.ReadAll(f)
	f.Close()
	if err != nil || !bytes.Equal(got, sent) {
		t.Fatalf("read back %d bytes: %v", len(got), err)
	}
	b, _ := st.BlobByID(ctx, sentID)
	w := httptest.NewRecorder()
	m.ServeEncrypted(w, httptest.NewRequest(http.MethodGet, "/media/attachments/x", nil), b)
	if w.Code != http.StatusFound || !strings.HasPrefix(w.Header().Get("Location"), "https://bucket.test/attachments/") || w.Header().Get("Cache-Control") != "private, no-store" || reads != 1 {
		t.Fatalf("download: %d %q %q, %d reads counted", w.Code, w.Header().Get("Location"), w.Header().Get("Cache-Control"), reads)
	}
	// the web app asks for the link itself, to fetch it without its token
	asked := httptest.NewRequest(http.MethodGet, "/media/attachments/x", nil)
	asked.Header.Set("X-Nexa-Link", "1")
	w = httptest.NewRecorder()
	m.ServeEncrypted(w, asked, b)
	if w.Code != http.StatusOK || w.Header().Get("Content-Type") != LinkType || !strings.Contains(w.Body.String(), `"url":"https://bucket.test/attachments/`) || !strings.Contains(w.Body.String(), `"expires_in":3600`) {
		t.Fatalf("link asked for: %d %q %s", w.Code, w.Header().Get("Content-Type"), w.Body.String())
	}

	// with room again the held one goes; the unsent one never does
	if res, err := m.MoveToRemote(ctx, 10, func(int64) bool { return true }); err != nil || res.Moved != 1 || res.Held {
		t.Fatalf("second pass: %+v %v", res, err)
	}
	if bucket.has(remoteKey(nameOf(unsentID))) {
		t.Fatal("a file nothing holds was moved")
	}
	if got, err := io.ReadAll(objstore.NewReader(ctx, bucket, remoteKey(nameOf(heldID)), 3000)); err != nil || !bytes.Equal(got, held) {
		t.Fatalf("the bucket's copy differs: %v", err)
	}

	// removed: the copy goes with the next sweep
	if err := st.AddBlobRef(ctx, sentID, -1); err != nil {
		t.Fatal(err)
	}
	b, _ = st.BlobByID(ctx, sentID)
	if err := m.Remove(ctx, b); err != nil {
		t.Fatal(err)
	}
	if n, err := m.SweepRemote(ctx, 10); err != nil || n != 1 {
		t.Fatalf("sweep: %d %v", n, err)
	}
	if bucket.has(remoteKey(nameOf(heldID))) == false || bucket.has(remoteKey(shaOf(b))) {
		t.Fatal("the sweep deleted the wrong copy")
	}
	if _, ok, _ := st.RemoteBlobSize(ctx, shaOf(b)); ok {
		t.Fatal("the deleted copy is still recorded")
	}
}
