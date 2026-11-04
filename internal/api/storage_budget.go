package api

import (
	"context"
	"net/http"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"evilion.xyz/chat/internal/objstore"
)

// The monthly limit on what keeping attachments in the bucket may cost,
// set in Settings, Nexa Admin (instance setting storage_budget, in US
// cents; unset means no limit). R2 has no spending cap of its own, so this
// is it. The cost is worked out the way R2 bills it, from what Nexa keeps
// there and the requests it makes: keeping everything for a month, plus
// this month's requests so far. Once a new attachment would take that past
// the limit it is refused (storage_budget), and the mover leaves files here
// rather than take the bucket past it. Nothing is deleted.

// R2's prices for Standard storage, checked on 2026-11-04 against
// developers.cloudflare.com/r2/pricing: $0.015 a GB-month, $4.50 a million
// Class A requests and $0.36 a million Class B, after the first 10
// GB-months, 1 million A and 10 million B each month, each rounded up to
// the next whole unit. Downloads and deletes are free. In mills ($0.001).
const (
	r2MillsPerGBMonth  = 15
	r2MillsPerMillionA = 4500
	r2MillsPerMillionB = 360
	r2FreeGB           = 10
	r2FreeA            = 1_000_000
	r2FreeB            = 10_000_000
	// maxStorageBudget is the highest limit accepted, $100,000.
	maxStorageBudget = 10_000_000
	// storageFresh is how long the byte totals are trusted before they are
	// read again; an upload finishing or a file moving drops them sooner.
	storageFresh = 30 * time.Second
	// storagePass is how often the mover looks for work, and moveBatch how
	// many files one pass moves before looking again.
	storagePass = 30 * time.Second
	moveBatch   = 20
)

var errStorageFull = E(http.StatusInsufficientStorage, "storage_budget", "New files can't be uploaded right now. Try again later.")

// storageCostCents is what R2 bills for a month of keeping bytes and making
// a Class A and b Class B requests, rounded up to a cent.
func storageCostCents(bytes, a, b int64) int64 {
	var mills int64
	if gb := ceilDiv(bytes, 1_000_000_000); gb > r2FreeGB {
		mills += (gb - r2FreeGB) * r2MillsPerGBMonth
	}
	if a > r2FreeA {
		mills += ceilDiv(a-r2FreeA, 1_000_000) * r2MillsPerMillionA
	}
	if b > r2FreeB {
		mills += ceilDiv(b-r2FreeB, 1_000_000) * r2MillsPerMillionB
	}
	return ceilDiv(mills, 10)
}

func ceilDiv(a, b int64) int64 {
	if a <= 0 {
		return 0
	}
	return (a + b - 1) / b
}

type storageMeter struct {
	// requests counted since they were last added to storage_usage
	a, b atomic.Int64
	mu   sync.Mutex
	// when stored and waiting were read; zero means read them again
	at      time.Time
	stored  int64 // in the bucket
	waiting int64 // attachments still on this disk, and uploads on their way
	held    bool  // the last state logged: new attachments refused
}

type storageNumbers struct{ stored, waiting, a, b int64 }

func storageMonth(t time.Time) string { return t.UTC().Format("2006-01") }

// StorageBilled counts a request the bucket bills (the bucket client and
// the media store report them).
func (s *Server) StorageBilled(class byte) {
	switch class {
	case objstore.ClassA:
		s.storage.a.Add(1)
	case objstore.ClassB:
		s.storage.b.Add(1)
	}
}

// storageChanged drops the byte totals, for the next look to read them again.
func (s *Server) storageChanged() {
	s.storage.mu.Lock()
	s.storage.at = time.Time{}
	s.storage.mu.Unlock()
}

// flushStorageOps adds the requests counted in memory to this month's row.
func (s *Server) flushStorageOps(ctx context.Context) {
	a, b := s.storage.a.Swap(0), s.storage.b.Swap(0)
	if a == 0 && b == 0 {
		return
	}
	if err := s.Store.AddStorageOps(ctx, storageMonth(time.Now()), a, b); err != nil {
		s.storage.a.Add(a)
		s.storage.b.Add(b)
		s.Log.Warn("storage: count requests", "err", err)
	}
}

// storageNow reads what the bucket holds, what waits here to go, and this
// month's requests.
func (s *Server) storageNow(ctx context.Context) (storageNumbers, error) {
	s.storage.mu.Lock()
	defer s.storage.mu.Unlock()
	if time.Since(s.storage.at) > storageFresh {
		stored, err := s.Store.RemoteBlobBytes(ctx)
		if err != nil {
			return storageNumbers{}, err
		}
		local, err := s.Store.LocalAttachmentBytes(ctx)
		if err != nil {
			return storageNumbers{}, err
		}
		arriving, err := s.Store.OpenUploadBytes(ctx)
		if err != nil {
			return storageNumbers{}, err
		}
		s.storage.stored, s.storage.waiting, s.storage.at = stored, local+arriving, time.Now()
	}
	a, b, err := s.Store.StorageOps(ctx, storageMonth(time.Now()))
	if err != nil {
		return storageNumbers{}, err
	}
	return storageNumbers{stored: s.storage.stored, waiting: s.storage.waiting, a: a + s.storage.a.Load(), b: b + s.storage.b.Load()}, nil
}

