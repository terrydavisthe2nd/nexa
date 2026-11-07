package perf

import (
	"os"
	"regexp"
	"strconv"
	"strings"
	"testing"
)

// The apps sum their timings into the server's buckets and send only the
// counts, so their edges must be these exactly: a mismatch would quietly move
// every app timing into the wrong span.
func TestAppsShareTheBucketEdges(t *testing.T) {
	for _, src := range []struct{ file, pattern string }{
		{"../../web/src/lib/perfReports.ts", `BOUNDS = \[([^\]]*)\]`},
		{"../../android/app/src/main/java/fish/nexa/data/PerfReports.kt", `BOUNDS = doubleArrayOf\(([^)]*)\)`},
	} {
		b, err := os.ReadFile(src.file)
		if err != nil {
			t.Fatal(err)
		}
		m := regexp.MustCompile(src.pattern).FindSubmatch(b)
		if m == nil {
			t.Fatalf("%s: no BOUNDS", src.file)
		}
		var got []float64
		for _, f := range strings.Split(string(m[1]), ",") {
			v, err := strconv.ParseFloat(strings.TrimSpace(f), 64)
			if err != nil {
				t.Fatalf("%s: %q", src.file, f)
			}
			got = append(got, v)
		}
		if len(got) != len(bounds) {
			t.Fatalf("%s has %d edges, the server %d", src.file, len(got), len(bounds))
		}
		for i := range got {
			if got[i] != bounds[i] {
				t.Fatalf("%s edge %d is %v, the server's %v", src.file, i, got[i], bounds[i])
			}
		}
	}
}
