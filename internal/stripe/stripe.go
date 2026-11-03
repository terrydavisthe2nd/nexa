// Package stripe is the little of Stripe's API that subscriptions need:
// prices by lookup key, Checkout and Billing Portal sessions, reading and
// cancelling a subscription, and checking a webhook's signature. Requests are
// form-encoded, as Stripe's API takes them, pinned to one API version so the
// objects read back keep their shape whatever the account's default is.
package stripe

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// APIVersion is the version every request asks for. Managed Payments needs
// 2025-03-31.basil or later; from it, a subscription's period is on its items.
const APIVersion = "2025-03-31.basil"

// Client talks to Stripe with a secret key.
type Client struct {
	Key     string
	BaseURL string // https://api.stripe.com unless a test points it elsewhere
	HTTP    *http.Client
}

// New is a client for the live API (or BaseURL when not empty).
func New(key, baseURL string) *Client {
	if baseURL == "" {
		baseURL = "https://api.stripe.com"
	}
	return &Client{Key: key, BaseURL: strings.TrimRight(baseURL, "/"), HTTP: &http.Client{Timeout: 20 * time.Second}}
}

// Error is what Stripe answered a request it refused with.
type Error struct {
	Status  int
	Code    string `json:"code"`
	Message string `json:"message"`
}

func (e *Error) Error() string { return fmt.Sprintf("stripe: %d %s: %s", e.Status, e.Code, e.Message) }

func (c *Client) do(ctx context.Context, method, path string, form url.Values, out any) error {
	var body io.Reader
	if form != nil && method != http.MethodGet {
		body = strings.NewReader(form.Encode())
	}
	u := c.BaseURL + path
	if form != nil && method == http.MethodGet {
		u += "?" + form.Encode()
	}
	req, err := http.NewRequestWithContext(ctx, method, u, body)
	if err != nil {
		return err
	}
	req.SetBasicAuth(c.Key, "")
	req.Header.Set("Stripe-Version", APIVersion)
	if body != nil {
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	}
	res, err := c.HTTP.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(res.Body, 4<<20))
	if err != nil {
		return err
	}
	if res.StatusCode >= 300 {
		var env struct {
			Error Error `json:"error"`
		}
		_ = json.Unmarshal(raw, &env)
		env.Error.Status = res.StatusCode
		return &env.Error
	}
	if out == nil {
		return nil
	}
	return json.Unmarshal(raw, out)
}

// Price is one of Stripe's prices, as far as a plan needs it.
type Price struct {
	ID         string `json:"id"`
	LookupKey  string `json:"lookup_key"`
	UnitAmount int64  `json:"unit_amount"`
	Currency   string `json:"currency"`
	Recurring  *struct {
		Interval string `json:"interval"`
	} `json:"recurring"`
}

// PricesByLookupKey reads the active prices with these lookup keys, by key.
func (c *Client) PricesByLookupKey(ctx context.Context, keys []string) (map[string]Price, error) {
	q := url.Values{"active": {"true"}, "limit": {"100"}}
	for _, k := range keys {
		q.Add("lookup_keys[]", k)
	}
	var list struct {
		Data []Price `json:"data"`
	}
	if err := c.do(ctx, http.MethodGet, "/v1/prices", q, &list); err != nil {
		return nil, err
	}
	out := map[string]Price{}
	for _, p := range list.Data {
		out[p.LookupKey] = p
	}
	return out, nil
}

// CheckoutParams describe a Checkout session for one subscription.
type CheckoutParams struct {
	PriceID    string
	UserID     string // kept on the session and the subscription
	CustomerID string // the account's Stripe customer, when it has one
	SuccessURL string
	CancelURL  string
}

// CreateCheckout makes a Checkout session for a subscription sold through
// Managed Payments (Stripe the merchant of record, handling the tax) and
// returns the address to send the buyer to.
func (c *Client) CreateCheckout(ctx context.Context, p CheckoutParams) (string, error) {
	f := url.Values{
		"mode":                                 {"subscription"},
		"line_items[0][price]":                 {p.PriceID},
		"line_items[0][quantity]":              {"1"},
		"managed_payments[enabled]":            {"true"},
		"client_reference_id":                  {p.UserID},
		"metadata[user_id]":                    {p.UserID},
		"subscription_data[metadata][user_id]": {p.UserID},
		"success_url":                          {p.SuccessURL},
		"cancel_url":                           {p.CancelURL},
	}
	if p.CustomerID != "" {
		f.Set("customer", p.CustomerID)
	}
	var out struct {
		URL string `json:"url"`
	}
	if err := c.do(ctx, http.MethodPost, "/v1/checkout/sessions", f, &out); err != nil {
		return "", err
	}
	return out.URL, nil
}

// GiftParams describe a Checkout session for a plan bought for someone else:
// one payment, with who it is from and for kept on the session.
type GiftParams struct {
	PriceID    string
	FromID     string
	ToID       string // empty for a gift link
	Link       bool
	Plan       string
	Period     string
	SuccessURL string
	CancelURL  string
}

// CreateGiftCheckout makes a Checkout session for one payment through Managed
// Payments and returns the address to send the buyer to.
func (c *Client) CreateGiftCheckout(ctx context.Context, p GiftParams) (string, error) {
	f := url.Values{
		"mode":                      {"payment"},
		"line_items[0][price]":      {p.PriceID},
		"line_items[0][quantity]":   {"1"},
		"managed_payments[enabled]": {"true"},
		"client_reference_id":       {p.FromID},
		"metadata[gift_from]":       {p.FromID},
		"metadata[gift_to]":         {p.ToID},
		"metadata[gift_plan]":       {p.Plan},
		"metadata[gift_period]":     {p.Period},
		"success_url":               {p.SuccessURL},
		"cancel_url":                {p.CancelURL},
	}
	if p.Link {
		f.Del("metadata[gift_to]")
		f.Set("metadata[gift_link]", "1")
	}
	var out struct {
		URL string `json:"url"`
	}
	if err := c.do(ctx, http.MethodPost, "/v1/checkout/sessions", f, &out); err != nil {
		return "", err
	}
	return out.URL, nil
}

