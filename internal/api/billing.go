package api

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/store"
	"evilion.xyz/chat/internal/stripe"
)

// Subscriptions. Everything Nexa does stays free; a plan only raises what
// costs money to keep: how big one file may be, how much an account keeps in
// files each month, how sharp a screen share may be, and some name effects. Pro also
// boosts servers, which lets every member there send bigger files. People buy
// through Stripe Checkout (Managed Payments, Stripe the merchant of record)
// and manage or cancel in Stripe's Billing Portal; the webhook is what puts an
// account on a plan, always by reading the subscription back from Stripe, so
// events arriving twice or out of order end the same way.

// planLimits is what one plan allows. The apps read the table from
// /api/v1/instance and draw their limits and offers from it.
type planLimits struct {
	FileMax int64 `json:"file_max"`
	// MonthUploadMax is how much an account may upload in any 30 days
	// (uploadWindow); each upload counts until it is that old.
	MonthUploadMax int64 `json:"month_upload_max"`
	ShareQuality   bool  `json:"share_quality"` // the Quality share mode (the screen's own size)
	ShareMaxBps    int64 `json:"share_max_bps"` // the most a share may send
	NameEffects    bool  `json:"name_effects"`  // the effects only plans have
	Boosts         int   `json:"boosts"`
}

const (
	gib = int64(1) << 30
	// freeFileMax is the largest file an account without a plan may send.
	freeFileMax = 1 * gib
	// boostFileMax is the largest file anyone may send in a boosted server.
	boostFileMax = 2 * gib
)

func (s *Server) planLimitsFor(plan string) planLimits {
	// The plans' sizes are their own: MAX_UPLOAD_BYTES sizes the files that
	// come with no plan (webhooks, apps, notes), and raising it once lifted
	// every plan to the same number, so the plans meant nothing.
	free := planLimits{FileMax: freeFileMax, MonthUploadMax: 5 * gib, ShareMaxBps: 6_000_000}
	switch plan {
	case store.PlanSupporter:
		return planLimits{FileMax: 2 * gib, MonthUploadMax: 100 * gib, ShareQuality: true, ShareMaxBps: 12_000_000, NameEffects: true}
	case store.PlanPro:
		return planLimits{FileMax: 4 * gib, MonthUploadMax: 500 * gib, ShareQuality: true, ShareMaxBps: 20_000_000, NameEffects: true, Boosts: 2}
	}
	return free
}

// plansInfo is the table of plans the instance answers with.
func (s *Server) plansInfo() map[string]any {
	return map[string]any{
		"enabled":        s.billingEnabled(),
		"free":           s.planLimitsFor(store.PlanFree),
		"supporter":      s.planLimitsFor(store.PlanSupporter),
		"pro":            s.planLimitsFor(store.PlanPro),
		"boost_file_max": boostFileMax,
	}
}

func (s *Server) billingEnabled() bool {
	return s.Cfg.StripeSecretKey != "" && s.Cfg.StripeWebhookSecret != ""
}

func (s *Server) stripeClient() *stripe.Client {
	return stripe.New(s.Cfg.StripeSecretKey, s.Cfg.StripeAPIURL)
}

// planOf is the plan of the account making the request, free for anyone else.
func planOf(ctx context.Context) string {
	if u := auth.UserFrom(ctx); u != nil {
		return u.Plan
	}
	return store.PlanFree
}

// fileMaxFor is the largest file uid may send: its plan's, or a boosted
// server's when the file is for a channel there and that is more.
func (s *Server) fileMaxFor(ctx context.Context, plan string, channelID *ids.ID) int64 {
	limit := s.planLimitsFor(plan).FileMax
	if channelID == nil {
		return limit
	}
	ch, err := s.Store.ChannelByID(ctx, *channelID)
	if err != nil || ch.ServerID == nil {
		return limit
	}
	if srv, err := s.Store.ServerByID(ctx, *ch.ServerID); err == nil && srv.Boosts > 0 {
		return max(limit, boostFileMax)
	}
	return limit
}

// errFileTooBig is a file over the sender's limit. The apps offer a plan when
// they see it; they also check before sending, from the same table.
func errFileTooBig() error {
	return E(http.StatusRequestEntityTooLarge, "too_large", "That file is bigger than your plan sends.")
}

