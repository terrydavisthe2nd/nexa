package api

import (
	"context"
	"evilion.xyz/chat/internal/realtime"
	"evilion.xyz/chat/internal/voice"
	"testing"
	"time"
)

func TestSFUModerationRetiresExistingSessionsAndRejectsForbiddenAudio(t *testing.T) {
	srv, ts := newTestServer(t)
	rt, cf := newRealtimeStandIn(t)
	srv.Realtime = realtime.New("app", "secret")
	srv.Realtime.Base = cf.URL
	srv.Meter = realtime.NewMeter(srv.Store, 1000)
	owner, ownerID := register(t, ts, "owner")
	member, memberID := register(t, ts, "member")
	view := createServer(t, ts, owner, "Moderation")
	sid := view["id"].(string)
	general := channelNamed(view, "general")["id"].(string)
	channel := channelNamed(view, "General")["id"].(string)
	join(t, ts, member, inviteFor(t, ts, owner, general))
	gateway := connectGW(t, ts, member)
	gateway.op(t, "voice_state", map[string]any{"channel_id": channel, "sfu": true})
	if gateway.next("voice_state", 3*time.Second) == nil {
		t.Fatal("no join")
	}
	newSession := func() string {
		t.Helper()
		res, body := do(t, ts, "POST", "/api/v1/voice/sfu/session", member, nil)
		if res.StatusCode != 200 {
			t.Fatalf("session: %d %v", res.StatusCode, body)
		}
		return body["session_id"].(string)
	}
	session := newSession()
	for _, tracks := range [][]map[string]any{
		{{"mid": "1", "kind": "audio"}},
		{{"mid": "0", "kind": "audio"}, {"mid": "0", "kind": "screen_audio"}},
	} {
		res, body := do(t, ts, "POST", "/api/v1/voice/sfu/publish", member, map[string]any{"session_id": session, "offer": offerSDP, "tracks": tracks})
		if res.StatusCode != 400 {
			t.Fatalf("media relabeling was accepted: %d %v", res.StatusCode, body)
		}
	}
	publish := func(session string) int {
		t.Helper()
		res, _ := do(t, ts, "POST", "/api/v1/voice/sfu/publish", member, map[string]any{"session_id": session, "offer": offerSDP, "tracks": []map[string]any{{"mid": "0", "kind": "audio"}}})
		return res.StatusCode
	}
	if code := publish(session); code != 200 {
		t.Fatalf("initial publish %d", code)
	}
	if code := publish(session); code != 409 {
		t.Fatalf("duplicate publication %d", code)
	}
	res, body := do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/members/"+memberID+"/voice", owner, map[string]any{"mute": true, "deaf": true})
	if res.StatusCode != 200 && res.StatusCode != 204 {
		t.Fatalf("moderation: %d %v", res.StatusCode, body)
	}
	if !rt.wasClosed(session) {
		t.Fatal("moderation left an existing upstream session live")
	}
	if code := publish(session); code != 409 {
		t.Fatalf("retired session publish %d", code)
	}
	ownGateway := connectGW(t, ts, owner)
	ownGateway.op(t, "voice_state", map[string]any{"channel_id": channel, "sfu": true})
	if ownGateway.next("voice_state", 3*time.Second) == nil {
		t.Fatal("owner did not join")
	}
	_, own := do(t, ts, "POST", "/api/v1/voice/sfu/session", owner, nil)
	ownSession := own["session_id"].(string)
	if res, body := do(t, ts, "POST", "/api/v1/voice/sfu/publish", owner, map[string]any{"session_id": ownSession, "offer": offerSDP, "tracks": []map[string]any{{"mid": "0", "kind": "audio"}}}); res.StatusCode != 200 {
		t.Fatalf("owner audio: %d %v", res.StatusCode, body)
	}
	muted := newSession()
	_, pulled := do(t, ts, "POST", "/api/v1/voice/sfu/subscribe", member, map[string]any{"session_id": muted, "tracks": []map[string]any{{"user_id": ownerID, "kind": "audio"}}})
	if tracks, ok := pulled["tracks"].([]any); !ok || len(tracks) != 1 || tracks[0].(map[string]any)["error"] != "server_deaf" {
		t.Fatalf("server-deaf listener pulled audio: %v", pulled)
	}
	if code := publish(muted); code != 403 {
		t.Fatalf("muted microphone publish %d", code)
	}
	res, body = do(t, ts, "PATCH", "/api/v1/servers/"+sid+"/members/"+memberID+"/voice", owner, map[string]any{"mute": false, "deaf": false})
	if res.StatusCode != 200 && res.StatusCode != 204 {
		t.Fatalf("unmute: %d %v", res.StatusCode, body)
	}
	if !rt.wasClosed(muted) {
		t.Fatal("unmute did not retire the restricted session for recovery")
	}
	if code := publish(newSession()); code != 200 {
		t.Fatalf("microphone could not recover: %d", code)
	}
}

