package api

import (
	"net/http/httptest"
	"testing"
	"time"

	"evilion.xyz/chat/internal/objstore"
	"evilion.xyz/chat/internal/objstore/memstore"
)

// The monthly limit refuses an attachment once keeping it would cost more
// than the limit, and only then; Settings takes the limit in whole cents or
// null; and the cost is worked out the way R2 bills it.
func TestStorageLimit(t *testing.T) {
	for _, c := range []struct{ bytes, a, b, cents int64 }{
		{10_000_000_000, 1_000_000, 10_000_000, 0}, // all inside the free tier
		{10_000_000_001, 0, 0, 2},                  // an 11th GB-month, 1.5¢, rounds up
		{110_000_000_000, 0, 0, 150},
		{0, 1_000_001, 10_000_001, 486}, // a started million of each past the free ones
	} {
		if got := storageCostCents(c.bytes, c.a, c.b); got != c.cents {
			t.Errorf("cost of %d bytes, %d A, %d B: %d¢, want %d¢", c.bytes, c.a, c.b, got, c.cents)
		}
	}

	srv, ts := newTestServer(t)
	tok, _ := register(t, ts, "owner")
	srv.Media.Remote = &memstore.Bucket{}
	for _, bad := range []any{-1, 1.5, "500", 10_000_001} {
		if res, body := do(t, ts, "PATCH", "/api/v1/admin/settings", tok, map[string]any{"storage_budget": bad}); res.StatusCode != 400 || errCode(body) != "invalid_value" {
			t.Errorf("limit %v: %d %v", bad, res.StatusCode, body)
		}
	}
	// 15 GB in the bucket costs 8¢ a month
	if err := srv.Store.AddRemoteBlob(t.Context(), "seeded", 15_000_000_000); err != nil {
		t.Fatal(err)
	}
	if res, body := do(t, ts, "PATCH", "/api/v1/admin/settings", tok, map[string]any{"storage_budget": 7}); res.StatusCode != 200 {
		t.Fatalf("set the limit: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "POST", "/api/v1/uploads", tok, map[string]any{"size": 1000}); res.StatusCode != 507 || errCode(body) != "storage_budget" {
		t.Fatalf("over the limit: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "PATCH", "/api/v1/admin/settings", tok, map[string]any{"storage_budget": 900}); res.StatusCode != 200 || body["storage_budget_cents"] != float64(900) {
		t.Fatalf("raise the limit: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "POST", "/api/v1/uploads", tok, map[string]any{"size": 1000}); res.StatusCode != 201 {
		t.Fatalf("under the limit: %d %v", res.StatusCode, body)
	}
	_, stats := do(t, ts, "GET", "/api/v1/admin/stats", tok, nil)
	meter, _ := stats["storage"].(map[string]any)
	// the upload on its way counts: 15 GB and 1000 bytes is a 16th GB-month
	if meter == nil || meter["cost_cents"] != float64(9) || meter["limit_cents"] != float64(900) || meter["held"] != false {
		t.Fatalf("meter: %v", stats["storage"])
	}
	if res, body := do(t, ts, "PATCH", "/api/v1/admin/settings", tok, map[string]any{"storage_budget": nil}); res.StatusCode != 200 || body["storage_budget_cents"] != nil {
		t.Fatalf("take the limit away: %d %v", res.StatusCode, body)
	}

	// Cloudflare's numbers, once read, count what this server could not: the
	// bucket measured at 30 GB, and 12 million reads (links fetched again)
	srv.storage.mu.Lock()
	srv.storage.measured = &objstore.Usage{StoredBytes: 30_000_000_000, ClassB: 12_000_000}
	srv.storage.measuredMonth = storageMonth(time.Now())
	srv.storage.measuredUntil = time.Now().Add(-measureLag)
	srv.storage.mu.Unlock()
	_, stats = do(t, ts, "GET", "/api/v1/admin/stats", tok, nil)
	meter, _ = stats["storage"].(map[string]any)
	if want := storageCostCents(30_000_000_000+1000, 0, 12_000_000); meter == nil || meter["cost_cents"] != float64(want) || meter["measured_at"] == nil {
		t.Fatalf("measured meter: %v, want %d cents", stats["storage"], want)
	}

	// Download links stop once the month's reads cost more than their cap (a
	// fifth of the limit, $10 without one), and only then: 12 million reads
	// cost 72 cents, a $2 cap stops them, a $5 one does not.
	req := httptest.NewRequest("GET", "/media/attachments/1", nil)
	for _, c := range []struct {
		limit any
		on    bool
	}{{nil, true}, {200, false}, {500, true}} {
		if res, body := do(t, ts, "PATCH", "/api/v1/admin/settings", tok, map[string]any{"storage_budget": c.limit}); res.StatusCode != 200 {
			t.Fatalf("limit %v: %d %v", c.limit, res.StatusCode, body)
		}
		srv.noteReads(t.Context())
		if got := srv.LinkOK(req); got != c.on {
			t.Errorf("limit %v: links on %v, want %v", c.limit, got, c.on)
		}
		_, stats = do(t, ts, "GET", "/api/v1/admin/stats", tok, nil)
		links, _ := stats["storage"].(map[string]any)["links"].(map[string]any)
		if links == nil || links["on"] != c.on || links["read_cents"] != float64(72) {
			t.Errorf("limit %v: meter says %v", c.limit, links)
		}
	}
}
