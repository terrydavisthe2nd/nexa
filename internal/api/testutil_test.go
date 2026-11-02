package api

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"
	"golang.org/x/time/rate"

	"evilion.xyz/chat/internal/config"
	"evilion.xyz/chat/internal/db"
	"evilion.xyz/chat/internal/gateway"
	"evilion.xyz/chat/internal/media"
	"evilion.xyz/chat/internal/ratelimit"
	"evilion.xyz/chat/internal/store"
)

// The api tests run in parallel: each has its own server and database. A
// test that changes package-level settings (a stand-in's address, a grace
// period) calls serial first, which waits for the parallel ones and runs alone.
var (
	globalsMu   sync.RWMutex
	serialTests sync.Map
	parallelOn  sync.Map
)

// serial marks a test that changes package-level settings: it runs alone.
func serial(t *testing.T) {
	t.Helper()
	if _, done := serialTests.LoadOrStore(t.Name(), true); done {
		return
	}
	globalsMu.Lock()
	t.Cleanup(globalsMu.Unlock)
}

// parallel lets a top-level test run beside the others, holding off any
// serial test until it ends. Subtests keep their parent's order.
func parallel(t *testing.T) {
	if strings.Contains(t.Name(), "/") {
		return
	}
	if _, ok := serialTests.Load(t.Name()); ok {
		return
	}
	if _, done := parallelOn.LoadOrStore(t.Name(), true); done {
		return
	}
	t.Parallel()
	globalsMu.RLock()
	t.Cleanup(globalsMu.RUnlock)
}

func newTestServer(t *testing.T) (*Server, *httptest.Server) {
	t.Helper()
	parallel(t)
	dir := t.TempDir()
	d, err := openTestDB(t, dir)
	if err != nil {
		t.Fatal(err)
	}
	if err := d.Migrate(context.Background()); err != nil {
		t.Fatal(err)
	}
	cfg, _ := config.Load(func(k string) string {
		switch k {
		case "DATA_DIR":
			return dir
		case "PUBLIC_URL":
			return "http://localhost"
		}
		return ""
	})
	st := store.New(d)
	srv := NewServer(cfg, st, slog.New(slog.NewTextHandler(io.Discard, nil)), nil)
	m, err := media.New(filepath.Join(dir, "media"), st)
	if err != nil {
		t.Fatal(err)
	}
	srv.Media = m
	// Tests hammer the API from one loopback address; keep only the per-username limiter meaningful.
	srv.limReg = ratelimit.New(rate.Inf, 1000, time.Hour)
	srv.limSignup = ratelimit.New(rate.Inf, 1000, time.Hour)
	srv.limAuthIP = ratelimit.New(rate.Inf, 1000, time.Hour)
	srv.limGeneric = ratelimit.New(rate.Inf, 1000, time.Hour)
	srv.limMsg = ratelimit.New(rate.Inf, 1000, time.Hour)
	srv.limUpload = ratelimit.New(rate.Inf, 1000, time.Hour)
	srv.limGuest = ratelimit.New(rate.Inf, 1000, time.Hour)
	hub := gateway.New(st, srv.Auth, srv.Log, cfg.Origins())
	srv.Bcast = hub
	srv.WireServers(hub)
	srv.WireVoice(hub)
	srv.WireActivities(hub)
	srv.WireModeration(hub)
	srv.Extra = append(srv.Extra, func(mux *http.ServeMux) { mux.Handle("GET /gateway", hub) })
	ts := httptest.NewServer(srv.Handler())
	t.Cleanup(func() { ts.Close(); d.Close() })
	return srv, ts
}

// do performs a JSON request and decodes the body into a generic map.
func do(t *testing.T, ts *httptest.Server, method, path, token string, body any) (*http.Response, map[string]any) {
	t.Helper()
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, ts.URL+path, rd)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	raw, _ := io.ReadAll(res.Body)
	out := map[string]any{}
	if len(raw) > 0 {
		var v any
		if err := json.Unmarshal(raw, &v); err == nil {
			if m, ok := v.(map[string]any); ok {
				out = m
			} else {
				out["_list"] = v
			}
		} else {
			out["_raw"] = string(raw)
		}
	}
	return res, out
}

func errCode(m map[string]any) string {
	if e, ok := m["error"].(map[string]any); ok {
		c, _ := e["code"].(string)
		return c
	}
	return ""
}