func TestSFUSubscriptionAdmissionAndFailedCloseAccounting(t *testing.T) {
	srv, ts := newTestServer(t)
	rt, cf := newRealtimeStandIn(t)
	srv.Realtime = realtime.New("app", "secret")
	srv.Realtime.Base = cf.URL
	srv.Meter = realtime.NewMeter(srv.Store, 1000)
	a, idA := register(t, ts, "alice")
	b, idB := register(t, ts, "bob")
	view := createServer(t, ts, a, "Accounting")
	general := channelNamed(view, "general")["id"].(string)
	channel := channelNamed(view, "General")["id"].(string)
	join(t, ts, b, inviteFor(t, ts, a, general))
	for _, token := range []string{a, b} {
		g := connectGW(t, ts, token)
		g.op(t, "voice_state", map[string]any{"channel_id": channel, "sfu": true})
		if g.next("voice_state", 3*time.Second) == nil {
			t.Fatal("no join")
		}
	}
	_, body := do(t, ts, "POST", "/api/v1/voice/sfu/session", a, nil)
	pub := body["session_id"].(string)
	_, body = do(t, ts, "POST", "/api/v1/voice/sfu/session", b, nil)
	sub := body["session_id"].(string)
	res, body := do(t, ts, "POST", "/api/v1/voice/sfu/publish", a, map[string]any{"session_id": pub, "offer": offerSDP, "tracks": []map[string]any{{"mid": "0", "kind": "audio"}}})
	if res.StatusCode != 200 {
		t.Fatalf("publish: %d %v", res.StatusCode, body)
	}
	mid := ""
	for i := 0; i < 3; i++ {
		res, body = do(t, ts, "POST", "/api/v1/voice/sfu/subscribe", b, map[string]any{"session_id": sub, "tracks": []map[string]any{{"user_id": idA, "kind": "audio"}}})
		if res.StatusCode != 200 {
			t.Fatalf("subscribe: %d %v", res.StatusCode, body)
		}
		tracks := body["tracks"].([]any)
		got := tracks[0].(map[string]any)["mid"].(string)
		if i == 0 {
			mid = got
		} else if mid != got {
			t.Fatal("duplicate upstream allocation")
		}
	}
	rt.mu.Lock()
	count := len(rt.pulled)
	rt.refuseClose = true
	rt.mu.Unlock()
	if count != 1 {
		t.Fatalf("allocated %d copies of one subscription", count)
	}
	res, body = do(t, ts, "PUT", "/api/v1/voice/sfu/close", b, map[string]any{"session_id": sub, "mids": []string{mid}})
	if res.StatusCode != 502 {
		t.Fatalf("failed upstream close: %d %v", res.StatusCode, body)
	}
	used := srv.Meter.Used(context.Background())
	time.Sleep(20 * time.Millisecond)
	if srv.Meter.Used(context.Background()) <= used {
		t.Fatal("failed close stopped charging a live subscription")
	}
	// A moderation flag can change while the provider handles an add request,
	// before the route acquiring the listener's stripe can retire its session.
	rt.mu.Lock()
	rt.refuseClose = false
	rt.beforeRemoteAdd = func() {
		srv.Voice.Update(mustParseID(t, idB), func(st *voice.State) { st.ServerDeaf = true })
	}
	rt.mu.Unlock()
	_, body = do(t, ts, "POST", "/api/v1/voice/sfu/session", b, nil)
	fresh := body["session_id"].(string)
	res, body = do(t, ts, "POST", "/api/v1/voice/sfu/subscribe", b, map[string]any{"session_id": fresh, "tracks": []map[string]any{{"user_id": idA, "kind": "audio"}}})
	if res.StatusCode != 409 || errCode(body) != "sfu_session_gone" {
		t.Fatalf("in-flight subscribe escaped moderation: %d %v", res.StatusCode, body)
	}
	if !rt.wasClosed(fresh) {
		t.Fatal("stale negotiation left its upstream session alive")
	}
}

func TestSFURetirementRetriesWithoutErasingLiveUsage(t *testing.T) {
	srv, _ := newTestServer(t)
	rt, cf := newRealtimeStandIn(t)
	srv.Realtime = realtime.New("app", "secret")
	srv.Realtime.Base = cf.URL
	srv.Meter = realtime.NewMeter(srv.Store, 1000)
	rt.mu.Lock()
	rt.refuseCloses = 1
	rt.mu.Unlock()
	srv.Meter.Start("listener", "0", realtime.Source("retired", "audio"), realtime.RateFor("audio", 0), 0, 0)
	srv.closeSFUSession("retired")
	time.Sleep(50 * time.Millisecond)
	if _, charged := srv.Meter.ActiveMid("listener", realtime.Source("retired", "audio")); !charged {
		t.Fatal("unconfirmed session closure erased live usage")
	}
	if !rt.wasClosed("retired") {
		t.Fatal("transient provider close failure was not retried")
	}
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		if _, charged := srv.Meter.ActiveMid("listener", realtime.Source("retired", "audio")); !charged {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("confirmed closure did not end accounting")
}
