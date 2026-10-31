package api

import (
	"context"
	"net/http"
	"time"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/realtime"
	"evilion.xyz/chat/internal/store"
)

// Cloudflare TURN relays for the networks where a device cannot reach the SFU
// directly. A TURN credential relays to any address, not only the SFU, and its
// egress comes out of the same monthly allowance as the calls, so credentials
// are handed out only around calls: one minted before a call has turnGrace to
// see its account join one, and one whose account is no longer in a call is
// revoked turnGrace later (so moving between channels keeps the relay). Each
// is tagged with its account, whose relaying Cloudflare measures; an account
// past REALTIME_TURN_DAILY_GB in a UTC day is cut off until the next.

const (
	// turnTTL is how long a minted credential lasts. The apps fetch ICE
	// servers again at most every 40 minutes of a call (a 30 minute cache
	// read every 10), so a credential handed out with turnReuseLeft still to
	// run lasts until the next one.
	turnTTL       = time.Hour
	turnReuseLeft = 45 * time.Minute
	// turnGrace is how long a credential waits for its account's call to
	// start, and outlives its end.
	turnGrace = 2 * time.Minute
	// turnRestartGrace is the same after a restart, while the apps reconnect
	// and join their calls again.
	turnRestartGrace = 3 * time.Minute
	turnSweepEvery   = 15 * time.Second
)

var turnClient = &http.Client{Timeout: 6 * time.Second}

// turnAPI is the TURN key's client, or nil when TURN is not set up.
func (s *Server) turnAPI() *realtime.TURN {
	t := realtime.NewTURN(s.Cfg.TurnKeyID, s.Cfg.TurnAPIToken, turnClient)
	if t != nil && s.turnBase != "" {
		t.Base = s.turnBase
	}
	return t
}

// turnCred is the credential an account is handed again while enough of it is left.
type turnCred struct {
	username string
	servers  []map[string]any
	expires  time.Time
}

// turnGrant is a live credential the sweep watches.
type turnGrant struct {
	user    ids.ID
	expires time.Time
	// idleSince is when its account was first seen out of a call (zero
	// while in one); the credential is revoked turnGrace after it.
	idleSince time.Time
}

// loadTURN picks up the credentials a previous run handed out, so they are
// still revoked once their accounts are out of calls. The caller holds turnMu.
func (s *Server) loadTURNLocked(ctx context.Context, now time.Time) {
	if s.turnLoaded {
		return
	}
	live, err := s.Store.LiveTurnCredentials(ctx, now.UnixMilli())
	if err != nil {
		return
	}
	if s.turnLive == nil {
		s.turnLive = map[string]*turnGrant{}
	}
	for _, c := range live {
		if _, ok := s.turnLive[c.Username]; ok {
			continue
		}
		// a call the restart interrupted has turnRestartGrace to come back
		s.turnLive[c.Username] = &turnGrant{user: c.User, expires: time.UnixMilli(c.ExpiresAt), idleSince: now.Add(turnRestartGrace - turnGrace)}
	}
	s.turnLoaded = true
}

// turnBlockedLocked reports whether an account is past its relay for the day.
func (s *Server) turnBlockedLocked(uid ids.ID, now time.Time) bool {
	until, ok := s.turnBlocked[uid]
	if ok && !now.Before(until) {
		delete(s.turnBlocked, uid)
		return false
	}
	return ok
}

// cloudflareTURN hands this account a relay credential for a call it is in
// or about to start, or nil: a call without a relay is better than none, and
// most devices never need one.
func (s *Server) cloudflareTURN(r *http.Request) []map[string]any {
	me := auth.UserFrom(r.Context())
	if me == nil || s.turnAPI() == nil {
		return nil
	}
	// Parallel ICE requests share one mint, including on the first request
	// from a guest before a cached credential exists.
	value, _, _ := s.turnMint.Do(me.ID.String(), func() (any, error) {
		return s.mintTURN(r), nil
	})
	servers, _ := value.([]map[string]any)
	return servers
}

