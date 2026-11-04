package objstore

import (
	"bytes"
	"context"
	"crypto/rand"
	"errors"
	"io"
	"net/http"
	"os"
	"strings"
	"testing"
	"time"
)

// TestBucketRoundTrip runs against a real bucket, only with OBJSTORE_LIVE=1
// and R2_ACCOUNT_ID, R2_BUCKET, R2_ACCESS_KEY_ID and R2_SECRET_ACCESS_KEY
// set, and only on a bucket whose name says it is for tests: it writes and
// deletes objects.
func TestBucketRoundTrip(t *testing.T) {
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
	// R2's smallest part is 5 MiB; three parts, the last a short one
	c.singleMax, c.partSize = 5<<20, 5<<20
	for _, size := range []int64{1 << 10, 2*(5<<20) + 12345} {
		data := make([]byte, size)
		rand.Read(data)
		key := "objstore-test/" + time.Now().Format("150405.000000000")
		if err := c.Put(ctx, key, bytes.NewReader(data), size, "application/octet-stream"); err != nil {
			t.Fatalf("put %d bytes: %v", size, err)
		}
		if got, err := c.Head(ctx, key); err != nil || got != size {
			t.Fatalf("head: %d, %v", got, err)
		}
		// a stretch across the first part boundary when there are parts
		from := min(size-100, int64(5<<20)-50)
		buf := make([]byte, 100)
		if _, err := NewReader(ctx, c, key, size).ReadAt(buf, from); err != nil || !bytes.Equal(buf, data[from:from+100]) {
			t.Fatalf("range read at %d: %v", from, err)
		}
		r := NewReader(ctx, c, key, size)
		r.Seek(7, io.SeekStart)
		all, err := io.ReadAll(r)
		r.Close()
		if err != nil || !bytes.Equal(all, data[7:]) {
			t.Fatalf("read from 7: %d bytes, %v", len(all), err)
		}
		// a signed link works with no credentials at all
		resp, err := http.Get(c.Presign(key, time.Minute))
		if err != nil {
			t.Fatal(err)
		}
		body, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		if resp.StatusCode != 200 || !bytes.Equal(body, data) {
			t.Fatalf("signed link: HTTP %d, %d bytes", resp.StatusCode, len(body))
		}
		if err := c.Delete(ctx, key); err != nil {
			t.Fatal(err)
		}
		if _, err := c.Head(ctx, key); !errors.Is(err, ErrNotFound) {
			t.Fatalf("still there after delete: %v", err)
		}
		if err := c.Delete(ctx, key); err != nil {
			t.Fatalf("deleting what is gone: %v", err)
		}
	}
}
