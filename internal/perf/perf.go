// Package perf is chatd's always-on profiler: it times every API route, every
// database call site, the gateway (client ops, fan-out, writes, and the
// network round trip of each socket's pings) and the Go runtime, folds it all
// into one summary for everyone, and writes it under DATA_DIR/perf every
// quarter hour with a Markdown report (report.go) and a short CPU profile every
// half hour (run.go).
//
// Nothing here names an account, an address or a conversation: routes are kept
// by their pattern, queries by the line of code that runs them.
//
// Recording is a clock read and a map update under one mutex, so it can stay
// on in production. It never blocks on disk: files are written by Run, outside
// the lock, from a window already swapped out.
package perf

import (
	"context"
	"math"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// Group is what a timing measures; each has its own table in the report.
type Group uint8

const (
	HTTP        Group = iota // an API route by pattern: time to the first response byte
	Query                    // a database call site: running the query and reading its rows
	TxWait                   // a write transaction's call site: waiting for the writer
	TxHold                   // the same: holding the writer, from begin to commit
	GwOp                     // a gateway op a client sent, handled
	GwEmit                   // a gateway event fanned out (Bytes: connections it went to)
	GwWrite                  // one frame written to one socket, by event (Bytes: its size)
	GwRTT                    // a WebSocket ping answered, by client platform: the network round trip
	GwClose                  // a gateway socket closed, by reason (a count)
	Runtime                  // the Go scheduler's and GC's own latencies
	ClientAPI                // an API request as the apps timed it, by platform and route: network included
	Client                   // a stage the apps timed (opening a conversation, decrypting, a stalled frame), by platform
	ClientClass              // the same split by device tier and connection
	nGroups
)

var groupNames = [nGroups]string{"http", "db", "tx_wait", "tx_hold", "gw_op", "gw_emit", "gw_write", "gw_rtt", "gw_close", "runtime",
	"client_api", "client", "client_class"}

func (g Group) String() string { return groupNames[g] }

// bounds are the upper edges of the histogram buckets in milliseconds; the
// last bucket holds everything slower.
var bounds = [...]float64{0.1, 0.25, 0.5, 1, 2, 3, 5, 7.5, 10, 15, 20, 30, 50, 75, 100, 150, 200, 300, 500, 750,
	1000, 1500, 2000, 3000, 5000, 7500, 10000, 15000, 30000, 60000}

const nBuckets = len(bounds) + 1

func bucketOf(ms float64) int {
	lo, hi := 0, len(bounds)
	for lo < hi {
		m := (lo + hi) / 2
		if ms <= bounds[m] {
			hi = m
		} else {
			lo = m + 1
		}
	}
	return lo
}

// maxNames caps the distinct names one group can hold in a window; past it the
// rest share "(other)", so nothing unexpected can grow the table without end.
const maxNames = 1500

// maxSlow is how many of the slowest requests each window remembers.
const maxSlow = 25

type key struct {
	g    Group
	name string
	pc   uintptr // a database call site, named when the window is written
}

type acc struct {
	n, errs  int64
	sum, max time.Duration
	h        [nBuckets]int64
	db       time.Duration
	q        int64
	bytes    int64
	in       int64
	note     string
}

func (a *acc) add(d time.Duration) {
	a.n++
	a.sum += d
	if d > a.max {
		a.max = d
	}
	a.h[bucketOf(float64(d)/float64(time.Millisecond))]++
}

// Sample is one of a window's slowest requests.
type Sample struct {
	At      time.Time `json:"at"`
	Route   string    `json:"route"`
	Status  int       `json:"status"`
	Ms      float64   `json:"ms"`
	DBMs    float64   `json:"db_ms,omitempty"`
	Queries int64     `json:"queries,omitempty"`
}

type window struct {
	start  time.Time
	stats  map[key]*acc
	counts [nGroups]int
	slow   []Sample
}

func newWindow(now time.Time) *window { return &window{start: now, stats: make(map[key]*acc, 512)} }

var (
	mu  sync.Mutex
	cur = newWindow(time.Now())
)

// get finds or makes the accumulator for k; mu must be held.
func (w *window) get(k key) *acc {
	if a := w.stats[k]; a != nil {
		return a
	}
	if w.counts[k.g] >= maxNames {
		k = key{g: k.g, name: "(other)"}
		if a := w.stats[k]; a != nil {
			return a
		}
	}
	a := &acc{}
	w.stats[k] = a
	w.counts[k.g]++
	return a
}

// Observe records one timing under a group and name.
func Observe(g Group, name string, d time.Duration) { ObserveN(g, name, d, 0) }

// ObserveN is Observe with an amount beside it: bytes written, connections
// reached.
func ObserveN(g Group, name string, d time.Duration, n int64) {
	mu.Lock()
	a := cur.get(key{g: g, name: name})
	a.add(d)
	a.bytes += n
	mu.Unlock()
}

// Count records that something happened, with no time attached.
func Count(g Group, name string) {
	mu.Lock()
	cur.get(key{g: g, name: name}).n++
	mu.Unlock()
}

// Request gathers the database time spent on behalf of one HTTP request, so a
// slow route shows whether the database is why. It is carried in the context.
type Request struct {
	db atomic.Int64
	q  atomic.Int64
}

type ctxKey struct{}

// Begin returns a context that attributes database work to a new Request.
func Begin(ctx context.Context) (context.Context, *Request) {
	r := &Request{}
	return context.WithValue(ctx, ctxKey{}, r), r
}

func requestOf(ctx context.Context) *Request {
	if ctx == nil {
		return nil
	}
	r, _ := ctx.Value(ctxKey{}).(*Request)
	return r
}

// Route records one finished API request. ttfb is the time until the first
// byte of the answer, which leaves out how long the client takes to download
// it; in and out are the body sizes.
func Route(pattern string, status int, ttfb time.Duration, in, out int64, r *Request) {
	var db time.Duration
	var q int64
	if r != nil {
		db, q = time.Duration(r.db.Load()), r.q.Load()
	}
	now := time.Now()
	mu.Lock()
	a := cur.get(key{g: HTTP, name: pattern})
	a.add(ttfb)
	if status >= 500 {
		a.errs++
	}
	a.db += db
	a.q += q
	a.bytes += out
	if in > 0 {
		a.in += in
	}
	if s := cur.slow; len(s) < maxSlow || ttfb > time.Duration(s[len(s)-1].Ms*float64(time.Millisecond)) {
		cur.slow = insertSlow(s, Sample{At: now, Route: pattern, Status: status, Ms: ms(ttfb), DBMs: ms(db), Queries: q})
	}
	mu.Unlock()
}

// insertSlow keeps s sorted slowest first and at most maxSlow long.
func insertSlow(s []Sample, x Sample) []Sample {
	i := len(s)
	for i > 0 && s[i-1].Ms < x.Ms {
		i--
	}
	s = append(s, Sample{})
	copy(s[i+1:], s[i:])
	s[i] = x
	if len(s) > maxSlow {
		s = s[:maxSlow]
	}
	return s
}

// Site is a call site on the stack: skip 0 is the line that called Site, 1 the
// line that called that function, and so on. The database layer asks for 1, the
// line in the store that ran the query.
func Site(skip int) uintptr {
	var pcs [1]uintptr
	if runtime.Callers(skip+2, pcs[:]) == 0 {
		return 0
	}
	return pcs[0]
}

// DB records database work at a call site: a query (Query) or a transaction's
// wait and hold (TxWait, TxHold). The SQL is kept once per site, as an example
// of what runs there.
func DB(ctx context.Context, g Group, site uintptr, sql string, d time.Duration, failed bool) {
	if r := requestOf(ctx); r != nil {
		r.db.Add(int64(d))
		if g == Query {
			r.q.Add(1)
		}
	}
	mu.Lock()
	a := cur.get(key{g: g, pc: site})
	a.add(d)
	if failed {
		a.errs++
	}
	if a.note == "" && sql != "" {
		a.note = sql
	}
	mu.Unlock()
}

// Merge adds timings an app measured and summed itself: h counts per bucket of
// the shared bounds (the apps use the same edges), sum and max in milliseconds.
// The caller has checked the numbers.
func Merge(g Group, name string, h []int64, errs int64, sumMs, maxMs float64) {
	mu.Lock()
	a := cur.get(key{g: g, name: name})
	for i, v := range h {
		if i < nBuckets {
			a.h[i] += v
			a.n += v
		}
	}
	a.errs += errs
	a.sum += time.Duration(sumMs * float64(time.Millisecond))
	if m := time.Duration(maxMs * float64(time.Millisecond)); m > a.max {
		a.max = m
	}
	mu.Unlock()
}

// Buckets is how many histogram buckets there are, for checking what apps send.
const Buckets = nBuckets

// swap takes the current window and starts a fresh one.
func swap(now time.Time) *window {
	mu.Lock()
	w := cur
	cur = newWindow(now)
	mu.Unlock()
	return w
}

func ms(d time.Duration) float64 { return math.Round(float64(d)/float64(time.Microsecond)) / 1000 }

// Platform names the kind of app a User-Agent belongs to, for the report's
// splits: android, desktop, web-mobile, web or other (bots, scripts).
func Platform(ua string) string {
	switch {
	case strings.HasPrefix(ua, "NexaAndroid/"):
		return "android"
	case strings.Contains(ua, "Electron/"):
		return "desktop"
	case !strings.HasPrefix(ua, "Mozilla/"):
		return "other"
	case strings.Contains(ua, "Mobi") || strings.Contains(ua, "Android") || strings.Contains(ua, "iPad"):
		return "web-mobile"
	default:
		return "web"
	}
}

// Snapshot is what has been measured since the last window was written, as it
// would be written; for tests.
func Snapshot() *Window {
	mu.Lock()
	defer mu.Unlock()
	return cur.export(time.Now())
}
