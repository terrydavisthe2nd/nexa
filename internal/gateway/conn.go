package gateway

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"
	"golang.org/x/time/rate"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/store"
	"evilion.xyz/chat/internal/textutil"
)

const (
	readLimit    = 1 << 16
	writeTimeout = 10 * time.Second
	// The socket is pinged at the protocol level, which the browser answers
	// whatever the page is doing. A tab in the background has its timers
	// throttled, so its own heartbeat can be a minute late while the pong
	// still comes back on time. A peer that has really gone — a closed
	// laptop, a network that dropped — answers nothing, and its presence
	// turns offline within pingEvery + pingTimeout rather than after the
	// heartbeat's whole minute of silence.
	pingEvery   = 12 * time.Second
	pingTimeout = 8 * time.Second
	// typingCooldown sits well below the composer's 8 s resend interval, so a
	// refresh that arrives a little early on a jittery link is not dropped.
	typingCooldown = 5 * time.Second
	// typingTTL is how long receivers show an indicator; after it a stop is moot.
	typingTTL = 10 * time.Second
	// typingKeep is how many channels a connection remembers before it forgets
	// the ones whose indicators have run out.
	typingKeep = 32

	// A connection may send opRate frames a second with bursts of opBurst. The
	// burst covers the client flushing its offline queue and a call's ICE
	// candidates; past that the socket is closed.
	opRate  = 20
	opBurst = 100
	// Each presence op writes to the database and fans out to every co-member.
	presenceEvery = 2 * time.Second
	presenceBurst = 5
	// Activity changes reach co-members at most this often, like Discord's
	// five updates per twenty seconds; the latest waits for its turn.
	activityEvery = 4 * time.Second
	activityBurst = 5
	// maxActivityBytes bounds one activity as sent.
	maxActivityBytes = 8 << 10
	// Voice signals are relayed to one peer, so their bytes are budgeted to
	// keep a flood from filling that peer's send buffer.
	signalBytesRate  = 128 << 10
	signalBytesBurst = 512 << 10
	// Where someone's caret is in a note is sent as it moves, a few times a
	// second at most; past this the extra ones are dropped, not the socket.
	noteCursorRate  = 10
	noteCursorBurst = 20
	// noteCursorMax bounds one sealed cursor.
	noteCursorMax = 2 << 10
	// Who a note's cursors go to is looked up once in a while, not for
	// every move of a caret: someone taken out of a note stops getting them
	// within this long.
	noteAudienceTTL = 5 * time.Second
	// noteAudienceKeep is how many notes one connection remembers that for.
	noteAudienceKeep = 16

	maxStatusText  = 128
	maxStatusEmoji = 64

	// CloseSessionRevoked tells the client its session is gone; it signs out.
	CloseSessionRevoked websocket.StatusCode = 4001
	// CloseRateLimited tells the client it sent too much; it reconnects.
	CloseRateLimited websocket.StatusCode = 4008
	// CloseTooManyConnections says this socket was one too many for the
	// account, the address or the whole server. Reconnecting at once only gets the
	// same answer; the client should close a tab or wait.
	CloseTooManyConnections websocket.StatusCode = 4009
	// CloseBanned says the account is banned from Nexa. The session
	// still works for the ban screen and an appeal, so the client keeps it and
	// stops reconnecting instead of signing out.
	CloseBanned websocket.StatusCode = 4003
)

// Conn is the exported view of a connection for hook implementers.
type Conn struct {
	c *conn
}

// User returns the connection's user.
func (x *Conn) User() *store.User { return x.c.currentUser() }

// SessionID returns the session id.
func (x *Conn) SessionID() ids.ID { return x.c.session.ID }

// ID identifies this one connection. Every tab and every reconnect of a device
// shares the session, so state owned by a single socket is keyed by this.
func (x *Conn) ID() ids.ID { return x.c.id }

// Guest returns the guest on this connection, or nil for an account.
func (x *Conn) Guest() *store.Guest { return x.c.guest }

// Send enqueues a frame to this connection only.
func (x *Conn) Send(t string, d any) { x.c.enqueue(ServerFrame{T: t, S: x.c.hub.nextSeq(), D: d}) }

