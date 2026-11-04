package api

import (
	"context"
	"errors"
	"net/http"
	"os"
	"strconv"
	"sync"
	"time"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/media"
	"evilion.xyz/chat/internal/store"
)

const (
	// MaxChunkBytes is the size of one piece of a resumable upload: how much has
	// to be sent again after a dropped connection, and how much the sender has
	// to hold in memory at once.
	MaxChunkBytes = 32 << 20
	// SingleUploadBytes is the largest file clients are told to send in one
	// request; anything bigger goes up in resumable pieces of MaxChunkBytes.
	// It is one piece on purpose. Bigger requests would save a round trip or
	// two per file, and cost the sender that much more memory (a piece is
	// encrypted whole before it goes) and, when a connection drops, a restart
	// from nothing rather than from the last piece that arrived.
	SingleUploadBytes = MaxChunkBytes
	// MaxSingleUploadBytes is the most one request may carry, which clients
	// from before SingleUploadBytes still use. Cloudflare's free plan refuses
	// a body over 100 MB, so this sits just under that.
	MaxSingleUploadBytes = 90 << 20
	// uploadIdleLife is how long an untouched transfer is kept before its
	// bytes are swept up.
	uploadIdleLife = 24 * time.Hour
	// diskHeadroom is what must remain free after a transfer finishes.
	diskHeadroom = 2 << 30
	// maxOpenUploads is how many unfinished transfers one account may have.
	maxOpenUploads = 12
	// pendingAllowance is what one account may hold in files not sent yet,
	// beyond the largest single file: room for a few files on their way
	// while the big one finishes.
	pendingAllowance = 4 << 30
	// aheadWait is how long a piece sent ahead waits for the one before it.
	aheadWait               = 2 * time.Minute
	maxChunkStages          = 16
	maxChunkStagesPerUpload = 2 // a stalled request must still allow its retry
)

type uploadReservation struct {
	bytes int64
	open  int
}

type uploadReservations struct {
	sync.Mutex
	users map[ids.ID]uploadReservation
	bytes int64
	// remoteBytes is the part of bytes headed for the bucket, which the
	// monthly limit counts as on its way (storage_budget.go)
	remoteBytes int64
	stages      map[ids.ID]int
	stageCount  int
	stageBytes  int64
}

// reserveUpload makes quota checks and admission atomic, and counts bodies
// still arriving. Counting only committed blobs lets concurrent requests each
// spend the same remaining allowance before any of them creates its blob.
// remote says the file is an end-to-end encrypted attachment, which ends up
// in the bucket when there is one, and so has to fit under the monthly limit.
func (s *Server) reserveUpload(ctx context.Context, uid ids.ID, size int64, session, remote bool) (func(), error) {
	s.uploads.Lock()
	defer s.uploads.Unlock()
	if err := s.uploadRoomLocked(ctx, uid, size, session, remote); err != nil {
		return nil, err
	}
	if s.uploads.users == nil {
		s.uploads.users = make(map[ids.ID]uploadReservation)
	}
	r := s.uploads.users[uid]
	r.bytes += size
	r.open++
	s.uploads.users[uid] = r
	s.uploads.bytes += size
	if remote {
		s.uploads.remoteBytes += size
	}
	return func() {
		s.uploads.Lock()
		defer s.uploads.Unlock()
		r := s.uploads.users[uid]
		r.bytes -= size
		r.open--
		if r.open == 0 {
			delete(s.uploads.users, uid)
		} else {
			s.uploads.users[uid] = r
		}
		s.uploads.bytes -= size
		if remote {
			s.uploads.remoteBytes -= size
			// what arrived is a file waiting here now, or an upload on its way
			s.storageChanged()
		}
	}, nil
}

