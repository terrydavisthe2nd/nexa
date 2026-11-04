package media

import (
	"bytes"
	"context"
	"errors"
	"image"
	"image/color"
	"image/gif"
	"image/jpeg"
	"image/png"
	"io"
	"os"
	"path/filepath"
	"sync"
	"testing"

	"evilion.xyz/chat/internal/db"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/store"
)

func pngBytes(w, h int) []byte {
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for i := range img.Pix {
		img.Pix[i] = byte(i)
	}
	var buf bytes.Buffer
	png.Encode(&buf, img)
	return buf.Bytes()
}

func jpegBytes() []byte {
	img := image.NewRGBA(image.Rect(0, 0, 8, 8))
	var buf bytes.Buffer
	jpeg.Encode(&buf, img, nil)
	return buf.Bytes()
}

func gifBytes(frames int) []byte {
	pal := color.Palette{color.Black, color.White}
	g := &gif.GIF{}
	for i := 0; i < frames; i++ {
		img := image.NewPaletted(image.Rect(0, 0, 4, 4), pal)
		img.SetColorIndex(i%4, 0, 1)
		g.Image = append(g.Image, img)
		g.Delay = append(g.Delay, 10)
	}
	var buf bytes.Buffer
	gif.EncodeAll(&buf, g)
	return buf.Bytes()
}

func TestSniff(t *testing.T) {
	cases := []struct {
		name string
		b    []byte
		mime string
		anim bool
		ok   bool
	}{
		{"png", pngBytes(2, 2), "image/png", false, true},
		{"apng", append(append([]byte{}, pngBytes(2, 2)[:33]...), []byte("acTL")...), "image/png", true, true},
		{"jpeg", jpegBytes(), "image/jpeg", false, true},
		{"gif1", gifBytes(1), "image/gif", false, true},
		{"gif2", gifBytes(3), "image/gif", true, true},
		{"webp", []byte("RIFF\x00\x00\x00\x00WEBPVP8 \x00\x00\x00\x00\x00"), "image/webp", false, true},
		{"webp-anim", []byte("RIFF\x00\x00\x00\x00WEBPVP8X\x0a\x00\x00\x00\x12\x00\x00\x00"), "image/webp", true, true},
		{"svg", []byte("<svg xmlns='http://www.w3.org/2000/svg'/>"), "", false, false},
		{"text", []byte("hello"), "", false, false},
		{"empty", nil, "", false, false},
	}
	for _, c := range cases {
		mime, anim, ok := Sniff(c.b)
		if mime != c.mime || anim != c.anim || ok != c.ok {
			t.Errorf("%s: got (%s,%v,%v) want (%s,%v,%v)", c.name, mime, anim, ok, c.mime, c.anim, c.ok)
		}
	}
}

func newMedia(t *testing.T) (*Store, *store.Store) {
	t.Helper()
	dir := t.TempDir()
	d, err := db.Open(filepath.Join(dir, "chat.db"))
	if err != nil {
		t.Fatal(err)
	}
	if err := d.Migrate(context.Background()); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { d.Close() })
	st := store.New(d)
	m, err := New(filepath.Join(dir, "media"), st)
	if err != nil {
		t.Fatal(err)
	}
	return m, st
}