// gw is a minimal gateway client for asserting events in handler tests.
type gw struct {
	frames chan map[string]any
	done   chan struct{}
	ws     *websocket.Conn
	// ready is the ready payload (only kept by connectGWReady).
	ready map[string]any
}

// close ends the connection (the server treats it as a disconnect).
func (g *gw) close() {
	_ = g.ws.Close(websocket.StatusNormalClosure, "")
	<-g.done
}

// connectGWReady connects and keeps the ready payload for inspection.
func connectGWReady(t *testing.T, ts *httptest.Server, token string) *gw {
	t.Helper()
	return connectGWOpts(t, ts, token, true)
}

func connectGW(t *testing.T, ts *httptest.Server, token string) *gw {
	t.Helper()
	return connectGWOpts(t, ts, token, false)
}

func connectGWOpts(t *testing.T, ts *httptest.Server, token string, keepReady bool) *gw {
	t.Helper()
	ws, _, err := websocket.Dial(context.Background(), "ws"+strings.TrimPrefix(ts.URL, "http")+"/gateway", &websocket.DialOptions{HTTPHeader: map[string][]string{"Origin": {"http://localhost"}}})
	if err != nil {
		t.Fatal(err)
	}
	g := &gw{frames: make(chan map[string]any, 256), done: make(chan struct{}), ws: ws}
	go func() {
		defer close(g.done)
		for {
			_, raw, err := ws.Read(context.Background())
			if err != nil {
				return
			}
			var f map[string]any
			if json.Unmarshal(raw, &f) == nil {
				g.frames <- f
			}
		}
	}()
	t.Cleanup(func() { ws.Close(websocket.StatusNormalClosure, "") })
	b, _ := json.Marshal(map[string]any{"op": "identify", "d": map[string]string{"token": token}})
	if err := ws.Write(context.Background(), websocket.MessageText, b); err != nil {
		t.Fatal(err)
	}
	ready := g.next("ready", 3*time.Second)
	if ready == nil {
		t.Fatal("gateway ready not received")
	}
	if keepReady {
		g.ready = ready
	}
	return g
}

// next returns the data of the next frame with type t (discarding others).
func (g *gw) next(t string, timeout time.Duration) map[string]any {
	deadline := time.After(timeout)
	for {
		select {
		case f := <-g.frames:
			if f["t"] == t {
				d, _ := f["d"].(map[string]any)
				if d == nil {
					d = map[string]any{"_": true}
				}
				return d
			}
		case <-deadline:
			return nil
		case <-g.done:
			// frames that arrived before the close still count
			for {
				select {
				case f := <-g.frames:
					if f["t"] == t {
						d, _ := f["d"].(map[string]any)
						if d == nil {
							d = map[string]any{"_": true}
						}
						return d
					}
				default:
					return nil
				}
			}
		}
	}
}

// newHTTPTestServer starts another listener over the same Server (after config changes).
func newHTTPTestServer(t *testing.T, srv *Server) *httptest.Server {
	t.Helper()
	ts := httptest.NewServer(srv.Handler())
	t.Cleanup(ts.Close)
	return ts
}

var apiSchemaSeq atomic.Int64

// openTestDB is SQLite in a temporary directory, or — with CHAT_TEST_POSTGRES
// set — a schema of its own on that Postgres, so the whole API suite can be run
// against either database.
func openTestDB(t *testing.T, dir string) (*db.DB, error) {
	t.Helper()
	url := os.Getenv("CHAT_TEST_POSTGRES")
	if url == "" {
		return db.Open(filepath.Join(dir, "chat.db"))
	}
	name := fmt.Sprintf("a%d", apiSchemaSeq.Add(1))
	admin, err := db.OpenPostgres(url)
	if err != nil {
		return nil, err
	}
	ctx := context.Background()
	if _, err := admin.W.ExecContext(ctx, `DROP SCHEMA IF EXISTS `+name+` CASCADE`); err != nil {
		admin.Close()
		return nil, err
	}
	if _, err := admin.W.ExecContext(ctx, `CREATE SCHEMA `+name); err != nil {
		admin.Close()
		return nil, err
	}
	admin.Close()
	sep := "?"
	if strings.Contains(url, "?") {
		sep = "&"
	}
	return db.OpenPostgres(url + sep + "search_path=" + name)
}
