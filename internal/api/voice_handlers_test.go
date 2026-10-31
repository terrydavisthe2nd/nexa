package api

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/perms"
	"evilion.xyz/chat/internal/store"
)

// op sends a gateway op frame from a test client.
func (g *gw) op(t *testing.T, op string, d any) {
	t.Helper()
	b, _ := json.Marshal(map[string]any{"op": op, "d": d})
	if err := g.ws.Write(context.Background(), 1, b); err != nil {
		t.Fatal(err)
	}
}

func TestVoiceChannels(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	tokC, idC := register(t, ts, "carol")
	view := createServer(t, ts, tokA, "Voice")
	sid := view["id"].(string)
	general := channelNamed(view, "general")["id"].(string)
	voiceCh := channelNamed(view, "General")["id"].(string)
	code := inviteFor(t, ts, tokA, general)
	join(t, ts, tokB, code)
	join(t, ts, tokC, code)
	ga := connectGW(t, ts, tokA)
	gb := connectGW(t, ts, tokB)
	gc := connectGW(t, ts, tokC)

	// ICE config is available to members
	res, body := do(t, ts, "GET", "/api/v1/voice/ice", tokA, nil)
	if res.StatusCode != 200 || len(body["ice_servers"].([]any)) < 1 {
		t.Fatalf("ice: %d %v", res.StatusCode, body)
	}
	// text channels are not joinable
	ga.op(t, "voice_state", map[string]any{"channel_id": general})
	if ev := ga.next("voice_error", 3*time.Second); ev == nil || ev["code"] != "not_voice" {
		t.Errorf("join text channel: %v", ev)
	}
	// alice joins; everyone who sees the channel hears it
	ga.op(t, "voice_state", map[string]any{"channel_id": voiceCh, "self_mute": true})
	for _, g := range []*gw{ga, gb, gc} {
		ev := g.next("voice_state", 3*time.Second)
		if ev == nil || ev["user_id"] != idA || ev["channel_id"] != voiceCh || ev["self_mute"] != true {
			t.Fatalf("join event: %v", ev)
		}
	}
	// bob joins and signals alice; carol cannot signal without being in voice
	gb.op(t, "voice_state", map[string]any{"channel_id": voiceCh})
	if ev := ga.next("voice_state", 3*time.Second); ev == nil || ev["user_id"] != idB {
		t.Fatal("bob join event")
	}
	gb.next("voice_state", 3*time.Second)
	gc.next("voice_state", 3*time.Second)
	gb.op(t, "voice_signal", map[string]any{"to": idA, "payload": map[string]any{"type": "offer", "sdp": "v=0"}})
	ev := ga.next("voice_signal", 3*time.Second)
	if ev == nil || ev["from"] != idB || ev["payload"].(map[string]any)["sdp"] != "v=0" {
		t.Fatalf("signal relay: %v", ev)
	}
	// the numbers a loss report carries arrive held to their range
	gb.op(t, "voice_signal", map[string]any{"to": idA, "payload": map[string]any{"voice_loss": 1e300, "voice_delay": -5, "voice_span": "x"}})
	ev = ga.next("voice_signal", 3*time.Second)
	if p, _ := ev["payload"].(map[string]any); p == nil || p["voice_loss"] != float64(1) || p["voice_delay"] != float64(0) || p["voice_span"] != nil {
		t.Fatalf("loss report relay: %v", ev)
	}
	gc.op(t, "voice_signal", map[string]any{"to": idA, "payload": map[string]any{}})
	if ev := gc.next("voice_error", 3*time.Second); ev == nil || ev["code"] != "not_in_voice" {
		t.Errorf("signal outside voice: %v", ev)
	}
	// participants endpoint and a fresh ready payload carry both states
	res, body = do(t, ts, "GET", "/api/v1/channels/"+voiceCh+"/voice", tokC, nil)
	if res.StatusCode != 200 || len(body["_list"].([]any)) != 2 {
		t.Errorf("participants: %d %v", res.StatusCode, body)
	}
	gc2 := connectGWReady(t, ts, tokC)
	if n := len(gc2.ready["voice_states"].([]any)); n != 2 {
		t.Errorf("ready voice states: %d", n)
	}
	// user limit: 2 → carol is refused, but the owner (Move Members) is not
	if res, _ = do(t, ts, "PATCH", "/api/v1/channels/"+voiceCh, tokA, map[string]any{"user_limit": 2}); res.StatusCode != 200 {
		t.Fatal("set limit")
	}
	gc.op(t, "voice_state", map[string]any{"channel_id": voiceCh})
	if ev := gc.next("voice_error", 3*time.Second); ev == nil || ev["code"] != "channel_full" {
		t.Errorf("full channel: %v", ev)
	}
	// a refused move changes nothing, not even the mute sent with it
	if res, body = do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/members/"+idB+"/voice", tokA, map[string]any{"mute": true, "channel_id": general}); res.StatusCode != 400 {
		t.Errorf("move to a text channel: %d %v", res.StatusCode, body)
	}
	if ev := gb.next("voice_state", 500*time.Millisecond); ev != nil {
		t.Errorf("a refused request must not mute: %v", ev)
	}
	// moderation: alice server-mutes bob, then moves him to a new channel
	if res, body = do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/members/"+idB+"/voice", tokC, map[string]any{"mute": true}); res.StatusCode != 403 {
		t.Errorf("member mutes: %d %v", res.StatusCode, body)
	}
	res, body = do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/members/"+idB+"/voice", tokA, map[string]any{"mute": true})
	if res.StatusCode != 200 || body["server_mute"] != true {
		t.Fatalf("server mute: %d %v", res.StatusCode, body)
	}
	if ev := gb.next("voice_state", 3*time.Second); ev == nil || ev["server_mute"] != true {
		t.Errorf("mute event: %v", ev)
	}
	res, body = do(t, ts, "POST", "/api/v1/servers/"+sid+"/channels", tokA, map[string]any{"type": "voice", "name": "Lounge"})
	lounge := body["id"].(string)
	for _, g := range []*gw{ga, gb, gc} {
		g.next("channel_create", 3*time.Second)
	}
	res, body = do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/members/"+idB+"/voice", tokA, map[string]any{"channel_id": lounge})
	if res.StatusCode != 200 || body["channel_id"] != lounge || body["server_mute"] != true {
		t.Fatalf("move: %d %v", res.StatusCode, body)
	}
	// the move shows up as a leave from General and a join to Lounge
	saw := map[string]bool{}
	for i := 0; i < 2; i++ {
		ev := gc.next("voice_state", 3*time.Second)
		if ev == nil {
			t.Fatal("move events")
		}
		if ev["channel_id"] == nil {
			saw["leave"] = ev["old_channel_id"] == voiceCh
		} else {
			saw["join"] = ev["channel_id"] == lounge
		}
	}
	if !saw["leave"] || !saw["join"] {
		t.Errorf("move events: %v", saw)
	}
	// bob himself only hears the join, which his client treats as a move;
	// a leave of his own channel would make it hang up
	if ev := gb.next("voice_state", 3*time.Second); ev == nil || ev["channel_id"] != lounge {
		t.Errorf("moved member's first event should be the join: %v", ev)
	}
	for i := 0; i < 2; i++ {
		ga.next("voice_state", 3*time.Second) // alice's copies of the move
	}
	// disconnecting bob's gateway clears his state
	gb.close()
	if ev := ga.next("voice_state", 3*time.Second); ev == nil || ev["user_id"] != idB || ev["channel_id"] != nil {
		t.Errorf("leave on disconnect: %v", ev)
	}
	// deleting the channel alice is in kicks her out
	if res, _ = do(t, ts, "DELETE", "/api/v1/channels/"+voiceCh, tokA, nil); res.StatusCode != 204 {
		t.Fatal("delete voice channel")
	}
	if ev := ga.next("voice_state", 3*time.Second); ev == nil || ev["user_id"] != idA || ev["channel_id"] != nil {
		t.Errorf("leave on delete: %v", ev)
	}
	_ = idC
	_ = perms.Connect
}

