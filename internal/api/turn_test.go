package api

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/realtime"
	"evilion.xyz/chat/internal/voice"
)

// fakeTURN stands in for Cloudflare's TURN key API: each mint names a new
// credential, and revocations are recorded.
type fakeTURN struct {
	mu      sync.Mutex
	minted  int
	revoked []string
	ttls    []float64
}

func newFakeTURN(t *testing.T, srv *Server) *fakeTURN {
	t.Helper()
	f := &fakeTURN{}
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		defer f.mu.Unlock()
		switch {
		case strings.HasSuffix(r.URL.Path, "/generate-ice-servers"):
			var body map[string]any
			_ = json.NewDecoder(r.Body).Decode(&body)
			f.minted++
			ttl, _ := body["ttl"].(float64)
			f.ttls = append(f.ttls, ttl)
			w.WriteHeader(http.StatusCreated)
			fmt.Fprintf(w, `{"iceServers":[{"urls":["stun:stun.example:3478"]},{"urls":["turn:turn.example:3478"],"username":"cred-%d","credential":"secret"}]}`, f.minted)
		case strings.HasSuffix(r.URL.Path, "/revoke"):
			parts := strings.Split(r.URL.Path, "/")
			f.revoked = append(f.revoked, parts[len(parts)-2])
			w.WriteHeader(http.StatusNoContent)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	t.Cleanup(up.Close)
	srv.Cfg.TurnKeyID, srv.Cfg.TurnAPIToken = "key", "token"
	srv.turnBase = up.URL
	return f
}

func (f *fakeTURN) count() (int, []string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.minted, append([]string(nil), f.revoked...)
}

// iceUsername fetches the ICE servers and returns the relay credential's name ("" for none).
func iceUsername(t *testing.T, ts *httptest.Server, tok string) string {
	t.Helper()
	res, body := do(t, ts, "GET", "/api/v1/voice/ice", tok, nil)
	if res.StatusCode != 200 {
		t.Fatalf("ice: %d %v", res.StatusCode, body)
	}
	servers, _ := body["ice_servers"].([]any)
	for _, entry := range servers {
		if m, _ := entry.(map[string]any); m["username"] != nil {
			return m["username"].(string)
		}
	}
	return ""
}

// idleTURN makes a credential's account look out of a call for d already.
func idleTURN(srv *Server, username string, d time.Duration) {
	srv.turnMu.Lock()
	defer srv.turnMu.Unlock()
	if g := srv.turnLive[username]; g != nil {
		g.idleSince = time.Now().Add(-d)
	}
}

func liveTURN(t *testing.T, srv *Server) map[string]bool {
	t.Helper()
	list, err := srv.Store.LiveTurnCredentials(context.Background(), time.Now().UnixMilli())
	if err != nil {
		t.Fatal(err)
	}
	out := map[string]bool{}
	for _, c := range list {
		out[c.Username] = true
	}
	return out
}

func putInCall(srv *Server, uid ids.ID) {
	srv.Voice.Join(voice.State{UserID: uid, ChannelID: ids.ID(424242), SessionID: ids.ID(1), ConnID: ids.ID(2)}, false)
}

// A credential fetched before a call is revoked when no call follows, and
// one is never handed out again after it is revoked.
func TestTURNCredentialWithoutACallIsRevoked(t *testing.T) {
	srv, ts := newTestServer(t)
	f := newFakeTURN(t, srv)
	ctx := context.Background()
	tok, _ := register(t, ts, "alice")
	name := iceUsername(t, ts, tok)
	if name != "cred-1" || iceUsername(t, ts, tok) != name {
		t.Fatalf("one credential, handed out again: %q", name)
	}
	if minted, _ := f.count(); minted != 1 {
		t.Fatalf("minted %d", minted)
	}
	if f.ttls[0] != turnTTL.Seconds() {
		t.Fatalf("lasts %v seconds", f.ttls[0])
	}
	if !liveTURN(t, srv)[name] {
		t.Fatal("the credential handed out is not recorded")
	}
	srv.sweepTURN(ctx)
	if _, revoked := f.count(); len(revoked) != 0 {
		t.Fatal("revoked before its grace ran out")
	}
	idleTURN(srv, name, turnGrace+time.Second)
	srv.sweepTURN(ctx)
	if _, revoked := f.count(); len(revoked) != 1 || revoked[0] != name {
		t.Fatalf("revoked %v", revoked)
	}
	if liveTURN(t, srv)[name] {
		t.Error("a revoked credential is still recorded as live")
	}
	if next := iceUsername(t, ts, tok); next == name || next == "" {
		t.Errorf("after a revocation a new one is minted: %q", next)
	}
}

// A credential lasts as long as its account's call, and turnGrace after it.
func TestTURNCredentialLastsTheCall(t *testing.T) {
	srv, ts := newTestServer(t)
	f := newFakeTURN(t, srv)
	ctx := context.Background()
	tok, id := register(t, ts, "alice")
	uid, _ := ids.Parse(id)
	putInCall(srv, uid)
	name := iceUsername(t, ts, tok)
	idleTURN(srv, name, time.Hour)
	srv.sweepTURN(ctx)
	if _, revoked := f.count(); len(revoked) != 0 {
		t.Fatal("a credential was revoked during its account's call")
	}
	srv.Voice.Leave(uid)
	srv.sweepTURN(ctx)
	if _, revoked := f.count(); len(revoked) != 0 {
		t.Fatal("revoked the moment the call ended, with no grace for a move")
	}
	idleTURN(srv, name, turnGrace+time.Second)
	srv.sweepTURN(ctx)
	if _, revoked := f.count(); len(revoked) != 1 {
		t.Fatalf("not revoked after the call: %v", revoked)
	}
}

// An account past its relay for the day gets none until the next, and loses
// what it holds.
func TestTURNCutOffForTheDay(t *testing.T) {
	srv, ts := newTestServer(t)
	f := newFakeTURN(t, srv)
	tok, id := register(t, ts, "alice")
	uid, _ := ids.Parse(id)
	putInCall(srv, uid)
	name := iceUsername(t, ts, tok)
	srv.blockTURN(uid, 11_000_000_000, time.Now())
	srv.sweepTURN(context.Background())
	if _, revoked := f.count(); len(revoked) != 1 || revoked[0] != name {
		t.Fatalf("the cut-off account kept its relay: %v", revoked)
	}
	if again := iceUsername(t, ts, tok); again != "" {
		t.Fatalf("a cut-off account was handed %q", again)
	}
	if !srv.turnCutOff(uid) {
		t.Error("cut-off not reported")
	}
}

// Credentials handed out before a restart are still revoked once their
// accounts are out of calls.
func TestTURNCredentialsOutliveARestart(t *testing.T) {
	srv, ts := newTestServer(t)
	f := newFakeTURN(t, srv)
	ctx := context.Background()
	tok, _ := register(t, ts, "alice")
	name := iceUsername(t, ts, tok)
	srv.turnMu.Lock()
	srv.turnLive, srv.turnCreds, srv.turnLoaded = nil, nil, false
	srv.turnMu.Unlock()
	srv.sweepTURN(ctx)
	if _, revoked := f.count(); len(revoked) != 0 {
		t.Fatal("a restart revoked credentials before calls could come back")
	}
	idleTURN(srv, name, turnGrace+time.Second)
	srv.sweepTURN(ctx)
	if _, revoked := f.count(); len(revoked) != 1 || revoked[0] != name {
		t.Fatalf("a credential from before the restart was not revoked: %v", revoked)
	}
}

// While no call can start, nobody outside a call is handed a relay; a call
// already running keeps its own.
func TestTURNNotHandedOutWhileCallsArePaused(t *testing.T) {
	srv, ts := newTestServer(t)
	f := newFakeTURN(t, srv)
	srv.Meter = realtime.NewMeter(srv.Store, 0)
	tok, id := register(t, ts, "alice")
	if name := iceUsername(t, ts, tok); name != "" {
		t.Fatalf("handed %q while calls are paused", name)
	}
	uid, _ := ids.Parse(id)
	putInCall(srv, uid)
	if name := iceUsername(t, ts, tok); name == "" {
		t.Fatal("a running call lost its relay")
	}
	if minted, _ := f.count(); minted != 1 {
		t.Fatalf("minted %d", minted)
	}
}

// Minting has a limit of its own, past the reuse of what was handed out.
func TestTURNMintsAreLimited(t *testing.T) {
	srv, ts := newTestServer(t)
	f := newFakeTURN(t, srv)
	tok, id := register(t, ts, "alice")
	uid, _ := ids.Parse(id)
	got := 0
	for i := 0; i < 10; i++ {
		srv.turnMu.Lock()
		delete(srv.turnCreds, uid)
		srv.turnMu.Unlock()
		if iceUsername(t, ts, tok) != "" {
			got++
		}
	}
	if minted, _ := f.count(); got != minted || got >= 10 || got == 0 {
		t.Fatalf("handed out %d of 10 forced mints (minted %d)", got, minted)
	}
}