func TestPutPublic(t *testing.T) {
	m, st := newMedia(t)
	ctx := context.Background()
	lim := Limits{MaxBytes: 10 << 20, MaxW: 64, MaxH: 64}
	owner := ids.New()
	b, err := m.PutPublic(ctx, "avatar", bytes.NewReader(pngBytes(16, 8)), lim, owner)
	if err != nil {
		t.Fatal(err)
	}
	if b.Mime != "image/png" || *b.Width != 16 || *b.Height != 8 || b.RefCount != 1 || b.Kind != "avatar" || b.Animated {
		t.Errorf("blob: %+v", b)
	}
	f, got, err := m.Open(ctx, b.ID)
	if err != nil || got.ID != b.ID {
		t.Fatal("open", err)
	}
	f.Close()
	// dedupe increments ref
	b2, err := m.PutPublic(ctx, "avatar", bytes.NewReader(pngBytes(16, 8)), lim, owner)
	if err != nil || b2.ID != b.ID || b2.RefCount != 2 {
		t.Errorf("dedupe: %v %+v", err, b2)
	}
	fromDB, _ := st.BlobByID(ctx, b.ID)
	if fromDB.RefCount != 2 {
		t.Errorf("ref in db = %d", fromDB.RefCount)
	}
	// same bytes as another kind is a separate row
	b3, err := m.PutPublic(ctx, "emoji", bytes.NewReader(pngBytes(16, 8)), lim, owner)
	if err != nil || b3.ID == b.ID {
		t.Errorf("other kind: %v %+v", err, b3)
	}
	if _, err := m.PutPublic(ctx, "avatar", bytes.NewReader(pngBytes(100, 10)), lim, owner); !errors.Is(err, ErrBadDimensions) {
		t.Errorf("dims: %v", err)
	}
	if _, err := m.PutPublic(ctx, "avatar", bytes.NewReader([]byte("not an image")), lim, owner); !errors.Is(err, ErrUnsupported) {
		t.Errorf("unsupported: %v", err)
	}
	if _, err := m.PutPublic(ctx, "avatar", bytes.NewReader(pngBytes(16, 8)), Limits{MaxBytes: 10, MaxW: 64, MaxH: 64}, owner); !errors.Is(err, ErrTooLarge) {
		t.Errorf("too large: %v", err)
	}
	ag, err := m.PutPublic(ctx, "sticker", bytes.NewReader(gifBytes(2)), lim, owner)
	if err != nil || !ag.Animated || ag.Mime != "image/gif" {
		t.Errorf("animated gif: %v %+v", err, ag)
	}
	// GC: drop refs on the avatar and sweep
	_ = st.AddBlobRef(ctx, b.ID, -2)
	n, err := m.GC(ctx, ids.New().Time().UnixMilli()+1)
	if err != nil || n != 1 {
		t.Errorf("gc: %v %d", err, n)
	}
	if _, _, err := m.Open(ctx, b.ID); !errors.Is(err, store.ErrNotFound) {
		t.Error("avatar should be gone")
	}
	if f, _, err := m.Open(ctx, b3.ID); err != nil {
		t.Error("emoji sharing bytes must survive:", err)
	} else {
		f.Close()
	}
}

func TestPutEncrypted(t *testing.T) {
	m, _ := newMedia(t)
	ctx := context.Background()
	owner := ids.New()
	data := bytes.Repeat([]byte{0xAB}, 1000)
	a, err := m.PutEncrypted(ctx, bytes.NewReader(data), 2000, -1, owner)
	if err != nil || a.RefCount != 0 || !a.Encrypted || a.Size != 1000 || *a.OwnerID != owner {
		t.Fatalf("put: %v %+v", err, a)
	}
	b, err := m.PutEncrypted(ctx, bytes.NewReader(data), 2000, -1, owner)
	if err != nil || b.ID == a.ID {
		t.Errorf("identical ciphertext must still be separate rows: %v", err)
	}
	if _, err := m.PutEncrypted(ctx, bytes.NewReader(data), 999, -1, owner); !errors.Is(err, ErrTooLarge) {
		t.Errorf("too large: %v", err)
	}
	f, _, err := m.Open(ctx, a.ID)
	if err != nil {
		t.Fatal(err)
	}
	size, _ := f.Seek(0, io.SeekEnd)
	f.Close()
	if size != 1000 {
		t.Errorf("size on disk %d", size)
	}
	if err := m.Remove(ctx, a); err != nil {
		t.Fatal(err)
	}
	if f, _, err := m.Open(ctx, b.ID); err != nil {
		t.Error("b shares bytes with a and must survive a's removal:", err)
	} else {
		f.Close()
	}
}

// A blob listed as unreferenced but picked up again before the sweep reaches
// it keeps its row and its file.
func TestRemoveKeepsABlobReferencedAgain(t *testing.T) {
	m, st := newMedia(t)
	ctx := context.Background()
	lim := Limits{MaxBytes: 10 << 20, MaxW: 64, MaxH: 64}
	b, err := m.PutPublic(ctx, "avatar", bytes.NewReader(pngBytes(8, 8)), lim, ids.New())
	if err != nil {
		t.Fatal(err)
	}
	_ = st.AddBlobRef(ctx, b.ID, -1)
	listed, _ := st.UnreferencedBlobs(ctx, ids.New().Time().UnixMilli()+1)
	if len(listed) != 1 {
		t.Fatalf("listed: %d", len(listed))
	}
	// the same picture is uploaded again in between
	if again, err := m.PutPublic(ctx, "avatar", bytes.NewReader(pngBytes(8, 8)), lim, ids.New()); err != nil || again.ID != b.ID {
		t.Fatalf("reupload: %v", err)
	}
	if err := m.Remove(ctx, listed[0]); err == nil {
		t.Error("remove should refuse a blob in use")
	}
	if f, _, err := m.Open(ctx, b.ID); err != nil {
		t.Error("the picture must survive:", err)
	} else {
		f.Close()
	}
}

