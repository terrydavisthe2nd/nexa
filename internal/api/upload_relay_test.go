package api

import (
	"bytes"
	"encoding/hex"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"

	"evilion.xyz/chat/internal/objstore/memstore"
)

// sendPart puts part n of a relayed upload.
func sendPart(t *testing.T, ts *httptest.Server, token, id string, n int, data []byte) (*http.Response, map[string]any) {
	t.Helper()
	req, _ := http.NewRequest("PUT", ts.URL+"/api/v1/uploads/"+id+"/parts/"+strconv.Itoa(n), bytes.NewReader(data))
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/octet-stream")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	raw, _ := io.ReadAll(res.Body)
	out := map[string]any{}
	if len(raw) > 0 {
		_ = jsonUnmarshal(raw, &out)
	}
	return res, out
}

// Relayed uploads cost the bucket only what was declared: parts are taken
// for the upload's own numbers at their exact sizes, from its owner, a few
// times each; finishing joins only complete, exact parts and lands nothing
// on this disk; and a cancelled upload leaves nothing in the bucket.
func TestRelayedUploadsHoldTheirLimits(t *testing.T) {
	srv, ts := newTestServer(t)
	bucket := &memstore.Bucket{}
	srv.Media.Remote = bucket
	tok, _ := register(t, ts, "owner")
	other, _ := register(t, ts, "other")

	const size = 20<<20 + 3 // two equal parts, the last a byte shorter
	res, up := do(t, ts, "POST", "/api/v1/uploads", tok, map[string]any{"size": size, "relay": true})
	relay, _ := up["relay"].(map[string]any)
	if res.StatusCode != 201 || relay == nil || relay["parts"] != float64(2) || relay["part_size"] != float64(10<<20+2) {
		t.Fatalf("create: %d %v", res.StatusCode, up)
	}
	id := up["id"].(string)
	// small files go in one request
	if _, small := do(t, ts, "POST", "/api/v1/uploads", tok, map[string]any{"size": 1 << 20, "relay": true}); small["relay"] != nil {
		t.Fatalf("a small file was relayed in parts: %v", small)
	}

	data := bytes.Repeat([]byte{7}, size)
	part1, part2 := data[:10<<20+2], data[10<<20+2:]
	for _, bad := range []struct {
		n    int
		body []byte
	}{{0, part1}, {3, part2}, {1, part1[1:]}, {2, data[10<<20+1:]}} {
		if res, body := sendPart(t, ts, tok, id, bad.n, bad.body); res.StatusCode != 400 {
			t.Fatalf("part %d of %d bytes: %d %v", bad.n, len(bad.body), res.StatusCode, body)
		}
	}
	if res, _ := sendPart(t, ts, other, id, 1, part1); res.StatusCode != 404 {
		t.Fatalf("someone else's upload: %d", res.StatusCode)
	}
	if res, _ := sendChunk(t, ts, tok, id, 0, part1); res.StatusCode != 409 {
		t.Fatalf("a piece for a relayed upload: %d", res.StatusCode)
	}

	if res, body := sendPart(t, ts, tok, id, 1, part1); res.StatusCode != 200 {
		t.Fatalf("part 1: %d %v", res.StatusCode, body)
	}
	res, body := do(t, ts, "POST", "/api/v1/uploads/"+id+"/finish", tok, nil)
	if res.StatusCode != 409 || errCode(body) != "parts_missing" || res.Header.Get("X-Missing-Parts") != "2" {
		t.Fatalf("finish with a part missing: %d %v %q", res.StatusCode, body, res.Header.Get("X-Missing-Parts"))
	}
	if res, body := sendPart(t, ts, tok, id, 2, part2); res.StatusCode != 200 {
		t.Fatalf("part 2: %d %v", res.StatusCode, body)
	}
	res, done := do(t, ts, "POST", "/api/v1/uploads/"+id+"/finish", tok, nil)
	if res.StatusCode != 201 || done["size"] != float64(size) {
		t.Fatalf("finish: %d %v", res.StatusCode, done)
	}
	// the joined file is in the bucket, on the meter, and not on this disk
	name := hex.EncodeToString([]byte(id))
	if n, ok, _ := srv.Store.RemoteBlobSize(t.Context(), name); !ok || n != size || !bucket.Has("attachments/"+name) {
		t.Fatalf("in the bucket: %d %v", n, ok)
	}
	if _, err := osStat(srv.Media.Path(name)); err == nil {
		t.Fatal("the file was written here")
	}
	// finishing again after a lost answer gives the same file
	if res, again := do(t, ts, "POST", "/api/v1/uploads/"+id+"/finish", tok, nil); res.StatusCode != 201 || again["blob_id"] != done["blob_id"] {
		t.Fatalf("finish again: %d %v", res.StatusCode, again)
	}
	if bucket.Open() != 0 {
		t.Fatalf("%d uploads left open in the bucket", bucket.Open())
	}

	// each part may be sent a few times, retries included, and no more
	_, up = do(t, ts, "POST", "/api/v1/uploads", tok, map[string]any{"size": size, "relay": true})
	id = up["id"].(string)
	for i := 0; i < maxPartSends; i++ {
		if res, body := sendPart(t, ts, tok, id, 1, part1); res.StatusCode != 200 {
			t.Fatalf("send %d: %d %v", i+1, res.StatusCode, body)
		}
	}
	if res, body := sendPart(t, ts, tok, id, 1, part1); res.StatusCode != 429 || errCode(body) != "part_retries" {
		t.Fatalf("one send too many: %d %v", res.StatusCode, body)
	}
	// a cancelled upload leaves nothing in the bucket
	if res, _ := do(t, ts, "DELETE", "/api/v1/uploads/"+id, tok, nil); res.StatusCode != 204 || bucket.Open() != 0 {
		t.Fatalf("cancel: %d, %d open", res.StatusCode, bucket.Open())
	}
}
