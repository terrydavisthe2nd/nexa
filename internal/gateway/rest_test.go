package gateway

import (
	"testing"
	"time"

	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/store"
)

// A phone out of sight must still hear at once what it would be woken for, in
// order, and lose nothing else: what waited arrives, and presence catches up.
func TestRestingConnectionLosesNothing(t *testing.T) {
	e := newEnv(t)
	u, tok := e.user(t, "phone")
	other, _ := e.user(t, "friend")
	c := e.dial(t)
	if c.identify(tok) == nil {
		t.Fatal("no ready")
	}
	c.send(OpRest, restData{Level: RestDeep})
	c.roundTrip()
	emit := func(ev string, d any) { e.hub.EmitUsers([]ids.ID{u.ID}, ev, d) }
	msg := func(id int64, mentions ...ids.ID) *store.Message { return &store.Message{ID: ids.ID(id), Mentions: mentions} }
	idOf := func(f map[string]any) any {
		if f == nil {
			return nil
		}
		return f["d"].(map[string]any)["id"]
	}

	// the first of a quiet spell goes at once
	emit(EvMessageCreate, msg(1))
	if idOf(c.next(EvMessageCreate, 2*time.Second)) != ids.ID(1).String() {
		t.Fatal("the first message of a quiet spell was held")
	}
	// the next waits, and a mention brings it along, in order
	emit(EvMessageCreate, msg(2))
	if f := c.next(EvMessageCreate, 300*time.Millisecond); f != nil {
		t.Fatal("the second message in a window was not held")
	}
	emit(EvMessageCreate, msg(3, u.ID))
	if idOf(c.next(EvMessageCreate, 2*time.Second)) != ids.ID(2).String() || idOf(c.next(EvMessageCreate, 2*time.Second)) != ids.ID(3).String() {
		t.Fatal("a mention did not bring what waited, in order")
	}
	// a call rings through whatever is waiting
	emit(EvMessageUpdate, msg(4))
	emit(EvCallRing, map[string]any{"x": 1})
	if c.next(EvCallRing, 2*time.Second) == nil {
		t.Fatal("a ring did not go at once")
	}

	// typing and presence are left out, and presence catches up on coming back
	emit(EvTyping, map[string]any{"user_id": other.ID})
	emit(EvPresence, Presence{UserID: other.ID, Status: "idle"})
	emit(EvMessageDelete, map[string]any{"id": "5"})
	emit(EvMessageDelete, map[string]any{"id": "6"})
	c.send(OpRest, restData{Level: RestSlowPing})
	if f := c.next(EvMessageDelete, 2*time.Second); f == nil {
		t.Fatal("what waited did not arrive on coming back")
	}
	sync := c.next(EvPresenceSync, 2*time.Second)
	if sync == nil {
		t.Fatal("no presence sync on coming back")
	}
	list := sync["d"].(map[string]any)["presences"].([]any)
	if len(list) == 0 || list[len(list)-1].(map[string]any)["user_id"] != u.ID.String() {
		t.Fatalf("the sync should end with this account's own presence: %+v", list)
	}
	// awake again: everything flows, typing included
	emit(EvTyping, map[string]any{"user_id": other.ID})
	if c.next(EvTyping, 2*time.Second) == nil {
		t.Fatal("typing did not come back after the rest")
	}
}
