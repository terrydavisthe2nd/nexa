package gateway

import (
	"context"
	"errors"
	"log/slog"
	"net"
	"net/netip"
	"net/url"
	"runtime/debug"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/ratelimit"
	"evilion.xyz/chat/internal/rpcapps"
	"evilion.xyz/chat/internal/store"
)

// Hub tracks connections and fans events out.
type Hub struct {
	st   *store.Store
	auth *auth.Authenticator
	log  *slog.Logger
	seq  atomic.Uint64

	mu    sync.RWMutex
	conns map[ids.ID]map[*conn]struct{}
	// total and byIP count the same connections conns holds, so the caps can
	// be answered without walking every user's set on each new socket.
	total int
	byIP  map[string]int
	// waiting counts sockets that are up but have not identified yet. They
	// cost a goroutine each and need no account at all, so they are held to a
	// much smaller number than connections that got past identify.
	waiting map[string]int

	originPatterns   []string
	heartbeatTimeout time.Duration
	identifyTimeout  time.Duration
	sendBuffer       int

	// maxConnsPerUser is how many sockets one account may hold at once.
	//
	// The web client opens one per browser tab; the phone and the desktop app
	// are one each. Somebody with the app, their phone and half a dozen tabs
	// is about ten — and a laptop coming back from sleep can briefly double
	// that, because its old sockets are still registered until the ping loop
	// notices. This sits at roughly twice that worst honest case, which still
	// leaves an account nowhere near enough to matter on its own against the
	// Nexa-wide cap.
	//
	// Going over refuses the newest rather than closing the oldest. The cost
	// this bounds is buildReady, which runs once per accepted connection, so
	// evicting an older socket would bound nothing: the client that owned it
	// would reconnect, build ready again and evict the next one.
	maxConnsPerUser int
	// maxConnsPerIP bounds one address across every account it signs in as,
	// which is what registration being open would otherwise leave unbounded.
	// It has to clear an office or a school behind one NAT by a wide margin —
	// shutting a building out is a worse failure than letting an abuser hold
	// a few hundred sockets — so it sits at sixteen accounts' worth, and the
	// Nexa-wide cap is what actually bounds the process.
	maxConnsPerIP int
	// maxConns is the whole server's ceiling, so no combination of accounts
	// and addresses can take the process past a known number of goroutines and
	// send buffers.
	maxConns int
	// maxWaitingPerIP bounds sockets that are up but have not identified.
	// Those need no account, and each holds a goroutine for up to
	// identifyTimeout. A real client identifies within a round trip of the
	// upgrade, so this only ever bites on sockets that are being held open on
	// purpose — but it is still set well clear of a crowd behind one NAT
	// reloading at the same moment.
	maxWaitingPerIP int
	// live counts the ServeHTTP calls still running. http.Server.Shutdown says
	// outright that it neither closes nor waits for hijacked connections, and
	// websocket.Accept hijacks — so without this the database was closed while
	// every disconnecting client was still running its tail of queries through
	// it, and the WAL checkpoint on the way out could not run either.
	live sync.WaitGroup
	// connectRate meters how often one address may open a socket at all. The
	// caps above bound how many connections are held at once, which is not the
	// same as how much work opening them costs: every accepted connection runs
	// buildReady, tens of queries, so an address that connects and disconnects
	// in a loop stays inside every cap and still saturates the database.
	connectRate *ratelimit.Limiter
	// typingRate is each account's typing budget across every channel and
	// device. The per-channel cooldown alone let a client rotating through
	// the channels it can see make the server resolve a channel's audience
	// several times a second, from one tab; a person typing sends one every
	// eight seconds to the one channel they are in.
	typingRate *ratelimit.Limiter

	// Hooks the server layer (Phase 2) sets to extend DM-only behaviour. Who
	// may see a server channel comes from the store's permission snapshots.
	// Access reports whether uid may act in a server channel, handed the
	// channel the hub has already read.
	Access func(ctx context.Context, uid ids.ID, ch *store.Channel) (bool, error)
	// SendAccess reports whether uid may send messages, and so type, in a server channel.
	SendAccess func(ctx context.Context, uid ids.ID, ch *store.Channel) (bool, error)
	// Interested lists extra users who should receive uid's presence (server co-members).
	Interested func(ctx context.Context, uid ids.ID) ([]ids.ID, error)
	// GuestReady fills in a guest's ready payload with their call.
	GuestReady func(ctx context.Context, g *store.Guest, p *ReadyPayload) error
	// ReadyExtras adds server data to the ready payload.
	ReadyExtras func(ctx context.Context, u *store.User, p *ReadyPayload) error
	// ExtraChannels lists server channels whose read states belong in ready.
	ExtraChannels func(ctx context.Context, uid ids.ID) ([]ids.ID, error)
	// VoiceOp handles voice_state / voice_signal ops (Phase 4).
	VoiceOp func(ctx context.Context, c *Conn, op string, data []byte)
	// OnDisconnect runs when a user's last connection closes (voice cleanup).
	OnDisconnect func(ctx context.Context, uid ids.ID)
	// OnConnClose runs when any connection closes; voice states belong to one connection.
	OnConnClose func(ctx context.Context, uid, connID ids.ID)
	// ProxyURL rewrites an activity's image to this server's media proxy, so
	// viewers never fetch it from Discord or a third party themselves.
	ProxyURL func(raw string) string
	// Apps names Discord Rich Presence applications and their art (nil: activities show as sent).
	Apps AppLookup
	// IdentifyGame finds a detected game on IGDB: its id and a picture for its
	// card, or nothing (nil: detected games show as sent).
	IdentifyGame func(ctx context.Context, name, store, storeID, gameID string) (id, art string)
	// Restricted reports an account's moderation restrictions: a banned one
	// is refused at identify, and a muted one cannot type or set a status.
	Restricted func(ctx context.Context, uid ids.ID) (muted, banned bool)

	// linked holds activities that come from outside any connection: what a
	// person's linked Discord account says they are doing (internal/discordsync).
	// They are kept while the person is offline here, and shown once they are back.
	linkedMu    sync.Mutex
	linked      map[ids.ID][]Activity
	linkedTimer map[ids.ID]*time.Timer
	linkedAt    map[ids.ID]time.Time
}