func TestDMCalls(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	friend(t, ts, tokA, "bob", tokB, idA)
	_, body := do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{"recipient_ids": []string{idB}})
	dm := body["id"].(string)
	ga := connectGW(t, ts, tokA)
	gb := connectGW(t, ts, tokB)
	// alice starts a call by joining the DM's voice: bob rings, a system message lands
	ga.op(t, "voice_state", map[string]any{"channel_id": dm})
	if ev := gb.next("call_ring", 3*time.Second); ev == nil || ev["from"] != idA || ev["channel_id"] != dm {
		t.Fatalf("ring: %v", ev)
	}
	if ev := gb.next("message_create", 3*time.Second); ev == nil || ev["type"] != "system_call" {
		t.Errorf("call system message: %v", ev)
	}
	// bob declines: alice hears it
	if res, _ := do(t, ts, "POST", "/api/v1/channels/"+dm+"/call/decline", tokB, nil); res.StatusCode != 204 {
		t.Fatal("decline")
	}
	if ev := ga.next("call_end", 3*time.Second); ev == nil || ev["reason"] != "declined" || ev["user_id"] != idB {
		t.Errorf("decline event: %v", ev)
	}
	gb.next("call_end", 3*time.Second) // bob hears his own decline too
	// bob joins after all; then everyone leaves and the call ends
	gb.op(t, "voice_state", map[string]any{"channel_id": dm})
	if ev := ga.next("voice_state", 3*time.Second); ev == nil || ev["user_id"] != idB {
		t.Fatal("bob joins call")
	}
	// answering tells bob's other devices to stop ringing
	if ev := gb.next("call_end", 3*time.Second); ev == nil || ev["reason"] != "answered" || ev["user_id"] != idB {
		t.Errorf("answered event: %v", ev)
	}
	ga.op(t, "voice_state", map[string]any{"channel_id": nil})
	gb.op(t, "voice_state", map[string]any{"channel_id": nil})
	if ev := gb.next("call_end", 5*time.Second); ev == nil || ev["reason"] != "ended" {
		t.Errorf("call end: %v", ev)
	}
	// an outsider cannot join a DM call
	tokC, _ := register(t, ts, "carol")
	gc := connectGW(t, ts, tokC)
	gc.op(t, "voice_state", map[string]any{"channel_id": dm})
	if ev := gc.next("voice_error", 3*time.Second); ev == nil || ev["code"] != "not_found" {
		t.Errorf("outsider join: %v", ev)
	}
}