// uploadWindow is how long an upload counts against a plan's allowance: a
// rolling 30 days, so room comes back a file at a time as each turns 30 days
// old, with no reset day to pile uploads up against.
const uploadWindow = 30 * 24 * time.Hour

var errMonthFull = E(http.StatusInsufficientStorage, "upload_month_full", "You've uploaded all your plan allows in 30 days. Room comes back as older uploads pass 30 days.")

// monthUsed is what an account uploaded in the last 30 days, and when the
// first of that room comes back (0 for none).
func (s *Server) monthUsed(ctx context.Context, uid ids.ID) (used, freesAt int64, err error) {
	used, oldest, err := s.Store.UploadedSince(ctx, uid, time.Now().Add(-uploadWindow).UnixMilli())
	if err != nil || oldest == 0 {
		return used, 0, err
	}
	return used, oldest + uploadWindow.Milliseconds(), nil
}

// The prices, found by their lookup keys and kept for a while.
const priceLife = 10 * time.Minute

var planPrices = map[string]map[string]string{
	store.PlanSupporter: {"month": "nexa_supporter_monthly", "year": "nexa_supporter_yearly"},
	store.PlanPro:       {"month": "nexa_pro_monthly", "year": "nexa_pro_yearly"},
}

// giftPrices are the one-time prices of a plan bought for someone else, a
// month or a year of it, by their lookup keys.
var giftPrices = map[string]map[string]string{
	store.PlanSupporter: {"month": "nexa_supporter_gift_month", "year": "nexa_supporter_gift_year"},
	store.PlanPro:       {"month": "nexa_pro_gift_month", "year": "nexa_pro_gift_year"},
}

// giftDays is how long a gift of a period lasts.
var giftDays = map[string]int{"month": 30, "year": 365}

type priceCache struct {
	mu     sync.Mutex
	at     time.Time
	prices map[string]stripe.Price
}

func (s *Server) prices(ctx context.Context) (map[string]stripe.Price, error) {
	s.priceCache.mu.Lock()
	defer s.priceCache.mu.Unlock()
	if s.priceCache.prices != nil && time.Since(s.priceCache.at) < priceLife {
		return s.priceCache.prices, nil
	}
	var keys []string
	for _, table := range []map[string]map[string]string{planPrices, giftPrices} {
		for _, byPeriod := range table {
			for _, k := range byPeriod {
				keys = append(keys, k)
			}
		}
	}
	got, err := s.stripeClient().PricesByLookupKey(ctx, keys)
	if err != nil {
		return nil, err
	}
	s.priceCache.prices, s.priceCache.at = got, time.Now()
	return got, nil
}

// planForLookupKey reads which plan and period a price is.
func planForLookupKey(key string) (plan, period string) {
	for p, byPeriod := range planPrices {
		for per, k := range byPeriod {
			if k == key {
				return p, per
			}
		}
	}
	return "", ""
}

func (s *Server) billingRoutes(mux *http.ServeMux) {
	mux.Handle("GET /api/v1/billing", s.authed(s.handleBilling))
	mux.Handle("POST /api/v1/billing/checkout", s.authed(s.handleCheckout))
	mux.Handle("POST /api/v1/billing/portal", s.authed(s.handlePortal))
	mux.Handle("POST /api/v1/billing/gift", s.authed(s.handleGift))
	mux.Handle("POST /api/v1/billing/pay-what-you-want", s.authed(s.handlePayWhatYouWant))
	mux.Handle("GET /api/v1/gifts/{code}", s.authed(s.handleGiftLink))
	mux.Handle("POST /api/v1/gifts/{code}/claim", s.authed(s.handleGiftClaim))
	mux.Handle("POST /api/v1/billing/boosts", s.authed(s.handleBoostAdd))
	mux.Handle("DELETE /api/v1/billing/boosts/{id}", s.authed(s.handleBoostRemove))
	// Stripe's, signed with the endpoint's secret
	mux.Handle("POST /api/v1/billing/webhook", ipLimited(s.limLookup, s.handleBillingWebhook))
}