// AppLookup finds a Discord Rich Presence application.
type AppLookup interface {
	Lookup(ctx context.Context, id string) (*rpcapps.App, error)
}

// New creates a hub. Pages at any of `origins` may open a connection.
func New(st *store.Store, authn *auth.Authenticator, log *slog.Logger, origins []string) *Hub {
	h := &Hub{st: st, auth: authn, log: log, conns: map[ids.ID]map[*conn]struct{}{},
		byIP: map[string]int{}, waiting: map[string]int{},
		linked: map[ids.ID][]Activity{}, linkedTimer: map[ids.ID]*time.Timer{}, linkedAt: map[ids.ID]time.Time{},
		heartbeatTimeout: 60 * time.Second, identifyTimeout: 10 * time.Second, sendBuffer: 256,
		maxConnsPerUser: 32, maxConnsPerIP: 256, maxConns: 4096, maxWaitingPerIP: 32,
		// Two a second per address, sustained, with room for a burst of tabs
		// reopening together. No person reaches this; a loop does immediately.
		connectRate: ratelimit.New(ratelimit.Every(240, time.Minute), 120, 10*time.Minute),
		// five in ten seconds, across every channel
		typingRate: ratelimit.New(ratelimit.Every(30, time.Minute), 5, 10*time.Minute)}
	for _, o := range origins {
		if u, err := url.Parse(o); err == nil && u.Host != "" {
			h.originPatterns = append(h.originPatterns, u.Host)
		}
	}
	h.originPatterns = append(h.originPatterns, "localhost:*", "127.0.0.1:*")
	return h
}