type conn struct {
	id  ids.ID
	hub *Hub
	ws  *websocket.Conn
	// userID and ip never change, so the read loop, the presence timer and the
	// hub's sweeps can all read them without agreeing on a lock.
	userID ids.ID
	ip     string
	// user is replaced whenever a presence change re-reads the profile, and
	// that can happen on the timer goroutine while the read loop and
	// sweepSessions are looking at it.
	user    atomic.Pointer[store.User]
	session *store.Session
	// guest is set on a connection from a guest link: its user is a stand-in,
	// and all it may do is heartbeat and be in its one call
	guest  *store.Guest
	send   chan ServerFrame
	closed chan struct{}
	once   sync.Once
	beat   atomic.Int64
	typing map[ids.ID]time.Time
	typMu  sync.Mutex

	// away is whether the person has left this connection alone for a while
	// (the away op); see Hub.allAway.
	away atomic.Bool

	ops      *rate.Limiter
	presence *rate.Limiter
	// A presence change that comes too soon waits here rather than being
	// dropped; the latest one wins when its turn arrives.
	presMu      sync.Mutex
	presPending json.RawMessage
	presTimer   *time.Timer
	signalBytes *rate.Limiter
	// Cursors sent in notes (OpNoteCursor), and who each note's go to. Both
	// are only touched by handle, on the read loop.
	cursors  *rate.Limiter
	noteSeen map[ids.ID]noteAudience

	// Activities this connection reported, resolved and cleaned. Payloads that
	// arrive while one is being resolved wait in actPending, latest only.
	actMu      sync.Mutex
	acts       []Activity
	actsAt     time.Time
	actPending *[]json.RawMessage
	actBusy    bool
	actLimit   *rate.Limiter
	// gameLimit holds how many new detected games this connection may have
	// looked up on IGDB, whose allowance every account shares
	gameLimit *rate.Limiter
	actTimer  *time.Timer

	// Until ready is queued the connection is registered but not live: events
	// wait in pending, so nothing emitted while ready is built is lost or
	// arrives ahead of it.
	live    atomic.Bool
	revoked atomic.Bool
	// kicked holds the close code of a kick, for a connection kicked before
	// it went live to close with it — a ban's 4003, not a lapsed session's.
	kicked  atomic.Pointer[kickInfo]
	pendMu  sync.Mutex
	pending []ServerFrame

	// deliver and external are set on an external connection (external.go):
	// its frames go to deliver rather than to a socket, and closing it tells
	// its owner.
	deliver  func(ServerFrame)
	external *External
}

func (c *conn) lastBeat() time.Time { return time.UnixMilli(c.beat.Load()) }

// currentUser is the profile as this connection last saw it.
func (c *conn) currentUser() *store.User { return c.user.Load() }

func (c *conn) enqueue(f ServerFrame) {
	if c.deliver != nil {
		switch f.T {
		case frameRevoked:
			c.close(CloseSessionRevoked, "session revoked")
		case frameKick:
			k, _ := f.D.(kickInfo)
			c.close(k.code, k.reason)
		default:
			c.deliver(f)
		}
		return
	}
	if !c.live.Load() {
		c.pendMu.Lock()
		if !c.live.Load() {
			// ready takes one slot of the send buffer when it goes out
			if len(c.pending) >= cap(c.send)-1 {
				c.pendMu.Unlock()
				c.close(websocket.StatusPolicyViolation, "slow consumer")
				return
			}
			c.pending = append(c.pending, f)
			c.pendMu.Unlock()
			return
		}
		c.pendMu.Unlock()
	}
	select {
	case c.send <- f:
	default:
		c.close(websocket.StatusPolicyViolation, "slow consumer")
	}
}

// goLive queues ready and then everything emitted while it was built; from
// then on events go straight to the send buffer.
func (c *conn) goLive(ready ServerFrame) {
	c.pendMu.Lock()
	defer c.pendMu.Unlock()
	if c.revoked.Load() {
		// the session ended while ready was built: send none of it, and
		// close the way whatever ended it said to
		if k := c.kicked.Load(); k != nil {
			c.close(k.code, k.reason)
		} else {
			c.close(CloseSessionRevoked, "session revoked")
		}
		return
	}
	// Nothing reached the buffer before this and nothing is draining it yet —
	// writeLoop starts after this returns — while enqueue refuses to hold more
	// than cap-1 pending frames and ready takes the last slot, so the
	// arithmetic says these sends cannot block.
	//
	// They are written not to block anyway. A caller here holds pendMu, which
	// Hub.EmitUsers reaches while holding h.mu.RLock; one blocked reader of an
	// RWMutex queues every waiting writer behind it, so register, unregister
	// and every later emitter on the whole server would stop with it. A
	// dropped connection is a price worth paying to keep that impossible
	// rather than merely unlikely.
	send := func(f ServerFrame) bool {
		select {
		case c.send <- f:
			return true
		default:
			return false
		}
	}
	if !send(ready) {
		c.pending = nil
		c.close(websocket.StatusPolicyViolation, "slow consumer")
		return
	}
	for _, f := range c.pending {
		if !send(f) {
			c.pending = nil
			c.close(websocket.StatusPolicyViolation, "slow consumer")
			return
		}
	}
	c.pending = nil
	c.live.Store(true)
}

// close ends the connection once. The close handshake waits for the peer's
// reply, so it runs in its own goroutine and never blocks a caller such as
// the sweeper or an emitter.
func (c *conn) close(code websocket.StatusCode, reason string) {
	c.once.Do(func() {
		close(c.closed)
		if c.external != nil {
			c.closeExternal(code, reason)
			return
		}
		go func() {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			_ = c.ws.Close(code, reason)
			<-ctx.Done()
			_ = c.ws.CloseNow()
		}()
	})
}

