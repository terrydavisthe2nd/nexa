package api

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/realtime"
	"evilion.xyz/chat/internal/store"
)

func TestModCallsPage(t *testing.T) {
	srv, ts := newTestServer(t)
	ownTok, _ := register(t, ts, "owner")
	admTok, admID := register(t, ts, "adm")
	modTok, modID := register(t, ts, "moddy")
	memTok, bobID := register(t, ts, "bob")
	_, carolID := register(t, ts, "carol")
	setRank(t, ts, ownTok, admID, "admin")
	setRank(t, ts, ownTok, modID, "mod")
	ctx := context.Background()
	today := realtime.DayOf(time.Now())
	bob, _ := ids.Parse(bobID)
	carol, _ := ids.Parse(carolID)
	if err := srv.Store.AddCallUsage(ctx, today, []realtime.CallUsage{
		{User: int64(bob), Received: 5_000_000_000, Sent: 1_000_000, Seconds: 600},
		{User: int64(carol), Received: 1_000_000_000, Sent: 9_000_000_000, Seconds: 7200},
	}); err != nil {
		t.Fatal(err)
	}

	// call usage is for admins and up
	for _, tok := range []string{modTok, memTok} {
		if res, _ := do(t, ts, "GET", "/api/v1/moderation/calls", tok, nil); res.StatusCode != 403 && res.StatusCode != 404 {
			t.Errorf("refused rank saw the calls page: %d", res.StatusCode)
		}
		if res, _ := do(t, ts, "GET", "/api/v1/moderation/users/"+bobID+"/calls", tok, nil); res.StatusCode != 403 && res.StatusCode != 404 {
			t.Errorf("refused rank saw an account's calls: %d", res.StatusCode)
		}
	}

	first := func(query string) (map[string]any, map[string]any) {
		t.Helper()
		res, body := do(t, ts, "GET", "/api/v1/moderation/calls"+query, admTok, nil)
		if res.StatusCode != 200 {
			t.Fatalf("calls%s: %d %v", query, res.StatusCode, body)
		}
		list, _ := body["accounts"].([]any)
		if len(list) == 0 {
			t.Fatalf("calls%s: nobody listed: %v", query, body)
		}
		return list[0].(map[string]any), body
	}
	top, body := first("?period=today")
	if top["user_id"] != bobID || top["received_bytes"] != float64(5_000_000_000) || top["measured"] != false {
		t.Fatalf("largest first by what reached their devices: %v", top)
	}
	if user, _ := top["user"].(map[string]any); user["username"] != "bob" {
		t.Errorf("the account's profile comes with it: %v", top["user"])
	}
	if body["measurements"] != false || body["period"] != "today" {
		t.Errorf("page: %v", body)
	}
	if top, _ = first("?period=week&sort=sent"); top["user_id"] != carolID {
		t.Errorf("by what they sent others: %v", top)
	}
	if top, _ = first("?period=month&sort=time"); top["user_id"] != carolID {
		t.Errorf("by time in calls: %v", top)
	}

	// Cloudflare's measurement takes over once the day has one, and what no
	// account could be named for is listed apart
	if err := srv.Store.SaveCallMeasurements(ctx, today, []store.CallMeasurement{
		{User: bob, Received: 2_000_000_000, Relay: 3_000_000_000},
		{User: 0, Received: 7_000_000_000},
	}, time.Now().UnixMilli()); err != nil {
		t.Fatal(err)
	}
	top, body = first("?period=today")
	if top["user_id"] != bobID || top["received_bytes"] != float64(2_000_000_000) || top["relay_bytes"] != float64(3_000_000_000) || top["measured"] != true {
		t.Fatalf("measured usage: %v", top)
	}
	if unnamed, _ := body["unattributed"].(map[string]any); unnamed["received_bytes"] != float64(7_000_000_000) {
		t.Errorf("unattributed: %v", body["unattributed"])
	}
	for _, row := range body["accounts"].([]any) {
		if r := row.(map[string]any); r["user_id"] == "0" {
			t.Error("the unattributed row is listed as an account")
		}
	}

	res, one := do(t, ts, "GET", "/api/v1/moderation/users/"+bobID+"/calls", admTok, nil)
	if res.StatusCode != 200 {
		t.Fatalf("an account's calls: %d %v", res.StatusCode, one)
	}
	for _, p := range []string{"today", "week", "month"} {
		if got, _ := one[p].(map[string]any); got["received_bytes"] != float64(2_000_000_000) || got["seconds"] != float64(600) {
			t.Errorf("%s: %v", p, got)
		}
	}
}

