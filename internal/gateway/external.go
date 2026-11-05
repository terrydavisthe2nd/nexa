package gateway

import (
	"context"
	"encoding/json"
	"sync"
	"time"

	"github.com/coder/websocket"
	"golang.org/x/time/rate"

	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/store"
)

// An External is a connection that is not one of the Nexa apps' sockets: a
// bot speaking the bot protocol on a socket of its own (internal/api's bot
// gateway). The hub treats it as any other connection of its account, so
// every event its account may see reaches it and its presence shows, but the
// frames go to a callback instead of being written out, for the bot gateway
// to turn into its own events.
type External struct {
	c *conn
	// onClose is told when the hub ends the connection (a revoked token, a
	// ban), with the close code the hub would have used
	onClose func(code int, reason string)
	once    sync.Once
}

// AttachExternal registers an external connection for u. deliver is called
// with every frame for it and must not block; onClose is called once if the
// hub ends it. sessionID names it in place of a Nexa session.
func (h *Hub) AttachExternal(ctx context.Context, u *store.User, sessionID ids.ID, ip string, deliver func(ServerFrame), onClose func(code int, reason string)) (*External, error) {
	x := &External{onClose: onClose}
	c := &conn{id: ids.New(), hub: h, userID: u.ID, ip: ip, session: &store.Session{ID: sessionID, UserID: u.ID},
		send: make(chan ServerFrame, 1), closed: make(chan struct{}), typing: map[ids.ID]time.Time{},
		ops: rate.NewLimiter(opRate, opBurst), presence: rate.NewLimiter(rate.Every(presenceEvery), presenceBurst),
		signalBytes: rate.NewLimiter(signalBytesRate, signalBytesBurst), cursors: rate.NewLimiter(noteCursorRate, noteCursorBurst),
		noteSeen: map[ids.ID]noteAudience{}, actLimit: rate.NewLimiter(rate.Every(activityEvery), activityBurst), gameLimit: newGameLimit(),
		deliver: deliver, external: x}
	c.user.Store(u)
	c.beat.Store(time.Now().UnixMilli())
	x.c = c
	first, err := h.register(c)
	if err != nil {
		return nil, err
	}
	c.live.Store(true)
	if first {
		h.broadcastArrival(ctx, u)
	}
	return x, nil
}

// ID is the connection's own id.
func (x *External) ID() ids.ID { return x.c.id }

// Beat keeps the connection from being swept as dead; call it on every
// heartbeat the bot sends.
func (x *External) Beat() { x.c.beat.Store(time.Now().UnixMilli()) }

// SetActivities replaces what the account is shown doing, cleaned and
// resolved as the desktop app's activities are.
func (x *External) SetActivities(raw []json.RawMessage) {
	if len(raw) > maxActivities {
		raw = raw[:maxActivities]
	}
	x.c.queueActivities(raw)
}

// Refresh gives the connection the account as it is now (after its status
// or profile changed) and shows everyone who sees it its presence again.
func (x *External) Refresh(ctx context.Context, u *store.User) {
	h := x.c.hub
	h.refreshUser(u)
	h.broadcastPresence(ctx, u)
}

// Detach takes the connection out of the hub, as a closed socket is; the
// account shows as offline once its last connection goes.
func (x *External) Detach() {
	x.once.Do(func() {
		c := x.c
		c.once.Do(func() { close(c.closed) })
		h := c.hub
		last := h.unregister(c)
		bg, cancel := background()
		defer cancel()
		if h.OnConnClose != nil {
			h.OnConnClose(bg, c.userID, c.id)
		}
		if c.dropActivities() && !last {
			h.broadcastUser(bg, c)
		}
		if last {
			if u := c.currentUser(); u != nil {
				h.broadcastArrival(bg, u)
			}
			if h.OnDisconnect != nil {
				h.OnDisconnect(bg, c.userID)
			}
		}
	})
}

// closeExternal is conn.close for an external connection: there is no socket
// to close, so its owner is told instead, and it leaves the hub.
func (c *conn) closeExternal(code websocket.StatusCode, reason string) {
	x := c.external
	if x.onClose != nil {
		x.onClose(int(code), reason)
	}
	go x.Detach()
}