// AmountParams describe a Checkout session for one payment of an amount the
// buyer chose (pay what you want), with what it buys kept on the session.
type AmountParams struct {
	Amount     int64 // in cents
	Currency   string
	Name       string // what Stripe's page and receipt call it
	TaxCode    string
	UserID     string
	Metadata   map[string]string
	SuccessURL string
	CancelURL  string
}

// CreateAmountCheckout makes a Checkout session for one payment of a chosen
// amount through Managed Payments, the product made inline with its tax code
// as Managed Payments needs, and returns the address to send the buyer to.
func (c *Client) CreateAmountCheckout(ctx context.Context, p AmountParams) (string, error) {
	f := url.Values{
		"mode":                                              {"payment"},
		"line_items[0][price_data][currency]":               {p.Currency},
		"line_items[0][price_data][unit_amount]":            {strconv.FormatInt(p.Amount, 10)},
		"line_items[0][price_data][tax_behavior]":           {"exclusive"},
		"line_items[0][price_data][product_data][name]":     {p.Name},
		"line_items[0][price_data][product_data][tax_code]": {p.TaxCode},
		"line_items[0][quantity]":                           {"1"},
		"managed_payments[enabled]":                         {"true"},
		"client_reference_id":                               {p.UserID},
		"success_url":                                       {p.SuccessURL},
		"cancel_url":                                        {p.CancelURL},
	}
	for k, v := range p.Metadata {
		f.Set("metadata["+k+"]", v)
	}
	var out struct {
		URL string `json:"url"`
	}
	if err := c.do(ctx, http.MethodPost, "/v1/checkout/sessions", f, &out); err != nil {
		return "", err
	}
	return out.URL, nil
}

// CreatePortal makes a Billing Portal session for a customer and returns its address.
func (c *Client) CreatePortal(ctx context.Context, customerID, returnURL string) (string, error) {
	var out struct {
		URL string `json:"url"`
	}
	err := c.do(ctx, http.MethodPost, "/v1/billing_portal/sessions", url.Values{"customer": {customerID}, "return_url": {returnURL}}, &out)
	return out.URL, err
}

// Subscription is one of Stripe's subscriptions, as far as a plan needs it.
type Subscription struct {
	ID                string            `json:"id"`
	Customer          string            `json:"customer"`
	Status            string            `json:"status"`
	CancelAtPeriodEnd bool              `json:"cancel_at_period_end"`
	Metadata          map[string]string `json:"metadata"`
	Items             struct {
		Data []struct {
			Price            Price `json:"price"`
			CurrentPeriodEnd int64 `json:"current_period_end"`
		} `json:"data"`
	} `json:"items"`
}

// GetSubscription reads a subscription as it is now.
func (c *Client) GetSubscription(ctx context.Context, id string) (*Subscription, error) {
	var sub Subscription
	if err := c.do(ctx, http.MethodGet, "/v1/subscriptions/"+url.PathEscape(id), nil, &sub); err != nil {
		return nil, err
	}
	return &sub, nil
}

// CancelSubscription ends a subscription at once.
func (c *Client) CancelSubscription(ctx context.Context, id string) error {
	return c.do(ctx, http.MethodDelete, "/v1/subscriptions/"+url.PathEscape(id), url.Values{}, nil)
}

// Event is a webhook's event, with its object left raw for the type to read.
type Event struct {
	ID   string `json:"id"`
	Type string `json:"type"`
	Data struct {
		Object json.RawMessage `json:"object"`
	} `json:"data"`
}

// ErrBadSignature is a webhook whose signature does not check out.
var ErrBadSignature = errors.New("stripe: bad webhook signature")

// Tolerance is how old a webhook's signed time may be.
const Tolerance = 5 * time.Minute

// VerifyWebhook checks the Stripe-Signature header against the body with
// the endpoint's signing secret (t=<unix>,v1=<hex HMAC-SHA256 of "t.body">)
// and reads the event.
func VerifyWebhook(body []byte, header, secret string, now time.Time) (*Event, error) {
	var ts int64
	var sigs []string
	for _, part := range strings.Split(header, ",") {
		k, v, ok := strings.Cut(strings.TrimSpace(part), "=")
		if !ok {
			continue
		}
		switch k {
		case "t":
			ts, _ = strconv.ParseInt(v, 10, 64)
		case "v1":
			sigs = append(sigs, v)
		}
	}
	if ts == 0 || len(sigs) == 0 || secret == "" {
		return nil, ErrBadSignature
	}
	if d := now.Sub(time.Unix(ts, 0)); d > Tolerance || d < -Tolerance {
		return nil, ErrBadSignature
	}
	want := Sign(body, secret, ts)
	ok := false
	for _, s := range sigs {
		if hmac.Equal([]byte(s), []byte(want)) {
			ok = true
		}
	}
	if !ok {
		return nil, ErrBadSignature
	}
	var ev Event
	if err := json.Unmarshal(body, &ev); err != nil {
		return nil, err
	}
	return &ev, nil
}

// Sign is the v1 signature of a body sent at ts, for tests that play Stripe.
func Sign(body []byte, secret string, ts int64) string {
	mac := hmac.New(sha256.New, []byte(secret))
	fmt.Fprintf(mac, "%d.", ts)
	mac.Write(body)
	return hex.EncodeToString(mac.Sum(nil))
}
