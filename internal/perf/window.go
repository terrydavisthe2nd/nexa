package perf

import (
	"fmt"
	"path/filepath"
	"runtime"
	"runtime/metrics"
	"strings"
	"sync"
	"time"
)

// Stat is one name's numbers in a written window. Times are milliseconds.
type Stat struct {
	N     int64   `json:"n"`
	Errs  int64   `json:"e,omitempty"`
	Sum   float64 `json:"sum,omitempty"`
	Max   float64 `json:"max,omitempty"`
	H     []int64 `json:"h,omitempty"`   // counts per bucket of bounds, trailing empties cut
	DB    float64 `json:"db,omitempty"`  // database time inside (routes)
	Q     int64   `json:"q,omitempty"`   // queries inside (routes)
	Bytes int64   `json:"b,omitempty"`   // bytes out (routes, writes) or connections reached (emits)
	In    int64   `json:"in,omitempty"`  // request body bytes (routes)
	SQL   string  `json:"sql,omitempty"` // an example of what a database call site runs
}

// Window is a quarter hour's worth, as written to perf/windows/<date>.jsonl.
type Window struct {
	Start   time.Time                   `json:"start"`
	End     time.Time                   `json:"end"`
	Groups  map[string]map[string]*Stat `json:"groups"`
	Slow    []Sample                    `json:"slow,omitempty"`
	Runtime RuntimeSnap                 `json:"runtime"`
}

// RuntimeSnap is the process at the end of a window.
type RuntimeSnap struct {
	Goroutines int     `json:"goroutines"`
	HeapMB     float64 `json:"heap_mb"`
	SysMB      float64 `json:"sys_mb"`
	CPUPercent float64 `json:"cpu_pct"`    // of one core, averaged over the window
	GCPercent  float64 `json:"gc_cpu_pct"` // the part of that spent collecting garbage
	GCs        int64   `json:"gcs"`
	// Gauges are counts other packages report, such as open gateway sockets.
	Gauges map[string]float64 `json:"gauges,omitempty"`
}

func (s *Stat) merge(o *Stat) {
	s.N += o.N
	s.Errs += o.Errs
	s.Sum += o.Sum
	s.Max = max(s.Max, o.Max)
	if len(o.H) > len(s.H) {
		s.H = append(s.H, make([]int64, len(o.H)-len(s.H))...)
	}
	for i, v := range o.H {
		s.H[i] += v
	}
	s.DB += o.DB
	s.Q += o.Q
	s.Bytes += o.Bytes
	s.In += o.In
	if s.SQL == "" {
		s.SQL = o.SQL
	}
}

// Mean is the average time in milliseconds.
func (s *Stat) Mean() float64 {
	if s.N == 0 {
		return 0
	}
	return s.Sum / float64(s.N)
}

// Pct estimates the p-th percentile (0 to 100) in milliseconds from the
// histogram, interpolating inside the bucket and never past the maximum.
func (s *Stat) Pct(p float64) float64 {
	var total int64
	for _, v := range s.H {
		total += v
	}
	if total == 0 {
		return 0
	}
	rank := p / 100 * float64(total)
	var seen float64
	for i, v := range s.H {
		if v == 0 {
			continue
		}
		if seen+float64(v) >= rank {
			lo := 0.0
			if i > 0 {
				lo = bounds[i-1]
			}
			hi := s.Max
			if i < len(bounds) {
				hi = min(bounds[i], s.Max)
			}
			if hi < lo {
				hi = lo
			}
			return lo + (hi-lo)*(rank-seen)/float64(v)
		}
		seen += float64(v)
	}
	return s.Max
}

var (
	siteNames sync.Map // uintptr -> string
	modRoot   = moduleRoot()
)

// moduleRoot is the repository's directory as compiled in, cut from file names
// so a site reads internal/store/messages.go:120.
func moduleRoot() string {
	_, file, _, ok := runtime.Caller(0)
	if !ok {
		return ""
	}
	return filepath.Dir(filepath.Dir(filepath.Dir(file))) + "/"
}

func siteName(pc uintptr) string {
	if pc == 0 {
		return "(unknown)"
	}
	if v, ok := siteNames.Load(pc); ok {
		return v.(string)
	}
	fr, _ := runtime.CallersFrames([]uintptr{pc}).Next()
	fn := fr.Function
	if i := strings.LastIndex(fn, "/"); i >= 0 {
		fn = fn[i+1:]
	}
	name := fmt.Sprintf("%s (%s:%d)", fn, strings.TrimPrefix(fr.File, modRoot), fr.Line)
	siteNames.Store(pc, name)
	return name
}

// export turns a swapped-out window into what is written.
func (w *window) export(end time.Time) *Window {
	out := &Window{Start: w.start, End: end, Groups: map[string]map[string]*Stat{}, Slow: w.slow}
	for k, a := range w.stats {
		name := k.name
		if k.pc != 0 {
			name = siteName(k.pc)
		}
		g := out.Groups[k.g.String()]
		if g == nil {
			g = map[string]*Stat{}
			out.Groups[k.g.String()] = g
		}
		st := &Stat{N: a.n, Errs: a.errs, Sum: ms(a.sum), Max: ms(a.max), DB: ms(a.db), Q: a.q, Bytes: a.bytes, In: a.in, SQL: clipSQL(a.note)}
		last := -1
		for i, v := range a.h {
			if v != 0 {
				last = i
			}
		}
		if last >= 0 {
			st.H = append([]int64(nil), a.h[:last+1]...)
		}
		if prev := g[name]; prev != nil {
			prev.merge(st) // two sites that resolve to one name, or "(other)"
		} else {
			g[name] = st
		}
	}
	return out
}