// TestCloudflareTURNCredentials checks that a relay credential is minted per
// person, handed out with the STUN list, and reused rather than re-fetched.
func TestCloudflareTURNCredentials(t *testing.T) {
	calls := 0
	var lastAuth, lastBody string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		lastAuth = r.Header.Get("Authorization")
		b, _ := io.ReadAll(r.Body)
		lastBody = string(b)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"iceServers":{"urls":["turn:turn.cloudflare.com:3478?transport=udp"],"username":"u1","credential":"c1"}}`))
	}))
	defer upstream.Close()
	prev := turnClient.Transport
	turnClient.Transport = &redirectTransport{to: upstream.Listener.Addr().String()}
	defer func() { turnClient.Transport = prev }()

	srv, ts := newTestServer(t)
	srv.Cfg.TurnKeyID = "key-123"
	srv.Cfg.TurnAPIToken = "token-abc"
	token, id := register(t, ts, "alice")

	res, body := do(t, ts, "GET", "/api/v1/voice/ice", token, nil)
	if res.StatusCode != 200 {
		t.Fatalf("ice: %d %v", res.StatusCode, body)
	}
	servers, _ := body["ice_servers"].([]any)
	found := false
	for _, entry := range servers {
		m, _ := entry.(map[string]any)
		if m["username"] == "u1" && m["credential"] == "c1" {
			found = true
		}
	}
	if !found {
		t.Fatalf("the relay credential should be handed to the client: %v", body)
	}
	if lastAuth != "Bearer token-abc" {
		t.Errorf("the long-term key goes in the header: %q", lastAuth)
	}
	if !strings.Contains(lastBody, id) {
		t.Errorf("usage should be attributed to the person: %q", lastBody)
	}
	if !strings.Contains(lastBody, `"ttl"`) {
		t.Errorf("the credential should expire: %q", lastBody)
	}

	// asking again reuses it rather than minting another
	do(t, ts, "GET", "/api/v1/voice/ice", token, nil)
	if calls != 1 {
		t.Errorf("credentials should be reused, minted %d times", calls)
	}
}

func TestGuestTURNRequestsShareABoundedCredential(t *testing.T) {
	var calls atomic.Int32
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"iceServers":{"urls":["turn:relay.example:3478"],"username":"guest","credential":"test"}}`))
	}))
	defer upstream.Close()
	previous := turnClient.Transport
	turnClient.Transport = &redirectTransport{to: upstream.Listener.Addr().String()}
	defer func() { turnClient.Transport = previous }()
	srv, ts := newTestServer(t)
	srv.Cfg.GuestLinksP2P = true
	srv.Cfg.TurnKeyID, srv.Cfg.TurnAPIToken = "test-key", "test-token"
	owner, _ := register(t, ts, "owner")
	view := createServer(t, ts, owner, "Guest relay")
	channel := channelNamed(view, "General")["id"].(string)
	epoch := createEpoch(t, ts, owner, view["id"].(string))
	guest, id := guestJoin(t, ts, guestLink(t, ts, owner, channel, epoch), "Guest")
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Go(func() {
			res, _ := do(t, ts, "GET", "/api/v1/voice/ice", guest, nil)
			if res.StatusCode != 200 {
				t.Errorf("guest ICE: %d", res.StatusCode)
			}
		})
	}
	wg.Wait()
	do(t, ts, "GET", "/api/v1/voice/ice", guest, nil)
	if calls.Load() != 1 {
		t.Fatalf("guest minted %d credentials", calls.Load())
	}
	uid, _ := ids.Parse(id)
	srv.turnMu.Lock()
	hit := srv.turnCreds[uid]
	hit.expires = time.Now().Add(-time.Second)
	srv.turnCreds[uid] = hit
	srv.turnMu.Unlock()
	do(t, ts, "GET", "/api/v1/voice/ice", guest, nil)
	if calls.Load() != 2 {
		t.Fatalf("expired guest credential was reused: %d mints", calls.Load())
	}
}

func TestVoiceICEWithoutTURN(t *testing.T) {
	srv, ts := newTestServer(t)
	srv.Cfg.TurnKeyID = ""
	srv.Cfg.TurnAPIToken = ""
	token, _ := register(t, ts, "alice")
	res, body := do(t, ts, "GET", "/api/v1/voice/ice", token, nil)
	servers, _ := body["ice_servers"].([]any)
	if res.StatusCode != 200 {
		t.Fatalf("ice: %d %v", res.StatusCode, body)
	}
	for _, entry := range servers {
		m, _ := entry.(map[string]any)
		if m["credential"] != nil {
			t.Errorf("nothing should be minted without a key: %v", body)
		}
	}
}

// nextVoice waits for a voice_state about one user in a channel, or for their
// leave when channel is empty, skipping any other voice events on the way.
func (g *gw) nextVoice(uid, channel string, timeout time.Duration) map[string]any {
	deadline := time.Now().Add(timeout)
	for {
		left := time.Until(deadline)
		if left <= 0 {
			return nil
		}
		ev := g.next("voice_state", left)
		if ev == nil {
			return nil
		}
		if ev["user_id"] != uid {
			continue
		}
		if (channel == "" && ev["channel_id"] == nil) || (channel != "" && ev["channel_id"] == channel) {
			return ev
		}
	}
}