// frameRevoked marks where a revoked connection's queue ends; it is never sent.
const frameRevoked = "\x00revoked"

// frameKick marks where a kicked connection's queue ends, carrying the close
// code to end it with; it is never sent.
const frameKick = "\x00kick"

type kickInfo struct {
	code   websocket.StatusCode
	reason string
}

// kick ends a connection with its own close code once what is already queued
// has gone out, which is how the notice explaining it reaches the client.
func (c *conn) kick(code websocket.StatusCode, reason string) {
	c.kicked.Store(&kickInfo{code: code, reason: reason})
	c.revoked.Store(true)
	c.enqueue(ServerFrame{T: frameKick, D: kickInfo{code: code, reason: reason}})
	time.AfterFunc(writeTimeout, func() {
		defer c.hub.recoverTimer("kick")
		c.close(code, reason)
	})
}

// revoke ends a connection whose session is gone. Its ops are ignored at once,
// but frames already queued, such as session_expired, still go out before the
// close unless the client stops reading.
func (c *conn) revoke() {
	c.revoked.Store(true)
	c.enqueue(ServerFrame{T: frameRevoked})
	time.AfterFunc(writeTimeout, func() {
		defer c.hub.recoverTimer("revoke")
		c.close(CloseSessionRevoked, "session revoked")
	})
}

// ServeHTTP upgrades the request and runs the connection until it ends.
func (h *Hub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	ip := auth.RateKey(r)
	clientIP := auth.ClientIP(r)
	// A socket that has not identified still costs a goroutine and a file
	// descriptor for as long as identifyTimeout allows, and it needs no
	// account at all, so the room for it is taken before the upgrade — a
	// refusal is then an ordinary HTTP status the client can read.
	if err := h.admitWaiting(ip); err != nil {
		http.Error(w, err.Error(), http.StatusServiceUnavailable)
		return
	}
	waiting := true
	defer func() {
		if waiting {
			h.releaseWaiting(ip)
		}
	}()
	ws, err := websocket.Accept(w, r, &websocket.AcceptOptions{OriginPatterns: h.originPatterns, CompressionMode: websocket.CompressionNoContextTakeover})
	if err != nil {
		return
	}
	// From here on this connection is one the shutdown has to wait for: the
	// socket is hijacked, so http.Server.Shutdown will not.
	h.live.Add(1)
	defer h.live.Done()
	ws.SetReadLimit(readLimit)
	ctx := r.Context()

	// identify
	idCtx, cancel := context.WithTimeout(ctx, h.identifyTimeout)
	_, raw, err := ws.Read(idCtx)
	cancel()
	if err != nil {
		_ = ws.Close(4000, "identify timeout")
		return
	}
	var frame ClientFrame
	if err := json.Unmarshal(raw, &frame); err != nil || frame.Op != OpIdentify {
		_ = ws.Close(4000, "expected identify")
		return
	}
	var idd identifyData
	_ = json.Unmarshal(frame.D, &idd)
	user, session, err := h.auth.Check(ctx, idd.Token, clientIP)
	if err != nil {
		// the database failed, not the token: the client tries again rather
		// than signing out
		_ = ws.Close(websocket.StatusTryAgainLater, "try again")
		return
	}
	var guest *store.Guest
	if user == nil {
		user, session, guest = h.auth.ResolveGuest(ctx, idd.Token)
	}
	if user == nil {
		_ = ws.Close(4001, "authentication failed")
		return
	}
	// A banned account gets no live connection at all, so nothing said in
	// its conversations reaches it; its ban screen asks for what it needs.
	if guest == nil && h.Restricted != nil {
		if _, banned := h.Restricted(ctx, user.ID); banned {
			_ = ws.Close(CloseBanned, "banned")
			return
		}
	}
	c := &conn{id: ids.New(), hub: h, ws: ws, userID: user.ID, ip: ip, session: session, guest: guest, send: make(chan ServerFrame, h.sendBuffer), closed: make(chan struct{}), typing: map[ids.ID]time.Time{},
		ops: rate.NewLimiter(opRate, opBurst), presence: rate.NewLimiter(rate.Every(presenceEvery), presenceBurst), signalBytes: rate.NewLimiter(signalBytesRate, signalBytesBurst),
		cursors: rate.NewLimiter(noteCursorRate, noteCursorBurst), noteSeen: map[ids.ID]noteAudience{},
		actLimit: rate.NewLimiter(rate.Every(activityEvery), activityBurst), gameLimit: newGameLimit()}
	c.user.Store(user)
	c.beat.Store(time.Now().UnixMilli())

	// Register before building ready, so events fired meanwhile are held for
	// this connection instead of reaching nobody. A refusal happens here, one
	// step before the ready build this is mostly here to bound.
	first, err := h.register(c)
	if err != nil {
		_ = ws.Close(CloseTooManyConnections, err.Error())
		return
	}
	// The socket has a place of its own now; it stops holding a waiting one.
	waiting = false
	h.releaseWaiting(ip)
	var ready *ReadyPayload
	if guest != nil {
		ready, err = h.buildGuestReady(ctx, guest, session)
	} else {
		ready, err = h.buildReady(ctx, user, session)
	}
	if err != nil {
		h.log.Error("ready", "user", user.ID, "err", err)
		h.unregister(c)
		c.close(4002, "ready failed")
		return
	}
	ready.Now = time.Now().UnixMilli()
	c.goLive(ServerFrame{T: EvReady, S: h.nextSeq(), D: ready})
	// a guest has no presence: nobody lists them anywhere but in the call
	if first && guest == nil {
		h.broadcastArrival(ctx, user)
	} else if guest == nil && h.othersAway(user.ID, c) {
		// someone idle on every other device is back, on this one
		h.broadcastPresence(ctx, user)
	}

	// A write or ping loop that has stopped leaves a socket nobody is draining
	// or watching, so a panic in either ends the connection rather than
	// stranding it; close is once-only, so the ordinary exit paths are
	// unaffected.
	h.safely("write loop", func() {
		defer c.close(websocket.StatusInternalError, "internal error")
		c.writeLoop()
	})
	h.safely("ping loop", func() {
		defer c.close(websocket.StatusInternalError, "internal error")
		c.pingLoop()
	})
	c.readLoop(ctx)

	last := h.unregister(c)
	c.close(websocket.StatusNormalClosure, "bye")
	// The request's own context has ended by now, so this work carries a
	// deadline of its own rather than none at all — on shutdown in particular,
	// every connection runs this tail at once against a database that is about
	// to be closed underneath it.
	bg, cancelBg := background()
	defer cancelBg()
	if h.OnConnClose != nil {
		h.OnConnClose(bg, user.ID, c.id)
	}
	// What this connection was doing ends with it; the user's other devices
	// may still say something. The one device in use going away leaves an
	// account whose others are all idle showing as idle.
	if (c.dropActivities() || !c.away.Load() && h.allAway(user.ID)) && !last && guest == nil {
		h.broadcastUser(bg, c)
	}
	if last && guest == nil {
		h.broadcastArrival(bg, c.currentUser())
		if h.OnDisconnect != nil {
			h.OnDisconnect(bg, user.ID)
		}
	}
}