// Wait blocks until every connection's handler has finished, or ctx ends.
// It belongs between the HTTP server's shutdown and closing the database.
func (h *Hub) Wait(ctx context.Context) {
	done := make(chan struct{})
	go func() {
		h.live.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-ctx.Done():
	}
}

// SetLimits replaces the connection caps. A value of zero leaves that cap as
// it was, so a caller can raise one without knowing the others.
func (h *Hub) SetLimits(perUser, perIP, total, waitingPerIP int) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, f := range []struct {
		v   int
		dst *int
	}{{perUser, &h.maxConnsPerUser}, {perIP, &h.maxConnsPerIP}, {total, &h.maxConns}, {waitingPerIP, &h.maxWaitingPerIP}} {
		if f.v > 0 {
			*f.dst = f.v
		}
	}
}

// errTooMany is why a socket was refused, for the close reason.
type errTooMany string

func (e errTooMany) Error() string { return string(e) }

const (
	tooManyForUser     = errTooMany("too many connections for this account")
	tooManyForIP       = errTooMany("too many connections from this address")
	tooManyForInstance = errTooMany("the server is at its connection limit")
)

// AdmitWaiting lets another gateway share the same pre-authentication
// connection budget. The returned release function is safe to call twice.
func (h *Hub) AdmitWaiting(ip string) (func(), error) {
	if err := h.admitWaiting(ip); err != nil {
		return nil, err
	}
	var once sync.Once
	return func() { once.Do(func() { h.releaseWaiting(ip) }) }, nil
}

// admitWaiting reserves room for a socket that has not identified yet. The
// caller releases it once the socket has either registered or given up.
func (h *Hub) admitWaiting(ip string) error {
	// An address the proxy in front did not tell us about arrives as loopback,
	// and then every visitor shares one bucket; counting them together would
	// shut Nexa rather than an abuser out, so the per-address limits
	// stand down and the Nexa-wide one carries it.
	shared := sharedAddress(ip)
	if !shared && h.connectRate != nil {
		if ok, _ := h.connectRate.Allow("gw:" + ip); !ok {
			return tooManyForIP
		}
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.total+h.totalWaitingLocked() >= h.maxConns {
		return tooManyForInstance
	}
	if !shared && h.waiting[ip] >= h.maxWaitingPerIP {
		return tooManyForIP
	}
	h.waiting[ip]++
	return nil
}

func (h *Hub) releaseWaiting(ip string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if n := h.waiting[ip]; n <= 1 {
		delete(h.waiting, ip)
	} else {
		h.waiting[ip] = n - 1
	}
}

// totalWaitingLocked counts unidentified sockets; the caller holds h.mu.
func (h *Hub) totalWaitingLocked() int {
	n := 0
	for _, c := range h.waiting {
		n += c
	}
	return n
}

// sharedAddress reports an address that says nothing about who the visitor is,
// so the per-address caps would be counting the whole server as one person.
func sharedAddress(ip string) bool {
	if ip == "" {
		return true
	}
	// an IPv6 client counted by its /64 (auth.RateKey)
	if _, err := netip.ParsePrefix(ip); err == nil {
		return false
	}
	parsed := net.ParseIP(ip)
	return parsed == nil || parsed.IsLoopback() || parsed.IsUnspecified()
}

// fanOutTimeout bounds the database work behind an event that has nobody left
// to cancel it. These helpers are called from HTTP handlers whose own context
// is gone by then — the client navigated away, the phone lost its network — so
// without a deadline of their own a slow database pins the goroutine and the
// reader connection it holds, and enough of them empty the read pool.
const fanOutTimeout = 5 * time.Second

// background is a context for work that outlives the request that started it.
func background() (context.Context, context.CancelFunc) {
	return context.WithTimeout(context.Background(), fanOutTimeout)
}

// safely runs fn on its own goroutine and turns a panic into a log line.
// Everything the gateway does after the upgrade — the write loop, the ping
// loop, the activity resolver, the presence timers — runs outside the HTTP
// handler chain, so the api package's Recover never sees it and one panic
// would take the process down with every other connection on it.
func (h *Hub) safely(what string, fn func()) {
	go func() {
		defer func() {
			if r := recover(); r != nil {
				h.log.Error("gateway goroutine panicked", "where", what, "panic", r, "stack", string(debug.Stack()))
			}
		}()
		fn()
	}()
}

// recoverTimer is safely for a callback that is already on its own goroutine,
// such as a time.AfterFunc: deferred, it turns a panic into a log line rather
// than the end of the process.
func (h *Hub) recoverTimer(what string) {
	if r := recover(); r != nil {
		h.log.Error("gateway timer panicked", "where", what, "panic", r, "stack", string(debug.Stack()))
	}
}

// sessionCheckEvery is how many sweeps pass between session re-checks.
const sessionCheckEvery = 12

// Run sweeps dead connections until ctx ends.
func (h *Hub) Run(ctx context.Context) {
	// without these every address and account ever seen stays in memory
	if h.connectRate != nil {
		h.connectRate.Start(ctx)
	}
	if h.typingRate != nil {
		h.typingRate.Start(ctx)
	}
	t := time.NewTicker(5 * time.Second)
	defer t.Stop()
	for n := 1; ; n++ {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			h.sweep()
			if n%sessionCheckEvery == 0 {
				h.sweepSessions(ctx)
			}
		}
	}
}

