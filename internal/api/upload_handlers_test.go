package api

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strconv"
	"testing"
	"time"

	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/store"
)

func TestAnArrivingBodyAlreadySpendsUploadQuota(t *testing.T) {
	srv, ts := newTestServer(t)
	token, user := register(t, ts, "alice")
	_, bob := register(t, ts, "bob")
	channel := dmBetween(t, ts, token, bob)
	uid, _ := ids.Parse(user)
	// Leave eight bytes of monthly quota without allocating a large file.
	blob := &store.Blob{SHA256: "quota-test", Size: srv.planLimitsFor("").MonthUploadMax - 8,
		Kind: "attachment", Encrypted: true, OwnerID: &uid}
	if err := srv.Store.InsertBlob(t.Context(), blob); err != nil {
		t.Fatal(err)
	}
	pr, pw := io.Pipe()
	defer pw.CloseWithError(io.ErrUnexpectedEOF)
	firstDone := make(chan int, 1)
	go func() {
		req, _ := http.NewRequest("POST", ts.URL+"/api/v1/channels/"+channel+"/attachments", pr)
		req.ContentLength = 8
		req.Header.Set("Authorization", "Bearer "+token)
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			firstDone <- 0
			return
		}
		res.Body.Close()
		firstDone <- res.StatusCode
	}()
	if _, err := pw.Write([]byte("a")); err != nil {
		t.Fatal(err)
	}
	// A pipe write can finish in the HTTP transport before the handler runs.
	waitFor(t, "the first upload's admission", func() bool {
		srv.uploads.Lock()
		defer srv.uploads.Unlock()
		return srv.uploads.users[uid].open == 1
	})
	req, _ := http.NewRequest("POST", ts.URL+"/api/v1/channels/"+channel+"/attachments", bytes.NewBufferString("abcdefgh"))
	req.Header.Set("Authorization", "Bearer "+token)
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		pw.CloseWithError(err)
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != http.StatusInsufficientStorage {
		t.Errorf("second body bypassed the monthly quota: %d", res.StatusCode)
	}
	_, _ = pw.Write([]byte("bcdefgh"))
	_ = pw.Close()
	if code := <-firstDone; code != http.StatusCreated {
		t.Errorf("admitted upload: %d", code)
	}
	// Completion releases the reservation; the stored blob now counts once.
	srv.uploads.Lock()
	defer srv.uploads.Unlock()
	if len(srv.uploads.users) != 0 || srv.uploads.bytes != 0 {
		t.Errorf("upload reservations leaked: %+v", srv.uploads.users)
	}
}

func TestDuplicateChunkBodiesAreBoundedBeforeReading(t *testing.T) {
	srv, ts := newTestServer(t)
	token, _ := register(t, ts, "alice")
	_, created := do(t, ts, "POST", "/api/v1/uploads", token, map[string]any{"size": 8})
	id := created["id"].(string)
	var writers []*io.PipeWriter
	var completions []chan struct{}
	defer func() {
		for _, w := range writers {
			_ = w.CloseWithError(io.ErrUnexpectedEOF)
		}
		for _, done := range completions {
			<-done
		}
	}()
	for i := 0; i < maxChunkStagesPerUpload; i++ {
		pr, pw := io.Pipe()
		writers = append(writers, pw)
		done := make(chan struct{})
		completions = append(completions, done)
		go func() {
			defer close(done)
			req, _ := http.NewRequest("PATCH", ts.URL+"/api/v1/uploads/"+id, pr)
			req.Header.Set("Authorization", "Bearer "+token)
			req.Header.Set("Upload-Offset", "0")
			if res, err := http.DefaultClient.Do(req); err == nil {
				res.Body.Close()
			}
		}()
		if _, err := pw.Write([]byte("a")); err != nil {
			t.Fatal(err)
		}
	}
	waitFor(t, "both stalled chunks' admission", func() bool {
		srv.uploads.Lock()
		defer srv.uploads.Unlock()
		return srv.uploads.stageCount == maxChunkStagesPerUpload
	})
	if res, body := sendChunk(t, ts, token, id, 0, []byte("abcdefgh")); res.StatusCode != 429 || errCode(body) != "too_many_uploads" {
		t.Errorf("unbounded duplicate chunk: %d %v", res.StatusCode, body)
	}
}