// pingLoop asks the peer to answer, and ends the connection when it stops.
func (c *conn) pingLoop() {
	t := time.NewTicker(pingEvery)
	defer t.Stop()
	for {
		select {
		case <-c.closed:
			return
		case <-t.C:
		}
		ctx, cancel := context.WithTimeout(context.Background(), pingTimeout)
		err := c.ws.Ping(ctx)
		cancel()
		select {
		case <-c.closed:
			return
		default:
		}
		if err != nil {
			c.close(websocket.StatusPolicyViolation, "no answer")
			return
		}
		// An answered ping is proof enough of life; the heartbeat timeout is
		// left for a socket that answers pings while its page has gone.
		c.beat.Store(time.Now().UnixMilli())
	}
}

func (c *conn) writeLoop() {
	for {
		select {
		case <-c.closed:
			return
		case f := <-c.send:
			if f.T == frameRevoked {
				c.close(CloseSessionRevoked, "session revoked")
				return
			}
			if f.T == frameKick {
				k, _ := f.D.(kickInfo)
				c.close(k.code, k.reason)
				return
			}
			b, err := f.encode()
			if err != nil {
				continue
			}
			ctx, cancel := context.WithTimeout(context.Background(), writeTimeout)
			err = c.ws.Write(ctx, websocket.MessageText, b)
			cancel()
			if err != nil {
				c.close(websocket.StatusAbnormalClosure, "write failed")
				return
			}
		}
	}
}

func (c *conn) readLoop(ctx context.Context) {
	for {
		_, raw, err := c.ws.Read(ctx)
		if err != nil {
			return
		}
		if c.revoked.Load() {
			continue
		}
		if !c.ops.Allow() {
			c.close(CloseRateLimited, "rate limited")
			return
		}
		var f ClientFrame
		if err := json.Unmarshal(raw, &f); err != nil {
			continue
		}
		c.handle(ctx, f)
		select {
		case <-c.closed:
			return
		default:
		}
	}
}