func (h *Hub) sweep() {
	now := time.Now()
	var stale []*conn
	h.mu.RLock()
	for _, set := range h.conns {
		for c := range set {
			if now.Sub(c.lastBeat()) > h.heartbeatTimeout {
				stale = append(stale, c)
			}
		}
	}
	h.mu.RUnlock()
	for _, c := range stale {
		c.close(websocket.StatusPolicyViolation, "heartbeat timeout")
	}
}

// sweepSessions closes connections whose session was deleted or whose account
// was disabled. Handlers that end sessions close their sockets at once; this
// catches the rest, such as a socket that identified while its session was
// being revoked, or sessions removed by expiry.
func (h *Hub) sweepSessions(ctx context.Context) {
	var all []*conn
	h.mu.RLock()
	for _, set := range h.conns {
		for c := range set {
			all = append(all, c)
		}
	}
	h.mu.RUnlock()
	gone := map[ids.ID]bool{}
	for _, c := range all {
		// an external connection checks its own token
		if c.external != nil {
			continue
		}
		dead, checked := gone[c.session.ID]
		if !checked {
			if c.guest != nil {
				dead = !h.auth.GuestAlive(ctx, c.userID)
			} else {
				dead = !h.sessionAlive(ctx, c.session.ID, c.userID)
			}
			if ctx.Err() != nil {
				return
			}
			gone[c.session.ID] = dead
		}
		if dead {
			c.close(CloseSessionRevoked, "session revoked")
		}
	}
	// A ban shuts every door as it lands, but a socket that passed its ban
	// check a moment before and registered a moment after is not among the
	// ones it found; this finds it.
	if h.Restricted != nil {
		banned := map[ids.ID]bool{}
		for _, c := range all {
			if c.guest != nil {
				continue
			}
			b, checked := banned[c.userID]
			if !checked {
				_, b = h.Restricted(ctx, c.userID)
				banned[c.userID] = b
			}
			if b {
				c.close(CloseBanned, "banned")
			}
		}
	}
}

// sessionAlive reports whether a session still exists for an account that may
// log in. A failed lookup other than not-found counts as alive.
func (h *Hub) sessionAlive(ctx context.Context, sid, uid ids.ID) bool {
	se, err := h.st.SessionByID(ctx, sid)
	if errors.Is(err, store.ErrNotFound) || (err == nil && se.UserID != uid) {
		return false
	}
	if err != nil {
		return true
	}
	u, err := h.st.UserByID(ctx, uid)
	if errors.Is(err, store.ErrNotFound) {
		return false
	}
	return err != nil || u.Flags&(store.FlagDisabled|store.FlagDeleted) == 0
}