// reserveChunk bounds temporary copies, including duplicate offsets, before
// reading a body. The upload row reserves only one copy of the final file.
func (s *Server) reserveChunk(id ids.ID, size int64) (func(), error) {
	s.uploads.Lock()
	defer s.uploads.Unlock()
	if s.uploads.stageCount >= maxChunkStages || s.uploads.stages[id] >= maxChunkStagesPerUpload {
		return nil, E(http.StatusTooManyRequests, "too_many_uploads", "Too many upload pieces are arriving at once. Try again in a moment.")
	}
	// The staged piece and its copy into the spool may coexist.
	reserved := 2 * size
	if free, err := freeSpace(s.Media.Dir); err != nil {
		return nil, err
	} else if free-s.uploads.bytes-s.uploads.stageBytes < reserved+diskHeadroom/2 {
		return nil, errNoSpace
	}
	if s.uploads.stages == nil {
		s.uploads.stages = make(map[ids.ID]int)
	}
	s.uploads.stages[id]++
	s.uploads.stageCount++
	s.uploads.stageBytes += reserved
	return func() {
		s.uploads.Lock()
		defer s.uploads.Unlock()
		s.uploads.stages[id]--
		if s.uploads.stages[id] == 0 {
			delete(s.uploads.stages, id)
		}
		s.uploads.stageCount--
		s.uploads.stageBytes -= reserved
	}, nil
}

var errNoSpace = E(http.StatusInsufficientStorage, "no_space", "This server does not have room for a file that size right now.")

// roomForUpload decides whether an account may start putting size more bytes
// on the server. The disk has to hold them on top of what every unfinished
// transfer still has to bring, with headroom left over for the database —
// without that, transfers started together all pass a free-space check made
// before any of their bytes arrive. And no one account may hold more than a
// file of the largest size plus a working allowance in files it has not sent,
// so one account cannot fill the disk for everyone, and none may upload more
// in 30 days than its plan allows. session says a resumable transfer is
// being opened, which counts against the open-transfer cap.
func (s *Server) roomForUpload(ctx context.Context, uid ids.ID, size int64, session, remote bool) error {
	s.uploads.Lock()
	defer s.uploads.Unlock()
	return s.uploadRoomLocked(ctx, uid, size, session, remote)
}

func (s *Server) uploadRoomLocked(ctx context.Context, uid ids.ID, size int64, session, remote bool) error {
	room, err := s.Store.UploadRoomFor(ctx, uid)
	if err != nil {
		return err
	}
	inflight := s.uploads.users[uid]
	if inflight.open >= maxOpenUploads || (session && room.UserOpen+inflight.open >= maxOpenUploads) {
		return E(http.StatusTooManyRequests, "too_many_uploads", "You have too many uploads going at once. Let some finish first.")
	}
	limits := s.planLimitsFor(planOf(ctx))
	if room.UserPending+inflight.bytes+size > limits.FileMax+pendingAllowance {
		return E(http.StatusInsufficientStorage, "upload_quota", "You have too much uploaded that has not been sent yet. Send or cancel some of it first.")
	}
	// what is on its way counts too; files not sent yet are blobs already
	if used, _, err := s.monthUsed(ctx, uid); err != nil {
		return err
	} else if used+room.UserDeclared+inflight.bytes+size > limits.MonthUploadMax {
		return errMonthFull
	}
	if free, err := freeSpace(s.Media.Dir); err != nil {
		return err
	} else if free-room.AllRemaining-s.uploads.bytes-s.uploads.stageBytes < size+diskHeadroom {
		return errNoSpace
	}
	if remote {
		return s.storageRoomLocked(ctx, size)
	}
	return nil
}

func (s *Server) uploadRoutes(mux *http.ServeMux) {
	mux.Handle("POST /api/v1/uploads", s.authed(s.handleUploadCreate))
	mux.Handle("GET /api/v1/uploads/{id}", s.authed(s.handleUploadStatus))
	mux.Handle("PATCH /api/v1/uploads/{id}", s.authed(s.handleUploadChunk))
	mux.Handle("POST /api/v1/uploads/{id}/finish", s.authed(s.handleUploadFinish))
	mux.Handle("DELETE /api/v1/uploads/{id}", s.authed(s.handleUploadCancel))
}