// queuePresence applies a presence change, at most one every presenceEvery.
// One that arrives too soon is held rather than dropped — flipping status
// twice, or setting a status right after, would otherwise leave everyone else
// looking at the older one — and the latest wins when its turn comes.
func (c *conn) queuePresence(ctx context.Context, raw json.RawMessage) {
	c.presMu.Lock()
	c.presPending = raw
	if c.presTimer != nil {
		c.presMu.Unlock()
		return
	}
	r := c.presence.Reserve()
	if !r.OK() {
		c.presPending = nil
		c.presMu.Unlock()
		return
	}
	if delay := r.Delay(); delay > 0 {
		c.presTimer = time.AfterFunc(delay, func() {
			defer c.hub.recoverTimer("presence flush")
			c.flushPresence()
		})
		c.presMu.Unlock()
		return
	}
	c.presPending = nil
	c.presMu.Unlock()
	c.applyPresence(ctx, raw)
}

func (c *conn) flushPresence() {
	c.presMu.Lock()
	c.presTimer = nil
	raw := c.presPending
	c.presPending = nil
	c.presMu.Unlock()
	if raw == nil {
		return
	}
	select {
	case <-c.closed:
		return
	default:
	}
	ctx, cancel := background()
	defer cancel()
	c.applyPresence(ctx, raw)
}

func (c *conn) applyPresence(ctx context.Context, raw json.RawMessage) {
	h := c.hub
	var d presenceData
	if err := json.Unmarshal(raw, &d); err != nil {
		return
	}
	if d.StatusExpiresAt != nil && *d.StatusExpiresAt < 0 {
		return
	}
	var p store.ProfilePatch
	if d.Status != nil {
		switch *d.Status {
		case "online", "idle", "dnd", "invisible":
			p.PresencePref = d.Status
		}
	}
	// A muted account keeps its online, idle and do-not-disturb, and may
	// clear its status, but cannot put new words or an emoji in it.
	if !d.ClearStatus && (d.StatusText != nil || d.StatusEmoji != nil) && h.muted(ctx, c.userID) {
		d.StatusText, d.StatusEmoji, d.StatusExpiresAt = nil, nil, nil
	}
	if d.ClearStatus {
		empty := ""
		var nilExp *int64
		p.StatusText, p.StatusEmoji, p.StatusExpiresAt = &empty, &empty, &nilExp
	} else {
		// The same rules as PATCH /users/@me. Overlong text is cut by
		// characters rather than refused, as there is no reply to show.
		if d.StatusText != nil {
			t, ok := textutil.CleanText(strings.ReplaceAll(*d.StatusText, "\n", " "), maxStatusText)
			if !ok && t != "" {
				t, ok = strings.TrimSpace(string([]rune(t)[:maxStatusText])), true
			}
			if ok {
				p.StatusText = &t
			}
		}
		if d.StatusEmoji != nil {
			if e, ok := textutil.CleanText(*d.StatusEmoji, maxStatusEmoji); ok && !strings.ContainsAny(e, " \n") {
				p.StatusEmoji = &e
			}
		}
		// A new status always sets its expiry: JSON null decodes like a
		// missing field, and must not leave an earlier status's timer behind.
		if d.StatusExpiresAt != nil || d.StatusText != nil || d.StatusEmoji != nil {
			exp := d.StatusExpiresAt
			p.StatusExpiresAt = &exp
		}
	}
	if err := h.st.UpdateProfile(ctx, c.userID, p); err != nil {
		return
	}
	u, err := h.st.UserByID(ctx, c.userID)
	if err != nil {
		return
	}
	// every device's copy, not only this one's: another device going idle
	// or flushing activities builds its presence from its own copy
	h.refreshUser(u)
	h.broadcastPresence(ctx, u)
}

