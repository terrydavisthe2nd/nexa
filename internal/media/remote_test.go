package media

import (
	"bytes"
	"context"
	"crypto/rand"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/objstore"
	"evilion.xyz/chat/internal/objstore/memstore"
)

// A held attachment moves to the bucket and its file here goes only once the
// copy is there; reads and downloads find it there; one nothing holds is
// never moved; the monthly limit holds files back; and a removed file's copy
// is deleted from the bucket.
func TestAttachmentsMoveToTheBucket(t *testing.T) {
	m, st := newMedia(t)
	ctx := context.Background()
	bucket := &memstore.Bucket{}
	m.Remote = bucket
	defer func(was time.Duration) { moveAfter = was }(moveAfter)
	moveAfter = -time.Second

	put := func() ([]byte, ids.ID) {
		time.Sleep(3 * time.Millisecond) // oldest first needs times apart
		data := make([]byte, 3000)
		rand.Read(data)
		b, err := m.PutEncrypted(ctx, bytes.NewReader(data), 1<<20, -1, ids.New())
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
	if !bucket.Has(remoteKey(nameOf(sentID))) {
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
	// downloads stream through here, never a link to the bucket, ranges too
	b, _ := st.BlobByID(ctx, sentID)
	for _, linkAsked := range []bool{false, true} {
		req := httptest.NewRequest(http.MethodGet, "/media/attachments/x", nil)
		if linkAsked {
			req.Header.Set("X-Nexa-Link", "1") // what apps from before asked with
		}
		w := httptest.NewRecorder()
		m.ServeEncrypted(w, req, b)
		if w.Code != http.StatusOK || w.Header().Get("Location") != "" || !bytes.Equal(w.Body.Bytes(), sent) {
			t.Fatalf("download: %d %q %d bytes", w.Code, w.Header().Get("Location"), w.Body.Len())
		}
	}
	ranged := httptest.NewRequest(http.MethodGet, "/media/attachments/x", nil)
	ranged.Header.Set("Range", "bytes=10-19")
	w := httptest.NewRecorder()
	m.ServeEncrypted(w, ranged, b)
	if w.Code != http.StatusPartialContent || !bytes.Equal(w.Body.Bytes(), sent[10:20]) {
		t.Fatalf("range: %d %d bytes", w.Code, w.Body.Len())
	}
	// past an account's pace, a read is turned down rather than made
	m.ReadOK = func(*http.Request) bool { return false }
	w = httptest.NewRecorder()
	m.ServeEncrypted(w, httptest.NewRequest(http.MethodGet, "/media/attachments/x", nil), b)
	if w.Code != http.StatusTooManyRequests {
		t.Fatalf("over the pace: %d", w.Code)
	}
	m.ReadOK = nil

	// with room again the held one goes; the unsent one never does
	if res, err := m.MoveToRemote(ctx, 10, func(int64) bool { return true }); err != nil || res.Moved != 1 || res.Held {
		t.Fatalf("second pass: %+v %v", res, err)
	}
	if bucket.Has(remoteKey(nameOf(unsentID))) {
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
	if bucket.Has(remoteKey(nameOf(heldID))) == false || bucket.Has(remoteKey(shaOf(b))) {
		t.Fatal("the sweep deleted the wrong copy")
	}
	if _, ok, _ := st.RemoteBlobSize(ctx, shaOf(b)); ok {
		t.Fatal("the deleted copy is still recorded")
	}
}

// With a bucket, an attachment of a declared size streams straight there:
// nothing is written to this disk, and a body that ends early leaves no
// file and no row behind.
func TestAttachmentsStreamToTheBucket(t *testing.T) {
	m, st := newMedia(t)
	ctx := context.Background()
	bucket := &memstore.Bucket{}
	m.Remote = bucket
	data := make([]byte, 5000)
	rand.Read(data)
	b, err := m.PutEncrypted(ctx, bytes.NewReader(data), 1<<20, int64(len(data)), ids.New())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(m.Path(shaOf(b))); !os.IsNotExist(err) {
		t.Fatalf("it was written here: %v", err)
	}
	f, _, err := m.Open(ctx, b.ID)
	if err != nil {
		t.Fatal(err)
	}
	got, _ := io.ReadAll(f)
	f.Close()
	if !bytes.Equal(got, data) {
		t.Fatal("the bucket's copy differs")
	}
	before, _ := st.RemoteBlobBytes(ctx)
	if _, err := m.PutEncrypted(ctx, bytes.NewReader(data[:4000]), 1<<20, int64(len(data)), ids.New()); err == nil {
		t.Fatal("a body that ended early was kept")
	}
	if after, _ := st.RemoteBlobBytes(ctx); after != before {
		t.Fatalf("a failed upload was recorded: %d to %d bytes", before, after)
	}
}