// CloseSession disconnects the sockets of a session that was just deleted
// (api.Broadcaster).
func (h *Hub) CloseSession(sid ids.ID) {
	var hit []*conn
	h.mu.RLock()
	for _, set := range h.conns {
		for c := range set {
			if c.session.ID == sid {
				hit = append(hit, c)
			}
		}
	}
	h.mu.RUnlock()
	for _, c := range hit {
		c.revoke()
	}
}

// DisconnectUser closes every socket of a user with the given close code,
// after the frames already queued for them have gone out — the notice that
// explains the disconnect is one of them (api.Broadcaster).
func (h *Hub) DisconnectUser(uid ids.ID, code int, reason string) {
	var hit []*conn
	h.mu.RLock()
	for c := range h.conns[uid] {
		hit = append(hit, c)
	}
	h.mu.RUnlock()
	for _, c := range hit {
		c.kick(websocket.StatusCode(code), reason)
	}
}

// CloseUserSessions disconnects every socket of a user except those of one
// session, whose sessions were just deleted; except 0 closes them all
// (api.Broadcaster).
func (h *Hub) CloseUserSessions(uid, except ids.ID) {
	var hit []*conn
	h.mu.RLock()
	for c := range h.conns[uid] {
		if c.session.ID != except {
			hit = append(hit, c)
		}
	}
	h.mu.RUnlock()
	for _, c := range hit {
		c.revoke()
	}
}

func (h *Hub) nextSeq() uint64 { return h.seq.Add(1) }

// register adds a connection; returns true when it is the user's first. It
// refuses when a cap is reached, before the caller spends a ready build on it.
func (h *Hub) register(c *conn) (bool, error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	set := h.conns[c.userID]
	// The socket being registered already holds a waiting slot, so it is
	// counted once either way and the server's total does not double-count it.
	if h.total+h.totalWaitingLocked() > h.maxConns {
		return false, tooManyForInstance
	}
	if len(set) >= h.maxConnsPerUser {
		return false, tooManyForUser
	}
	if !sharedAddress(c.ip) && h.byIP[c.ip] >= h.maxConnsPerIP {
		return false, tooManyForIP
	}
	if set == nil {
		set = map[*conn]struct{}{}
		h.conns[c.userID] = set
	}
	set[c] = struct{}{}
	h.total++
	h.byIP[c.ip]++
	return len(set) == 1, nil
}

// unregister removes a connection; returns true when it was the user's last.
func (h *Hub) unregister(c *conn) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	set := h.conns[c.userID]
	if set == nil {
		return false
	}
	if _, ok := set[c]; !ok {
		return false
	}
	delete(set, c)
	h.total--
	if n := h.byIP[c.ip]; n <= 1 {
		delete(h.byIP, c.ip)
	} else {
		h.byIP[c.ip] = n - 1
	}
	if len(set) == 0 {
		delete(h.conns, c.userID)
		return true
	}
	return false
}

// allAway reports whether the user has connections and every one of them
// says its person has been away for a while.
func (h *Hub) allAway(uid ids.ID) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	set := h.conns[uid]
	if len(set) == 0 {
		return false
	}
	for c := range set {
		if !c.away.Load() {
			return false
		}
	}
	return true
}

// othersAway reports whether the user has connections besides `except` and
// all of those are away.
func (h *Hub) othersAway(uid ids.ID, except *conn) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	others := 0
	for c := range h.conns[uid] {
		if c == except {
			continue
		}
		if !c.away.Load() {
			return false
		}
		others++
	}
	return others > 0
}

// Online reports whether the user has any open connection.
func (h *Hub) Online(uid ids.ID) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.conns[uid]) > 0
}

// OnlineAmong returns those of list with an open connection, taking the hub's
// lock once rather than once per person.
func (h *Hub) OnlineAmong(list []ids.ID) []ids.ID {
	h.mu.RLock()
	defer h.mu.RUnlock()
	out := make([]ids.ID, 0, len(list))
	for _, uid := range list {
		if len(h.conns[uid]) > 0 {
			out = append(out, uid)
		}
	}
	return out
}

