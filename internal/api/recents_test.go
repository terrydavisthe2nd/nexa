package api

import (
	"encoding/json"
	"net/http/httptest"
	"testing"
	"time"
)

func uploadSticker(t *testing.T, ts *httptest.Server, tok, pack, name string, w, h int) string {
	t.Helper()
	res, raw := rawPost(t, ts.URL+"/api/v1/stickers/packs/"+pack+"/stickers?name="+name, tok, "image/png", pngBytes(w, h))
	if res.StatusCode != 201 {
		t.Fatalf("upload %s: %d %s", name, res.StatusCode, raw)
	}
	var st map[string]any
	if err := json.Unmarshal(raw, &st); err != nil {
		t.Fatal(err)
	}
	return st["id"].(string)
}

func recentIDs(t *testing.T, ts *httptest.Server, tok string) []string {
	t.Helper()
	res, body := do(t, ts, "GET", "/api/v1/stickers/recent", tok, nil)
	if res.StatusCode != 200 {
		t.Fatalf("recents: %d %v", res.StatusCode, body)
	}
	out := []string{}
	for _, s := range body["_list"].([]any) {
		out = append(out, s.(map[string]any)["id"].(string))
	}
	return out
}

func TestRecentStickers(t *testing.T) {
	_, ts := newTestServer(t)
	tokA, _ := register(t, ts, "alice")
	tokB, _ := register(t, ts, "bob")
	ga := connectGW(t, ts, tokA)

	if got := recentIDs(t, ts, tokA); len(got) != 0 {
		t.Fatalf("fresh recents: %v", got)
	}
	res, body := do(t, ts, "POST", "/api/v1/stickers/packs", tokA, map[string]any{"name": "Mine"})
	if res.StatusCode != 201 {
		t.Fatalf("pack: %d %v", res.StatusCode, body)
	}
	pack := body["id"].(string)
	s1 := uploadSticker(t, ts, tokA, pack, "one", 32, 32)
	s2 := uploadSticker(t, ts, tokA, pack, "two", 32, 32)

	// unknown and empty ids are refused
	if res, _ := do(t, ts, "POST", "/api/v1/stickers/recent", tokA, map[string]any{"sticker_id": "1"}); res.StatusCode != 404 {
		t.Errorf("unknown sticker: %d", res.StatusCode)
	}
	if res, _ := do(t, ts, "POST", "/api/v1/stickers/recent", tokA, map[string]any{}); res.StatusCode != 400 {
		t.Errorf("empty sticker: %d", res.StatusCode)
	}

	// touch s1: listed, and the touch is broadcast to the account's devices
	if res, _ := do(t, ts, "POST", "/api/v1/stickers/recent", tokA, map[string]any{"sticker_id": s1}); res.StatusCode != 204 {
		t.Fatalf("touch: %d", res.StatusCode)
	}
	if ev := ga.next("sticker_recent", 3*time.Second); ev == nil || ev["sticker"].(map[string]any)["id"] != s1 {
		t.Fatalf("sticker_recent: %v", ev)
	}
	if got := recentIDs(t, ts, tokA); len(got) != 1 || got[0] != s1 {
		t.Fatalf("after s1: %v", got)
	}
	// touch s2, then s1 again: newest first, re-sends move to the front
	if res, _ := do(t, ts, "POST", "/api/v1/stickers/recent", tokA, map[string]any{"sticker_id": s2}); res.StatusCode != 204 {
		t.Fatalf("touch s2: %d", res.StatusCode)
	}
	if res, _ := do(t, ts, "POST", "/api/v1/stickers/recent", tokA, map[string]any{"sticker_id": s1}); res.StatusCode != 204 {
		t.Fatalf("touch s1 again: %d", res.StatusCode)
	}
	if got := recentIDs(t, ts, tokA); len(got) != 2 || got[0] != s1 || got[1] != s2 {
		t.Fatalf("order: %v", got)
	}
	// recents are per account
	if got := recentIDs(t, ts, tokB); len(got) != 0 {
		t.Fatalf("bob sees alice's: %v", got)
	}

	// past the cap only the newest 24 survive
	ids := []string{s1, s2}
	for i := 0; i < 25; i++ {
		ids = append(ids, uploadSticker(t, ts, tokA, pack, "filler", 8, 8))
	}
	for _, id := range ids {
		if res, _ := do(t, ts, "POST", "/api/v1/stickers/recent", tokA, map[string]any{"sticker_id": id}); res.StatusCode != 204 {
			t.Fatalf("touch %s: %d", id, res.StatusCode)
		}
	}
	if got := recentIDs(t, ts, tokA); len(got) != 24 || got[0] != ids[len(ids)-1] {
		t.Fatalf("cap: %d first %v", len(got), got[:1])
	}

	// clearing empties only this account's row, on all its devices
	if res, _ := do(t, ts, "POST", "/api/v1/stickers/recent", tokB, map[string]any{"sticker_id": s1}); res.StatusCode != 404 {
		t.Fatalf("bob touching a private sticker: %d", res.StatusCode)
	}
	if res, _ := do(t, ts, "DELETE", "/api/v1/stickers/recent", tokA, nil); res.StatusCode != 204 {
		t.Fatalf("clear: %d", res.StatusCode)
	}
	if ev := ga.next("sticker_recent_clear", 3*time.Second); ev == nil {
		t.Fatal("sticker_recent_clear never arrived")
	}
	if got := recentIDs(t, ts, tokA); len(got) != 0 {
		t.Fatalf("after clear: %v", got)
	}
}
