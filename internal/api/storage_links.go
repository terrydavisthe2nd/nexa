package api

import (
	"context"
	"net/http"

	"evilion.xyz/chat/internal/auth"
)

// Downloads of files in the bucket get a short signed link to it
// (media.Store.serveFromBucket), so the bytes come from Cloudflare rather
// than this machine's connection. A link cannot be taken back: until it
// runs out (two minutes) whoever holds it can fetch with it again and
// again, each fetch a read the bucket bills. Reads are cheap (the first ten
// million a month are free, then $0.36 a million), so what bounds a
// replayed link is a cap on what reads may cost in a month: past it, links
// stop and downloads stream through this server, paced per account, until
// the month ends. The cap is a fifth of the monthly limit (readCapShare),
// or readCapNoLimit when there is none, so reads alone can never stop
// uploads, which only stop at the whole limit. Cloudflare's numbers arrive
// minutes late (measureLag), so a burst can pass the cap by the reads of
// those minutes. An account asking for links faster than limLinks gets the
// bytes through here instead, so nobody is refused for it.

const (
	// readCapShare: links stop once reads cost more than limit/readCapShare.
	readCapShare = 5
	// readCapNoLimit is the cap on reads, in cents, without a monthly limit.
	readCapNoLimit = 1000
)

// readCapCents is this month's cap on what reads may cost while links are on.
func (s *Server) readCapCents(ctx context.Context) int64 {
	limit, set, err := s.storageLimit(ctx)
	if err != nil {
		return 0
	}
	if !set {
		return readCapNoLimit
	}
	return limit / readCapShare
}

// noteReads works out, each pass, whether this month's reads have passed
// their cap, which stops links (LinkOK).
func (s *Server) noteReads(ctx context.Context) {
	n, err := s.storageNow(ctx)
	over := err != nil // unreadable: be careful
	if err == nil {
		over = storageCostCents(0, 0, n.b) > s.readCapCents(ctx)
	}
	s.storage.mu.Lock()
	changed := s.storage.readsOver != over
	s.storage.readsOver = over
	s.storage.mu.Unlock()
	if changed && over {
		s.Log.Warn("storage: this month's reads passed their cap, so downloads come through this server until the month ends")
	} else if changed {
		s.Log.Info("storage: downloads get links to the bucket again")
	}
}

// LinkOK says whether a download may be handed a link to the bucket
// (media.Store.LinkOK): under the month's cap on reads, and within this
// account's pace.
func (s *Server) LinkOK(r *http.Request) bool {
	s.storage.mu.Lock()
	over := s.storage.readsOver
	s.storage.mu.Unlock()
	if over {
		return false
	}
	ok, _ := s.limLinks.Allow(readKey(r))
	return ok
}

// BucketReadOK paces downloads streamed through here from the bucket
// (media.Store.ReadOK), each a read the bucket bills.
func (s *Server) BucketReadOK(r *http.Request) bool {
	ok, _ := s.limReads.Allow(readKey(r))
	return ok
}

func readKey(r *http.Request) string {
	if u := auth.UserFrom(r.Context()); u != nil {
		return "u:" + u.ID.String()
	}
	return "ip:" + auth.ClientIP(r)
}

// linksStatus is the links' state for Settings, Nexa Admin.
func (s *Server) linksStatus(ctx context.Context, b int64) map[string]any {
	s.storage.mu.Lock()
	over := s.storage.readsOver
	s.storage.mu.Unlock()
	return map[string]any{"on": !over, "read_cents": storageCostCents(0, 0, b), "cap_cents": s.readCapCents(ctx)}
}