// OnlineUsers lists users with at least one connection.
func (h *Hub) OnlineUsers() []ids.ID {
	h.mu.RLock()
	defer h.mu.RUnlock()
	out := make([]ids.ID, 0, len(h.conns))
	for uid := range h.conns {
		out = append(out, uid)
	}
	return out
}

// ConnectionCount returns the number of open connections.
func (h *Hub) ConnectionCount() int {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return h.total
}

// EmitUsers sends an event to every connection of the given users.
func (h *Hub) EmitUsers(userIDs []ids.ID, t string, d any) {
	if len(userIDs) == 0 {
		return
	}
	frame := ServerFrame{T: t, S: h.nextSeq(), D: d}.fanOut()
	h.mu.RLock()
	defer h.mu.RUnlock()
	seen := make(map[ids.ID]bool, len(userIDs))
	for _, uid := range userIDs {
		if seen[uid] {
			continue
		}
		seen[uid] = true
		for c := range h.conns[uid] {
			c.enqueue(frame)
		}
	}
}

// EmitConn sends an event to one connection of a user, if it is still open.
func (h *Hub) EmitConn(uid, connID ids.ID, t string, d any) {
	frame := ServerFrame{T: t, S: h.nextSeq(), D: d}
	h.mu.RLock()
	defer h.mu.RUnlock()
	for c := range h.conns[uid] {
		if c.id == connID {
			c.enqueue(frame)
			return
		}
	}
}

// EmitUsersExcept sends to users but skips one connection (the originator).
func (h *Hub) emitUsersExcept(userIDs []ids.ID, except *conn, t string, d any) {
	frame := ServerFrame{T: t, S: h.nextSeq(), D: d}.fanOut()
	h.mu.RLock()
	defer h.mu.RUnlock()
	seen := map[ids.ID]bool{}
	for _, uid := range userIDs {
		if seen[uid] {
			continue
		}
		seen[uid] = true
		for c := range h.conns[uid] {
			if c != except {
				c.enqueue(frame)
			}
		}
	}
}

// EmitAll sends to every connection.
func (h *Hub) EmitAll(t string, d any) {
	frame := ServerFrame{T: t, S: h.nextSeq(), D: d}.fanOut()
	h.mu.RLock()
	defer h.mu.RUnlock()
	for _, set := range h.conns {
		for c := range set {
			c.enqueue(frame)
		}
	}
}

// Viewers lists the users who can see a channel: a conversation's
// participants, or a server channel's members who may view it. The list may be
// shared with other callers; read it, do not change it.
func (h *Hub) Viewers(ctx context.Context, channelID ids.ID) ([]ids.ID, error) {
	return h.st.ChannelViewers(ctx, channelID)
}

// CanAccess reports whether uid may read/act in a channel.
func (h *Hub) CanAccess(ctx context.Context, uid, channelID ids.ID) (bool, error) {
	ch, err := h.st.ChannelForAccess(ctx, channelID)
	if err != nil {
		return false, err
	}
	return h.canAccess(ctx, uid, ch)
}

func (h *Hub) canAccess(ctx context.Context, uid ids.ID, ch *store.Channel) (bool, error) {
	// A subchat is DM-like everywhere else, and taking the hook branch for one
	// cost two extra channel reads and — because only this branch checks
	// DMBlocked — let someone who had been blocked go on acting in a subchat
	// of the 1:1 conversation they were blocked in.
	if store.IsDMLike(ch.Type) {
		if !slices.Contains(ch.Recipients, uid) {
			return false, nil
		}
		// a block closes a 1:1 conversation both ways, typing included
		blocked, err := h.st.DMBlocked(ctx, ch, uid)
		return !blocked, err
	}
	if h.Access != nil {
		return h.Access(ctx, uid, ch)
	}
	return false, nil
}