// TestVoiceBelongsToOneConnection checks that other sockets of the same
// session (a second tab, a socket reaped after a reconnect) cannot end the
// call, and that picking the call up elsewhere tells the old socket.
func TestVoiceBelongsToOneConnection(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	view := createServer(t, ts, tokA, "Voice")
	general := channelNamed(view, "general")["id"].(string)
	voiceCh := channelNamed(view, "General")["id"].(string)
	join(t, ts, tokB, inviteFor(t, ts, tokA, general))
	ga1 := connectGW(t, ts, tokA)
	ga2 := connectGW(t, ts, tokA)
	gb := connectGW(t, ts, tokB)

	ga1.op(t, "voice_state", map[string]any{"channel_id": voiceCh})
	if ev := gb.nextVoice(idA, voiceCh, 3*time.Second); ev == nil {
		t.Fatal("alice joins")
	}
	ga1.nextVoice(idA, voiceCh, 3*time.Second)
	// closing the other tab leaves the call alone
	ga2.close()
	if ev := gb.nextVoice(idA, "", time.Second); ev != nil {
		t.Errorf("closing another socket must not end the call: %v", ev)
	}
	// a reconnect picks the call up on a new socket; the old one is told
	ga3 := connectGW(t, ts, tokA)
	ga3.op(t, "voice_state", map[string]any{"channel_id": voiceCh, "resume": true})
	if ev := ga1.next("voice_state", 3*time.Second); ev == nil || ev["channel_id"] != nil || ev["old_channel_id"] != voiceCh || ev["reason"] != "moved" {
		t.Errorf("the socket that lost the call hears it left, and why: %v", ev)
	}
	if ev := gb.next("voice_state", 3*time.Second); ev == nil || ev["channel_id"] != voiceCh {
		t.Errorf("others only see alice still in the call: %v", ev)
	}
	// signals reach only the socket in the call
	gb.op(t, "voice_state", map[string]any{"channel_id": voiceCh})
	ga3.nextVoice(idB, voiceCh, 3*time.Second)
	gb.op(t, "voice_signal", map[string]any{"to": idA, "payload": map[string]any{"type": "offer"}})
	if ev := ga3.next("voice_signal", 3*time.Second); ev == nil || ev["from"] != idB {
		t.Errorf("signal to the socket in the call: %v", ev)
	}
	if ev := ga1.next("voice_signal", 500*time.Millisecond); ev != nil {
		t.Errorf("signal must not reach the old socket: %v", ev)
	}
	// the old socket can neither hang up nor, by closing, end the call
	ga1.op(t, "voice_state", map[string]any{"channel_id": nil})
	ga1.close()
	if ev := gb.nextVoice(idA, "", time.Second); ev != nil {
		t.Errorf("the old socket must not end the call: %v", ev)
	}
	ga3.op(t, "voice_state", map[string]any{"channel_id": nil})
	if ev := gb.nextVoice(idA, "", 3*time.Second); ev == nil {
		t.Error("the socket in the call hangs up")
	}
}

// TestDMCallResumeAndBlock checks that rejoining a call that just ended posts
// no second call message, and that a block stops calls both ways.
func TestDMCallResumeAndBlock(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	friend(t, ts, tokA, "bob", tokB, idA)
	_, body := do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{"recipient_ids": []string{idB}})
	dm := body["id"].(string)
	ga := connectGW(t, ts, tokA)
	gb := connectGW(t, ts, tokB)

	ga.op(t, "voice_state", map[string]any{"channel_id": dm})
	if ev := gb.next("message_create", 3*time.Second); ev == nil || ev["type"] != "system_call" {
		t.Fatalf("call message: %v", ev)
	}
	ga.op(t, "voice_state", map[string]any{"channel_id": nil})
	gb.next("call_end", 3*time.Second)
	// the socket blips and the client rejoins: bob still rings, no new message
	ga.op(t, "voice_state", map[string]any{"channel_id": dm, "resume": true})
	if ev := gb.next("call_ring", 3*time.Second); ev == nil {
		t.Error("a resumed call still rings")
	}
	if ev := gb.next("message_create", 500*time.Millisecond); ev != nil {
		t.Errorf("a resumed call posts no second message: %v", ev)
	}
	ga.op(t, "voice_state", map[string]any{"channel_id": nil})
	gb.next("call_end", 3*time.Second)
	// a device that still holds its SFU session is picking up a call the
	// server forgot (a restart): nobody is rung again, and no line is posted
	ga.op(t, "voice_state", map[string]any{"channel_id": dm, "resume": true, "sfu_session": "held"})
	if ev := gb.next("call_ring", 500*time.Millisecond); ev != nil {
		t.Errorf("a call picked up after a restart rang again: %v", ev)
	}
	if ev := gb.next("message_create", 300*time.Millisecond); ev != nil {
		t.Errorf("a call picked up after a restart posted a line: %v", ev)
	}
	ga.op(t, "voice_state", map[string]any{"channel_id": nil})
	gb.next("call_end", 3*time.Second)
	// calling again once the call is over is a new call, with a line of its own
	ga.op(t, "voice_state", map[string]any{"channel_id": dm})
	if ev := gb.next("call_ring", 3*time.Second); ev == nil {
		t.Error("calling again rings")
	}
	if ev := gb.next("message_create", 3*time.Second); ev == nil || ev["type"] != "system_call" {
		t.Errorf("calling again posts its own line: %v", ev)
	}
	ga.op(t, "voice_state", map[string]any{"channel_id": nil})
	gb.next("call_end", 3*time.Second)

	if res, body := do(t, ts, "PUT", "/api/v1/users/@me/blocks/"+idB, tokA, nil); res.StatusCode >= 300 {
		t.Fatalf("block: %d %v", res.StatusCode, body)
	}
	gb.op(t, "voice_state", map[string]any{"channel_id": dm})
	if ev := gb.next("voice_error", 3*time.Second); ev == nil || ev["code"] != "blocked" {
		t.Errorf("blocked caller: %v", ev)
	}
	if ev := ga.next("call_ring", 500*time.Millisecond); ev != nil {
		t.Errorf("the blocker must not ring: %v", ev)
	}
	ga.op(t, "voice_state", map[string]any{"channel_id": dm})
	if ev := ga.next("voice_error", 3*time.Second); ev == nil || ev["code"] != "blocked" {
		t.Errorf("the blocker cannot call either: %v", ev)
	}
}

