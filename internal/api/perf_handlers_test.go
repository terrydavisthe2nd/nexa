package api

import (
	"bytes"
	"encoding/json"
	"net/http"
	"strings"
	"testing"

	"evilion.xyz/chat/internal/perf"
)

// An app's performance report is folded into everyone's totals by route
// pattern and stage only: nothing naming the account, a conversation or an id
// survives, and what is not a known stage or route is dropped.
func TestPerfReportKeepsOnlyPatternsAndStages(t *testing.T) {
	_, ts := newTestServer(t)
	tok, uid := register(t, ts, "perfprobe")
	report := map[string]any{
		"tier": "low", "net": "cellular",
		"stages": []map[string]any{
			{"name": "open_conversation", "e": 0, "sum": 900, "max": 600, "h": []int{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1}},
			{"name": "perfprobe secret", "e": 0, "sum": 1, "max": 1, "h": []int{1}}, // not a stage
			{"name": "decrypt", "e": 0, "sum": 1, "max": 1, "h": []int{-5, 6}},      // negative counts
			{"name": "startup", "e": 0, "sum": 1, "max": 1, "h": make([]int, 40)},   // too many buckets
		},
		"api": []map[string]any{
			{"method": "GET", "path": "/api/v1/channels/{id}/messages", "e": 1, "sum": 40, "max": 30, "h": []int{0, 0, 0, 0, 0, 0, 0, 0, 0, 2}},
			{"method": "GET", "path": "/api/v1/channels/" + uid + "/perfprobe-not-a-route", "e": 0, "sum": 1, "max": 1, "h": []int{1}},
			{"method": "TRACE", "path": "/api/v1/users/@me", "e": 0, "sum": 1, "max": 1, "h": []int{1}},
			{"method": "GET", "path": "/media/avatar/" + uid, "e": 0, "sum": 1, "max": 1, "h": []int{1}},
		},
	}
	b, _ := json.Marshal(report)
	req, _ := http.NewRequest("POST", ts.URL+"/api/v1/perf", bytes.NewReader(b))
	req.Header.Set("Authorization", "Bearer "+tok)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", "NexaAndroid/9.9 (Linux; Android 15; Pixel 7)")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != http.StatusNoContent {
		t.Fatalf("status %d", res.StatusCode)
	}

	w := perf.Snapshot()
	if s := w.Groups["client"]["open_conversation · android"]; s == nil || s.N < 2 {
		t.Fatalf("stage not kept: %+v", s)
	}
	if s := w.Groups["client_class"]["open_conversation · android · low · cellular"]; s == nil {
		t.Fatal("stage not kept by device class")
	}
	if s := w.Groups["client_api"]["android GET /api/v1/channels/{id}/messages"]; s == nil || s.Errs < 1 {
		t.Fatalf("route not kept by pattern: %+v", s)
	}
	for g, names := range w.Groups {
		for name := range names {
			if strings.Contains(name, uid) || strings.Contains(name, "perfprobe") || strings.Contains(name, "TRACE") ||
				(strings.HasPrefix(g, "client") && (strings.Contains(name, "decrypt") || strings.Contains(name, "startup") || strings.Contains(name, "/media/"))) {
				t.Errorf("%s kept %q", g, name)
			}
		}
	}
}