// EmitChannel sends an event to everyone who can see the channel.
func (h *Hub) EmitChannel(channelID ids.ID, t string, d any) {
	ctx, cancel := background()
	defer cancel()
	viewers, err := h.Viewers(ctx, channelID)
	if err != nil {
		h.log.Warn("emit channel: viewers", "channel", channelID, "err", err)
		return
	}
	h.EmitUsers(viewers, t, d)
}

// presenceAudience lists who sees uid's presence, custom status and
// activities: uid, their friends and the people they share a server with —
// minus anyone either of them has blocked. A conversation alone does not
// subscribe anyone: someone you once exchanged a message with is not owed a
// live view of when you are at your computer and what you are running, and a
// block has to end that view too, or blocking a harasser would leave them
// watching.
func (h *Hub) presenceAudience(ctx context.Context, uid ids.ID) []ids.ID {
	return h.audience(ctx, uid, false)
}

// profileAudience is presenceAudience and everyone uid shares a conversation
// with (blocks still excepted), who see their name and picture change.
func (h *Hub) profileAudience(ctx context.Context, uid ids.ID) []ids.ID {
	return h.audience(ctx, uid, true)
}

func (h *Hub) audience(ctx context.Context, uid ids.ID, conversations bool) []ids.ID {
	out := []ids.ID{uid}
	if friends, err := h.st.Friends(ctx, uid); err == nil {
		out = append(out, friends...)
	}
	if conversations {
		if partners, err := h.st.DMPartners(ctx, uid); err == nil {
			out = append(out, partners...)
		}
	}
	if h.Interested != nil {
		if more, err := h.Interested(ctx, uid); err == nil {
			out = append(out, more...)
		}
	}
	blocked, err := h.st.BlockedEitherWay(ctx, uid)
	if err != nil {
		// not knowing who is blocked, tell only the person themselves
		return []ids.ID{uid}
	}
	if len(blocked) == 0 {
		return out
	}
	kept := out[:0]
	for _, id := range out {
		if !blocked[id] {
			kept = append(kept, id)
		}
	}
	return kept
}

// UserUpdated broadcasts a profile change (api.Broadcaster).
func (h *Hub) UserUpdated(u *store.User) {
	ctx, cancel := background()
	defer cancel()
	h.refreshUser(u)
	h.EmitUsers(h.profileAudience(ctx, u.ID), EvUserUpdate, u.Public())
	// presence carries the custom status too
	h.broadcastPresence(ctx, u)
}

// refreshUser gives every connection of the account this copy of it, unless
// one holds a newer one. Each connection keeps its own: the name a voice join
// carries to guests, the presence choice going idle checks, and what every
// presence it sends is built from. One changed anywhere — on another device,
// by a moderator — changes it here too.
func (h *Hub) refreshUser(u *store.User) {
	h.mu.RLock()
	defer h.mu.RUnlock()
	for c := range h.conns[u.ID] {
		for {
			old := c.user.Load()
			if old != nil && old.UpdatedAt > u.UpdatedAt {
				break
			}
			if c.user.CompareAndSwap(old, u) {
				break
			}
		}
	}
}

// KeysMaybeNeeded tells the online holders of each of a conversation's keys
// about members who still lack it (api.Broadcaster). Every epoch, not only
// the newest: someone who arrives after a key was replaced needs the older
// ones to read what was said before them.
func (h *Hub) KeysMaybeNeeded(convID ids.ID) {
	ctx, cancel := background()
	defer cancel()
	epochs, err := h.st.Epochs(ctx, convID)
	if err != nil || len(epochs) == 0 {
		return
	}
	entitled, err := h.st.Entitled(ctx, convID)
	if err != nil {
		return
	}
	// newest first: what is being said now matters before what was said
	for i := len(epochs) - 1; i >= 0; i-- {
		epoch := epochs[i].Epoch
		holders, err := h.st.HoldersOf(ctx, convID, epoch)
		if err != nil {
			return
		}
		lacking := store.Lacking(entitled, holders)
		if len(lacking) == 0 {
			continue
		}
		sharers, err := h.st.SharersOf(ctx, convID, epoch)
		if err != nil {
			return
		}
		h.EmitUsers(h.OnlineAmong(sharers), EvKeysNeeded, store.KeyNeed{ConvID: convID, Epoch: epoch, UserIDs: lacking})
	}
}