// clipSQL folds a query onto one line and keeps its start.
func clipSQL(q string) string {
	if q == "" {
		return ""
	}
	q = strings.Join(strings.Fields(q), " ")
	if len(q) > 240 {
		q = strings.ToValidUTF8(q[:240], "") + "…"
	}
	return q
}

// ---- the runtime ----

var (
	gaugeMu sync.Mutex
	gauges  = map[string]func() float64{}
)

// Gauge names a number to read at the end of every window.
func Gauge(name string, fn func() float64) {
	gaugeMu.Lock()
	gauges[name] = fn
	gaugeMu.Unlock()
}

// runtimeReader reads runtime/metrics and keeps the last cumulative values, so
// each window gets only what happened during it.
type runtimeReader struct {
	samples []metrics.Sample
	lastCPU float64
	lastGC  float64
	lastGCs int64
	lastAt  time.Time
	lastH   map[string][]uint64
}

const (
	mGoroutines = "/sched/goroutines:goroutines"
	mHeap       = "/memory/classes/heap/objects:bytes"
	mTotal      = "/memory/classes/total:bytes"
	mCPU        = "/cpu/classes/total:cpu-seconds"
	mIdle       = "/cpu/classes/idle:cpu-seconds"
	mGCCPU      = "/cpu/classes/gc/total:cpu-seconds"
	mGCs        = "/gc/cycles/total:gc-cycles"
	mSched      = "/sched/latencies:seconds"
	mPauses     = "/sched/pauses/total/gc:seconds"
)

func newRuntimeReader() *runtimeReader {
	names := []string{mGoroutines, mHeap, mTotal, mCPU, mIdle, mGCCPU, mGCs, mSched, mPauses}
	known := map[string]bool{}
	for _, d := range metrics.All() {
		known[d.Name] = true
	}
	r := &runtimeReader{lastH: map[string][]uint64{}}
	for _, n := range names {
		if known[n] {
			r.samples = append(r.samples, metrics.Sample{Name: n})
		}
	}
	r.read(time.Now(), nil)
	return r
}

// read fills snap and, when into is given, adds the scheduler's and GC's
// latencies since the last read to it as Runtime timings.
func (r *runtimeReader) read(now time.Time, into *Window) RuntimeSnap {
	metrics.Read(r.samples)
	var snap RuntimeSnap
	var cpu, idle, gc float64
	var gcs int64
	for _, s := range r.samples {
		switch s.Value.Kind() {
		case metrics.KindUint64:
			v := s.Value.Uint64()
			switch s.Name {
			case mGoroutines:
				snap.Goroutines = int(v)
			case mHeap:
				snap.HeapMB = float64(v) / (1 << 20)
			case mTotal:
				snap.SysMB = float64(v) / (1 << 20)
			case mGCs:
				gcs = int64(v)
			}
		case metrics.KindFloat64:
			switch s.Name {
			case mCPU:
				cpu = s.Value.Float64()
			case mIdle:
				idle = s.Value.Float64()
			case mGCCPU:
				gc = s.Value.Float64()
			}
		case metrics.KindFloat64Histogram:
			h := s.Value.Float64Histogram()
			prev := r.lastH[s.Name]
			if into != nil {
				name := map[string]string{mSched: "scheduler wait", mPauses: "gc pause"}[s.Name]
				st := &Stat{}
				for i, c := range h.Counts {
					if len(prev) > i {
						c -= prev[i]
					}
					if c == 0 {
						continue
					}
					// a bucket's upper edge, in milliseconds (the last is +Inf)
					edge := h.Buckets[i+1] * 1000
					if edge > 1e9 {
						edge = h.Buckets[i] * 1000
					}
					b := bucketOf(edge)
					if len(st.H) <= b {
						st.H = append(st.H, make([]int64, b+1-len(st.H))...)
					}
					st.H[b] += int64(c)
					st.N += int64(c)
					st.Sum += edge * float64(c)
					st.Max = max(st.Max, edge)
				}
				if st.N > 0 {
					g := into.Groups[Runtime.String()]
					if g == nil {
						g = map[string]*Stat{}
						into.Groups[Runtime.String()] = g
					}
					g[name] = st
				}
			}
			r.lastH[s.Name] = append(r.lastH[s.Name][:0], h.Counts...)
		}
	}
	// the runtime's CPU classes count the time every P was available; what was
	// not idle is what the process used
	if secs := now.Sub(r.lastAt).Seconds(); !r.lastAt.IsZero() && secs > 0 {
		used := (cpu - idle) - r.lastCPU
		snap.CPUPercent = round1(100 * used / secs)
		snap.GCPercent = round1(100 * (gc - r.lastGC) / secs)
	}
	snap.GCs = gcs - r.lastGCs
	snap.HeapMB, snap.SysMB = round1(snap.HeapMB), round1(snap.SysMB)
	r.lastCPU, r.lastGC, r.lastGCs, r.lastAt = cpu-idle, gc, gcs, now
	gaugeMu.Lock()
	if len(gauges) > 0 {
		snap.Gauges = map[string]float64{}
		for n, fn := range gauges {
			snap.Gauges[n] = fn()
		}
	}
	gaugeMu.Unlock()
	return snap
}

func round1(v float64) float64 { return float64(int64(v*10+0.5)) / 10 }