// TestVoiceModerationScope checks that server mutes stay in their server, that
// a channel denying Speak silences people, and that a kick ends the call.
func TestVoiceModerationScope(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	view := createServer(t, ts, tokA, "Voice")
	sid := view["id"].(string)
	general := channelNamed(view, "general")["id"].(string)
	voiceCh := channelNamed(view, "General")["id"].(string)
	join(t, ts, tokB, inviteFor(t, ts, tokA, general))
	own := createServer(t, ts, tokB, "Bob's")
	ownVoice := channelNamed(own, "General")["id"].(string)
	ga := connectGW(t, ts, tokA)
	gb := connectGW(t, ts, tokB)

	res, body := do(t, ts, "POST", "/api/v1/servers/"+sid+"/channels", tokA, map[string]any{"type": "voice", "name": "Stage"})
	if res.StatusCode >= 300 {
		t.Fatalf("stage: %d %v", res.StatusCode, body)
	}
	stage := body["id"].(string)
	if res, body = do(t, ts, "PUT", "/api/v1/channels/"+stage+"/permissions/"+sid, tokA, map[string]any{"type": "role", "deny": permStr(perms.Speak)}); res.StatusCode != 200 {
		t.Fatalf("deny speak: %d %v", res.StatusCode, body)
	}
	gb.op(t, "voice_state", map[string]any{"channel_id": stage})
	if ev := gb.nextVoice(idB, stage, 3*time.Second); ev == nil || ev["server_mute"] != true {
		t.Errorf("no Speak silences: %v", ev)
	}
	gb.op(t, "voice_state", map[string]any{"channel_id": voiceCh})
	if ev := gb.nextVoice(idB, voiceCh, 3*time.Second); ev == nil || ev["server_mute"] != false {
		t.Errorf("Speak allowed again elsewhere: %v", ev)
	}
	if res, body = do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/members/"+idB+"/voice", tokA, map[string]any{"mute": true}); res.StatusCode != 200 {
		t.Fatalf("mute: %d %v", res.StatusCode, body)
	}
	if ev := gb.nextVoice(idB, voiceCh, 3*time.Second); ev == nil || ev["server_mute"] != true {
		t.Fatalf("mute event: %v", ev)
	}
	// the mute does not follow bob into his own server
	gb.op(t, "voice_state", map[string]any{"channel_id": ownVoice})
	if ev := gb.nextVoice(idB, ownVoice, 3*time.Second); ev == nil || ev["server_mute"] != false {
		t.Errorf("mute must stay behind: %v", ev)
	}
	// back in alice's server and kicked: the call ends for everyone
	gb.op(t, "voice_state", map[string]any{"channel_id": voiceCh})
	if ev := gb.nextVoice(idB, voiceCh, 3*time.Second); ev == nil {
		t.Fatal("bob rejoins")
	}
	if res, _ = do(t, ts, "DELETE", "/api/v1/servers/"+sid+"/members/"+idB, tokA, nil); res.StatusCode != 204 {
		t.Fatalf("kick: %d", res.StatusCode)
	}
	if ev := gb.nextVoice(idB, "", 3*time.Second); ev == nil || ev["old_channel_id"] != voiceCh {
		t.Errorf("a kick ends the call: %v", ev)
	}
	res, body = do(t, ts, "GET", "/api/v1/channels/"+voiceCh+"/voice", tokA, nil)
	if res.StatusCode != 200 || len(body["_list"].([]any)) != 0 {
		t.Errorf("no ghost after a kick: %d %v", res.StatusCode, body)
	}
	_ = ga
}