// PresenceOf computes a user's presence as others see it.
func (h *Hub) PresenceOf(ctx context.Context, u *store.User) Presence {
	p := Presence{UserID: u.ID, Status: "offline"}
	if !h.Online(u.ID) {
		return p
	}
	p.StatusText, p.StatusEmoji, p.StatusExpiresAt = u.StatusText, u.StatusEmoji, u.StatusExpiresAt
	if p.StatusExpiresAt != nil && *p.StatusExpiresAt < time.Now().UnixMilli() {
		p.StatusText, p.StatusEmoji, p.StatusExpiresAt = "", "", nil
	}
	switch u.PresencePref {
	case "idle", "dnd":
		p.Status = u.PresencePref
	case "invisible":
		// exactly what someone offline looks like: a custom status shown
		// beside "offline" would say they are really here
		return Presence{UserID: u.ID, Status: "offline"}
	default:
		p.Status = "online"
		// online, but left alone on every device for a while: idle until back
		if h.allAway(u.ID) {
			p.Status = "idle"
		}
	}
	// someone invisible shows no activity either
	if p.Status != "offline" {
		p.Activities = h.activitiesOf(u.ID)
	}
	return p
}

// selfPresence is what the user's own devices see (invisible stays visible to self).
func (h *Hub) selfPresence(ctx context.Context, u *store.User) Presence {
	p := h.PresenceOf(ctx, u)
	if u.PresencePref == "invisible" && h.Online(u.ID) {
		p.Status = "invisible"
		p.Activities = h.activitiesOf(u.ID)
	}
	return p
}

// PresenceChanged re-announces a user's presence to everyone who should see
// it. Joining a server gives them a new audience: without this the people
// already there add them to the member list with nothing to say about them,
// and they sit at the bottom under Offline until something else happens.
func (h *Hub) PresenceChanged(uid ids.ID) {
	if !h.Online(uid) {
		return
	}
	ctx, cancel := background()
	defer cancel()
	u, err := h.st.UserByID(ctx, uid)
	if err != nil || u == nil {
		return
	}
	h.broadcastPresence(ctx, u)
}

// broadcastPresence pushes the user's current presence to interested users.
// broadcastArrival is broadcastPresence for a first connection or a last
// disconnection. Someone invisible looks offline either way, so the others
// are told nothing — an "offline" arriving the moment they connect would be
// the tell — and only their own other devices hear.
func (h *Hub) broadcastArrival(ctx context.Context, u *store.User) {
	if u.PresencePref == "invisible" {
		h.EmitUsers([]ids.ID{u.ID}, EvPresence, h.selfPresence(ctx, u))
		return
	}
	h.broadcastPresence(ctx, u)
}

func (h *Hub) broadcastPresence(ctx context.Context, u *store.User) {
	interested := h.presenceAudience(ctx, u.ID)
	others := interested[:0:0]
	for _, id := range interested {
		if id != u.ID {
			others = append(others, id)
		}
	}
	h.EmitUsers(others, EvPresence, h.PresenceOf(ctx, u))
	h.EmitUsers([]ids.ID{u.ID}, EvPresence, h.selfPresence(ctx, u))
}

// CloseAll disconnects every client (shutdown).
func (h *Hub) CloseAll(code websocket.StatusCode, reason string) {
	h.mu.RLock()
	var all []*conn
	for _, set := range h.conns {
		for c := range set {
			all = append(all, c)
		}
	}
	h.mu.RUnlock()
	for _, c := range all {
		c.close(code, reason)
	}
}

func hostOf(origin string) string {
	u, err := url.Parse(origin)
	if err != nil {
		return ""
	}
	return strings.ToLower(u.Host)
}