// upload loads a transfer and checks it belongs to the caller.
func (s *Server) upload(w http.ResponseWriter, r *http.Request) (*store.Upload, *store.User) {
	me := auth.UserFrom(r.Context())
	id, err := ids.Parse(r.PathValue("id"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil, nil
	}
	up, err := s.Store.UploadByID(r.Context(), id)
	if err != nil || up.UserID != me.ID {
		WriteError(w, ErrNotFound)
		return nil, nil
	}
	return up, me
}

func uploadView(up *store.Upload) map[string]any {
	return map[string]any{"id": up.ID, "size": up.Size, "received": up.Received, "chunk_size": MaxChunkBytes}
}

func (s *Server) handleUploadCreate(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	var req struct {
		Size int64 `json:"size"`
		// ChannelID is where the file is going, when that is a channel: a
		// boosted server takes bigger files. Apps from before boosts leave it out.
		ChannelID *ids.ID `json:"channel_id"`
	}
	if err := ReadJSON(w, r, &req, 4<<10); err != nil {
		WriteError(w, err)
		return
	}
	if req.ChannelID != nil {
		// only a channel the sender may send files in counts
		if ch, err := s.Store.ChannelByID(r.Context(), *req.ChannelID); err != nil {
			req.ChannelID = nil
		} else if ok, err := s.canAccess(r, me, ch, NeedAttach); err != nil || !ok {
			req.ChannelID = nil
		}
	}
	if req.Size <= 0 || req.Size > s.fileMaxFor(r.Context(), me.Plan, req.ChannelID) {
		WriteError(w, errFileTooBig())
		return
	}
	if ok, retry := s.limUpload.Allow("up:" + me.ID.String()); !ok {
		w.Header().Set("Retry-After", retryAfter(retry))
		WriteError(w, ErrRateLimited)
		return
	}
	release, err := s.reserveUpload(r.Context(), me.ID, req.Size, true, true)
	if err != nil {
		WriteError(w, err)
		return
	}
	defer release()
	up, err := s.Store.CreateUpload(r.Context(), me.ID, req.Size)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusCreated, uploadView(up))
}

// handleUploadStatus says how much has landed, so a client that lost its
// connection can pick up where it left off.
func (s *Server) handleUploadStatus(w http.ResponseWriter, r *http.Request) {
	up, _ := s.upload(w, r)
	if up == nil {
		return
	}
	if up.BlobID != nil {
		WriteError(w, ErrNotFound) // finished: nothing left to resume
		return
	}
	WriteJSON(w, http.StatusOK, uploadView(up))
}

