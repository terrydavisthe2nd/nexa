package api

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"evilion.xyz/chat/internal/stripe"
)

const testWebhookSecret = "whsec_test"

// fakeStripe plays the parts of Stripe's API billing.go calls.
type fakeStripe struct {
	mu        sync.Mutex
	subs      map[string]map[string]any
	checkouts []map[string]string
	canceled  []string
}

func newFakeStripe(t *testing.T) (*fakeStripe, *httptest.Server) {
	f := &fakeStripe{subs: map[string]map[string]any{}}
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		defer f.mu.Unlock()
		if v := r.Header.Get("Stripe-Version"); v != stripe.APIVersion {
			t.Errorf("Stripe-Version %q", v)
		}
		_ = r.ParseForm()
		switch {
		case r.Method == "GET" && r.URL.Path == "/v1/prices":
			var data []map[string]any
			for _, k := range r.Form["lookup_keys[]"] {
				amount := 500
				if strings.Contains(k, "pro") {
					amount = 1000
				}
				data = append(data, map[string]any{"id": "price_" + k, "lookup_key": k, "unit_amount": amount, "currency": "usd"})
			}
			json.NewEncoder(w).Encode(map[string]any{"data": data})
		case r.Method == "POST" && r.URL.Path == "/v1/checkout/sessions":
			form := map[string]string{}
			for k := range r.PostForm {
				form[k] = r.PostForm.Get(k)
			}
			f.checkouts = append(f.checkouts, form)
			json.NewEncoder(w).Encode(map[string]any{"url": "https://checkout.stripe.test/c/1"})
		case r.Method == "POST" && r.URL.Path == "/v1/billing_portal/sessions":
			json.NewEncoder(w).Encode(map[string]any{"url": "https://billing.stripe.test/p/" + r.PostForm.Get("customer")})
		case strings.HasPrefix(r.URL.Path, "/v1/subscriptions/"):
			id := strings.TrimPrefix(r.URL.Path, "/v1/subscriptions/")
			sub, ok := f.subs[id]
			if !ok {
				w.WriteHeader(404)
				json.NewEncoder(w).Encode(map[string]any{"error": map[string]any{"code": "resource_missing", "message": "no such subscription"}})
				return
			}
			if r.Method == "DELETE" {
				f.canceled = append(f.canceled, id)
				sub["status"] = "canceled"
			}
			json.NewEncoder(w).Encode(sub)
		default:
			t.Errorf("unexpected Stripe call %s %s", r.Method, r.URL.Path)
			w.WriteHeader(404)
		}
	}))
	t.Cleanup(ts.Close)
	return f, ts
}

func (f *fakeStripe) setSub(id, uid, lookup, status string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.subs[id] = map[string]any{
		"id": id, "customer": "cus_" + uid, "status": status, "cancel_at_period_end": false,
		"metadata": map[string]string{"user_id": uid},
		"items": map[string]any{"data": []any{map[string]any{
			"price":              map[string]any{"id": "price_" + lookup, "lookup_key": lookup, "unit_amount": 500, "currency": "usd", "recurring": map[string]any{"interval": "month"}},
			"current_period_end": time.Now().Add(30 * 24 * time.Hour).Unix(),
		}}},
	}
}

// sendWebhook posts a signed event the way Stripe does.
func sendWebhook(t *testing.T, ts *httptest.Server, typ string, object map[string]any, secret string) int {
	t.Helper()
	body, _ := json.Marshal(map[string]any{"id": "evt_1", "type": typ, "data": map[string]any{"object": object}})
	now := time.Now().Unix()
	req, _ := http.NewRequest("POST", ts.URL+"/api/v1/billing/webhook", bytes.NewReader(body))
	req.Header.Set("Stripe-Signature", fmt.Sprintf("t=%d,v1=%s", now, stripe.Sign(body, secret, now)))
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	return res.StatusCode
}

func billingServer(t *testing.T) (*Server, *httptest.Server, *fakeStripe) {
	srv, ts := newTestServer(t)
	f, fs := newFakeStripe(t)
	srv.Cfg.StripeSecretKey, srv.Cfg.StripeWebhookSecret, srv.Cfg.StripeAPIURL = "sk_test", testWebhookSecret, fs.URL
	return srv, ts, f
}

