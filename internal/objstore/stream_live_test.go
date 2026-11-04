package objstore

import (
	"bytes"
	"context"
	"crypto/rand"
	"errors"
	"io"
	"os"
	"strings"
	"testing"
	"time"
)

// TestStreamsToTheBucket checks, against a real bucket (as
// TestBucketRoundTrip), what relayed uploads rest on: bytes streamed with
// no hash up front are stored and checked against the bucket's ETag, a
// stream that ends early stores nothing, and parts join into the file.
func TestStreamsToTheBucket(t *testing.T) {
	if os.Getenv("OBJSTORE_LIVE") != "1" {
		t.Skip("set OBJSTORE_LIVE=1 and the R2_* variables to run against a real bucket")
	}
	bucket := os.Getenv("R2_BUCKET")
	if !strings.Contains(bucket, "test") {
		t.Fatalf("refusing to write to %q: use a test bucket", bucket)
	}
	c, err := New("https://"+os.Getenv("R2_ACCOUNT_ID")+".r2.cloudflarestorage.com", bucket, os.Getenv("R2_ACCESS_KEY_ID"), os.Getenv("R2_SECRET_ACCESS_KEY"))
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()
	const part = 5 << 20
	data := make([]byte, part+700)
	rand.Read(data)

	key := "objstore-test/stream-" + time.Now().Format("150405.000000000")
	if err := c.PutStream(ctx, key, io.MultiReader(bytes.NewReader(data[:1000])), 1000, "application/octet-stream"); err != nil {
		t.Fatal(err)
	}
	defer c.Delete(context.Background(), key)
	if got, err := c.Head(ctx, key); err != nil || got != 1000 {
		t.Fatalf("head: %d, %v", got, err)
	}
	short := key + "-short"
	if err := c.PutStream(ctx, short, bytes.NewReader(data[:999]), 1000, "application/octet-stream"); err == nil {
		t.Fatal("a stream that ended early was taken")
	}
	if _, err := c.Head(ctx, short); !errors.Is(err, ErrNotFound) {
		t.Fatalf("a short stream left something: %v", err)
	}

	mkey := key + "-parts"
	id, err := c.CreateMultipart(ctx, mkey, "application/octet-stream")
	if err != nil {
		t.Fatal(err)
	}
	defer c.AbortMultipart(context.Background(), mkey, id)
	p1, err := c.PutPartStream(ctx, mkey, id, 1, bytes.NewReader(data[:part]), part)
	if err != nil {
		t.Fatal(err)
	}
	p2, err := c.PutPartStream(ctx, mkey, id, 2, bytes.NewReader(data[part:]), 700)
	if err != nil {
		t.Fatal(err)
	}
	parts, err := c.ListParts(ctx, mkey, id)
	if err != nil || len(parts) != 2 || parts[0].ETag != p1.ETag || parts[1].ETag != p2.ETag {
		t.Fatalf("parts: %+v, %v", parts, err)
	}
	if err := c.CompleteMultipart(ctx, mkey, id, parts); err != nil {
		t.Fatal(err)
	}
	defer c.Delete(context.Background(), mkey)
	r := NewReader(ctx, c, mkey, int64(len(data)))
	all, err := io.ReadAll(r)
	r.Close()
	if err != nil || !bytes.Equal(all, data) {
		t.Fatalf("read back %d bytes: %v", len(all), err)
	}
}