// downloadAttachment fetches a blob's bytes as token.
func downloadAttachment(t *testing.T, ts *httptest.Server, token, blobID string) []byte {
	t.Helper()
	req, _ := http.NewRequest("GET", ts.URL+"/media/attachments/"+blobID, nil)
	req.Header.Set("Authorization", "Bearer "+token)
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	b, _ := io.ReadAll(res.Body)
	return b
}

// A lost reply to finish must not cost the upload: finishing again gives the same file.
func TestFinishingTwiceGivesTheSameFile(t *testing.T) {
	_, ts := newTestServer(t)
	token, _ := register(t, ts, "alice")
	body := bytes.Repeat([]byte("abc"), 400)
	_, created := do(t, ts, "POST", "/api/v1/uploads", token, map[string]any{"size": len(body)})
	id := created["id"].(string)
	if r, out := sendChunk(t, ts, token, id, 0, body); r.StatusCode != 200 {
		t.Fatalf("piece: %d %v", r.StatusCode, out)
	}
	res, first := do(t, ts, "POST", "/api/v1/uploads/"+id+"/finish", token, nil)
	if res.StatusCode != 201 {
		t.Fatalf("finish: %d %v", res.StatusCode, first)
	}
	res, again := do(t, ts, "POST", "/api/v1/uploads/"+id+"/finish", token, nil)
	if res.StatusCode != 201 || again["blob_id"] != first["blob_id"] || again["size"] != first["size"] {
		t.Fatalf("finish again: %d %v, first %v", res.StatusCode, again, first)
	}
	if r, _ := sendChunk(t, ts, token, id, int64(len(body)), []byte("x")); r.StatusCode < 400 {
		t.Errorf("a finished upload takes no more pieces: %d", r.StatusCode)
	}
	if got := downloadAttachment(t, ts, token, first["blob_id"].(string)); !bytes.Equal(got, body) {
		t.Errorf("stored bytes differ: %d of %d", len(got), len(body))
	}
}

// A request still stuck on its body when the retry of its piece lands must
// not be able to wipe what the retry wrote once it finally fails.
func TestStalePieceCannotSpoilItsRetry(t *testing.T) {
	_, ts := newTestServer(t)
	token, _ := register(t, ts, "alice")
	body := bytes.Repeat([]byte("0123456789abcdef"), 256)
	_, created := do(t, ts, "POST", "/api/v1/uploads", token, map[string]any{"size": len(body)})
	id := created["id"].(string)

	pr, pw := io.Pipe()
	stale := make(chan struct{})
	go func() {
		defer close(stale)
		req, _ := http.NewRequest("PATCH", ts.URL+"/api/v1/uploads/"+id, pr)
		req.Header.Set("Authorization", "Bearer "+token)
		req.Header.Set("Upload-Offset", "0")
		if res, err := http.DefaultClient.Do(req); err == nil {
			res.Body.Close()
		}
	}()
	if _, err := pw.Write(body[:1000]); err != nil {
		t.Fatal(err)
	}
	time.Sleep(100 * time.Millisecond) // let the server start reading it

	if r, out := sendChunk(t, ts, token, id, 0, body); r.StatusCode != 200 {
		t.Fatalf("retry: %d %v", r.StatusCode, out)
	}
	pw.CloseWithError(io.ErrUnexpectedEOF)
	<-stale
	time.Sleep(100 * time.Millisecond) // and let its handler give up

	res, fin := do(t, ts, "POST", "/api/v1/uploads/"+id+"/finish", token, nil)
	if res.StatusCode != 201 {
		t.Fatalf("finish: %d %v", res.StatusCode, fin)
	}
	if got := downloadAttachment(t, ts, token, fin["blob_id"].(string)); !bytes.Equal(got, body) {
		t.Errorf("stored bytes differ: %d of %d", len(got), len(body))
	}
}

