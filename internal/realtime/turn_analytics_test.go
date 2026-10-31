package realtime

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// A session whose connection Cloudflare no longer reaches answers 410 after
// waiting for it; closing it is done, since nothing can flow to it.
func TestCloseSessionTakesADisconnectedSessionAsClosed(t *testing.T) {
	var asked int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		asked++
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusGone)
		_, _ = io.WriteString(w, `{"errorCode":"session_error","errorDescription":"Session appears to be disconnected. Please check if the PeerConnection is connected."}`)
	}))
	defer srv.Close()
	c := New("app1", "secret1")
	c.Base = srv.URL
	if err := c.CloseSession(context.Background(), "gone"); err != nil {
		t.Fatalf("a disconnected session is closed: %v", err)
	}
	if asked != 1 {
		t.Fatalf("asked %d times", asked)
	}
	if !Disconnected(&Error{Status: http.StatusGone}) || Disconnected(&Error{Status: http.StatusBadGateway}) || Disconnected(ErrSessionGone) {
		t.Error("Disconnected names 410 answers only")
	}
	if CloseTimeout <= 5*time.Second {
		t.Error("an attempt must outlast Cloudflare's five second wait for a connection")
	}
}

func TestTURNMintsAndRevokes(t *testing.T) {
	var minted map[string]any
	var paths []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		paths = append(paths, r.Method+" "+r.URL.Path)
		if r.Header.Get("Authorization") != "Bearer tok" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		switch {
		case strings.HasSuffix(r.URL.Path, "/generate-ice-servers"):
			raw, _ := io.ReadAll(r.Body)
			_ = json.Unmarshal(raw, &minted)
			w.WriteHeader(http.StatusCreated)
			_, _ = io.WriteString(w, `{"iceServers":[{"urls":["stun:stun.cloudflare.com:3478"]},{"urls":["turn:turn.cloudflare.com:3478?transport=udp"],"username":"user-1","credential":"pass-1"}]}`)
		case strings.HasSuffix(r.URL.Path, "/user-1/revoke"):
			w.WriteHeader(http.StatusNoContent)
		case strings.HasSuffix(r.URL.Path, "/expired/revoke"):
			w.WriteHeader(http.StatusNotFound)
		default:
			w.WriteHeader(http.StatusInternalServerError)
		}
	}))
	defer srv.Close()
	if NewTURN("", "tok", nil) != nil || NewTURN("key", "", nil) != nil {
		t.Fatal("no client without a key")
	}
	turn := NewTURN("key 1", "tok", nil)
	turn.Base = srv.URL
	ctx := context.Background()
	cred, err := turn.Mint(ctx, time.Hour, "1234")
	if err != nil {
		t.Fatal(err)
	}
	if cred.Username != "user-1" || len(cred.Servers) != 2 {
		t.Fatalf("credential: %+v", cred)
	}
	if minted["ttl"] != float64(3600) || minted["customIdentifier"] != "1234" {
		t.Fatalf("asked for %v", minted)
	}
	if err := turn.Revoke(ctx, "user-1"); err != nil {
		t.Fatal(err)
	}
	if err := turn.Revoke(ctx, "expired"); err != nil {
		t.Fatalf("a credential Cloudflare no longer knows counts as revoked: %v", err)
	}
	if err := turn.Revoke(ctx, "broken"); err == nil {
		t.Fatal("a refused revocation is an error, to be tried again")
	}
	if paths[0] != "POST /turn/keys/key 1/credentials/generate-ice-servers" || paths[1] != "POST /turn/keys/key 1/credentials/user-1/revoke" {
		t.Fatalf("paths: %v", paths)
	}
}

// A credential that names no username could never be revoked, so it is refused.
func TestTURNRefusesAnUnnamedCredential(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, `{"iceServers":{"urls":["turn:turn.cloudflare.com:3478"]}}`)
	}))
	defer srv.Close()
	turn := NewTURN("key", "tok", nil)
	turn.Base = srv.URL
	if _, err := turn.Mint(context.Background(), time.Hour, "1"); err == nil {
		t.Fatal("an unnamed credential was handed out")
	}
}

func TestAnalyticsReadsTheMonthAndEachDay(t *testing.T) {
	var got struct {
		Query     string         `json:"query"`
		Variables map[string]any `json:"variables"`
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/graphql" || r.Header.Get("Authorization") != "Bearer an-token" {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		raw, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(raw, &got)
		_, _ = io.WriteString(w, `{"data":{"viewer":{"accounts":[{
			"sfu":[{"sum":{"egressBytes":1000}}],
			"turn":[{"sum":{"egressBytes":50}}],
			"ds0":[{"sum":{"egressBytes":300}}],
			"dt0":[],
			"bs0":[{"dimensions":{"sessionId":"s1"},"sum":{"egressBytes":200}},{"dimensions":{"sessionId":"s2"},"sum":{"egressBytes":100}}],
			"bk0":[{"dimensions":{"trackId":"42-screen-abc"},"sum":{"egressBytes":300}}],
			"bu0":[{"dimensions":{"customIdentifier":"7"},"sum":{"egressBytes":0}}]
		}]}},"errors":null}`)
	}))
	defer srv.Close()
	if NewAnalytics("", "t") != nil || NewAnalytics("a", "") != nil {
		t.Fatal("no reader without both settings")
	}
	a := NewAnalytics("acct1", "an-token")
	a.Base = srv.URL
	day := time.Date(2026, 10, 31, 0, 0, 0, 0, time.UTC)
	totals, days, err := a.Read(context.Background(), Window{From: MonthStart(day), To: day.Add(time.Hour)}, Window{From: day, To: day.Add(2 * time.Hour)})
	if err != nil {
		t.Fatal(err)
	}
	if totals.SFU != 1000 || totals.TURN != 50 {
		t.Fatalf("month: %+v", totals)
	}
	d := days[0]
	if d.Totals.SFU != 300 || d.Totals.TURN != 0 || d.Sessions["s1"] != 200 || d.Sessions["s2"] != 100 || d.Tracks["42-screen-abc"] != 300 || !d.Complete {
		t.Fatalf("day: %+v", d)
	}
	if got.Variables["acct"] != "acct1" || got.Variables["mf"] != "2026-10-01T00:00:00Z" || got.Variables["f0"] != "2026-10-31T00:00:00Z" || got.Variables["t0"] != "2026-10-31T02:00:00Z" {
		t.Fatalf("variables: %v", got.Variables)
	}
	for _, want := range []string{"callsUsageAdaptiveGroups", "callsTurnUsageAdaptiveGroups", "dimensions { sessionId }", "dimensions { trackId }", "dimensions { customIdentifier }", "datetime_lt: $t0"} {
		if !strings.Contains(got.Query, want) {
			t.Errorf("query lacks %q: %s", want, got.Query)
		}
	}
}

func TestAnalyticsErrors(t *testing.T) {
	for name, body := range map[string]string{
		"graphql error":   `{"data":null,"errors":[{"message":"cannot request data older than 4w4d"}]}`,
		"no account":      `{"data":{"viewer":{"accounts":[]}},"errors":null}`,
		"not json at all": `<html>`,
	} {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = io.WriteString(w, body) }))
		a := NewAnalytics("acct", "tok")
		a.Base = srv.URL
		if _, _, err := a.Read(context.Background(), Window{From: time.Now().Add(-time.Hour), To: time.Now()}); err == nil {
			t.Errorf("%s: no error", name)
		}
		srv.Close()
	}
}