// TestCallSummary checks that the caller alone can seal an encrypted summary
// onto a call's line, once, and that it neither marks the line edited nor
// leaves anything readable about the call on the server.
func TestCallSummary(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	friend(t, ts, tokA, "bob", tokB, idA)
	_, body := do(t, ts, "POST", "/api/v1/users/@me/channels", tokA, map[string]any{"recipient_ids": []string{idB}})
	dm := body["id"].(string)
	epoch := createEpoch(t, ts, tokA, dm)
	ga := connectGW(t, ts, tokA)
	gb := connectGW(t, ts, tokB)

	ga.op(t, "voice_state", map[string]any{"channel_id": dm})
	line := gb.next("message_create", 3*time.Second)
	if line == nil || line["type"] != "system_call" {
		t.Fatalf("call line: %v", line)
	}
	mid := line["id"].(string)
	ga.op(t, "voice_state", map[string]any{"channel_id": nil})
	gb.next("call_end", 3*time.Second)

	summary := map[string]any{"ciphertext": envelopeFor(dm, epoch, "summary"), "key_conv_id": dm, "key_epoch": epoch}
	path := "/api/v1/channels/" + dm + "/messages/" + mid + "/call"
	if res, body := do(t, ts, "PUT", path, tokB, summary); res.StatusCode != 403 {
		t.Errorf("only the caller writes the summary: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "PUT", path, tokA, map[string]any{"ciphertext": envelopeFor(dm, epoch+5, "x"), "key_conv_id": dm, "key_epoch": epoch + 5}); res.StatusCode != 400 {
		t.Errorf("a summary under a key that does not exist: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "PUT", path, tokA, summary); res.StatusCode != 204 {
		t.Fatalf("seal the summary: %d %v", res.StatusCode, body)
	}
	ev := gb.next("message_update", 3*time.Second)
	if ev == nil || ev["id"] != mid || ev["ciphertext"] == nil || ev["edited_at"] != nil || ev["type"] != "system_call" {
		t.Errorf("the summary reaches the conversation, unedited: %v", ev)
	}
	if res, body := do(t, ts, "PUT", path, tokA, summary); res.StatusCode != 409 {
		t.Errorf("a summary is written once: %d %v", res.StatusCode, body)
	}
	// an ordinary message has no summary to write
	res, sent := sendMsg(t, ts, tokA, dm, dm, epoch, "hello", "n1", nil)
	if res.StatusCode != 201 {
		t.Fatalf("send: %d %v", res.StatusCode, sent)
	}
	if res, body := do(t, ts, "PUT", "/api/v1/channels/"+dm+"/messages/"+sent["id"].(string)+"/call", tokA, summary); res.StatusCode != 404 {
		t.Errorf("summary on a message that is not a call: %d %v", res.StatusCode, body)
	}
	_, page := do(t, ts, "GET", "/api/v1/channels/"+dm+"/messages?limit=10", tokB, nil)
	for _, x := range page["_list"].([]any) {
		m := x.(map[string]any)
		if m["id"] == mid && (m["ciphertext"] == nil || m["edited_at"] != nil || m["key_conv_id"] != dm) {
			t.Errorf("the stored line: %v", m)
		}
	}
}

func TestVoiceStateCodecs(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	view := createServer(t, ts, tokA, "Voice")
	general := channelNamed(view, "general")["id"].(string)
	voiceCh := channelNamed(view, "General")["id"].(string)
	join(t, ts, tokB, inviteFor(t, ts, tokA, general))
	ga := connectGW(t, ts, tokA)
	gb := connectGW(t, ts, tokB)

	// what a device can watch besides VP8 reaches the others, known codecs only and once each
	ga.op(t, "voice_state", map[string]any{"channel_id": voiceCh, "codecs": []string{"h264", "vp9", "h264", "x"}})
	ev := gb.next("voice_state", 3*time.Second)
	if ev == nil || ev["user_id"] != idA || fmt.Sprint(ev["codecs"]) != "[h264]" {
		t.Fatalf("join with codecs: %v", ev)
	}
	ga.next("voice_state", 3*time.Second)
	// a device that names none has none, and the field is left out
	gb.op(t, "voice_state", map[string]any{"channel_id": voiceCh})
	ev = ga.next("voice_state", 3*time.Second)
	if ev == nil || ev["user_id"] != idB {
		t.Fatalf("bob join: %v", ev)
	}
	if _, has := ev["codecs"]; has {
		t.Errorf("codecs on a device that named none: %v", ev)
	}
	gb.next("voice_state", 3*time.Second)
	// every state a device sends says it again: a toggle without them clears them
	ga.op(t, "voice_state", map[string]any{"channel_id": voiceCh, "self_mute": true})
	ev = gb.next("voice_state", 3*time.Second)
	if _, has := ev["codecs"]; ev == nil || has || ev["self_mute"] != true {
		t.Errorf("mute without codecs: %v", ev)
	}
	ga.next("voice_state", 3*time.Second)
	// and the call's roster carries them
	ga.op(t, "voice_state", map[string]any{"channel_id": voiceCh, "codecs": []string{"h264"}})
	gb.next("voice_state", 3*time.Second)
	ga.next("voice_state", 3*time.Second)
	res, err := http.NewRequest("GET", ts.URL+"/api/v1/channels/"+voiceCh+"/voice", nil)
	if err != nil {
		t.Fatal(err)
	}
	res.Header.Set("Authorization", "Bearer "+tokB)
	resp, err := http.DefaultClient.Do(res)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var roster []map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&roster); err != nil {
		t.Fatalf("roster: %v", err)
	}
	found := false
	for _, st := range roster {
		if st["user_id"] == idA {
			found = fmt.Sprint(st["codecs"]) == "[h264]"
		}
	}
	if !found {
		t.Errorf("roster without alice's codecs: %v", roster)
	}
}

// TestVoiceModerationTools checks the moderation a voice channel's people
// get from the apps: permissions are read in the channel the person is in, so
// an overwrite can make someone a channel's own moderator; nobody moves people
// into a channel they cannot see; a disconnect says a moderator did it; and a
// channel that denies Speak is told apart from a moderator's mute.
func TestVoiceModerationTools(t *testing.T) {
	srv, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	tokC, idC := register(t, ts, "carol")
	view := createServer(t, ts, tokA, "Voice")
	sid := view["id"].(string)
	general := channelNamed(view, "general")["id"].(string)
	voiceCh := channelNamed(view, "General")["id"].(string)
	code := inviteFor(t, ts, tokA, general)
	join(t, ts, tokB, code)
	join(t, ts, tokC, code)
	// carol holds a role, which puts her above bob, but no moderation anywhere but General
	mods := createRole(t, ts, tokA, sid, "Helpers", 0)
	if res, body := do(t, ts, "PUT", "/api/v1/servers/"+sid+"/members/"+idC+"/roles/"+mods, tokA, nil); res.StatusCode != 200 {
		t.Fatalf("give role: %d %v", res.StatusCode, body)
	}
	newVoice := func(name string) string {
		res, body := do(t, ts, "POST", "/api/v1/servers/"+sid+"/channels", tokA, map[string]any{"type": "voice", "name": name})
		if res.StatusCode >= 300 {
			t.Fatalf("%s: %d %v", name, res.StatusCode, body)
		}
		return body["id"].(string)
	}
	lounge := newVoice("Lounge")
	hidden := newVoice("Hidden")
	if res, body := do(t, ts, "PUT", "/api/v1/channels/"+hidden+"/permissions/"+mods, tokA, map[string]any{"type": "role", "deny": permStr(perms.ViewChannel)}); res.StatusCode != 200 {
		t.Fatalf("hide: %d %v", res.StatusCode, body)
	}
	gb := connectGW(t, ts, tokB)
	gb.op(t, "voice_state", map[string]any{"channel_id": voiceCh})
	if ev := gb.nextVoice(idB, voiceCh, 3*time.Second); ev == nil || ev["suppress"] != nil {
		t.Fatalf("bob joins, not suppressed: %v", ev)
	}
	// without the permission in General, carol cannot mute
	if res, body := do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/members/"+idB+"/voice", tokC, map[string]any{"mute": true}); res.StatusCode != 403 {
		t.Fatalf("mute without permission: %d %v", res.StatusCode, body)
	}
	// an overwrite on General makes her its moderator
	grant := permStr(perms.MuteMembers | perms.DeafenMembers | perms.MoveMembers)
	if res, body := do(t, ts, "PUT", "/api/v1/channels/"+voiceCh+"/permissions/"+idC, tokA, map[string]any{"type": "member", "allow": grant}); res.StatusCode != 200 {
		t.Fatalf("grant: %d %v", res.StatusCode, body)
	}
	res, body := do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/members/"+idB+"/voice", tokC, map[string]any{"mute": true, "deaf": true})
	if res.StatusCode != 200 || body["server_mute"] != true || body["server_deaf"] != true {
		t.Fatalf("mute and deafen through the overwrite: %d %v", res.StatusCode, body)
	}
	if ev := gb.nextVoice(idB, voiceCh, 3*time.Second); ev == nil || ev["server_mute"] != true || ev["server_deaf"] != true || ev["suppress"] != nil {
		t.Fatalf("bob hears he is muted and deafened by a moderator: %v", ev)
	}
	// a channel carol cannot see is no destination, and saying so is the same as for no channel at all
	if res, body := do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/members/"+idB+"/voice", tokC, map[string]any{"channel_id": hidden}); res.StatusCode != 400 || errCode(body) != "invalid_channel" {
		t.Fatalf("move into a hidden channel: %d %v", res.StatusCode, body)
	}
	res, body = do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/members/"+idB+"/voice", tokC, map[string]any{"channel_id": lounge})
	if res.StatusCode != 200 || body["channel_id"] != lounge || body["server_mute"] != true {
		t.Fatalf("move to Lounge, mute and all: %d %v", res.StatusCode, body)
	}
	if ev := gb.nextVoice(idB, lounge, 3*time.Second); ev == nil {
		t.Fatal("bob is told he was moved")
	}
	// in Lounge carol has no overwrite, so bob is out of her reach there
	if res, body := do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/members/"+idB+"/voice", tokC, map[string]any{"channel_id": nil}); res.StatusCode != 403 {
		t.Fatalf("disconnect from a channel she does not moderate: %d %v", res.StatusCode, body)
	}
	// the owner disconnects him: the leave says a moderator did it
	if res, body := do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/members/"+idB+"/voice", tokA, map[string]any{"channel_id": nil}); res.StatusCode != 204 {
		t.Fatalf("disconnect: %d %v", res.StatusCode, body)
	}
	if ev := gb.nextVoice(idB, "", 3*time.Second); ev == nil || ev["reason"] != "disconnected" || ev["old_channel_id"] != lounge {
		t.Fatalf("the leave names the moderator's disconnect: %v", ev)
	}
	// joining again does not lift a moderator's mute and deafen: they are kept on the member
	gb.op(t, "voice_state", map[string]any{"channel_id": voiceCh})
	if ev := gb.nextVoice(idB, voiceCh, 3*time.Second); ev == nil || ev["server_mute"] != true || ev["server_deaf"] != true {
		t.Fatalf("still muted and deafened after joining again: %v", ev)
	}
	res, body = do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/members/"+idB+"/voice", tokC, map[string]any{"mute": false, "deaf": false})
	if res.StatusCode != 200 || body["server_mute"] != false || body["server_deaf"] != false {
		t.Fatalf("lifted: %d %v", res.StatusCode, body)
	}
	gb.nextVoice(idB, voiceCh, 3*time.Second)
	gb.op(t, "voice_state", map[string]any{"channel_id": nil})
	gb.nextVoice(idB, "", 3*time.Second)
	gb.op(t, "voice_state", map[string]any{"channel_id": voiceCh})
	if ev := gb.nextVoice(idB, voiceCh, 3*time.Second); ev == nil || ev["server_mute"] != false || ev["server_deaf"] != false {
		t.Fatalf("lifted for good: %v", ev)
	}
	// AutoMod's hold and the voice mute sit on the same flags without undoing each other
	if res, body := do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/members/"+idB+"/voice", tokA, map[string]any{"mute": true}); res.StatusCode != 200 {
		t.Fatalf("mute again: %d %v", res.StatusCode, body)
	}
	if m, err := srv.Store.Member(context.Background(), mustSnowflake(t, sid), mustSnowflake(t, idB)); err != nil || m.Flags != store.MemberVoiceMuted {
		t.Fatalf("flags after the mute: %v %v", m, err)
	}
	gb.op(t, "voice_state", map[string]any{"channel_id": nil})
	gb.nextVoice(idB, "", 3*time.Second)
	// a channel that denies Speak silences, and says it is the channel
	if res, body := do(t, ts, "PUT", "/api/v1/channels/"+lounge+"/permissions/"+sid, tokA, map[string]any{"type": "role", "deny": permStr(perms.Speak)}); res.StatusCode != 200 {
		t.Fatalf("deny speak: %d %v", res.StatusCode, body)
	}
	gb.op(t, "voice_state", map[string]any{"channel_id": lounge})
	if ev := gb.nextVoice(idB, lounge, 3*time.Second); ev == nil || ev["server_mute"] != true || ev["suppress"] != true {
		t.Fatalf("suppressed, not muted by anyone: %v", ev)
	}
}