// Bytes on disk that do not match what the database counted are never sent
// on as a file that fails to decrypt.
func TestFinishRefusesADamagedSpool(t *testing.T) {
	srv, ts := newTestServer(t)
	token, _ := register(t, ts, "alice")
	body := bytes.Repeat([]byte("z"), 500)
	_, created := do(t, ts, "POST", "/api/v1/uploads", token, map[string]any{"size": len(body)})
	id := created["id"].(string)
	sendChunk(t, ts, token, id, 0, body)
	parsed, _ := parseID(id)
	if err := os.Truncate(srv.Media.SpoolPath(parsed), 200); err != nil {
		t.Fatal(err)
	}
	res, out := do(t, ts, "POST", "/api/v1/uploads/"+id+"/finish", token, nil)
	if res.StatusCode != 409 || errCode(out) != "upload_damaged" {
		t.Errorf("finish of a damaged upload: %d %v", res.StatusCode, out)
	}
}

func jsonUnmarshal(b []byte, v any) error  { return json.Unmarshal(b, v) }
func parseID(s string) (ids.ID, error)     { return ids.Parse(s) }
func osStat(p string) (os.FileInfo, error) { return os.Stat(p) }

// sendChunk pushes one piece of a resumable upload.
func sendChunk(t *testing.T, ts *httptest.Server, token, id string, offset int64, data []byte) (*http.Response, map[string]any) {
	t.Helper()
	req, _ := http.NewRequest("PATCH", ts.URL+"/api/v1/uploads/"+id, bytes.NewReader(data))
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/octet-stream")
	req.Header.Set("Upload-Offset", strconv.FormatInt(offset, 10))
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

func TestResumableUploadInPieces(t *testing.T) {
	srv, ts := newTestServer(t)
	token, _ := register(t, ts, "alice")
	tokenB, _ := register(t, ts, "bob")

	// three pieces, the last one short
	body := bytes.Repeat([]byte("abcdefgh"), 300)
	res, created := do(t, ts, "POST", "/api/v1/uploads", token, map[string]any{"size": len(body)})
	if res.StatusCode != 201 {
		t.Fatalf("create: %d %v", res.StatusCode, created)
	}
	id := created["id"].(string)
	if created["chunk_size"].(float64) != float64(MaxChunkBytes) {
		t.Errorf("the client needs to know how much fits in one request: %v", created)
	}

	// nobody else can see it, push to it or finish it
	if r, _ := do(t, ts, "GET", "/api/v1/uploads/"+id, tokenB, nil); r.StatusCode != 404 {
		t.Errorf("someone else reading it: %d", r.StatusCode)
	}
	if r, _ := sendChunk(t, ts, tokenB, id, 0, body[:10]); r.StatusCode != 404 {
		t.Errorf("someone else writing to it: %d", r.StatusCode)
	}

	// a piece that does not follow on is refused, and says where to resume
	r, _ := sendChunk(t, ts, token, id, 500, body[500:])
	if r.StatusCode != 409 || r.Header.Get("Upload-Offset") != "0" {
		t.Fatalf("out of order: %d %q", r.StatusCode, r.Header.Get("Upload-Offset"))
	}

	sizes := []int{1000, 1000, len(body) - 2000}
	at := 0
	for _, n := range sizes {
		r, out := sendChunk(t, ts, token, id, int64(at), body[at:at+n])
		if r.StatusCode != 200 {
			t.Fatalf("piece at %d: %d %v", at, r.StatusCode, out)
		}
		at += n
		if out["received"].(float64) != float64(at) {
			t.Fatalf("received after %d: %v", at, out["received"])
		}
	}

	// finishing early is refused before everything lands, but we are done now
	res, fin := do(t, ts, "POST", "/api/v1/uploads/"+id+"/finish", token, nil)
	if res.StatusCode != 201 {
		t.Fatalf("finish: %d %v", res.StatusCode, fin)
	}
	if fin["size"].(float64) != float64(len(body)) {
		t.Errorf("stored size: %v", fin)
	}

	// the bytes come back exactly as they went in
	blobID := fin["blob_id"].(string)
	req, _ := http.NewRequest("GET", ts.URL+"/media/attachments/"+blobID, nil)
	req.Header.Set("Authorization", "Bearer "+token)
	got, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer got.Body.Close()
	back, _ := io.ReadAll(got.Body)
	if got.StatusCode != 200 || !bytes.Equal(back, body) {
		t.Fatalf("download: %d, %d bytes back of %d", got.StatusCode, len(back), len(body))
	}

	// the session is gone once it is finished
	if r, _ := do(t, ts, "GET", "/api/v1/uploads/"+id, token, nil); r.StatusCode != 404 {
		t.Errorf("finished session should not linger: %d", r.StatusCode)
	}
	_ = srv
}

func TestUploadRefusesWhatItCannotHold(t *testing.T) {
	srv, ts := newTestServer(t)
	token, _ := register(t, ts, "alice")

	res, body := do(t, ts, "POST", "/api/v1/uploads", token, map[string]any{"size": srv.planLimitsFor("free").FileMax + 1})
	if res.StatusCode != 413 || errCode(body) != "too_large" {
		t.Errorf("over the limit: %d %v", res.StatusCode, body)
	}
	for _, bad := range []int64{0, -5} {
		res, body = do(t, ts, "POST", "/api/v1/uploads", token, map[string]any{"size": bad})
		if res.StatusCode != 413 {
			t.Errorf("size %d: %d %v", bad, res.StatusCode, body)
		}
	}

	// an unfinished transfer cannot be turned into a blob
	_, created := do(t, ts, "POST", "/api/v1/uploads", token, map[string]any{"size": 100})
	id := created["id"].(string)
	sendChunk(t, ts, token, id, 0, bytes.Repeat([]byte("x"), 40))
	res, body = do(t, ts, "POST", "/api/v1/uploads/"+id+"/finish", token, nil)
	if res.StatusCode != 400 || errCode(body) != "incomplete" {
		t.Errorf("finishing early: %d %v", res.StatusCode, body)
	}

	// and cancelling takes the bytes with it
	res, _ = do(t, ts, "DELETE", "/api/v1/uploads/"+id, token, nil)
	if res.StatusCode != 204 {
		t.Fatalf("cancel: %d", res.StatusCode)
	}
	if _, err := srvStat(srv, id); err == nil {
		t.Error("the spooled bytes should be gone")
	}
}

func srvStat(s *Server, id string) (any, error) {
	parsed, err := parseID(id)
	if err != nil {
		return nil, err
	}
	return osStat(s.Media.SpoolPath(parsed))
}

func TestUploadChunkTooBig(t *testing.T) {
	_, ts := newTestServer(t)
	token, _ := register(t, ts, "alice")
	_, created := do(t, ts, "POST", "/api/v1/uploads", token, map[string]any{"size": MaxChunkBytes * 2})
	id := created["id"].(string)
	res, body := sendChunk(t, ts, token, id, 0, bytes.Repeat([]byte("y"), MaxChunkBytes+1024))
	if res.StatusCode != 413 {
		t.Errorf("an over-sized piece should be refused: %d %v", res.StatusCode, body)
	}
}

// A connection that drops part-way through a piece can leave bytes in the
// spool that the database never counted. The retry must still go through.
func TestResumeAfterAPartialPiece(t *testing.T) {
	srv, ts := newTestServer(t)
	token, _ := register(t, ts, "alice")
	body := bytes.Repeat([]byte("0123456789"), 500)
	_, created := do(t, ts, "POST", "/api/v1/uploads", token, map[string]any{"size": len(body)})
	id := created["id"].(string)

	if r, out := sendChunk(t, ts, token, id, 0, body[:2000]); r.StatusCode != 200 {
		t.Fatalf("first piece: %d %v", r.StatusCode, out)
	}
	// what a request cut off mid-body leaves behind
	parsed, _ := parseID(id)
	f, err := os.OpenFile(srv.Media.SpoolPath(parsed), os.O_WRONLY|os.O_APPEND, 0)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.Write([]byte("GARBAGE-FROM-A-DROPPED-REQUEST")); err != nil {
		t.Fatal(err)
	}
	f.Close()

	// the client asks where it got to and sends from there
	_, state := do(t, ts, "GET", "/api/v1/uploads/"+id, token, nil)
	if state["received"].(float64) != 2000 {
		t.Fatalf("the database is the record of what arrived: %v", state)
	}
	if r, out := sendChunk(t, ts, token, id, 2000, body[2000:]); r.StatusCode != 200 {
		t.Fatalf("retry after a partial piece: %d %v", r.StatusCode, out)
	}
	_, fin := do(t, ts, "POST", "/api/v1/uploads/"+id+"/finish", token, nil)
	req, _ := http.NewRequest("GET", ts.URL+"/media/attachments/"+fin["blob_id"].(string), nil)
	req.Header.Set("Authorization", "Bearer "+token)
	got, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer got.Body.Close()
	back, _ := io.ReadAll(got.Body)
	if !bytes.Equal(back, body) {
		t.Fatalf("stored bytes differ: got %d bytes, want %d", len(back), len(body))
	}
}

// A single request is only for what fits through the proxy; bigger files have
// to use the resumable path, and the client is told where that line is.
func TestSingleUploadIsCapped(t *testing.T) {
	srv, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	_, idB := register(t, ts, "bob")
	dm := dmBetween(t, ts, tokA, idB)

	_, info := do(t, ts, "GET", "/api/v1/instance", "", nil)
	// clients send one piece's worth at most in one request...
	if info["upload_single_bytes"].(float64) != float64(SingleUploadBytes) || SingleUploadBytes != MaxChunkBytes {
		t.Errorf("the client needs the single-request ceiling: %v", info)
	}
	// ...and the server still takes the larger requests older clients send
	if MaxSingleUploadBytes < SingleUploadBytes || MaxSingleUploadBytes >= 100<<20 {
		t.Errorf("the single-request cap must fit Cloudflare and older clients: %d", MaxSingleUploadBytes)
	}

	// the smaller of the two limits applies to one request: the plan's file
	// limit is far past the single-request one, so that one holds
	if srv.planLimitsFor("").FileMax <= MaxSingleUploadBytes {
		t.Fatalf("the free plan's files should outgrow one request: %d", srv.planLimitsFor("").FileMax)
	}
	req, _ := http.NewRequest("POST", ts.URL+"/api/v1/channels/"+dm+"/attachments", bytes.NewReader(bytes.Repeat([]byte("z"), MaxSingleUploadBytes+1)))
	req.Header.Set("Authorization", "Bearer "+tokA)
	req.Header.Set("Content-Type", "application/octet-stream")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != 413 {
		t.Errorf("an over-limit single upload should be refused: %d", res.StatusCode)
	}
}

// One account can only have so many transfers open, and only so much on the
// server that it has not sent: without that, one account opens transfers until
// the disk is spoken for, or uploads files nobody ever sends until it is full.
func TestUploadsHaveAnAllowancePerAccount(t *testing.T) {
	srv, ts := newTestServer(t)
	token, _ := register(t, ts, "alice")
	other, _ := register(t, ts, "bob")
	var first string
	for i := 0; i < maxOpenUploads; i++ {
		res, body := do(t, ts, "POST", "/api/v1/uploads", token, map[string]any{"size": 1000})
		if res.StatusCode != 201 {
			t.Fatalf("transfer %d: %d %v", i, res.StatusCode, body)
		}
		if first == "" {
			first = body["id"].(string)
		}
	}
	if res, body := do(t, ts, "POST", "/api/v1/uploads", token, map[string]any{"size": 1000}); res.StatusCode != 429 || errCode(body) != "too_many_uploads" {
		t.Errorf("one transfer too many: %d %v", res.StatusCode, body)
	}
	// someone else is not held up by it
	if res, body := do(t, ts, "POST", "/api/v1/uploads", other, map[string]any{"size": 1000}); res.StatusCode != 201 {
		t.Errorf("another account: %d %v", res.StatusCode, body)
	}
	// and cancelling one makes room again
	if res, _ := do(t, ts, "DELETE", "/api/v1/uploads/"+first, token, nil); res.StatusCode != 204 {
		t.Fatalf("cancel: %d", res.StatusCode)
	}
	if res, body := do(t, ts, "POST", "/api/v1/uploads", token, map[string]any{"size": 1000}); res.StatusCode != 201 {
		t.Errorf("after cancelling: %d %v", res.StatusCode, body)
	}

	// files uploaded and never sent count against the account too
	room, err := srv.Store.UploadRoomFor(t.Context(), modID(mustID(t, ts, token)))
	if err != nil || room.UserOpen != maxOpenUploads || room.UserPending != maxOpenUploads*1000 {
		t.Fatalf("room: %+v %v", room, err)
	}
	if err := srv.roomForUpload(t.Context(), modID(mustID(t, ts, token)), pendingAllowance+srv.planLimitsFor("").FileMax+1, false, true); err == nil {
		t.Error("a file past the allowance was let in")
	}
}
