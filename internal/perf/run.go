package perf

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"log/slog"
	"os"
	"path/filepath"
	"runtime/pprof"
	"sort"
	"strings"
	"time"
)

const (
	windowEvery = 15 * time.Minute
	cpuEvery    = 30 * time.Minute
	cpuFor      = 10 * time.Second
	keepWindows = 14 * 24 * time.Hour // perf/windows/<date>.jsonl
	keepDays    = 90 * 24 * time.Hour // perf/days/<date>.json
	keepCPU     = 48                  // perf/cpu/*.pprof, a day of them
)

// Run writes a window every quarter hour (and the one in progress when ctx
// ends), rebuilds report.md after each, and takes a ten second CPU profile
// every half hour. It returns once the last window is written.
func Run(ctx context.Context, dir string, log *slog.Logger) {
	for _, d := range []string{"windows", "days", "cpu"} {
		if err := os.MkdirAll(filepath.Join(dir, d), 0o750); err != nil {
			log.Warn("perf: cannot make its folder; profiling stays in memory only", "err", err)
			return
		}
	}
	// the first window also holds what ran before this (startup, migrations)
	rt := newRuntimeReader()
	flush := func(now time.Time) {
		w := swap(now).export(now)
		w.Runtime = rt.read(now, w)
		if err := appendWindow(dir, w); err != nil {
			log.Warn("perf: write window", "err", err)
			return
		}
		if err := writeReport(dir, now); err != nil {
			log.Warn("perf: write report", "err", err)
		}
		if err := writeHeap(dir); err != nil {
			log.Warn("perf: heap profile", "err", err)
		}
		prune(dir, now)
	}
	next := time.Now().Truncate(windowEvery).Add(windowEvery)
	nextCPU := time.Now().Add(5 * time.Minute) // not in the busy minute after a start
	timer := time.NewTimer(time.Until(next))
	cpuTimer := time.NewTimer(time.Until(nextCPU))
	defer timer.Stop()
	defer cpuTimer.Stop()
	for {
		select {
		case <-ctx.Done():
			flush(time.Now())
			return
		case <-timer.C:
			flush(time.Now())
			next = next.Add(windowEvery)
			for !next.After(time.Now()) {
				next = next.Add(windowEvery)
			}
			timer.Reset(time.Until(next))
		case <-cpuTimer.C:
			if err := captureCPU(ctx, dir); err != nil {
				log.Warn("perf: cpu profile", "err", err)
			}
			cpuTimer.Reset(cpuEvery)
		}
	}
}

func dayOf(t time.Time) string { return t.UTC().Format("2006-01-02") }

func appendWindow(dir string, w *Window) error {
	b, err := json.Marshal(w)
	if err != nil {
		return err
	}
	f, err := os.OpenFile(filepath.Join(dir, "windows", dayOf(w.Start)+".jsonl"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o640)
	if err != nil {
		return err
	}
	_, err = f.Write(append(b, '\n'))
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	return err
}

// readWindows reads one day's windows; a missing file is an empty day, and a
// damaged line (a crash mid-write) is skipped.
func readWindows(dir, day string) []*Window {
	f, err := os.Open(filepath.Join(dir, "windows", day+".jsonl"))
	if err != nil {
		return nil
	}
	defer f.Close()
	var out []*Window
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 1<<20), 64<<20)
	for sc.Scan() {
		w := &Window{}
		if json.Unmarshal(sc.Bytes(), w) == nil {
			out = append(out, w)
		}
	}
	return out
}

// dayRollup is a whole past day merged, made once from its windows and kept
// longer than they are.
func dayRollup(dir, day string) *Window {
	path := filepath.Join(dir, "days", day+".json")
	if b, err := os.ReadFile(path); err == nil {
		w := &Window{}
		if json.Unmarshal(b, w) == nil {
			return w
		}
	}
	ws := readWindows(dir, day)
	if len(ws) == 0 {
		return nil
	}
	w := mergeWindows(ws)
	if b, err := json.Marshal(w); err == nil {
		tmp := path + ".tmp"
		if os.WriteFile(tmp, b, 0o640) == nil {
			_ = os.Rename(tmp, path)
		}
	}
	return w
}

// mergeWindows adds windows together: counts and histograms sum, the slowest
// requests stay the slowest, and the runtime is the latest window's.
func mergeWindows(ws []*Window) *Window {
	out := &Window{Groups: map[string]map[string]*Stat{}}
	for _, w := range ws {
		if out.Start.IsZero() || w.Start.Before(out.Start) {
			out.Start = w.Start
		}
		if w.End.After(out.End) {
			out.End = w.End
			out.Runtime = w.Runtime
		}
		for g, names := range w.Groups {
			og := out.Groups[g]
			if og == nil {
				og = map[string]*Stat{}
				out.Groups[g] = og
			}
			for n, s := range names {
				if o := og[n]; o != nil {
					o.merge(s)
				} else {
					c := *s
					c.H = append([]int64(nil), s.H...)
					og[n] = &c
				}
			}
		}
		out.Slow = append(out.Slow, w.Slow...)
	}
	sort.Slice(out.Slow, func(i, j int) bool { return out.Slow[i].Ms > out.Slow[j].Ms })
	if len(out.Slow) > maxSlow {
		out.Slow = out.Slow[:maxSlow]
	}
	return out
}

func captureCPU(ctx context.Context, dir string) error {
	var buf bytes.Buffer
	if err := pprof.StartCPUProfile(&buf); err != nil {
		return err // someone else is profiling; try again next time
	}
	t := time.NewTimer(cpuFor)
	select {
	case <-t.C:
	case <-ctx.Done():
		t.Stop()
	}
	pprof.StopCPUProfile()
	name := filepath.Join(dir, "cpu", time.Now().UTC().Format("2006-01-02T1504")+".pprof")
	return writeAtomic(name, buf.Bytes())
}

func writeHeap(dir string) error {
	var buf bytes.Buffer
	if err := pprof.Lookup("heap").WriteTo(&buf, 0); err != nil {
		return err
	}
	return writeAtomic(filepath.Join(dir, "heap.pprof"), buf.Bytes())
}

func writeAtomic(path string, b []byte) error {
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o640); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// prune drops windows, day rollups and CPU profiles past their keeping.
func prune(dir string, now time.Time) {
	dropOld := func(sub, ext string, keep time.Duration) {
		entries, _ := os.ReadDir(filepath.Join(dir, sub))
		for _, e := range entries {
			day, ok := strings.CutSuffix(e.Name(), ext)
			if !ok {
				continue
			}
			if t, err := time.Parse("2006-01-02", day); err == nil && now.Sub(t) > keep {
				_ = os.Remove(filepath.Join(dir, sub, e.Name()))
			}
		}
	}
	dropOld("windows", ".jsonl", keepWindows)
	dropOld("days", ".json", keepDays)
	entries, _ := os.ReadDir(filepath.Join(dir, "cpu"))
	var profiles []string
	for _, e := range entries {
		if strings.HasSuffix(e.Name(), ".pprof") {
			profiles = append(profiles, e.Name())
		}
	}
	sort.Strings(profiles) // named by time, so oldest first
	for len(profiles) > keepCPU {
		_ = os.Remove(filepath.Join(dir, "cpu", profiles[0]))
		profiles = profiles[1:]
	}
}