// Two people setting the same picture at the same moment both miss the lookup
// that dedupes by hash, and the unique index on (sha256, kind) refuses the
// second insert. Both should still come away with the blob.
func TestConcurrentIdenticalUploadsBothSucceed(t *testing.T) {
	m, st := newMedia(t)
	ctx := context.Background()
	lim := Limits{MaxBytes: 10 << 20, MaxW: 64, MaxH: 64}
	img := pngBytes(12, 12)

	const n = 4
	var wg sync.WaitGroup
	got := make([]*store.Blob, n)
	errs := make([]error, n)
	start := make(chan struct{})
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			<-start
			got[i], errs[i] = m.PutPublic(ctx, "avatar", bytes.NewReader(img), lim, ids.New())
		}(i)
	}
	close(start)
	wg.Wait()
	for i := range got {
		if errs[i] != nil {
			t.Fatalf("upload %d: %v", i, errs[i])
		}
		if got[i].ID != got[0].ID {
			t.Errorf("upload %d got blob %v, want the shared %v", i, got[i].ID, got[0].ID)
		}
	}
	// every one of them holds a reference, so none can collect the others' bytes
	b, err := st.BlobByID(ctx, got[0].ID)
	if err != nil {
		t.Fatal(err)
	}
	if b.RefCount != n {
		t.Errorf("ref_count = %d after %d uploads of the same image", b.RefCount, n)
	}
}

// Finishing an upload must be safe to repeat. The bytes are renamed into place
// before the rows that record them are written, so a database error in between
// used to leave an upload whose spool had gone: every retry answered 404 and
// the person had to send the whole file again, however large it was.
func TestFinishSpoolIsIdempotent(t *testing.T) {
	m, st := newMedia(t)
	ctx := context.Background()
	u := &store.User{Username: "uploader", DisplayName: "uploader", AuthHash: "h", KDFSalt: []byte{1}, KDFParams: "{}", PublicKey: []byte{1}, EncPrivateKey: []byte{1}}
	if err := st.CreateUser(ctx, u); err != nil {
		t.Fatal(err)
	}
	owner := u.ID
	up, err := st.CreateUpload(ctx, owner, 5)
	if err != nil {
		t.Fatal(err)
	}
	piece, n, err := m.StagePiece(bytes.NewReader([]byte("hello")), 1<<20)
	if err != nil {
		t.Fatal(err)
	}
	defer os.Remove(piece)
	if _, err := m.AppendSpool(up.ID, 0, piece); err != nil {
		t.Fatal(err)
	}
	if n != 5 {
		t.Fatalf("staged %d bytes", n)
	}

	b, err := m.FinishSpool(ctx, up.ID, owner, 5)
	if err != nil {
		t.Fatal(err)
	}
	// the spool is gone now, which is the state the failed retry used to see
	if _, err := os.Stat(m.SpoolPath(up.ID)); err == nil {
		t.Fatal("the spool should have been renamed away")
	}
	// so simulate the database write having failed: drop the rows it wrote and
	// finish again, exactly as the retried request does
	if _, err := st.DeleteBlob(ctx, b.ID); err != nil {
		t.Fatal(err)
	}
	again, err := m.FinishSpool(ctx, up.ID, owner, 5)
	if err != nil {
		t.Fatalf("a repeated finish must pick up the committed bytes: %v", err)
	}
	if again.SHA256 != b.SHA256 {
		t.Errorf("the second finish named the blob %q, want %q", again.SHA256, b.SHA256)
	}
	f, got, err := m.Open(ctx, again.ID)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	if got.Size != 5 {
		t.Errorf("size %d", got.Size)
	}

	// and an upload with neither a spool nor committed bytes is simply not there
	if _, err := m.FinishSpool(ctx, ids.New(), owner, 5); !errors.Is(err, ErrNotFound) {
		t.Errorf("finishing nothing = %v, want ErrNotFound", err)
	}
}