func mustSnowflake(t *testing.T, s string) ids.ID {
	t.Helper()
	id, err := ids.Parse(s)
	if err != nil {
		t.Fatalf("parse %q: %v", s, err)
	}
	return id
}

// TestPrioritySpeaker checks that holding the Priority Speaker key counts only
// for someone the channel gives Priority Speaker.
func TestPrioritySpeaker(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, idA := register(t, ts, "alice")
	tokB, idB := register(t, ts, "bob")
	view := createServer(t, ts, tokA, "Voice")
	sid := view["id"].(string)
	general := channelNamed(view, "general")["id"].(string)
	voiceCh := channelNamed(view, "General")["id"].(string)
	join(t, ts, tokB, inviteFor(t, ts, tokA, general))
	ga := connectGW(t, ts, tokA)
	gb := connectGW(t, ts, tokB)

	ga.op(t, "voice_state", map[string]any{"channel_id": voiceCh, "priority": true})
	if ev := ga.nextVoice(idA, voiceCh, 3*time.Second); ev == nil || ev["priority_speaker"] != true {
		t.Errorf("the owner may be Priority Speaker: %v", ev)
	}
	gb.op(t, "voice_state", map[string]any{"channel_id": voiceCh, "priority": true})
	if ev := gb.nextVoice(idB, voiceCh, 3*time.Second); ev == nil || ev["priority_speaker"] != nil {
		t.Errorf("without the permission the key does nothing: %v", ev)
	}
	if res, body := do(t, ts, "PUT", "/api/v1/channels/"+voiceCh+"/permissions/"+sid, tokA, map[string]any{"type": "role", "allow": permStr(perms.PrioritySpeaker)}); res.StatusCode != 200 {
		t.Fatalf("allow: %d %v", res.StatusCode, body)
	}
	gb.op(t, "voice_state", map[string]any{"channel_id": voiceCh, "priority": true})
	if ev := gb.nextVoice(idB, voiceCh, 3*time.Second); ev == nil || ev["priority_speaker"] != true {
		t.Errorf("allowed in the channel: %v", ev)
	}
	gb.op(t, "voice_state", map[string]any{"channel_id": voiceCh})
	if ev := gb.nextVoice(idB, voiceCh, 3*time.Second); ev == nil || ev["priority_speaker"] != nil {
		t.Errorf("letting go of the key: %v", ev)
	}
}
