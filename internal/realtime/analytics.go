package realtime

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// AnalyticsBase is Cloudflare's API, whose GraphQL endpoint answers with what
// Realtime measured.
const AnalyticsBase = "https://api.cloudflare.com/client/v4"

// AnalyticsLag is how far behind now a measurement is taken to end: the most
// recent minutes may not have reached Cloudflare's analytics yet, so the meter
// keeps its own estimate for them.
const AnalyticsLag = 10 * time.Minute

// breakdownRows is the most rows each breakdown asks for. The rows come
// largest first, so a day with more than this misses only its smallest users.
const breakdownRows = 5000

// Analytics reads the egress Cloudflare bills the account for, through its
// GraphQL Analytics API: SFU and TURN, which share the monthly allowance. It
// needs an API token that can read the account's analytics.
type Analytics struct {
	AccountID string
	Token     string
	Base      string
	HTTP      *http.Client
}

// NewAnalytics returns a reader for the account, or nil when either setting
// is missing.
func NewAnalytics(accountID, token string) *Analytics {
	if accountID == "" || token == "" {
		return nil
	}
	return &Analytics{AccountID: accountID, Token: token, Base: AnalyticsBase, HTTP: &http.Client{Timeout: 20 * time.Second}}
}

// Window is a span of time, from inclusive and to exclusive.
type Window struct{ From, To time.Time }

// Totals is the account's egress over a window.
type Totals struct{ SFU, TURN int64 }

// Attribution is a window's egress broken down the three ways Cloudflare can
// tell it apart: by the SFU session it went to, by the published track it
// carried (whose name starts with its publisher's account), and by the custom
// identifier of the TURN credential that relayed it (the account it was
// minted for).
type Attribution struct {
	Window   Window
	Totals   Totals
	Sessions map[string]int64
	Tracks   map[string]int64
	TURN     map[string]int64
	// Complete is false when a breakdown filled its rows: what is listed is
	// right, and only the smallest entries are missing.
	Complete bool
}

type groupRow struct {
	Dimensions map[string]string `json:"dimensions"`
	Sum        struct {
		EgressBytes int64 `json:"egressBytes"`
	} `json:"sum"`
}

// Read returns the account's egress over month, and the breakdown of each of
// days, in one request.
func (a *Analytics) Read(ctx context.Context, month Window, days ...Window) (Totals, []Attribution, error) {
	var q strings.Builder
	vars := map[string]any{"acct": a.AccountID, "mf": stamp(month.From), "mt": stamp(month.To)}
	q.WriteString("query ($acct: String!, $mf: Time!, $mt: Time!")
	for i, d := range days {
		fmt.Fprintf(&q, ", $f%d: Time!, $t%d: Time!", i, i)
		vars[fmt.Sprintf("f%d", i)] = stamp(d.From)
		vars[fmt.Sprintf("t%d", i)] = stamp(d.To)
	}
	q.WriteString(") { viewer { accounts(filter: {accountTag: $acct}) {")
	total := func(alias, dataset, from, to string) {
		fmt.Fprintf(&q, " %s: %s(limit: 1, filter: {datetime_geq: $%s, datetime_lt: $%s}) { sum { egressBytes } }", alias, dataset, from, to)
	}
	breakdown := func(alias, dataset, dimension, from, to string) {
		fmt.Fprintf(&q, " %s: %s(limit: %d, filter: {datetime_geq: $%s, datetime_lt: $%s}, orderBy: [sum_egressBytes_DESC]) { dimensions { %s } sum { egressBytes } }", alias, dataset, breakdownRows, from, to, dimension)
	}
	total("sfu", "callsUsageAdaptiveGroups", "mf", "mt")
	total("turn", "callsTurnUsageAdaptiveGroups", "mf", "mt")
	for i := range days {
		f, t := fmt.Sprintf("f%d", i), fmt.Sprintf("t%d", i)
		total(fmt.Sprintf("ds%d", i), "callsUsageAdaptiveGroups", f, t)
		total(fmt.Sprintf("dt%d", i), "callsTurnUsageAdaptiveGroups", f, t)
		breakdown(fmt.Sprintf("bs%d", i), "callsUsageAdaptiveGroups", "sessionId", f, t)
		breakdown(fmt.Sprintf("bk%d", i), "callsUsageAdaptiveGroups", "trackId", f, t)
		breakdown(fmt.Sprintf("bu%d", i), "callsTurnUsageAdaptiveGroups", "customIdentifier", f, t)
	}
	q.WriteString(" } } }")

	groups, err := a.query(ctx, q.String(), vars)
	if err != nil {
		return Totals{}, nil, err
	}
	sum := func(alias string) int64 {
		var n int64
		for _, r := range groups[alias] {
			n += r.Sum.EgressBytes
		}
		return n
	}
	byDimension := func(alias, dimension string) (map[string]int64, bool) {
		out := map[string]int64{}
		rows := groups[alias]
		for _, r := range rows {
			if key := r.Dimensions[dimension]; key != "" {
				out[key] += r.Sum.EgressBytes
			}
		}
		return out, len(rows) < breakdownRows
	}
	totals := Totals{SFU: sum("sfu"), TURN: sum("turn")}
	out := make([]Attribution, len(days))
	for i, d := range days {
		sessions, c1 := byDimension(fmt.Sprintf("bs%d", i), "sessionId")
		tracks, c2 := byDimension(fmt.Sprintf("bk%d", i), "trackId")
		turn, c3 := byDimension(fmt.Sprintf("bu%d", i), "customIdentifier")
		out[i] = Attribution{
			Window:   d,
			Totals:   Totals{SFU: sum(fmt.Sprintf("ds%d", i)), TURN: sum(fmt.Sprintf("dt%d", i))},
			Sessions: sessions,
			Tracks:   tracks,
			TURN:     turn,
			Complete: c1 && c2 && c3,
		}
	}
	return totals, out, nil
}

func stamp(t time.Time) string { return t.UTC().Format(time.RFC3339) }

// query runs a GraphQL query and returns the rows of each alias under the
// account.
func (a *Analytics) query(ctx context.Context, query string, vars map[string]any) (map[string][]groupRow, error) {
	body, _ := json.Marshal(map[string]any{"query": query, "variables": vars})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, a.Base+"/graphql", bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+a.Token)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", userAgent)
	res, err := a.HTTP.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(res.Body, 16<<20))
	var parsed struct {
		Data *struct {
			Viewer struct {
				Accounts []map[string][]groupRow `json:"accounts"`
			} `json:"viewer"`
		} `json:"data"`
		Errors []struct {
			Message string `json:"message"`
		} `json:"errors"`
	}
	if err := json.Unmarshal(raw, &parsed); err != nil {
		return nil, fmt.Errorf("realtime analytics: unreadable response (%d): %w", res.StatusCode, err)
	}
	if len(parsed.Errors) > 0 {
		return nil, fmt.Errorf("realtime analytics: %s", parsed.Errors[0].Message)
	}
	if res.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("realtime analytics: status %d", res.StatusCode)
	}
	if parsed.Data == nil || len(parsed.Data.Viewer.Accounts) == 0 {
		return nil, errors.New("realtime analytics: the token cannot read this account")
	}
	return parsed.Data.Viewer.Accounts[0], nil
}
