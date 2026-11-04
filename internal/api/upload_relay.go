package api

import (
	"errors"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/media"
	"evilion.xyz/chat/internal/store"
)

// Uploads relayed to the bucket. An app that asks for it (relay: true) and
// sends a file of at least relayMinBytes gets an upload whose parts it PUTs
// here (PUT /uploads/{id}/parts/{n}), each streamed on to the bucket as it
// arrives and never written to this disk; then it finishes the upload as any
// other. Apps never hold a link to the bucket, so nothing they do reaches it
// except through these signed-in requests, and the bucket bills only what
// this server sends, counted as it goes. What keeps it to the file:
//
//   - The upload is admitted exactly as one landing here: the plan's largest
//     file, the 30-day allowance, what an account may hold unsent, the
//     open-upload cap and the monthly limit all count its declared size.
//   - The server opens the bucket's multipart upload, names the object and
//     sets the part size; a part is taken only at its exact length
//     (Content-Length, no chunked bodies), only for a part number the upload
//     has, at most maxPartSends times, at limParts' pace, and its bytes are
//     checked against the bucket's checksum (objstore.PutPartStream).
//   - Finishing lists the parts, refuses any not of their size, joins them
//     and checks the whole file's size before a blob exists. A finish that
//     keeps failing throws the upload away.
//   - The multipart upload is aborted when the upload is cancelled or left
//     untouched (the hourly sweep in cmd/chatd), and the bucket's lifecycle
//     rule aborts any left over.

const (
	// relayMinBytes is the smallest file sent in parts to the bucket: R2's
	// smallest part. Smaller ones go in one request (PutEncrypted streams
	// those to the bucket too).
	relayMinBytes = 5 << 20
	// relayPartTarget is the part size aimed for. Parts are made equal (R2
	// wants every part but the last the same size), so none is small.
	relayPartTarget = 16 << 20
	// maxPartSends is how often one part may be sent, retries included.
	maxPartSends = 6
	// maxRelayFinishes is how often finishing may fail before the upload goes.
	maxRelayFinishes = 6
)

// relayPartSize is the part size for a relayed upload of size bytes: parts
// as equal as R2 allows, near relayPartTarget.
func relayPartSize(size int64) int64 {
	n := (size + relayPartTarget - 1) / relayPartTarget
	return (size + n - 1) / n
}

// relayCounts counts, per upload, the sends of each part and the finishes
// that failed. In memory: a restart starts the counts again, which allows
// at most another round of them.
type relayCounts struct {
	mu   sync.Mutex
	byID map[ids.ID]*relayCount
}

type relayCount struct {
	sends    map[int]int
	finishes int
	at       time.Time
}

func (d *relayCounts) get(id ids.ID) *relayCount {
	if d.byID == nil {
		d.byID = map[ids.ID]*relayCount{}
	}
	c := d.byID[id]
	if c == nil {
		c = &relayCount{sends: map[int]int{}}
		d.byID[id] = c
	}
	c.at = time.Now()
	return c
}

func (d *relayCounts) forget(id ids.ID) {
	d.mu.Lock()
	defer d.mu.Unlock()
	delete(d.byID, id)
}

// prune forgets uploads left untouched as long as the sweep allows them.
func (d *relayCounts) prune() {
	d.mu.Lock()
	defer d.mu.Unlock()
	for id, c := range d.byID {
		if time.Since(c.at) > uploadIdleLife {
			delete(d.byID, id)
		}
	}
}

// relayView adds what an app needs to send a relayed upload's parts.
func relayView(up *store.Upload, view map[string]any) map[string]any {
	if up.RemoteID != "" && up.PartSize > 0 {
		view["relay"] = map[string]any{"part_size": up.PartSize, "parts": media.PartCount(up.Size, up.PartSize)}
	}
	return view
}

// startRelay makes a new upload go to the bucket in parts. False leaves it
// to land here in pieces.
func (s *Server) startRelay(r *http.Request, up *store.Upload) bool {
	remoteID, err := s.Media.StartRelay(r.Context(), up.ID)
	if err != nil {
		s.Log.Warn("storage: could not open an upload in the bucket, so it lands here", "err", err)
		return false
	}
	part := relayPartSize(up.Size)
	if err := s.Store.SetUploadRemote(r.Context(), up.ID, remoteID, part); err != nil {
		s.Media.AbortRelay(r.Context(), up.ID, remoteID)
		return false
	}
	up.RemoteID, up.PartSize = remoteID, part
	return true
}

