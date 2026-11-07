package perf

import (
	"fmt"
	"math"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// writeReport rebuilds perf/report.md from the windows on disk: the last
// quarter hour, the last 24 hours with an hourly trend, and the last 7 days.
func writeReport(dir string, now time.Time) error {
	var recent []*Window // the last 24 hours
	for _, day := range []string{dayOf(now.AddDate(0, 0, -1)), dayOf(now)} {
		for _, w := range readWindows(dir, day) {
			if now.Sub(w.End) <= 24*time.Hour {
				recent = append(recent, w)
			}
		}
	}
	if len(recent) == 0 {
		return nil
	}
	sort.Slice(recent, func(i, j int) bool { return recent[i].End.Before(recent[j].End) })
	day := mergeWindows(recent)
	last := recent[len(recent)-1]
	week := []*Window{mergeWindows(readWindows(dir, dayOf(now)))}
	for i := 1; i < 7; i++ {
		if w := dayRollup(dir, dayOf(now.AddDate(0, 0, -i))); w != nil {
			week = append(week, w)
		}
	}
	weekly := mergeWindows(week)

	var b strings.Builder
	p := func(format string, a ...any) { fmt.Fprintf(&b, format, a...) }
	p("# Nexa performance report\n\n")
	p("Written %s by chatd's always-on profiler (`internal/perf`). Server numbers cover every request, query and socket; "+
		"app numbers come from everyone who leaves Settings, Privacy, Send performance reports on. Nothing here "+
		"names an account or an address.\n\n", now.UTC().Format("2006-01-02 15:04 MST"))
	p("How to read it: times are milliseconds. HTTP time is until the first byte of the answer, so downloads do not count. " +
		"`db` time is inside the HTTP time, not beside it. `total s` is count times mean: what to fix first. p95 and p99 are " +
		"estimated from histogram buckets. Raw quarter-hour windows are in `windows/<date>.jsonl` (14 days), whole days in " +
		"`days/` (90 days), CPU profiles in `cpu/` (`go tool pprof -top -nodecount=40 bin/chatd data/perf/cpu/*.pprof` merges " +
		"them) and the latest heap in `heap.pprof`.\n\n")

	findings(&b, day, recent)

	p("## Server now\n\n")
	rt := last.Runtime
	p("At %s: %d goroutines, %.0f MB heap (%.0f MB from the OS), %.1f%% of a core over the last window (%.1f%% collecting garbage, %d collections).",
		last.End.UTC().Format("15:04"), rt.Goroutines, rt.HeapMB, rt.SysMB, rt.CPUPercent, rt.GCPercent, rt.GCs)
	for _, n := range sortedKeys(rt.Gauges) {
		p(" %s: %.0f.", n, rt.Gauges[n])
	}
	p("\n\n")

	p("## Hour by hour (last 24 hours, UTC)\n\n")
	trend(&b, recent)

	p("## Where the time goes (last 24 hours)\n\n")
	p("Everything server-side and in the apps, ranked by total time. Database rows are also inside their routes.\n\n")
	overall(&b, day)

	p("## API routes on the server (last 24 hours)\n\n")
	routeTable(&b, day.Groups[HTTP.String()], 50, byTotal)
	p("### Slowest routes by p95 (at least 20 requests)\n\n")
	routeTable(&b, filterMin(day.Groups[HTTP.String()], 20), 20, byP95)

	p("## API routes as the apps see them (last 24 hours)\n\n")
	p("The whole round trip from the app: the server's time plus Cloudflare, the network and the device. `server p50` is the same route's " +
		"server-side p50; the gap is what the trip adds.\n\n")
	clientRoutes(&b, day)

	p("## App stages (last 24 hours)\n\n")
	p("What people wait for on their device. `startup` is opening the app until it is ready; `open_conversation` is choosing a " +
		"conversation until its messages are on screen; `decrypt` is opening one batch of messages; `long_task` is the page's main " +
		"thread blocked for 50 ms or more (web); `slow_frame` is a frame over 32 ms (Android, `e` is frames drawn); `interaction` is " +
		"a tap or key until the screen answered (web, 100 ms and over); `gateway_connect` is the socket opening until ready; " +
		"`gateway_rtt` is a heartbeat answered as the app saw it.\n\n")
	statTable(&b, day.Groups[Client.String()], 60)
	p("### By device tier and connection\n\n")
	p("Tier comes from the device's cores and memory (low, mid, high); connection is what the device reports.\n\n")
	statTable(&b, day.Groups[ClientClass.String()], 80)

	p("## Database call sites (last 24 hours)\n\n")
	sqlTable(&b, day.Groups[Query.String()], 40)
	p("## Waiting for the database writer (last 24 hours)\n\n")
	p("SQLite has one writer: `tx_wait` is how long a transaction queued for it, `tx_hold` how long it kept it.\n\n")
	statTable(&b, day.Groups[TxWait.String()], 20)
	statTable(&b, day.Groups[TxHold.String()], 20)

	p("## Gateway (last 24 hours)\n\n")
	p("### Network round trip by platform\n\nWebSocket pings answered, timed by the server, for every connected app.\n\n")
	statTable(&b, day.Groups[GwRTT.String()], 10)
	p("### Client ops handled\n\n")
	statTable(&b, day.Groups[GwOp.String()], 20)
	p("### Events fanned out (`bytes` is connections reached)\n\n")
	statTable(&b, day.Groups[GwEmit.String()], 30)
	p("### Frames written to sockets (`bytes` is bytes sent)\n\n")
	statTable(&b, day.Groups[GwWrite.String()], 30)
	p("### Sockets closed, by reason\n\n")
	countTable(&b, day.Groups[GwClose.String()])
	p("### Go runtime\n\n")
	statTable(&b, day.Groups[Runtime.String()], 5)

	p("## Slowest single requests (last 24 hours)\n\n")
	if len(day.Slow) == 0 {
		p("None.\n\n")
	} else {
		p("| when (UTC) | route | status | ms | db ms | queries |\n|---|---|---|---|---|---|\n")
		for _, s := range day.Slow {
			p("| %s | `%s` | %d | %s | %s | %d |\n", s.At.UTC().Format("01-02 15:04:05"), s.Route, s.Status, f(s.Ms), f(s.DBMs), s.Queries)
		}
		p("\n")
	}

	p("## The last quarter hour (%s to %s UTC)\n\n", last.Start.UTC().Format("15:04"), last.End.UTC().Format("15:04"))
	routeTable(&b, last.Groups[HTTP.String()], 15, byTotal)
	sqlTable(&b, last.Groups[Query.String()], 10)

	p("## The last 7 days\n\n")
	routeTable(&b, weekly.Groups[HTTP.String()], 25, byTotal)
	clientRoutes(&b, weekly)
	statTable(&b, weekly.Groups[Client.String()], 40)
	sqlTable(&b, weekly.Groups[Query.String()], 25)
	statTable(&b, weekly.Groups[GwRTT.String()], 10)

	return writeAtomic(filepath.Join(dir, "report.md"), []byte(b.String()))
}

// findings names what looks wrong, so the report starts with what to fix.
func findings(b *strings.Builder, day *Window, recent []*Window) {
	var out []string
	add := func(format string, a ...any) { out = append(out, fmt.Sprintf(format, a...)) }
	for name, s := range day.Groups[HTTP.String()] {
		if s.N < 20 {
			continue
		}
		q := float64(s.Q) / float64(s.N)
		// many queries matter once they add up to real time
		if q > 15 && s.DB/float64(s.N) > 3 {
			add("`%s` runs %.0f queries per request (an N+1 loop?), %s ms of database per request.", name, q, f(s.DB/float64(s.N)))
		}
		if s.Pct(95) > 300 && s.DB > 0.7*s.Sum {
			add("`%s` is database bound: p95 %s ms, %.0f%% of its time in queries.", name, f(s.Pct(95)), 100*s.DB/s.Sum)
		} else if s.Pct(95) > 500 {
			add("`%s` is slow: p95 %s ms over %d requests, only %.0f%% of it in the database.", name, f(s.Pct(95)), s.N, pctOf(s.DB, s.Sum))
		}
		if s.Errs*100 > s.N {
			add("`%s` failed with a 5xx on %d of %d requests.", name, s.Errs, s.N)
		}
		if s.In > 0 && s.Sum > 0 && s.In/s.N > 256<<10 {
			add("`%s` takes %s KB bodies on average, so its time includes the upload itself.", name, f(float64(s.In/s.N)/1024))
		}
	}
	for name, s := range day.Groups[Query.String()] {
		if s.N >= 20 && s.Pct(95) > 50 {
			add("Query `%s` p95 is %s ms over %d runs: `%s`", name, f(s.Pct(95)), s.N, s.SQL)
		}
	}
	for name, s := range day.Groups[TxWait.String()] {
		if s.N >= 20 && s.Pct(95) > 25 {
			add("Writes from `%s` queued %s ms (p95) for the writer: something holds it too long (see tx_hold).", name, f(s.Pct(95)))
		}
	}
	for name, s := range day.Groups[TxHold.String()] {
		if s.N >= 5 && s.Pct(95) > 100 {
			add("`%s` holds the writer %s ms (p95), stalling every other write meanwhile.", name, f(s.Pct(95)))
		}
	}
	for name, s := range day.Groups[GwRTT.String()] {
		if s.N >= 20 && s.Pct(95) > 600 {
			add("Network round trips for %s are slow: p50 %s ms, p95 %s ms.", name, f(s.Pct(50)), f(s.Pct(95)))
		}
	}
	for name, s := range day.Groups[GwEmit.String()] {
		if s.N >= 20 && s.Pct(95) > 50 {
			add("Fanning out `%s` takes %s ms (p95) to reach %.0f connections on average.", name, f(s.Pct(95)), float64(s.Bytes)/float64(s.N))
		}
	}
	if s := day.Groups[GwClose.String()]["slow consumer"]; s != nil && s.N > 10 {
		add("%d sockets were closed as slow consumers: events piled up faster than those apps read them.", s.N)
	}
	if s := day.Groups[Runtime.String()]["scheduler wait"]; s != nil && s.Pct(99) > 10 {
		add("Goroutines waited %s ms (p99) to be scheduled: the server is short of CPU at times.", f(s.Pct(99)))
	}
	for name, s := range day.Groups[Client.String()] {
		if s.N < 20 {
			continue
		}
		switch {
		case strings.HasPrefix(name, "open_conversation") && s.Pct(75) > 800:
			add("Opening a conversation (%s) takes %s ms at p75, %s ms at p95.", name, f(s.Pct(75)), f(s.Pct(95)))
		case strings.HasPrefix(name, "startup") && s.Pct(75) > 4000:
			add("Starting the app (%s) takes %s ms at p75.", name, f(s.Pct(75)))
		case strings.HasPrefix(name, "decrypt") && s.Pct(95) > 200:
			add("Decrypting a batch of messages (%s) takes %s ms at p95.", name, f(s.Pct(95)))
		case strings.HasPrefix(name, "long_task") && s.Pct(95) > 300:
			add("The web page's main thread blocks for %s ms at p95 (%s, %d times).", f(s.Pct(95)), name, s.N)
		case strings.HasPrefix(name, "interaction") && s.Pct(75) > 300:
			add("Taps and keys take %s ms to answer at p75 (%s).", f(s.Pct(75)), name)
		case strings.HasPrefix(name, "slow_frame") && s.Errs > 0 && s.N*20 > s.Errs:
			add("%.1f%% of frames on %s miss 32 ms.", 100*float64(s.N)/float64(s.Errs), name)
		}
	}
	if len(recent) >= 8 {
		first, last := recent[0].Runtime, recent[len(recent)-1].Runtime
		if last.Goroutines > 1000 && last.Goroutines > 2*first.Goroutines {
			add("Goroutines grew from %d to %d over the day; check for a leak (a goroutine profile, or the heap).", first.Goroutines, last.Goroutines)
		}
		if last.HeapMB > 200 && last.HeapMB > 2*first.HeapMB {
			add("The heap grew from %.0f MB to %.0f MB over the day; compare `heap.pprof` over time.", first.HeapMB, last.HeapMB)
		}
	}
	fmt.Fprintf(b, "## Findings (last 24 hours)\n\n")
	if len(out) == 0 {
		b.WriteString("Nothing past the thresholds. The tables below still rank where the time goes.\n\n")
		return
	}
	sort.Strings(out)
	for _, line := range out {
		fmt.Fprintf(b, "- %s\n", line)
	}
	b.WriteString("\n")
}

func trend(b *strings.Builder, recent []*Window) {
	hours := map[time.Time][]*Window{}
	var order []time.Time
	for _, w := range recent {
		h := w.Start.UTC().Truncate(time.Hour)
		if hours[h] == nil {
			order = append(order, h)
		}
		hours[h] = append(hours[h], w)
	}
	sort.Slice(order, func(i, j int) bool { return order[i].Before(order[j]) })
	fmt.Fprintf(b, "| hour | requests | http p50 | http p95 | http p99 | 5xx | db s | rtt p50 | rtt p95 | cpu %% | heap MB | goroutines | sched p99 |\n")
	b.WriteString("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n")
	for _, h := range order {
		m := mergeWindows(hours[h])
		all := &Stat{}
		for _, s := range m.Groups[HTTP.String()] {
			all.merge(s)
		}
		rtt := &Stat{}
		for _, s := range m.Groups[GwRTT.String()] {
			rtt.merge(s)
		}
		var db float64
		for _, s := range m.Groups[Query.String()] {
			db += s.Sum
		}
		var cpu float64
		for _, w := range hours[h] {
			cpu += w.Runtime.CPUPercent
		}
		sched := m.Groups[Runtime.String()]["scheduler wait"]
		sp := 0.0
		if sched != nil {
			sp = sched.Pct(99)
		}
		fmt.Fprintf(b, "| %s | %d | %s | %s | %s | %d | %s | %s | %s | %.1f | %.0f | %d | %s |\n", h.Format("01-02 15h"), all.N,
			f(all.Pct(50)), f(all.Pct(95)), f(all.Pct(99)), all.Errs, f(db/1000), f(rtt.Pct(50)), f(rtt.Pct(95)),
			cpu/float64(len(hours[h])), m.Runtime.HeapMB, m.Runtime.Goroutines, f(sp))
	}
	b.WriteString("\n")
}

func overall(b *strings.Builder, w *Window) {
	type row struct {
		kind, name string
		s          *Stat
	}
	var rows []row
	for _, g := range []Group{HTTP, Query, TxWait, GwOp, GwEmit, ClientAPI, Client} {
		for n, s := range w.Groups[g.String()] {
			if strings.HasPrefix(n, "slow_frame") || strings.HasPrefix(n, "gateway_rtt") {
				continue // a count of frames, and a measurement rather than a wait
			}
			rows = append(rows, row{g.String(), n, s})
		}
	}
	sort.Slice(rows, func(i, j int) bool { return rows[i].s.Sum > rows[j].s.Sum })
	if len(rows) > 25 {
		rows = rows[:25]
	}
	b.WriteString("| kind | name | count | mean | p95 | total s |\n|---|---|---|---|---|---|\n")
	for _, r := range rows {
		fmt.Fprintf(b, "| %s | `%s` | %d | %s | %s | %s |\n", r.kind, r.name, r.s.N, f(r.s.Mean()), f(r.s.Pct(95)), f(r.s.Sum/1000))
	}
	b.WriteString("\n")
}

type order int

const (
	byTotal order = iota
	byP95
)

func routeTable(b *strings.Builder, g map[string]*Stat, limit int, by order) {
	if len(g) == 0 {
		b.WriteString("No requests.\n\n")
		return
	}
	names := sortStats(g, by)
	if len(names) > limit {
		names = names[:limit]
	}
	b.WriteString("| route | n | mean | p50 | p95 | p99 | max | total s | 5xx | db ms/req | queries/req | KB out/req |\n")
	b.WriteString("|---|---|---|---|---|---|---|---|---|---|---|---|\n")
	for _, n := range names {
		s := g[n]
		fmt.Fprintf(b, "| `%s` | %d | %s | %s | %s | %s | %s | %s | %d | %s | %s | %s |\n", n, s.N, f(s.Mean()), f(s.Pct(50)), f(s.Pct(95)),
			f(s.Pct(99)), f(s.Max), f(s.Sum/1000), s.Errs, f(s.DB/float64(s.N)), f(float64(s.Q)/float64(s.N)), f(float64(s.Bytes)/float64(s.N)/1024))
	}
	b.WriteString("\n")
}

func clientRoutes(b *strings.Builder, w *Window) {
	g := w.Groups[ClientAPI.String()]
	if len(g) == 0 {
		b.WriteString("No app reports.\n\n")
		return
	}
	names := sortStats(g, byTotal)
	if len(names) > 40 {
		names = names[:40]
	}
	server := w.Groups[HTTP.String()]
	b.WriteString("| platform and route | n | p50 | p95 | p99 | failed | total s | server p50 | added p50 |\n|---|---|---|---|---|---|---|---|---|\n")
	for _, n := range names {
		s := g[n]
		sp := "-"
		added := "-"
		if _, route, ok := strings.Cut(n, " "); ok {
			if ss := server[route]; ss != nil {
				sp = f(ss.Pct(50))
				added = f(s.Pct(50) - ss.Pct(50))
			}
		}
		fmt.Fprintf(b, "| `%s` | %d | %s | %s | %s | %d | %s | %s | %s |\n", n, s.N, f(s.Pct(50)), f(s.Pct(95)), f(s.Pct(99)), s.Errs, f(s.Sum/1000), sp, added)
	}
	b.WriteString("\n")
}

func statTable(b *strings.Builder, g map[string]*Stat, limit int) {
	if len(g) == 0 {
		b.WriteString("Nothing measured.\n\n")
		return
	}
	names := sortStats(g, byTotal)
	if len(names) > limit {
		names = names[:limit]
	}
	b.WriteString("| name | n | mean | p50 | p75 | p95 | p99 | max | total s | e | bytes/n |\n|---|---|---|---|---|---|---|---|---|---|---|\n")
	for _, n := range names {
		s := g[n]
		per := "-"
		if s.Bytes > 0 && s.N > 0 {
			per = f(float64(s.Bytes) / float64(s.N))
		}
		fmt.Fprintf(b, "| `%s` | %d | %s | %s | %s | %s | %s | %s | %s | %d | %s |\n", n, s.N, f(s.Mean()), f(s.Pct(50)), f(s.Pct(75)),
			f(s.Pct(95)), f(s.Pct(99)), f(s.Max), f(s.Sum/1000), s.Errs, per)
	}
	b.WriteString("\n")
}

func sqlTable(b *strings.Builder, g map[string]*Stat, limit int) {
	if len(g) == 0 {
		b.WriteString("No queries.\n\n")
		return
	}
	names := sortStats(g, byTotal)
	if len(names) > limit {
		names = names[:limit]
	}
	b.WriteString("| call site | n | mean | p95 | max | total s | errors | query |\n|---|---|---|---|---|---|---|---|\n")
	for _, n := range names {
		s := g[n]
		fmt.Fprintf(b, "| `%s` | %d | %s | %s | %s | %s | %d | `%s` |\n", n, s.N, f(s.Mean()), f(s.Pct(95)), f(s.Max), f(s.Sum/1000), s.Errs,
			strings.ReplaceAll(s.SQL, "|", "\\|"))
	}
	b.WriteString("\n")
}

func countTable(b *strings.Builder, g map[string]*Stat) {
	if len(g) == 0 {
		b.WriteString("None.\n\n")
		return
	}
	names := sortStats(g, byTotal)
	sort.SliceStable(names, func(i, j int) bool { return g[names[i]].N > g[names[j]].N })
	b.WriteString("| reason | count |\n|---|---|\n")
	for _, n := range names {
		fmt.Fprintf(b, "| %s | %d |\n", n, g[n].N)
	}
	b.WriteString("\n")
}

func sortStats(g map[string]*Stat, by order) []string {
	names := sortedKeys(g)
	sort.SliceStable(names, func(i, j int) bool {
		a, c := g[names[i]], g[names[j]]
		if by == byP95 {
			return a.Pct(95) > c.Pct(95)
		}
		return a.Sum > c.Sum
	})
	return names
}

func filterMin(g map[string]*Stat, n int64) map[string]*Stat {
	out := map[string]*Stat{}
	for k, s := range g {
		if s.N >= n {
			out[k] = s
		}
	}
	return out
}

func sortedKeys[V any](m map[string]V) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

func pctOf(a, b float64) float64 {
	if b == 0 {
		return 0
	}
	return 100 * a / b
}

// f writes a number short: two decimals under 10, one under 100, none above.
func f(v float64) string {
	if math.IsNaN(v) || math.IsInf(v, 0) {
		return "-"
	}
	switch a := math.Abs(v); {
	case a < 10:
		return fmt.Sprintf("%.2f", v)
	case a < 100:
		return fmt.Sprintf("%.1f", v)
	default:
		return fmt.Sprintf("%.0f", v)
	}
}