// The measurement is put to names: sessions by who opened them, tracks by
// whose name starts theirs, relay by whom the credential was minted for; the
// rest is kept apart, and an account past its daily relay is cut off.
func TestPollRealtimeNamesWhoUsedWhat(t *testing.T) {
	srv, ts := newTestServer(t)
	_, aliceID := register(t, ts, "alice")
	_, bobID := register(t, ts, "bob")
	alice, _ := ids.Parse(aliceID)
	bob, _ := ids.Parse(bobID)
	ctx := context.Background()
	if err := srv.Store.RecordRealtimeSession(ctx, "sess-alice", alice); err != nil {
		t.Fatal(err)
	}
	cf := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.ReadAll(r.Body)
		fmt.Fprintf(w, `{"data":{"viewer":{"accounts":[{
			"sfu":[{"sum":{"egressBytes":500000000000}}],
			"turn":[{"sum":{"egressBytes":20000000000}}],
			"ds0":[{"sum":{"egressBytes":3000000000}}],
			"dt0":[{"sum":{"egressBytes":12000000000}}],
			"bs0":[{"dimensions":{"sessionId":"sess-alice"},"sum":{"egressBytes":2000000000}},{"dimensions":{"sessionId":"sess-from-elsewhere"},"sum":{"egressBytes":1000000000}}],
			"bk0":[{"dimensions":{"trackId":"%s-screen-abc123"},"sum":{"egressBytes":2500000000}},{"dimensions":{"trackId":"not-a-nexa-track"},"sum":{"egressBytes":500000000}}],
			"bu0":[{"dimensions":{"customIdentifier":"%s"},"sum":{"egressBytes":11000000000}},{"dimensions":{"customIdentifier":"someone-else"},"sum":{"egressBytes":1000000000}}]
		}]}},"errors":null}`, bobID, aliceID)
	}))
	defer cf.Close()
	srv.Analytics = &realtime.Analytics{AccountID: "acct", Token: "tok", Base: cf.URL, HTTP: http.DefaultClient}
	srv.Meter = realtime.NewMeter(srv.Store, 1000)
	if err := srv.pollRealtime(ctx); err != nil {
		t.Fatal(err)
	}
	today := realtime.DayOf(time.Now())
	a, _ := srv.Store.CallUsageFor(ctx, alice, today, today)
	b, _ := srv.Store.CallUsageFor(ctx, bob, today, today)
	unnamed, _ := srv.Store.CallUsageFor(ctx, 0, today, today)
	if a.Received != 2_000_000_000 || a.Relay != 11_000_000_000 || !a.Measured {
		t.Errorf("alice: %+v", a)
	}
	if b.Sent != 2_500_000_000 || !b.Measured {
		t.Errorf("bob: %+v", b)
	}
	if unnamed.Received != 1_000_000_000 || unnamed.Sent != 500_000_000 || unnamed.Relay != 1_000_000_000 {
		t.Errorf("unattributed: %+v", unnamed)
	}
	if !srv.turnCutOff(alice) || srv.turnCutOff(bob) {
		t.Error("only the account past its daily relay is cut off")
	}
}

// A session Cloudflare says has lost its connection stops being charged at
// once; one whose closing never succeeds stops once the retries run out.
func TestSFURetirementNeverChargesForever(t *testing.T) {
	srv, _ := newTestServer(t)
	gone := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusGone)
		_, _ = io.WriteString(w, `{"errorCode":"session_error","errorDescription":"Session appears to be disconnected."}`)
	}))
	defer gone.Close()
	srv.Realtime = realtime.New("app", "secret")
	srv.Realtime.Base = gone.URL
	srv.Meter = realtime.NewMeter(srv.Store, 1000)
	srv.Meter.Start("listener", "0", realtime.Source("speaker", "audio"), realtime.AudioBps, 1, 2)
	srv.closeSFUSession("listener")
	waitUncharged := func(session string, within time.Duration) bool {
		deadline := time.Now().Add(within)
		for time.Now().Before(deadline) {
			if !srv.Meter.Charging(session) {
				return true
			}
			time.Sleep(5 * time.Millisecond)
		}
		return false
	}
	if !waitUncharged("listener", 2*time.Second) {
		t.Fatal("a disconnected session was still charged")
	}

	broken := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadGateway)
	}))
	defer broken.Close()
	srv.Realtime.Base = broken.URL
	saved := sfuCloseAfter
	sfuCloseAfter = []time.Duration{time.Millisecond, time.Millisecond}
	defer func() { sfuCloseAfter = saved }()
	srv.Meter.Start("watcher", "0", realtime.Source("speaker", "audio"), realtime.AudioBps, 1, 2)
	srv.closeSFUSession("watcher")
	if !waitUncharged("watcher", 2*time.Second) {
		t.Fatal("a session whose closing failed was charged after the retries ran out")
	}
}