func (c *conn) handle(ctx context.Context, f ClientFrame) {
	h := c.hub
	if c.guest != nil {
		switch f.Op {
		case OpHeartbeat, OpVoiceState, OpVoiceSignal:
		default:
			return
		}
	}
	switch f.Op {
	case OpHeartbeat:
		c.beat.Store(time.Now().UnixMilli())
		c.enqueue(ServerFrame{T: EvHeartbeatAck})
	case OpPresence:
		c.beat.Store(time.Now().UnixMilli())
		c.queuePresence(ctx, f.D)
	case OpAway:
		c.beat.Store(time.Now().UnixMilli())
		var d awayData
		if c.guest != nil || json.Unmarshal(f.D, &d) != nil {
			return
		}
		before := h.allAway(c.userID)
		if c.away.Swap(d.Away) == d.Away || h.allAway(c.userID) == before {
			return
		}
		// only an account showing as online goes idle by itself
		if u := c.user.Load(); u != nil && (u.PresencePref == "" || u.PresencePref == "online") {
			h.broadcastUser(ctx, c)
		}
	case OpActivities:
		c.beat.Store(time.Now().UnixMilli())
		var d activitiesData
		if err := json.Unmarshal(f.D, &d); err != nil {
			return
		}
		if len(d.Activities) > maxActivities {
			d.Activities = d.Activities[:maxActivities]
		}
		// An activity is text of the account's own choosing — names, details,
		// buttons with links — shown to everyone who sees its presence, so a
		// muted account may only clear what it had.
		if len(d.Activities) > 0 && h.muted(ctx, c.userID) {
			d.Activities = nil
		}
		c.queueActivities(d.Activities)
	case OpTyping:
		var d typingData
		if err := json.Unmarshal(f.D, &d); err != nil || d.ChannelID.IsZero() {
			return
		}
		if d.Stop {
			c.typMu.Lock()
			_, seen := c.typing[d.ChannelID]
			delete(c.typing, d.ChannelID)
			c.typMu.Unlock()
			if seen {
				h.TypingStop(ctx, c.userID, d.ChannelID)
			}
			return
		}
		c.typMu.Lock()
		last, seen := c.typing[d.ChannelID]
		c.typMu.Unlock()
		if seen && time.Since(last) < typingCooldown {
			return
		}
		// Past the cooldown each indicator costs a permission check and an
		// audience; the account's budget bounds how often, whatever channels
		// it rotates through.
		if !h.typingAllowed(c.userID) {
			return
		}
		// a muted account cannot post, so it has nothing to be typing
		if h.muted(ctx, c.userID) {
			return
		}
		// Access is checked before the channel is remembered, so made-up ids
		// cannot grow the map.
		others, ok := h.typingAudience(ctx, c.userID, d.ChannelID)
		if !ok {
			return
		}
		now := time.Now()
		c.typMu.Lock()
		if len(c.typing) >= typingKeep {
			for id, at := range c.typing {
				if now.Sub(at) > typingTTL {
					delete(c.typing, id)
				}
			}
		}
		c.typing[d.ChannelID] = now
		c.typMu.Unlock()
		h.EmitUsers(others, EvTyping, map[string]any{"channel_id": d.ChannelID, "user_id": c.userID, "ts": now.UnixMilli()})
	case OpNoteCursor:
		c.noteCursor(ctx, f.D)
	case OpVoiceSignal:
		if !c.signalBytes.AllowN(time.Now(), len(f.D)) {
			return
		}
		if h.VoiceOp != nil {
			h.VoiceOp(ctx, &Conn{c: c}, f.Op, f.D)
		}
	case OpVoiceState, OpSubscribe:
		if h.VoiceOp != nil {
			h.VoiceOp(ctx, &Conn{c: c}, f.Op, f.D)
		}
	}
}

// noteAudience is who a note's cursors go to, as last looked up.
type noteAudience struct {
	others []ids.ID
	member bool
	at     time.Time
}

type noteCursorData struct {
	ID   ids.ID `json:"id"`
	Data string `json:"data"`
}

// noteCursor passes on where this account's caret is in a note to the
// others in it. The server can read none of it: it is sealed with the
// note's key, and only checked for being the shape a sealed text has.
func (c *conn) noteCursor(ctx context.Context, raw json.RawMessage) {
	var d noteCursorData
	if json.Unmarshal(raw, &d) != nil || d.ID.IsZero() || !sealedText(d.Data, noteCursorMax) {
		return
	}
	if !c.cursors.Allow() {
		return
	}
	others, ok := c.noteAudience(ctx, d.ID)
	if !ok || len(others) == 0 {
		return
	}
	c.hub.EmitUsers(others, EvNoteCursor, map[string]any{"id": d.ID, "user_id": c.userID, "data": d.Data})
}

// noteAudience is everyone else in a note, when this account is in it.
func (c *conn) noteAudience(ctx context.Context, id ids.ID) ([]ids.ID, bool) {
	now := time.Now()
	if a, ok := c.noteSeen[id]; ok && now.Sub(a.at) < noteAudienceTTL {
		return a.others, a.member
	}
	members, err := c.hub.st.NoteMemberIDs(ctx, id)
	if err != nil {
		return nil, false
	}
	a := noteAudience{at: now}
	for _, m := range members {
		if m == c.userID {
			a.member = true
		} else {
			a.others = append(a.others, m)
		}
	}
	// made-up note ids are remembered too, so they cost one lookup a while,
	// and the map is kept small
	if len(c.noteSeen) >= noteAudienceKeep {
		for k, v := range c.noteSeen {
			if now.Sub(v.at) >= noteAudienceTTL {
				delete(c.noteSeen, k)
			}
		}
		if len(c.noteSeen) >= noteAudienceKeep {
			clear(c.noteSeen)
		}
	}
	c.noteSeen[id] = a
	return a.others, a.member
}

// sealedText reports whether v looks like something a device sealed: not
// empty, within max bytes, and nothing in it but base64 and a version tag.
func sealedText(v string, max int) bool {
	if v == "" || len(v) > max {
		return false
	}
	for i := 0; i < len(v); i++ {
		ch := v[i]
		if !(ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9' || ch == '-' || ch == '_' || ch == '.') {
			return false
		}
	}
	return true
}