func (s *Server) handleUploadChunk(w http.ResponseWriter, r *http.Request) {
	up, _ := s.upload(w, r)
	if up == nil {
		return
	}
	offset, err := strconv.ParseInt(r.Header.Get("Upload-Offset"), 10, 64)
	if err != nil || offset < 0 {
		WriteError(w, E(http.StatusBadRequest, "bad_offset", "Upload-Offset must say where this piece belongs."))
		return
	}
	// One piece may come ahead of the one still landing: apps send the next
	// as soon as the current one has left, so the connection never idles
	// through a round trip at each piece boundary. It waits its turn below.
	ahead := offset > up.Received && offset-up.Received <= MaxChunkBytes
	if offset != up.Received && !ahead {
		// Not an error worth failing over: tell the client where to resume.
		w.Header().Set("Upload-Offset", strconv.FormatInt(up.Received, 10))
		WriteError(w, E(http.StatusConflict, "offset_mismatch", "That piece does not follow on from what has arrived."))
		return
	}
	room := up.Size - up.Received
	if room <= 0 {
		WriteError(w, E(http.StatusBadRequest, "already_complete", "Everything has arrived already."))
		return
	}
	limit := int64(MaxChunkBytes)
	if room < limit {
		limit = room
	}
	// what this piece brings was set aside when the transfer began, but the
	// disk is shared with everything else the server writes
	release, err := s.reserveChunk(up.ID, limit)
	if err != nil {
		w.Header().Set("Retry-After", "1")
		WriteError(w, err)
		return
	}
	defer release()
	r.Body = http.MaxBytesReader(w, r.Body, limit+1)
	// The body lands in a file of its own first: a request stuck on its body
	// must not be able to touch the spool once a retry has taken over.
	piece, _, err := s.Media.StagePiece(r.Body, limit)
	if err != nil {
		if errors.Is(err, media.ErrTooLarge) {
			WriteError(w, E(http.StatusRequestEntityTooLarge, "chunk_too_large", "That piece is larger than this server takes at once."))
			return
		}
		WriteError(w, err)
		return
	}
	defer os.Remove(piece)
	if ahead {
		// the piece before it is still arriving or being written
		deadline := time.Now().Add(aheadWait)
		for {
			cur, err := s.Store.UploadByID(r.Context(), up.ID)
			if err != nil || cur.Received >= offset || cur.BlobID != nil || time.Now().After(deadline) {
				break
			}
			select {
			case <-r.Context().Done():
				return
			case <-time.After(50 * time.Millisecond):
			}
		}
	}
	unlock := s.Media.LockUpload(up.ID)
	defer unlock()
	// another request may have landed this piece while the body was arriving
	fresh, err := s.Store.UploadByID(r.Context(), up.ID)
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	if fresh.Received != offset || fresh.BlobID != nil {
		w.Header().Set("Upload-Offset", strconv.FormatInt(fresh.Received, 10))
		WriteError(w, E(http.StatusConflict, "offset_mismatch", "That piece does not follow on from what has arrived."))
		return
	}
	end, err := s.Media.AppendSpool(up.ID, offset, piece)
	if err != nil {
		if errors.Is(err, media.ErrBadOffset) {
			w.Header().Set("Upload-Offset", strconv.FormatInt(end, 10))
			WriteError(w, E(http.StatusConflict, "offset_mismatch", "That piece does not follow on from what has arrived."))
			return
		}
		WriteError(w, err)
		return
	}
	if ok, err := s.Store.AdvanceUpload(r.Context(), up.ID, offset, end); err != nil {
		WriteError(w, err)
		return
	} else if !ok {
		// another request moved it underneath us
		fresh, ferr := s.Store.UploadByID(r.Context(), up.ID)
		if ferr == nil {
			w.Header().Set("Upload-Offset", strconv.FormatInt(fresh.Received, 10))
		}
		WriteError(w, E(http.StatusConflict, "offset_mismatch", "That piece does not follow on from what has arrived."))
		return
	}
	up.Received = end
	w.Header().Set("Upload-Offset", strconv.FormatInt(end, 10))
	WriteJSON(w, http.StatusOK, uploadView(up))
}

func (s *Server) handleUploadFinish(w http.ResponseWriter, r *http.Request) {
	up, me := s.upload(w, r)
	if up == nil {
		return
	}
	unlock := s.Media.LockUpload(up.ID)
	defer unlock()
	up, err := s.Store.UploadByID(r.Context(), up.ID)
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	if up.BlobID != nil {
		// finished already and the reply went missing: give the same answer again
		b, err := s.Store.BlobByID(r.Context(), *up.BlobID)
		if err != nil {
			WriteError(w, ErrNotFound)
			return
		}
		WriteJSON(w, http.StatusCreated, map[string]any{"blob_id": b.ID, "size": b.Size})
		return
	}
	if up.Received != up.Size {
		WriteError(w, E(http.StatusBadRequest, "incomplete", "Some of the file has not arrived yet."))
		return
	}
	b, err := s.Media.FinishSpool(r.Context(), up.ID, me.ID, up.Size)
	if err != nil {
		if errors.Is(err, media.ErrBadOffset) {
			_ = s.Store.DeleteUpload(r.Context(), up.ID)
			WriteError(w, E(http.StatusConflict, "upload_damaged", "The file did not arrive intact. Send it again."))
			return
		}
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusCreated, map[string]any{"blob_id": b.ID, "size": b.Size})
}

func (s *Server) handleUploadCancel(w http.ResponseWriter, r *http.Request) {
	up, _ := s.upload(w, r)
	if up == nil {
		return
	}
	unlock := s.Media.LockUpload(up.ID)
	defer unlock()
	s.Media.DiscardSpool(up.ID)
	_ = s.Store.DeleteUpload(r.Context(), up.ID)
	w.WriteHeader(http.StatusNoContent)
}