// handleBilling is the account's side of it: its plan and what Stripe said
// last, what it uploaded in the last 30 days, its boosts and the prices.
func (s *Server) handleBilling(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	ctx := r.Context()
	used, freesAt, err := s.monthUsed(ctx, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	boosts, err := s.Store.BoostsOf(ctx, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	gifts, err := s.Store.GiftsFor(ctx, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	links, err := s.Store.GiftLinksFrom(ctx, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	out := map[string]any{
		"enabled": s.billingEnabled(), "plan": me.Plan, "limits": s.planLimitsFor(me.Plan),
		"month_used": used, "month_frees_at": freesAt, "boosts": boosts, "gifts": gifts, "gift_links": links,
		"period": "", "status": "", "period_end": 0, "cancel_at_end": false, "manageable": false,
		"prices": map[string]any{}, "gift_prices": map[string]any{},
	}
	if b, err := s.Store.BillingFor(ctx, me.ID); err == nil {
		out["period"], out["status"], out["period_end"], out["cancel_at_end"] = b.Period, b.Status, b.PeriodEnd, b.CancelAtEnd
		out["manageable"] = b.CustomerID != "" && s.billingEnabled()
	}
	if s.billingEnabled() {
		if got, err := s.prices(ctx); err == nil {
			view := func(table map[string]map[string]string) map[string]map[string]any {
				v := map[string]map[string]any{}
				for plan, byPeriod := range table {
					v[plan] = map[string]any{}
					for per, key := range byPeriod {
						if p, ok := got[key]; ok {
							v[plan][per] = map[string]any{"amount": p.UnitAmount, "currency": p.Currency}
						}
					}
				}
				return v
			}
			out["prices"], out["gift_prices"] = view(planPrices), view(giftPrices)
		} else {
			s.Log.Warn("billing: prices", "err", err)
		}
	}
	WriteJSON(w, http.StatusOK, out)
}

func (s *Server) billingReturnURL(r *http.Request) string {
	return strings.TrimRight(s.Cfg.PublicURL, "/") + "/channels/@me/support"
}

func (s *Server) handleCheckout(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	if !s.billingEnabled() {
		WriteError(w, E(http.StatusServiceUnavailable, "billing_off", "Subscriptions aren't open yet."))
		return
	}
	var req struct {
		Plan   string `json:"plan"`
		Period string `json:"period"`
	}
	if err := ReadJSON(w, r, &req, 1<<10); err != nil {
		WriteError(w, err)
		return
	}
	key := planPrices[req.Plan][req.Period]
	if key == "" {
		WriteError(w, E(http.StatusBadRequest, "invalid_plan", "Pick supporter or pro, by the month or the year."))
		return
	}
	b, _ := s.Store.BillingFor(r.Context(), me.ID)
	// changing plan, or paying for one already running, is the portal's job
	if me.Plan != store.PlanFree && b != nil && b.CustomerID != "" {
		WriteError(w, E(http.StatusConflict, "already_subscribed", "You already have a plan. Change it from Manage Subscription."))
		return
	}
	prices, err := s.prices(r.Context())
	if err != nil {
		s.Log.Error("billing: prices", "err", err)
		WriteError(w, E(http.StatusBadGateway, "billing_unavailable", "Payments can't be reached right now. Try again in a minute."))
		return
	}
	price, ok := prices[key]
	if !ok {
		s.Log.Error("billing: no active price", "lookup_key", key)
		WriteError(w, E(http.StatusBadGateway, "billing_unavailable", "That plan can't be bought right now."))
		return
	}
	p := stripe.CheckoutParams{PriceID: price.ID, UserID: me.ID.String(), SuccessURL: s.billingReturnURL(r) + "?checkout=done", CancelURL: s.billingReturnURL(r)}
	if b != nil {
		p.CustomerID = b.CustomerID
	}
	url, err := s.stripeClient().CreateCheckout(r.Context(), p)
	if err != nil {
		s.Log.Error("billing: checkout", "err", err)
		WriteError(w, E(http.StatusBadGateway, "billing_unavailable", "Payments can't be reached right now. Try again in a minute."))
		return
	}
	WriteJSON(w, http.StatusOK, map[string]string{"url": url})
}

func (s *Server) handlePortal(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	b, err := s.Store.BillingFor(r.Context(), me.ID)
	if !s.billingEnabled() || err != nil || b.CustomerID == "" {
		WriteError(w, E(http.StatusNotFound, "no_subscription", "There's no subscription to manage."))
		return
	}
	url, err := s.stripeClient().CreatePortal(r.Context(), b.CustomerID, s.billingReturnURL(r))
	if err != nil {
		s.Log.Error("billing: portal", "err", err)
		WriteError(w, E(http.StatusBadGateway, "billing_unavailable", "Payments can't be reached right now. Try again in a minute."))
		return
	}
	WriteJSON(w, http.StatusOK, map[string]string{"url": url})
}

func (s *Server) handleBillingWebhook(w http.ResponseWriter, r *http.Request) {
	if !s.billingEnabled() {
		WriteError(w, ErrNotFound)
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 1<<20))
	if err != nil {
		WriteError(w, E(http.StatusBadRequest, "bad_body", "The body could not be read."))
		return
	}
	ev, err := stripe.VerifyWebhook(body, r.Header.Get("Stripe-Signature"), s.Cfg.StripeWebhookSecret, time.Now())
	if err != nil {
		WriteError(w, E(http.StatusBadRequest, "bad_signature", "The signature does not check out."))
		return
	}
	var obj struct {
		ID                string            `json:"id"`
		Mode              string            `json:"mode"`
		PaymentStatus     string            `json:"payment_status"`
		Subscription      string            `json:"subscription"`
		ClientReferenceID string            `json:"client_reference_id"`
		Metadata          map[string]string `json:"metadata"`
	}
	_ = json.Unmarshal(ev.Data.Object, &obj)
	var sub string
	var hint ids.ID
	switch ev.Type {
	case "checkout.session.completed", "checkout.session.async_payment_succeeded":
		if obj.Mode == "payment" && obj.Metadata["pwyw"] == "1" {
			// an amount of the buyer's choosing, once its payment has come in
			if obj.PaymentStatus == "paid" {
				if err := s.applyPayWhatYouWant(r.Context(), obj.ID, obj.Metadata); err != nil {
					s.Log.Error("billing: pay what you want", "event", ev.ID, "session", obj.ID, "err", err)
					WriteError(w, E(http.StatusInternalServerError, "sync_failed", "Could not apply that yet."))
					return
				}
			}
			break
		}
		if obj.Mode == "payment" {
			// a plan bought for someone else, once its payment has come in
			if obj.PaymentStatus == "paid" {
				if err := s.applyGift(r.Context(), obj.ID, obj.Metadata); err != nil {
					s.Log.Error("billing: gift", "event", ev.ID, "session", obj.ID, "err", err)
					WriteError(w, E(http.StatusInternalServerError, "sync_failed", "Could not apply that yet."))
					return
				}
			}
			break
		}
		sub = obj.Subscription
		hint, _ = ids.Parse(obj.ClientReferenceID)
	case "customer.subscription.created", "customer.subscription.updated", "customer.subscription.deleted":
		sub = obj.ID
	}
	if sub != "" {
		if err := s.syncSubscription(r.Context(), sub, hint); err != nil {
			// Stripe sends it again later
			s.Log.Error("billing: sync", "event", ev.ID, "subscription", sub, "err", err)
			WriteError(w, E(http.StatusInternalServerError, "sync_failed", "Could not apply that yet."))
			return
		}
	}
	WriteJSON(w, http.StatusOK, map[string]bool{"received": true})
}

// planStatuses are the subscription statuses that keep a plan on: a payment
// that failed is retried for a while before Stripe ends it.
var planStatuses = map[string]bool{"active": true, "trialing": true, "past_due": true}

// syncSubscription reads a subscription from Stripe and puts its account on
// the plan it pays for, or back to free once it has ended. hint is the
// account a Checkout session named, for a subscription made before its
// metadata could be read back.
func (s *Server) syncSubscription(ctx context.Context, subID string, hint ids.ID) error {
	sub, err := s.stripeClient().GetSubscription(ctx, subID)
	if err != nil {
		return err
	}
	uid, _ := ids.Parse(sub.Metadata["user_id"])
	if uid.IsZero() {
		uid = hint
	}
	if uid.IsZero() {
		if uid, err = s.Store.UserForCustomer(ctx, sub.Customer); err != nil {
			s.Log.Warn("billing: subscription for nobody", "subscription", sub.ID, "customer", sub.Customer)
			return nil
		}
	}
	u, err := s.Store.UserByID(ctx, uid)
	if errors.Is(err, store.ErrNotFound) || (err == nil && u.Flags&store.FlagDeleted != 0) {
		s.Log.Warn("billing: subscription for a closed account", "subscription", sub.ID, "user", uid.String())
		return nil
	}
	if err != nil {
		return err
	}
	b := store.Billing{UserID: uid, CustomerID: sub.Customer, SubscriptionID: sub.ID, Status: sub.Status, CancelAtEnd: sub.CancelAtPeriodEnd}
	if len(sub.Items.Data) > 0 {
		item := sub.Items.Data[0]
		b.Plan, b.Period = planForLookupKey(item.Price.LookupKey)
		b.PeriodEnd = item.CurrentPeriodEnd * 1000
	}
	if !planStatuses[sub.Status] {
		b.Plan = store.PlanFree
	}
	// The end of an older subscription says nothing about a newer one.
	if old, err := s.Store.BillingFor(ctx, uid); err == nil && old.SubscriptionID != "" && old.SubscriptionID != sub.ID && planStatuses[old.Status] && b.Plan == store.PlanFree {
		return nil
	}
	changed, unboosted, err := s.Store.SetBilling(ctx, b)
	if err != nil {
		return err
	}
	s.afterPlanChange(ctx, uid, changed, unboosted)
	return nil
}

// afterPlanChange tells everyone about a plan that changed (the badge is
// public) and the servers about boosts that went with it.
func (s *Server) afterPlanChange(ctx context.Context, uid ids.ID, changed bool, unboosted []ids.ID) {
	if changed {
		if u, err := s.Store.UserByID(ctx, uid); err == nil {
			s.Bcast.UserUpdated(u)
		}
	}
	for _, id := range unboosted {
		s.emitServerFresh(ctx, id)
	}
	s.Bcast.EmitUsers([]ids.ID{uid}, "billing_update", map[string]any{})
}

func (s *Server) emitServerFresh(ctx context.Context, id ids.ID) {
	if srv, err := s.Store.ServerByID(ctx, id); err == nil {
		s.emitServer(ctx, id, "server_update", srv)
	}
}

// endSubscription cancels an account's subscription at once and puts it back
// on the free plan; for an account being closed.
func (s *Server) endSubscription(ctx context.Context, uid ids.ID) {
	b, err := s.Store.BillingFor(ctx, uid)
	if err != nil {
		return
	}
	if b.SubscriptionID != "" && planStatuses[b.Status] && s.billingEnabled() {
		if err := s.stripeClient().CancelSubscription(ctx, b.SubscriptionID); err != nil {
			s.Log.Error("billing: cancel on account close", "user", uid.String(), "subscription", b.SubscriptionID, "err", err)
		}
	}
	b.Plan, b.Status, b.CancelAtEnd = store.PlanFree, "canceled", false
	changed, unboosted, err := s.Store.SetBilling(ctx, *b)
	if err != nil {
		s.Log.Error("billing: free on account close", "user", uid.String(), "err", err)
		return
	}
	s.afterPlanChange(ctx, uid, changed, unboosted)
}

func (s *Server) handleBoostAdd(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	var req struct {
		ServerID ids.ID `json:"server_id"`
	}
	if err := ReadJSON(w, r, &req, 1<<10); err != nil {
		WriteError(w, err)
		return
	}
	limit := s.planLimitsFor(me.Plan).Boosts
	if limit == 0 {
		WriteError(w, E(http.StatusForbidden, "plan_required", "Boosting a server comes with Nexa Pro."))
		return
	}
	if ok, _ := s.Store.IsMember(r.Context(), req.ServerID, me.ID); !ok {
		WriteError(w, ErrNotFound)
		return
	}
	if err := s.Store.AddBoost(r.Context(), me.ID, req.ServerID, limit); err != nil {
		if errors.Is(err, store.ErrBoostLimit) {
			WriteError(w, E(http.StatusConflict, "no_boosts_left", "Both your boosts are in use. Take one back first."))
			return
		}
		WriteError(w, err)
		return
	}
	s.emitServerFresh(r.Context(), req.ServerID)
	s.Bcast.EmitUsers([]ids.ID{me.ID}, "billing_update", map[string]any{})
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleBoostRemove(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	id, err := ids.Parse(r.PathValue("id"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	server, err := s.Store.RemoveBoost(r.Context(), me.ID, id)
	if err != nil {
		WriteError(w, err)
		return
	}
	s.emitServerFresh(r.Context(), server)
	s.Bcast.EmitUsers([]ids.ID{me.ID}, "billing_update", map[string]any{})
	w.WriteHeader(http.StatusNoContent)
}

// giftable is whether an account can be given a plan: a person, not a bot,
// Nexa itself or a closed account.
func giftable(u *store.User) bool {
	return u.Flags&(store.NotPeople|store.FlagDeleted|store.FlagDisabled) == 0
}

// handleGift starts buying a month or a year of a plan for someone else: a
// person named by to, or, with link, a gift link the buyer passes on, which
// whoever opens it first claims.
func (s *Server) handleGift(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	if !s.billingEnabled() {
		WriteError(w, E(http.StatusServiceUnavailable, "billing_off", "Subscriptions aren't open yet."))
		return
	}
	var req struct {
		To     ids.ID `json:"to"`
		Link   bool   `json:"link"`
		Plan   string `json:"plan"`
		Period string `json:"period"`
	}
	if err := ReadJSON(w, r, &req, 1<<10); err != nil {
		WriteError(w, err)
		return
	}
	key := giftPrices[req.Plan][req.Period]
	if key == "" {
		WriteError(w, E(http.StatusBadRequest, "invalid_plan", "Pick supporter or pro, for a month or a year."))
		return
	}
	var toID string
	if !req.Link {
		if req.To == me.ID {
			WriteError(w, E(http.StatusBadRequest, "gift_self", "A gift is for someone else. Get a plan for yourself above."))
			return
		}
		to, err := s.Store.UserByID(r.Context(), req.To)
		if err != nil || !giftable(to) {
			WriteError(w, E(http.StatusNotFound, "unknown_user", "There's nobody to give it to."))
			return
		}
		// a gift goes to someone who would take a message from you
		if a, b, _ := s.Store.Blocked(r.Context(), me.ID, to.ID); a || b {
			WriteError(w, E(http.StatusForbidden, "cannot_gift", "You can't give a gift to this person."))
			return
		}
		toID = to.ID.String()
	}
	prices, err := s.prices(r.Context())
	if err != nil {
		s.Log.Error("billing: prices", "err", err)
		WriteError(w, E(http.StatusBadGateway, "billing_unavailable", "Payments can't be reached right now. Try again in a minute."))
		return
	}
	price, ok := prices[key]
	if !ok {
		s.Log.Error("billing: no active price", "lookup_key", key)
		WriteError(w, E(http.StatusBadGateway, "billing_unavailable", "That gift can't be bought right now."))
		return
	}
	done := "?gift=done"
	if req.Link {
		done = "?gift=link"
	}
	url, err := s.stripeClient().CreateGiftCheckout(r.Context(), stripe.GiftParams{
		PriceID: price.ID, FromID: me.ID.String(), ToID: toID, Link: req.Link, Plan: req.Plan, Period: req.Period,
		SuccessURL: s.billingReturnURL(r) + done, CancelURL: s.billingReturnURL(r),
	})
	if err != nil {
		s.Log.Error("billing: gift checkout", "err", err)
		WriteError(w, E(http.StatusBadGateway, "billing_unavailable", "Payments can't be reached right now. Try again in a minute."))
		return
	}
	WriteJSON(w, http.StatusOK, map[string]string{"url": url})
}

// applyGift gives the plan a paid gift session names to its recipient, once,
// or makes the gift link it bought. What the session says is what this
// server wrote on it, under the secret key, so it is taken as it stands; a
// gift to a closed account goes nowhere.
func (s *Server) applyGift(ctx context.Context, sessionID string, meta map[string]string) error {
	from, _ := ids.Parse(meta["gift_from"])
	days := giftDays[meta["gift_period"]]
	plan := meta["gift_plan"]
	if meta["gift_link"] == "1" && days > 0 && giftPrices[plan] != nil && !from.IsZero() {
		if _, err := s.Store.AddGiftLink(ctx, sessionID, from, plan, days); err != nil {
			return err
		}
		s.Bcast.EmitUsers([]ids.ID{from}, "billing_update", map[string]any{})
		return nil
	}
	to, err := ids.Parse(meta["gift_to"])
	if err != nil || days == 0 || giftPrices[plan] == nil {
		s.Log.Warn("billing: a payment that is no gift", "session", sessionID)
		return nil
	}
	u, err := s.Store.UserByID(ctx, to)
	if errors.Is(err, store.ErrNotFound) || (err == nil && !giftable(u)) {
		s.Log.Warn("billing: gift for nobody", "session", sessionID, "to", to.String())
		return nil
	}
	if err != nil {
		return err
	}
	added, changed, unboosted, err := s.Store.AddGift(ctx, sessionID, from, to, plan, days)
	if err != nil || !added {
		return err
	}
	s.afterPlanChange(ctx, to, changed, unboosted)
	if !from.IsZero() {
		s.Bcast.EmitUsers([]ids.ID{from}, "billing_update", map[string]any{})
	}
	return nil
}

// giftLinkView is a gift link as someone who opens it sees it: who it is from
// and what it holds, whether it is still to be claimed, and whether they were
// the one who claimed it. Who claimed it is for the buyer's list alone.
func giftLinkView(g *store.GiftLink, me ids.ID) map[string]any {
	return map[string]any{
		"code": g.Code, "from_id": g.FromID, "from_name": g.FromName, "plan": g.Plan, "days": g.Days,
		"claimed": g.Claimed, "claimed_by_me": g.Claimed && g.ClaimedBy == me, "mine": g.FromID == me,
	}
}

// handleGiftLink shows a gift link to someone who opened it.
func (s *Server) handleGiftLink(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	g, err := s.Store.GiftLinkByCode(r.Context(), r.PathValue("code"))
	if err != nil {
		WriteError(w, E(http.StatusNotFound, "unknown_gift", "That gift link doesn't exist."))
		return
	}
	WriteJSON(w, http.StatusOK, giftLinkView(g, me.ID))
}

// handleGiftClaim puts the plan a gift link holds on the account claiming
// it, the first time anyone does.
func (s *Server) handleGiftClaim(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	code := r.PathValue("code")
	g, err := s.Store.GiftLinkByCode(r.Context(), code)
	if err != nil {
		WriteError(w, E(http.StatusNotFound, "unknown_gift", "That gift link doesn't exist."))
		return
	}
	if g.FromID == me.ID {
		WriteError(w, E(http.StatusBadRequest, "gift_self", "This is your own gift. Send the link to the person it's for."))
		return
	}
	if !giftable(me) {
		WriteError(w, E(http.StatusForbidden, "cannot_gift", "This account can't take a gift."))
		return
	}
	_, changed, unboosted, err := s.Store.ClaimGiftLink(r.Context(), code, me.ID)
	if errors.Is(err, store.ErrGiftClaimed) {
		WriteError(w, E(http.StatusGone, "gift_claimed", "Someone has already claimed this gift."))
		return
	}
	if err != nil {
		WriteError(w, err)
		return
	}
	s.afterPlanChange(r.Context(), me.ID, changed, unboosted)
	if !g.FromID.IsZero() {
		s.Bcast.EmitUsers([]ids.ID{g.FromID}, "billing_update", map[string]any{})
	}
	g.Claimed, g.ClaimedBy = true, me.ID
	WriteJSON(w, http.StatusOK, giftLinkView(g, me.ID))
}

// StartBilling ends gifts as their time runs out, every few minutes, putting
// each account back on the plan it has without them.
func (s *Server) StartBilling(ctx context.Context) {
	go func() {
		t := time.NewTicker(5 * time.Minute)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				// a sweep that missed a few runs still catches what ended meanwhile
				lapsed, err := s.Store.RecomputePlans(ctx, time.Now().Add(-48*time.Hour).UnixMilli())
				if err != nil {
					s.Log.Warn("billing: gifts ending", "err", err)
				}
				for uid, unboosted := range lapsed {
					s.afterPlanChange(ctx, uid, true, unboosted)
				}
			}
		}
	}()
}

// Pay what you want: one payment of any amount from a dollar up, never
// renewed. Every full month price of Pro buys a month (30 days) of Pro, and
// what is left buys months of Supporter at its month price; less than a month
// of Supporter buys no plan time and is a thank-you. The apps work out the
// same split from the same prices (web lib/plans.ts payWhatYouWant, Android
// Plans.payWhatYouWant) and show it before Stripe's page opens.
const (
	pwywMin = int64(100)
	// pwywMax is the most one Checkout payment in US dollars may be.
	pwywMax = int64(99_999_999)
	// pwywTaxCode is the plans' own: software as a service, for personal use.
	pwywTaxCode = "txcd_10103000"
)

// pwywSplit is how many months of Pro and then Supporter an amount buys at
// these month prices (in cents).
func pwywSplit(amount, supporterMonth, proMonth int64) (pro, supporter int64) {
	if supporterMonth <= 0 || proMonth <= 0 {
		return 0, 0
	}
	pro = amount / proMonth
	supporter = (amount - pro*proMonth) / supporterMonth
	return pro, supporter
}

// monthPrices are what a month of each plan costs once, in cents: the gift
// prices, or the list prices when Stripe has not said.
func (s *Server) monthPrices(ctx context.Context) (supporter, pro int64, err error) {
	supporter, pro = 500, 1000
	got, err := s.prices(ctx)
	if err != nil {
		return 0, 0, err
	}
	if p, ok := got[giftPrices[store.PlanSupporter]["month"]]; ok && p.Currency == "usd" && p.UnitAmount > 0 {
		supporter = p.UnitAmount
	}
	if p, ok := got[giftPrices[store.PlanPro]["month"]]; ok && p.Currency == "usd" && p.UnitAmount > 0 {
		pro = p.UnitAmount
	}
	return supporter, pro, nil
}

// handlePayWhatYouWant starts a payment of the amount the account chose, in
// cents, and answers Stripe's page for it with what it buys.
func (s *Server) handlePayWhatYouWant(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	if !s.billingEnabled() {
		WriteError(w, E(http.StatusServiceUnavailable, "billing_off", "Payments aren't open yet."))
		return
	}
	var req struct {
		Amount int64 `json:"amount"`
	}
	if err := ReadJSON(w, r, &req, 1<<10); err != nil {
		WriteError(w, err)
		return
	}
	if req.Amount < pwywMin || req.Amount > pwywMax {
		WriteError(w, E(http.StatusBadRequest, "invalid_amount", "Pick an amount from $1 to $999,999.99."))
		return
	}
	sup, pro, err := s.monthPrices(r.Context())
	if err != nil {
		s.Log.Error("billing: prices", "err", err)
		WriteError(w, E(http.StatusBadGateway, "billing_unavailable", "Payments can't be reached right now. Try again in a minute."))
		return
	}
	proMonths, supMonths := pwywSplit(req.Amount, sup, pro)
	url, err := s.stripeClient().CreateAmountCheckout(r.Context(), stripe.AmountParams{
		Amount: req.Amount, Currency: "usd", Name: "Nexa, pay what you want", TaxCode: pwywTaxCode, UserID: me.ID.String(),
		Metadata: map[string]string{
			"pwyw": "1", "user_id": me.ID.String(), "amount": strconv.FormatInt(req.Amount, 10),
			"pro_days": strconv.FormatInt(proMonths*30, 10), "supporter_days": strconv.FormatInt(supMonths*30, 10),
		},
		SuccessURL: s.billingReturnURL(r) + "?paid=done", CancelURL: s.billingReturnURL(r),
	})
	if err != nil {
		s.Log.Error("billing: pay what you want checkout", "err", err)
		WriteError(w, E(http.StatusBadGateway, "billing_unavailable", "Payments can't be reached right now. Try again in a minute."))
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"url": url, "pro_months": proMonths, "supporter_months": supMonths})
}

// applyPayWhatYouWant puts the plan time a paid session bought on the account
// that paid, once. The days are what this server wrote on the session when it
// made it, under the secret key, so they are taken as they stand.
func (s *Server) applyPayWhatYouWant(ctx context.Context, sessionID string, meta map[string]string) error {
	uid, err := ids.Parse(meta["user_id"])
	if err != nil {
		s.Log.Warn("billing: pay what you want for nobody", "session", sessionID)
		return nil
	}
	proDays, _ := strconv.Atoi(meta["pro_days"])
	supDays, _ := strconv.Atoi(meta["supporter_days"])
	if proDays <= 0 && supDays <= 0 {
		return nil // a thank-you, with no plan time
	}
	u, err := s.Store.UserByID(ctx, uid)
	if errors.Is(err, store.ErrNotFound) || (err == nil && !giftable(u)) {
		s.Log.Warn("billing: pay what you want for a closed account", "session", sessionID, "user", uid.String())
		return nil
	}
	if err != nil {
		return err
	}
	added, changed, unboosted, err := s.Store.AddPaidTime(ctx, sessionID, uid, proDays, supDays)
	if err != nil || !added {
		return err
	}
	s.afterPlanChange(ctx, uid, changed, unboosted)
	return nil
}