// muted reports whether the account is muted, when moderation is wired in.
func (h *Hub) muted(ctx context.Context, uid ids.ID) bool {
	if h.Restricted == nil {
		return false
	}
	m, _ := h.Restricted(ctx, uid)
	return m
}

// typingAllowed spends one of the account's typing budget, across every
// channel and device (see Hub.typingRate).
func (h *Hub) typingAllowed(uid ids.ID) bool {
	if h.typingRate == nil {
		return true
	}
	ok, _ := h.typingRate.Allow("typing:" + uid.String())
	return ok
}

// TypingStop clears a typing indicator immediately (the author sent or cleared the box).
func (h *Hub) TypingStop(ctx context.Context, uid, channelID ids.ID) {
	ch, err := h.st.ChannelForAccess(ctx, channelID)
	if err != nil {
		return
	}
	if ok, err := h.canAccess(ctx, uid, ch); err != nil || !ok {
		return
	}
	var viewers []ids.ID
	if store.IsDMLike(ch.Type) {
		viewers, err = h.st.DMShownTo(ctx, channelID)
	} else {
		viewers, err = h.Viewers(ctx, channelID)
	}
	if err != nil {
		return
	}
	others := make([]ids.ID, 0, len(viewers))
	for _, v := range viewers {
		if v != uid {
			others = append(others, v)
		}
	}
	h.EmitUsers(others, EvTyping, map[string]any{"channel_id": channelID, "user_id": uid, "ts": time.Now().UnixMilli(), "stop": true})
}

// Typing broadcasts a typing indicator after checking access.
func (h *Hub) Typing(ctx context.Context, uid, channelID ids.ID) {
	if !h.typingAllowed(uid) {
		return
	}
	others, ok := h.typingAudience(ctx, uid, channelID)
	if !ok {
		return
	}
	h.EmitUsers(others, EvTyping, map[string]any{"channel_id": channelID, "user_id": uid, "ts": time.Now().UnixMilli()})
}

// typingAudience reports whether uid may type in a channel, which in a server
// channel means being allowed to send there, and lists who else sees it.
func (h *Hub) typingAudience(ctx context.Context, uid, channelID ids.ID) ([]ids.ID, bool) {
	ch, err := h.st.ChannelForAccess(ctx, channelID)
	if err != nil {
		return nil, false
	}
	var viewers []ids.ID
	if store.IsDMLike(ch.Type) {
		if !slices.Contains(ch.Recipients, uid) {
			return nil, false
		}
		// a block closes a 1:1 conversation both ways, typing included
		if blocked, err := h.st.DMBlocked(ctx, ch, uid); err != nil || blocked {
			return nil, false
		}
		// Only those who have the conversation in their sidebar: someone who
		// closed it, or has not been shown it because nothing has been said in
		// it yet, is not told that someone is typing there.
		if viewers, err = h.st.DMShownTo(ctx, ch.ID); err != nil {
			return nil, false
		}
	} else {
		if h.SendAccess == nil {
			return nil, false
		}
		if ok, err := h.SendAccess(ctx, uid, ch); err != nil || !ok {
			return nil, false
		}
		if viewers, err = h.Viewers(ctx, channelID); err != nil {
			return nil, false
		}
	}
	others := make([]ids.ID, 0, len(viewers))
	for _, v := range viewers {
		if v != uid {
			others = append(others, v)
		}
	}
	return others, true
}

// buildGuestReady is everything a guest is told: who they are, and their one
// call (filled in by GuestReady). No conversations, people or servers.
func (h *Hub) buildGuestReady(ctx context.Context, g *store.Guest, session *store.Session) (*ReadyPayload, error) {
	p := &ReadyPayload{SessionID: session.ID, Users: []store.PublicUser{}, Channels: []*store.Channel{}, Relationships: []store.RelationshipView{}, ReadStates: []*store.ReadState{},
		Presences: []Presence{}, KeyGrants: []*store.KeyGrant{}, Servers: []any{}, Emojis: []*store.Emoji{}, StickerPacks: []*store.StickerPack{}, VoiceStates: []any{}, GroupAddNotices: []store.GroupAddNotice{}}
	p.User = map[string]any{"id": g.ID, "username": "", "display_name": g.DisplayName, "avatar_id": nil, "flags": store.FlagGuest, "created_at": g.CreatedAt}
	if h.GuestReady != nil {
		if err := h.GuestReady(ctx, g, p); err != nil {
			return nil, err
		}
	}
	return p, nil
}