func TestBillingCheckoutWebhookAndPlan(t *testing.T) {
	srv, ts, f := billingServer(t)
	tok, uid := register(t, ts, "payer")

	res, body := do(t, ts, "POST", "/api/v1/billing/checkout", tok, map[string]any{"plan": "pro", "period": "year"})
	if res.StatusCode != 200 || body["url"] != "https://checkout.stripe.test/c/1" {
		t.Fatalf("checkout: %d %v", res.StatusCode, body)
	}
	got := f.checkouts[0]
	if got["managed_payments[enabled]"] != "true" || got["mode"] != "subscription" || got["line_items[0][price]"] != "price_nexa_pro_yearly" || got["client_reference_id"] != uid || got["subscription_data[metadata][user_id]"] != uid {
		t.Fatalf("checkout form: %v", got)
	}

	// a forged event changes nothing
	f.setSub("sub_1", uid, "nexa_pro_yearly", "active")
	if code := sendWebhook(t, ts, "checkout.session.completed", map[string]any{"subscription": "sub_1", "client_reference_id": uid}, "whsec_wrong"); code != 400 {
		t.Fatalf("forged webhook: %d", code)
	}
	if u, _ := srv.Store.UserByID(t.Context(), modID(uid)); u.Plan != "" {
		t.Fatalf("plan after forged webhook: %q", u.Plan)
	}

	if code := sendWebhook(t, ts, "checkout.session.completed", map[string]any{"subscription": "sub_1", "client_reference_id": uid}, testWebhookSecret); code != 200 {
		t.Fatalf("webhook: %d", code)
	}
	_, me := do(t, ts, "GET", "/api/v1/users/@me", tok, nil)
	if me["plan"] != "pro" {
		t.Fatalf("plan: %v", me["plan"])
	}
	_, bill := do(t, ts, "GET", "/api/v1/billing", tok, nil)
	if bill["plan"] != "pro" || bill["status"] != "active" || bill["manageable"] != true {
		t.Fatalf("billing: %v", bill)
	}
	if lim := bill["limits"].(map[string]any); lim["boosts"].(float64) != 2 || lim["file_max"].(float64) != float64(4<<30) {
		t.Fatalf("limits: %v", lim)
	}

	// buying again goes through the portal
	if res, body := do(t, ts, "POST", "/api/v1/billing/checkout", tok, map[string]any{"plan": "supporter", "period": "month"}); res.StatusCode != 409 || errCode(body) != "already_subscribed" {
		t.Fatalf("second checkout: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "POST", "/api/v1/billing/portal", tok, nil); res.StatusCode != 200 || body["url"] != "https://billing.stripe.test/p/cus_"+uid {
		t.Fatalf("portal: %d %v", res.StatusCode, body)
	}

	// boosts: two, on servers the booster is in
	s1 := createServer(t, ts, tok, "one")["id"].(string)
	s2 := createServer(t, ts, tok, "two")["id"].(string)
	s3 := createServer(t, ts, tok, "three")["id"].(string)
	for _, sid := range []string{s1, s2} {
		if res, body := do(t, ts, "POST", "/api/v1/billing/boosts", tok, map[string]any{"server_id": sid}); res.StatusCode != 204 {
			t.Fatalf("boost: %d %v", res.StatusCode, body)
		}
	}
	if res, body := do(t, ts, "POST", "/api/v1/billing/boosts", tok, map[string]any{"server_id": s3}); res.StatusCode != 409 || errCode(body) != "no_boosts_left" {
		t.Fatalf("third boost: %d %v", res.StatusCode, body)
	}
	if srv1, _ := srv.Store.ServerByID(t.Context(), modID(s1)); srv1.Boosts != 1 {
		t.Fatalf("boosts on the server: %d", srv1.Boosts)
	}

	// the subscription ends: back to free, boosts gone
	f.setSub("sub_1", uid, "nexa_pro_yearly", "canceled")
	if code := sendWebhook(t, ts, "customer.subscription.deleted", map[string]any{"id": "sub_1"}, testWebhookSecret); code != 200 {
		t.Fatalf("webhook: %d", code)
	}
	if u, _ := srv.Store.UserByID(t.Context(), modID(uid)); u.Plan != "" {
		t.Fatalf("plan after the end: %q", u.Plan)
	}
	if srv1, _ := srv.Store.ServerByID(t.Context(), modID(s1)); srv1.Boosts != 0 {
		t.Fatalf("boosts after the end: %d", srv1.Boosts)
	}
	if res, body := do(t, ts, "POST", "/api/v1/billing/boosts", tok, map[string]any{"server_id": s1}); res.StatusCode != 403 || errCode(body) != "plan_required" {
		t.Fatalf("free boost: %d %v", res.StatusCode, body)
	}
}

func TestBillingOlderSubscriptionEndingKeepsNewer(t *testing.T) {
	srv, ts, f := billingServer(t)
	_, uid := register(t, ts, "twice")
	f.setSub("sub_new", uid, "nexa_supporter_monthly", "active")
	sendWebhook(t, ts, "customer.subscription.created", map[string]any{"id": "sub_new"}, testWebhookSecret)
	f.setSub("sub_old", uid, "nexa_pro_monthly", "canceled")
	sendWebhook(t, ts, "customer.subscription.deleted", map[string]any{"id": "sub_old"}, testWebhookSecret)
	if u, _ := srv.Store.UserByID(t.Context(), modID(uid)); u.Plan != "supporter" {
		t.Fatalf("plan: %q", u.Plan)
	}
}

func TestPlanCaps(t *testing.T) {
	srv, ts, f := billingServer(t)
	tok, uid := register(t, ts, "capped")
	big := int64(1500) << 20
	if res, body := do(t, ts, "POST", "/api/v1/uploads", tok, map[string]any{"size": big}); res.StatusCode != 413 || errCode(body) != "too_large" {
		t.Fatalf("free 1.5 GB: %d %v", res.StatusCode, body)
	}
	// a boosted server's channel takes it
	sid := createServer(t, ts, tok, "boosted")["id"].(string)
	if _, err := srv.Store.DB().W.Exec(`INSERT INTO server_boosts (id, user_id, server_id, created_at) VALUES (1, ?, ?, 0)`, int64(modID(uid)), int64(modID(sid))); err != nil {
		t.Fatal(err)
	}
	if _, err := srv.Store.DB().W.Exec(`UPDATE servers SET boosts = 1 WHERE id = ?`, int64(modID(sid))); err != nil {
		t.Fatal(err)
	}
	_, chans := do(t, ts, "GET", "/api/v1/servers/"+sid+"/channels", tok, nil)
	var chID string
	for _, c := range chans["_list"].([]any) {
		if c.(map[string]any)["type"] == "text" {
			chID = c.(map[string]any)["id"].(string)
		}
	}
	if res, body := do(t, ts, "POST", "/api/v1/uploads", tok, map[string]any{"size": big, "channel_id": chID}); res.StatusCode != 201 {
		t.Fatalf("boosted 1.5 GB: %d %v", res.StatusCode, body)
	}

	// a paid name effect is for plans
	style := map[string]any{"name_style": map[string]any{"effect": "neon", "colors": []int{0xff00aa}}}
	if res, body := do(t, ts, "PATCH", "/api/v1/users/@me", tok, style); res.StatusCode != 403 || errCode(body) != "plan_required" {
		t.Fatalf("free neon: %d %v", res.StatusCode, body)
	}
	f.setSub("sub_c", uid, "nexa_supporter_monthly", "active")
	sendWebhook(t, ts, "customer.subscription.created", map[string]any{"id": "sub_c"}, testWebhookSecret)
	if res, body := do(t, ts, "PATCH", "/api/v1/users/@me", tok, style); res.StatusCode != 200 {
		t.Fatalf("supporter neon: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "POST", "/api/v1/uploads", tok, map[string]any{"size": big}); res.StatusCode != 201 {
		t.Fatalf("supporter 1.5 GB: %d %v", res.StatusCode, body)
	}
}

func TestMonthlyUploads(t *testing.T) {
	srv, ts := newTestServer(t)
	tok, uid := register(t, ts, "hoarder")
	now := time.Now().UnixMilli()
	// 5 GB put up today fills the free plan's 30 days
	if _, err := srv.Store.DB().W.Exec(`INSERT INTO blobs (id, sha256, size, mime, kind, encrypted, ref_count, owner_id, created_at) VALUES (99, 'x', ?, 'application/octet-stream', 'attachment', 1, 1, ?, ?)`, int64(5)<<30, int64(modID(uid)), now); err != nil {
		t.Fatal(err)
	}
	if res, body := do(t, ts, "POST", "/api/v1/uploads", tok, map[string]any{"size": 1 << 20}); res.StatusCode != 507 || errCode(body) != "upload_month_full" {
		t.Fatalf("over the month: %d %v", res.StatusCode, body)
	}
	_, bill := do(t, ts, "GET", "/api/v1/billing", tok, nil)
	if bill["month_used"].(float64) != float64(int64(5)<<30) || int64(bill["month_frees_at"].(float64)) != now+(30*24*time.Hour).Milliseconds() {
		t.Fatalf("billing: %v", bill)
	}
	// once it is 31 days old it no longer counts
	if _, err := srv.Store.DB().W.Exec(`UPDATE blobs SET created_at = ? WHERE id = 99`, now-(31*24*time.Hour).Milliseconds()); err != nil {
		t.Fatal(err)
	}
	if res, body := do(t, ts, "POST", "/api/v1/uploads", tok, map[string]any{"size": 1 << 20}); res.StatusCode != 201 {
		t.Fatalf("after 30 days: %d %v", res.StatusCode, body)
	}
}

func TestGiftAPlan(t *testing.T) {
	srv, ts, f := billingServer(t)
	tokA, idA := register(t, ts, "giver")
	_, idB := register(t, ts, "lucky")
	if res, body := do(t, ts, "POST", "/api/v1/billing/gift", tokA, map[string]any{"to": idA, "plan": "supporter", "period": "month"}); res.StatusCode != 400 || errCode(body) != "gift_self" {
		t.Fatalf("a gift to yourself: %d %v", res.StatusCode, body)
	}
	res, body := do(t, ts, "POST", "/api/v1/billing/gift", tokA, map[string]any{"to": idB, "plan": "supporter", "period": "month"})
	if res.StatusCode != 200 {
		t.Fatalf("gift checkout: %d %v", res.StatusCode, body)
	}
	got := f.checkouts[len(f.checkouts)-1]
	if got["mode"] != "payment" || got["metadata[gift_to]"] != idB || got["line_items[0][price]"] != "price_nexa_supporter_gift_month" || got["managed_payments[enabled]"] != "true" {
		t.Fatalf("gift checkout form: %v", got)
	}
	paid := func(session, period string) {
		t.Helper()
		obj := map[string]any{"id": session, "mode": "payment", "payment_status": "paid", "metadata": map[string]string{"gift_from": idA, "gift_to": idB, "gift_plan": "supporter", "gift_period": period}}
		if code := sendWebhook(t, ts, "checkout.session.completed", obj, testWebhookSecret); code != 200 {
			t.Fatalf("gift webhook: %d", code)
		}
	}
	// an unpaid one gives nothing
	if code := sendWebhook(t, ts, "checkout.session.completed", map[string]any{"id": "cs_unpaid", "mode": "payment", "payment_status": "unpaid", "metadata": map[string]string{"gift_to": idB, "gift_plan": "pro", "gift_period": "year"}}, testWebhookSecret); code != 200 {
		t.Fatalf("unpaid: %d", code)
	}
	paid("cs_1", "month")
	paid("cs_1", "month") // Stripe sending it twice gives it once
	paid("cs_2", "month") // a second month starts when the first ends
	if u, _ := srv.Store.UserByID(t.Context(), modID(idB)); u.Plan != "supporter" {
		t.Fatalf("plan: %q", u.Plan)
	}
	gifts, _ := srv.Store.GiftsFor(t.Context(), modID(idB))
	if len(gifts) != 2 || gifts[1].StartsAt != gifts[0].EndsAt || gifts[0].FromName == "" {
		t.Fatalf("gifts: %+v", gifts)
	}
	// once both have ended, the plan goes with them
	if _, err := srv.Store.DB().W.Exec(`UPDATE plan_gifts SET starts_at = 0, ends_at = ?`, time.Now().Add(-time.Minute).UnixMilli()); err != nil {
		t.Fatal(err)
	}
	if lapsed, err := srv.Store.RecomputePlans(t.Context(), 0); err != nil || len(lapsed) != 1 {
		t.Fatalf("recompute: %v %v", lapsed, err)
	}
	if u, _ := srv.Store.UserByID(t.Context(), modID(idB)); u.Plan != "" {
		t.Fatalf("plan after the gift: %q", u.Plan)
	}
}

func TestGiftLink(t *testing.T) {
	srv, ts, f := billingServer(t)
	tokA, idA := register(t, ts, "linkgiver")
	tokB, idB := register(t, ts, "claimer")
	tokC, _ := register(t, ts, "toolate")
	if res, body := do(t, ts, "POST", "/api/v1/billing/gift", tokA, map[string]any{"link": true, "plan": "pro", "period": "year"}); res.StatusCode != 200 {
		t.Fatalf("link checkout: %d %v", res.StatusCode, body)
	}
	got := f.checkouts[len(f.checkouts)-1]
	if got["metadata[gift_link]"] != "1" || got["metadata[gift_to]"] != "" || got["line_items[0][price]"] != "price_nexa_pro_gift_year" {
		t.Fatalf("link checkout form: %v", got)
	}
	obj := map[string]any{"id": "cs_link", "mode": "payment", "payment_status": "paid", "metadata": map[string]string{"gift_from": idA, "gift_link": "1", "gift_plan": "pro", "gift_period": "year"}}
	for range 2 { // sent twice, made once
		if code := sendWebhook(t, ts, "checkout.session.completed", obj, testWebhookSecret); code != 200 {
			t.Fatalf("link webhook: %d", code)
		}
	}
	_, bill := do(t, ts, "GET", "/api/v1/billing", tokA, nil)
	links, _ := bill["gift_links"].([]any)
	if len(links) != 1 {
		t.Fatalf("gift links: %v", bill["gift_links"])
	}
	code := links[0].(map[string]any)["code"].(string)
	if res, body := do(t, ts, "POST", "/api/v1/gifts/"+code+"/claim", tokA, nil); res.StatusCode != 400 || errCode(body) != "gift_self" {
		t.Fatalf("claiming your own: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "GET", "/api/v1/gifts/"+code, tokB, nil); res.StatusCode != 200 || body["plan"] != "pro" || body["claimed"] != false || body["days"].(float64) != 365 {
		t.Fatalf("preview: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "POST", "/api/v1/gifts/"+code+"/claim", tokB, nil); res.StatusCode != 200 || body["claimed_by_me"] != true {
		t.Fatalf("claim: %d %v", res.StatusCode, body)
	}
	if u, _ := srv.Store.UserByID(t.Context(), modID(idB)); u.Plan != "pro" {
		t.Fatalf("plan: %q", u.Plan)
	}
	if res, body := do(t, ts, "POST", "/api/v1/gifts/"+code+"/claim", tokC, nil); res.StatusCode != 410 || errCode(body) != "gift_claimed" {
		t.Fatalf("second claim: %d %v", res.StatusCode, body)
	}
	if res, body := do(t, ts, "GET", "/api/v1/gifts/nope", tokC, nil); res.StatusCode != 404 || errCode(body) != "unknown_gift" {
		t.Fatalf("unknown: %d %v", res.StatusCode, body)
	}
	_, bill = do(t, ts, "GET", "/api/v1/billing", tokA, nil)
	if l := bill["gift_links"].([]any)[0].(map[string]any); l["claimed"] != true || l["claimed_name"] == "" || l["claimed_by"] != idB {
		t.Fatalf("buyer's view: %v", l)
	}
}

// A big MAX_UPLOAD_BYTES (the live server's is 15 GB) sizes webhook, app and
// note files only: it once lifted every plan to the same size.
func TestPlansKeepTheirOwnFileSizes(t *testing.T) {
	srv, ts := newTestServer(t)
	srv.Cfg.MaxUploadBytes = 15 << 30
	_, info := do(t, ts, "GET", "/api/v1/instance", "", nil)
	plans := info["plans"].(map[string]any)
	for plan, want := range map[string]float64{"free": 1 << 30, "supporter": 2 << 30, "pro": 4 << 30} {
		if got := plans[plan].(map[string]any)["file_max"].(float64); got != want {
			t.Errorf("%s file_max %v, want %v", plan, got, want)
		}
	}
	if got := plans["boost_file_max"].(float64); got != 2<<30 {
		t.Errorf("boost_file_max %v", got)
	}
	if got := info["max_upload_bytes"].(float64); got != 1<<30 {
		t.Errorf("max_upload_bytes %v, want the free plan's", got)
	}
}

// Pay what you want: the split, the bounds, the session Stripe is asked for,
// and the time going on once, Pro first and Supporter after it.
func TestPayWhatYouWant(t *testing.T) {
	for _, c := range []struct{ amount, pro, sup int64 }{{100, 0, 0}, {499, 0, 0}, {500, 0, 1}, {999, 0, 1}, {1000, 1, 0}, {1500, 1, 1}, {2499, 2, 0}, {9600, 9, 1}} {
		if pro, sup := pwywSplit(c.amount, 500, 1000); pro != c.pro || sup != c.sup {
			t.Errorf("%d cents: %d Pro, %d Supporter", c.amount, pro, sup)
		}
	}
	srv, ts, f := billingServer(t)
	tok, id := register(t, ts, "tipper")
	for _, amount := range []int{99, 100_000_000} {
		if res, body := do(t, ts, "POST", "/api/v1/billing/pay-what-you-want", tok, map[string]any{"amount": amount}); res.StatusCode != 400 || errCode(body) != "invalid_amount" {
			t.Fatalf("%d: %d %v", amount, res.StatusCode, body)
		}
	}
	if res, body := do(t, ts, "POST", "/api/v1/billing/pay-what-you-want", tok, map[string]any{"amount": 2550}); res.StatusCode != 200 {
		t.Fatalf("checkout: %d %v", res.StatusCode, body)
	}
	got := f.checkouts[len(f.checkouts)-1]
	if got["mode"] != "payment" || got["line_items[0][price_data][unit_amount]"] != "2550" || got["metadata[pro_days]"] != "60" || got["metadata[supporter_days]"] != "30" || got["line_items[0][price_data][product_data][tax_code]"] == "" {
		t.Fatalf("checkout form: %v", got)
	}
	meta := map[string]string{}
	for k, v := range got {
		if strings.HasPrefix(k, "metadata[") {
			meta[strings.TrimSuffix(strings.TrimPrefix(k, "metadata["), "]")] = v
		}
	}
	obj := map[string]any{"id": "cs_pwyw", "mode": "payment", "payment_status": "paid", "metadata": meta}
	for range 2 { // Stripe sending it twice gives it once
		if code := sendWebhook(t, ts, "checkout.session.completed", obj, testWebhookSecret); code != 200 {
			t.Fatalf("webhook: %d", code)
		}
	}
	if u, _ := srv.Store.UserByID(t.Context(), modID(id)); u.Plan != "pro" {
		t.Fatalf("plan: %q", u.Plan)
	}
	gifts, _ := srv.Store.GiftsFor(t.Context(), modID(id))
	if len(gifts) != 2 || gifts[0].Plan != "pro" || gifts[1].Plan != "supporter" || gifts[1].StartsAt != gifts[0].EndsAt || gifts[0].EndsAt-gifts[0].StartsAt != 60*24*3600*1000 {
		t.Fatalf("gifts: %+v", gifts)
	}
}