func (s *Server) mintTURN(r *http.Request) []map[string]any {
	ctx := r.Context()
	me := auth.UserFrom(ctx)
	api := s.turnAPI()
	if me == nil || api == nil {
		return nil
	}
	now := time.Now()
	_, inCall := s.Voice.Get(me.ID)
	if !inCall && s.Meter != nil && !s.Meter.CallsAllowed(ctx) {
		// no call can start, so there is nothing to relay
		return nil
	}
	ttl := turnTTL
	if guest := auth.GuestFrom(ctx); guest != nil {
		// A guest's relay lasts no longer than the guest may stay.
		ttl = min(ttl, time.Until(time.UnixMilli(guest.ExpiresAt)))
		if ttl < time.Minute {
			return nil
		}
	}
	s.turnMu.Lock()
	s.loadTURNLocked(ctx, now)
	if s.turnBlockedLocked(me.ID, now) {
		s.turnMu.Unlock()
		return nil
	}
	if hit, ok := s.turnCreds[me.ID]; ok {
		_, live := s.turnLive[hit.username]
		// the same one again while enough of it is left, and never one
		// lasting past a guest's stay
		if live && hit.expires.Sub(now) >= min(turnReuseLeft, ttl-time.Minute) && !hit.expires.After(now.Add(ttl)) {
			s.turnMu.Unlock()
			return hit.servers
		}
	}
	s.turnMu.Unlock()
	if ok, _ := s.limTurn.Allow("turn:" + me.ID.String()); !ok {
		return nil
	}
	cred, err := api.Mint(ctx, ttl, me.ID.String())
	if err != nil {
		s.Log.Warn("turn credentials unavailable", "err", err)
		return nil
	}
	expires := now.Add(ttl)
	// a credential that is not recorded could never be revoked, so it is not handed out
	if err := s.Store.SaveTurnCredential(ctx, store.TurnCredential{Username: cred.Username, User: me.ID, CreatedAt: now.UnixMilli(), ExpiresAt: expires.UnixMilli()}); err != nil {
		s.Log.Warn("turn credential not recorded", "err", err)
		go s.revokeTURN(context.Background(), cred.Username)
		return nil
	}
	s.turnMu.Lock()
	defer s.turnMu.Unlock()
	if s.turnLive == nil {
		s.turnLive = map[string]*turnGrant{}
	}
	if s.turnCreds == nil {
		s.turnCreds = map[ids.ID]turnCred{}
	}
	g := &turnGrant{user: me.ID, expires: expires}
	if !inCall {
		g.idleSince = now
	}
	s.turnLive[cred.Username] = g
	s.turnCreds[me.ID] = turnCred{username: cred.Username, servers: cred.Servers, expires: expires}
	return cred.Servers
}

// sweepTURN revokes the credentials whose accounts have been out of calls for
// turnGrace, and those of accounts cut off for the day.
func (s *Server) sweepTURN(ctx context.Context) {
	if s.turnAPI() == nil {
		return
	}
	now := time.Now()
	var revoke []string
	s.turnMu.Lock()
	s.loadTURNLocked(ctx, now)
	for name, g := range s.turnLive {
		if !now.Before(g.expires) {
			delete(s.turnLive, name)
			continue
		}
		_, inCall := s.Voice.Get(g.user)
		switch {
		case s.turnBlockedLocked(g.user, now):
			revoke = append(revoke, name)
		case inCall:
			g.idleSince = time.Time{}
		case g.idleSince.IsZero():
			g.idleSince = now
		case now.Sub(g.idleSince) >= turnGrace:
			revoke = append(revoke, name)
		}
	}
	s.turnMu.Unlock()
	for _, name := range revoke {
		s.revokeTURN(ctx, name)
	}
}

// revokeTURN revokes one credential at Cloudflare, and forgets it once that
// is confirmed; a failure is tried again at the next sweep.
func (s *Server) revokeTURN(ctx context.Context, username string) {
	api := s.turnAPI()
	if api == nil {
		return
	}
	rctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	err := api.Revoke(rctx, username)
	cancel()
	if err != nil {
		s.Log.Warn("turn credential not revoked yet", "err", err)
		return
	}
	_ = s.Store.MarkTurnCredentialRevoked(ctx, username, time.Now().UnixMilli())
	s.turnMu.Lock()
	defer s.turnMu.Unlock()
	if g, ok := s.turnLive[username]; ok {
		if hit, cached := s.turnCreds[g.user]; cached && hit.username == username {
			delete(s.turnCreds, g.user)
		}
		delete(s.turnLive, username)
	}
}

// blockTURN cuts an account's relay off until the end of the UTC day, and
// says why in the log for the staff.
func (s *Server) blockTURN(uid ids.ID, relayed int64, now time.Time) {
	end := now.UTC().Truncate(24 * time.Hour).Add(24 * time.Hour)
	s.turnMu.Lock()
	if s.turnBlocked == nil {
		s.turnBlocked = map[ids.ID]time.Time{}
	}
	_, already := s.turnBlocked[uid]
	s.turnBlocked[uid] = end
	s.turnMu.Unlock()
	if !already {
		s.Log.Warn("turn relay cut off for the day", "user", uid.String(), "relayed_bytes", relayed, "limit_bytes", s.turnDailyLimit())
	}
}

// turnDailyLimit is the most one account may relay in a UTC day, in bytes (0: no limit).
func (s *Server) turnDailyLimit() int64 { return s.Cfg.RealtimeTURNDailyGB * 1_000_000_000 }

// turnCutOff reports whether an account's relay is cut off for the day.
func (s *Server) turnCutOff(uid ids.ID) bool {
	s.turnMu.Lock()
	defer s.turnMu.Unlock()
	return s.turnBlockedLocked(uid, time.Now())
}