// handleUploadPart takes part n of a relayed upload and streams it on to
// the bucket: exactly the part's size, answered with {n, size} once the
// bucket has it.
func (s *Server) handleUploadPart(w http.ResponseWriter, r *http.Request) {
	up, me := s.upload(w, r)
	if up == nil {
		return
	}
	if up.RemoteID == "" || up.PartSize <= 0 {
		WriteError(w, E(http.StatusConflict, "not_relayed", "This upload goes in pieces."))
		return
	}
	if up.BlobID != nil {
		WriteError(w, E(http.StatusBadRequest, "already_complete", "Everything has arrived already."))
		return
	}
	n, err := strconv.Atoi(r.PathValue("n"))
	if err != nil || n < 1 || n > media.PartCount(up.Size, up.PartSize) {
		WriteError(w, E(http.StatusBadRequest, "bad_part", "That upload has no such part."))
		return
	}
	size := media.PartLength(up.Size, up.PartSize, n)
	if r.ContentLength != size {
		WriteError(w, E(http.StatusBadRequest, "bad_part_size", "That part is not the size it should be."))
		return
	}
	if ok, retry := s.limParts.Allow("p:" + me.ID.String()); !ok {
		w.Header().Set("Retry-After", retryAfter(retry))
		WriteError(w, ErrRateLimited)
		return
	}
	s.relay.mu.Lock()
	c := s.relay.get(up.ID)
	if c.sends[n] >= maxPartSends {
		s.relay.mu.Unlock()
		WriteError(w, E(http.StatusTooManyRequests, "part_retries", "That part failed too many times. Send the file again."))
		return
	}
	c.sends[n]++
	s.relay.mu.Unlock()
	if err := s.Store.TouchUpload(r.Context(), up.ID); err != nil {
		WriteError(w, err)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, size)
	if err := s.Media.RelayPart(r.Context(), up.ID, up.RemoteID, n, r.Body, size); err != nil {
		if r.Context().Err() != nil {
			return // the app went away mid-part
		}
		s.Log.Warn("storage: a part did not reach the bucket", "upload", up.ID, "part", n, "err", err)
		WriteError(w, E(http.StatusBadGateway, "part_failed", "That part did not reach storage. Send it again."))
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"n": n, "size": size})
}

// finishRelay is handleUploadFinish for a relayed upload. The caller holds
// the upload's lock and has checked it is not finished yet.
func (s *Server) finishRelay(w http.ResponseWriter, r *http.Request, up *store.Upload) {
	me := auth.UserFrom(r.Context())
	// every finish asks the bucket for the parts, a request it bills
	if ok, retry := s.limParts.Allow("p:" + me.ID.String()); !ok {
		w.Header().Set("Retry-After", retryAfter(retry))
		WriteError(w, ErrRateLimited)
		return
	}
	s.relay.mu.Lock()
	tries := s.relay.get(up.ID).finishes
	s.relay.mu.Unlock()
	if tries >= maxRelayFinishes {
		s.dropRelay(r, up)
		WriteError(w, E(http.StatusConflict, "upload_damaged", "The file did not arrive intact. Send it again."))
		return
	}
	b, err := s.Media.FinishRelay(r.Context(), up, me.ID)
	var missing *media.ErrPartsMissing
	switch {
	case err == nil:
		s.relay.forget(up.ID)
		s.storageChanged()
		WriteJSON(w, http.StatusCreated, map[string]any{"blob_id": b.ID, "size": b.Size})
		return
	case errors.As(err, &missing):
		s.failedFinish(up.ID)
		// which ones, for the app to send again (at most a few hundred numbers)
		list := make([]string, len(missing.Missing))
		for i, n := range missing.Missing {
			list[i] = strconv.Itoa(n)
		}
		w.Header().Set("X-Missing-Parts", strings.Join(list, ","))
		WriteError(w, E(http.StatusConflict, "parts_missing", "Some of the file has not arrived yet."))
	case errors.Is(err, media.ErrBadOffset):
		s.dropRelay(r, up)
		WriteError(w, E(http.StatusConflict, "upload_damaged", "The file did not arrive intact. Send it again."))
	case errors.Is(err, store.ErrNotFound):
		s.dropRelay(r, up)
		WriteError(w, ErrNotFound)
	default:
		s.failedFinish(up.ID)
		WriteError(w, err)
	}
}

func (s *Server) failedFinish(id ids.ID) {
	s.relay.mu.Lock()
	s.relay.get(id).finishes++
	s.relay.mu.Unlock()
}

// dropRelay throws a relayed upload away: its parts in the bucket and its row.
func (s *Server) dropRelay(r *http.Request, up *store.Upload) {
	s.Media.AbortRelay(r.Context(), up.ID, up.RemoteID)
	_ = s.Store.DeleteUpload(r.Context(), up.ID)
	s.relay.forget(up.ID)
	s.storageChanged()
}

// BucketReadOK paces reads from the bucket per account (media.Store.ReadOK):
// every download of a file kept there is a read this server makes and the
// bucket bills.
func (s *Server) BucketReadOK(r *http.Request) bool {
	key := "ip:" + auth.ClientIP(r)
	if u := auth.UserFrom(r.Context()); u != nil {
		key = "u:" + u.ID.String()
	}
	ok, _ := s.limReads.Allow(key)
	return ok
}