// buildReady assembles the initial state for a user.
func (h *Hub) buildReady(ctx context.Context, u *store.User, session *store.Session) (*ReadyPayload, error) {
	p := &ReadyPayload{SessionID: session.ID, Servers: []any{}, VoiceStates: []any{}, Presences: []Presence{}, Users: []store.PublicUser{}}
	pub := u.Public()
	p.User = map[string]any{"id": u.ID, "username": u.Username, "display_name": u.DisplayName, "avatar_id": u.AvatarID, "banner_id": u.BannerID,
		"about_me": u.AboutMe, "pronouns": u.Pronouns, "accent_color": u.AccentColor, "status_text": u.StatusText, "status_emoji": u.StatusEmoji,
		"status_expires_at": u.StatusExpiresAt, "public_key": u.PublicKey, "created_at": u.CreatedAt, "flags": u.Flags, "presence_pref": u.PresencePref,
		"dm_privacy": u.DMPrivacy, "has_recovery": u.RecoveryAuthHash != "", "accent_color_2": u.AccentColor2, "group_link_only": u.GroupLinkOnly,
		"typing_style": pub.TypingStyle, "name_style": pub.NameStyle, "server_tag": pub.ServerTag}
	var err error
	if p.Channels, err = h.st.DMChannelsForUser(ctx, u.ID); err != nil {
		return nil, err
	}
	if p.GroupAddNotices, err = h.st.GroupAddNotices(ctx, u.ID); err != nil {
		return nil, err
	}
	rels, err := h.st.Relationships(ctx, u.ID)
	if err != nil {
		return nil, err
	}
	chanIDs := make([]ids.ID, 0, len(p.Channels))
	userSet := map[ids.ID]bool{}
	for _, ch := range p.Channels {
		chanIDs = append(chanIDs, ch.ID)
		for _, r := range ch.Recipients {
			userSet[r] = true
		}
	}
	for _, r := range rels {
		userSet[r.TargetID] = true
	}
	if h.ExtraChannels != nil {
		more, err := h.ExtraChannels(ctx, u.ID)
		if err != nil {
			return nil, err
		}
		chanIDs = append(chanIDs, more...)
	}
	if p.ReadStates, err = h.st.ReadStatesFor(ctx, u.ID, chanIDs); err != nil {
		return nil, err
	}
	if p.KeyGrants, err = h.st.GrantsForUser(ctx, u.ID); err != nil {
		return nil, err
	}
	if p.Settings, err = h.st.GetSettings(ctx, u.ID); err != nil {
		return nil, err
	}
	delete(userSet, u.ID)
	userIDs := make([]ids.ID, 0, len(userSet))
	for id := range userSet {
		userIDs = append(userIDs, id)
	}
	users, err := h.st.UsersByIDs(ctx, userIDs)
	if err != nil {
		return nil, err
	}
	// Whose presence this account may see: the same rule as the other way
	// round (Hub.presenceAudience), friends and co-members, blocks excepted.
	sees, err := h.presenceVisibleTo(ctx, u.ID, rels)
	if err != nil {
		return nil, err
	}
	online := map[ids.ID]bool{}
	for _, id := range h.OnlineAmong(userIDs) {
		online[id] = true
	}
	for _, id := range userIDs {
		if usr := users[id]; usr != nil {
			p.Users = append(p.Users, usr.Public())
			// only people who show as here: an "offline" entry for someone
			// connected would mark them out as invisible
			if online[id] && sees(id) {
				if pr := h.PresenceOf(ctx, usr); pr.Status != "offline" {
					p.Presences = append(p.Presences, pr)
				}
			}
		}
	}
	p.Relationships = make([]store.RelationshipView, 0, len(rels))
	for _, r := range rels {
		if usr := users[r.TargetID]; usr != nil {
			p.Relationships = append(p.Relationships, r.View(usr.Public()))
		}
	}
	p.Presences = append(p.Presences, h.selfPresence(ctx, u))
	if h.ReadyExtras != nil {
		if err := h.ReadyExtras(ctx, u, p); err != nil {
			return nil, err
		}
	}
	// the extras add co-members' presences; someone blocked either way is
	// left out of those too
	kept := p.Presences[:0]
	for _, pr := range p.Presences {
		if pr.UserID == u.ID || sees(pr.UserID) {
			kept = append(kept, pr)
		}
	}
	p.Presences = kept
	return p, nil
}

// presenceVisibleTo reports, for the account uid, whose presence it may see:
// its friends' and its co-members', unless either has blocked the other. rels
// are uid's relationships, already read.
func (h *Hub) presenceVisibleTo(ctx context.Context, uid ids.ID, rels []*store.Relationship) (func(ids.ID) bool, error) {
	blocked, err := h.st.BlockedEitherWay(ctx, uid)
	if err != nil {
		return nil, err
	}
	allowed := map[ids.ID]bool{}
	for _, r := range rels {
		if r.Type == "friend" {
			allowed[r.TargetID] = true
		}
	}
	if h.Interested != nil {
		co, err := h.Interested(ctx, uid)
		if err != nil {
			return nil, err
		}
		for _, id := range co {
			allowed[id] = true
		}
	}
	return func(id ids.ID) bool { return allowed[id] && !blocked[id] }, nil
}

var errClosed = errors.New("connection closed")