// storageLimit returns the monthly limit in cents, and whether one is set.
// A value that cannot be read holds everything, as a limit of nothing.
func (s *Server) storageLimit(ctx context.Context) (int64, bool, error) {
	v, err := s.Store.InstanceSetting(ctx, "storage_budget")
	if err != nil || v == "" {
		return 0, false, err
	}
	n, err := strconv.ParseInt(v, 10, 64)
	if err != nil || n < 0 {
		return 0, true, nil
	}
	return n, true, nil
}

func (s *Server) bucketOn() bool { return s.Media != nil && s.Media.Remote != nil }

// storageRoomLocked refuses an attachment of size bytes when keeping it, on
// top of what the bucket holds, what waits here and what is on its way,
// would cost more in a month than the limit. The caller holds s.uploads.
func (s *Server) storageRoomLocked(ctx context.Context, size int64) error {
	if !s.bucketOn() {
		return nil
	}
	limit, set, err := s.storageLimit(ctx)
	if err != nil || !set {
		return err
	}
	n, err := s.storageNow(ctx)
	if err != nil {
		return err
	}
	if storageCostCents(n.stored+n.waiting+s.uploads.remoteBytes+size, n.a, n.b) > limit {
		s.noteStorageHeld(true)
		return errStorageFull
	}
	s.noteStorageHeld(false)
	return nil
}

// noteStorageHeld logs when new attachments start or stop being refused.
func (s *Server) noteStorageHeld(held bool) {
	s.storage.mu.Lock()
	changed := s.storage.held != held
	s.storage.held = held
	s.storage.mu.Unlock()
	if changed && held {
		s.Log.Warn("storage: the monthly limit is reached, so new attachments are refused")
	} else if changed {
		s.Log.Info("storage: new attachments fit under the monthly limit again")
	}
}

// bucketRoom says, file by file, whether the mover may put size more bytes
// in the bucket. Moving only shifts bytes from this disk to the bucket, so
// this weighs what the bucket holds, not what waits here.
func (s *Server) bucketRoom(ctx context.Context) func(int64) bool {
	limit, set, err := s.storageLimit(ctx)
	if err != nil {
		return func(int64) bool { return false }
	}
	if !set {
		return func(int64) bool { return true }
	}
	n, err := s.storageNow(ctx)
	if err != nil {
		return func(int64) bool { return false }
	}
	stored := n.stored
	return func(size int64) bool {
		if storageCostCents(stored+size, n.a, n.b) > limit {
			return false
		}
		stored += size
		return true
	}
}

// StartStorage runs the mover while attachments go to a bucket: each pass
// counts requests, deletes copies nothing holds any more, and moves held
// attachments there as far as the limit allows.
func (s *Server) StartStorage(ctx context.Context) {
	if !s.bucketOn() {
		return
	}
	go func() {
		var backoff time.Duration
		for {
			wait := storagePass
			s.flushStorageOps(ctx)
			if n, err := s.Media.SweepRemote(ctx, 500); err != nil && ctx.Err() == nil {
				s.Log.Warn("storage: delete from the bucket", "err", err)
			} else if n > 0 {
				s.storageChanged()
			}
			res, err := s.Media.MoveToRemote(ctx, moveBatch, s.bucketRoom(ctx))
			if res.Moved > 0 {
				s.storageChanged()
				s.Log.Info("storage: moved to the bucket", "files", res.Moved, "bytes", res.Bytes)
			}
			switch {
			case ctx.Err() != nil:
			case err != nil:
				backoff = min(max(2*backoff, storagePass), 15*time.Minute)
				wait = backoff
				s.Log.Warn("storage: move to the bucket", "err", err, "next_try", wait)
			case res.Moved == moveBatch:
				backoff, wait = 0, time.Second // more is waiting
			default:
				backoff = 0
			}
			select {
			case <-ctx.Done():
				s.flushStorageOps(context.WithoutCancel(ctx))
				return
			case <-time.After(wait):
			}
		}
	}()
}

// storageStatus is the meter as Settings, Nexa Admin shows it, or nil while
// every file stays on this disk.
func (s *Server) storageStatus(ctx context.Context) map[string]any {
	if !s.bucketOn() {
		return nil
	}
	n, err := s.storageNow(ctx)
	if err != nil {
		return nil
	}
	limit, set, _ := s.storageLimit(ctx)
	s.uploads.Lock()
	onTheWay := s.uploads.remoteBytes
	s.uploads.Unlock()
	out := map[string]any{
		"stored_bytes":  n.stored,
		"waiting_bytes": n.waiting,
		"class_a":       n.a,
		"class_b":       n.b,
		"cost_cents":    storageCostCents(n.stored+n.waiting, n.a, n.b),
		"limit_cents":   nil,
		"held":          false,
	}
	if set {
		out["limit_cents"] = limit
		out["held"] = storageCostCents(n.stored+n.waiting+onTheWay+1, n.a, n.b) > limit
	}
	return out
}
