package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"

	"evilion.xyz/chat/internal/db"
	"evilion.xyz/chat/internal/ids"
)

// The plans an account can have (users.plan). PlanFree is no subscription.
const (
	PlanFree      = ""
	PlanSupporter = "supporter"
	PlanPro       = "pro"
)

// PaidNameEffects are the name effects only an account with a plan wears
// (api/name_style.go). One worn when a plan ends is shown as its first colour
// alone until it is changed (styleForPlan).
var PaidNameEffects = map[string]bool{"holo": true, "neon": true, "flame": true}

// styleForPlan is a stored name style as everyone sees it: a paid effect on
// an account without a plan is drawn as the plain name in its first colour.
func styleForPlan(style, plan string) string {
	if plan != PlanFree || style == "" {
		return style
	}
	var st map[string]any
	if json.Unmarshal([]byte(style), &st) != nil {
		return style
	}
	effect, _ := st["effect"].(string)
	if !PaidNameEffects[effect] {
		return style
	}
	st["effect"] = "solid"
	if cs, ok := st["colors"].([]any); ok && len(cs) > 1 {
		st["colors"] = cs[:1]
	}
	out, err := json.Marshal(st)
	if err != nil {
		return style
	}
	return string(out)
}

// ErrBoostLimit is a boost asked for by an account that has none left.
var ErrBoostLimit = errors.New("store: no boosts left")

// Billing is what Stripe said last about an account's subscription.
type Billing struct {
	UserID         ids.ID
	CustomerID     string
	SubscriptionID string
	Plan           string
	Period         string // "month" or "year"
	Status         string // Stripe's subscription status
	PeriodEnd      int64  // when it renews or, cancelled, ends (ms)
	CancelAtEnd    bool
	UpdatedAt      int64
}

// BillingFor is the account's billing record, or ErrNotFound.
func (s *Store) BillingFor(ctx context.Context, uid ids.ID) (*Billing, error) {
	var b Billing
	var cancel int64
	err := s.db.R.QueryRowContext(ctx, `SELECT user_id, customer_id, subscription_id, plan, period, status, period_end, cancel_at_end, updated_at FROM billing WHERE user_id = ?`, int64(uid)).
		Scan(&b.UserID, &b.CustomerID, &b.SubscriptionID, &b.Plan, &b.Period, &b.Status, &b.PeriodEnd, &cancel, &b.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	b.CancelAtEnd = cancel == 1
	return &b, err
}

// UserForCustomer is the account a Stripe customer pays for, or ErrNotFound.
func (s *Store) UserForCustomer(ctx context.Context, customerID string) (ids.ID, error) {
	var id int64
	err := s.db.R.QueryRowContext(ctx, `SELECT user_id FROM billing WHERE customer_id = ? AND customer_id != ''`, customerID).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, ErrNotFound
	}
	return ids.ID(id), err
}

// SetBilling keeps what Stripe said and puts the account on its plan. An
// account that is no longer Pro gives up its boosts; the servers they were on
// are returned, with whether the plan changed at all.
func (s *Store) SetBilling(ctx context.Context, b Billing) (changed bool, unboosted []ids.ID, err error) {
	now := nowMs()
	err = s.Tx(ctx, func(tx *db.Tx) error {
		if _, err := tx.ExecContext(ctx, `INSERT INTO billing (user_id, customer_id, subscription_id, plan, period, status, period_end, cancel_at_end, updated_at) VALUES (?,?,?,?,?,?,?,?,?)
			ON CONFLICT(user_id) DO UPDATE SET customer_id = excluded.customer_id, subscription_id = excluded.subscription_id, plan = excluded.plan, period = excluded.period,
			status = excluded.status, period_end = excluded.period_end, cancel_at_end = excluded.cancel_at_end, updated_at = excluded.updated_at`,
			int64(b.UserID), b.CustomerID, b.SubscriptionID, b.Plan, b.Period, b.Status, b.PeriodEnd, boolInt(b.CancelAtEnd), now); err != nil {
			return err
		}
		changed, unboosted, err = recomputePlanTx(ctx, tx, b.UserID)
		return err
	})
	return changed, unboosted, err
}

func planRank(p string) int {
	switch p {
	case PlanPro:
		return 2
	case PlanSupporter:
		return 1
	}
	return 0
}

// recomputePlanTx puts an account on the better of its own subscription
// (billing.plan, free unless Stripe says it runs) and any gift still running.
// An account that is no longer Pro gives up its boosts.
func recomputePlanTx(ctx context.Context, tx *db.Tx, uid ids.ID) (changed bool, unboosted []ids.ID, err error) {
	best := PlanFree
	var sub string
	if err := tx.QueryRowContext(ctx, `SELECT plan FROM billing WHERE user_id = ?`, int64(uid)).Scan(&sub); err != nil && !errors.Is(err, sql.ErrNoRows) {
		return false, nil, err
	}
	best = sub
	rows, err := tx.QueryContext(ctx, `SELECT plan FROM plan_gifts WHERE to_id = ? AND starts_at <= ? AND ends_at > ?`, int64(uid), nowMs(), nowMs())
	if err != nil {
		return false, nil, err
	}
	for rows.Next() {
		var p string
		if err := rows.Scan(&p); err != nil {
			rows.Close()
			return false, nil, err
		}
		if planRank(p) > planRank(best) {
			best = p
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return false, nil, err
	}
	res, err := tx.ExecContext(ctx, `UPDATE users SET plan = ?, updated_at = ? WHERE id = ? AND plan != ?`, best, nowMs(), int64(uid), best)
	if err != nil {
		return false, nil, err
	}
	n, _ := res.RowsAffected()
	if best == PlanPro {
		return n > 0, nil, nil
	}
	unboosted, err = dropBoostsTx(ctx, tx, `user_id = ?`, int64(uid))
	return n > 0, unboosted, err
}

// Gift is a plan someone bought for someone else.
type Gift struct {
	FromID   ids.ID `json:"from_id"`
	FromName string `json:"from_name"`
	Plan     string `json:"plan"`
	StartsAt int64  `json:"starts_at"`
	EndsAt   int64  `json:"ends_at"`
}

// AddGift gives an account a plan for days, bought in one Checkout session;
// a session seen before adds nothing. Another gift of the same plan starts
// when the last one running ends.
func (s *Store) AddGift(ctx context.Context, sessionID string, from, to ids.ID, plan string, days int) (added, changed bool, unboosted []ids.ID, err error) {
	err = s.Tx(ctx, func(tx *db.Tx) error {
		var seen int
		if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM plan_gifts WHERE session_id = ?`, sessionID).Scan(&seen); err != nil {
			return err
		}
		if seen > 0 {
			return nil
		}
		added = true
		changed, unboosted, err = addGiftTx(ctx, tx, sessionID, from, to, plan, days)
		return err
	})
	return added, changed, unboosted, err
}

func addGiftTx(ctx context.Context, tx *db.Tx, sessionID string, from, to ids.ID, plan string, days int) (changed bool, unboosted []ids.ID, err error) {
	now := nowMs()
	start := now
	var last sql.NullInt64
	if err := tx.QueryRowContext(ctx, `SELECT max(ends_at) FROM plan_gifts WHERE to_id = ? AND plan = ? AND ends_at > ?`, int64(to), plan, now).Scan(&last); err != nil {
		return false, nil, err
	}
	if last.Valid && last.Int64 > start {
		start = last.Int64
	}
	end := start + int64(days)*24*60*60*1000
	if _, err := tx.ExecContext(ctx, `INSERT INTO plan_gifts (id, session_id, from_id, to_id, plan, starts_at, ends_at, created_at) VALUES (?,?,?,?,?,?,?,?)`,
		int64(ids.New()), sessionID, int64(from), int64(to), plan, start, end, now); err != nil {
		return false, nil, err
	}
	return recomputePlanTx(ctx, tx, to)
}

// AddPaidTime gives uid the plan time one pay-what-you-want session bought,
// once: proDays of Pro after any Pro already given, then supporterDays of
// Supporter after all of that, so the Supporter time is not spent under Pro. It
// is kept as gifts from the account to itself (session ids sessionID and
// sessionID+":supporter"), which the Gift Giver badge does not count.
func (s *Store) AddPaidTime(ctx context.Context, sessionID string, uid ids.ID, proDays, supporterDays int) (added, changed bool, unboosted []ids.ID, err error) {
	const day = int64(24 * 60 * 60 * 1000)
	err = s.Tx(ctx, func(tx *db.Tx) error {
		var seen int
		if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM plan_gifts WHERE session_id = ? OR session_id = ?`, sessionID, sessionID+":supporter").Scan(&seen); err != nil {
			return err
		}
		if seen > 0 {
			return nil
		}
		added = true
		now := nowMs()
		latest := func(plan string) (int64, error) {
			var last sql.NullInt64
			if err := tx.QueryRowContext(ctx, `SELECT max(ends_at) FROM plan_gifts WHERE to_id = ? AND plan = ? AND ends_at > ?`, int64(uid), plan, now).Scan(&last); err != nil {
				return 0, err
			}
			return max(now, last.Int64), nil
		}
		insert := func(session, plan string, start int64, days int) error {
			_, err := tx.ExecContext(ctx, `INSERT INTO plan_gifts (id, session_id, from_id, to_id, plan, starts_at, ends_at, created_at) VALUES (?,?,?,?,?,?,?,?)`,
				int64(ids.New()), session, int64(uid), int64(uid), plan, start, start+int64(days)*day, now)
			return err
		}
		proEnd, err := latest(PlanPro)
		if err != nil {
			return err
		}
		if proDays > 0 {
			if err := insert(sessionID, PlanPro, proEnd, proDays); err != nil {
				return err
			}
			proEnd += int64(proDays) * day
		}
		if supporterDays > 0 {
			start, err := latest(PlanSupporter)
			if err != nil {
				return err
			}
			// after every Pro given, which would otherwise cover it
			start = max(start, proEnd)
			if err := insert(sessionID+":supporter", PlanSupporter, start, supporterDays); err != nil {
				return err
			}
		}
		changed, unboosted, err = recomputePlanTx(ctx, tx, uid)
		return err
	})
	return added, changed, unboosted, err
}

// GiftLink is a plan bought as a link, which whoever opens it first claims.
type GiftLink struct {
	Code        string `json:"code"`
	FromID      ids.ID `json:"from_id"`
	FromName    string `json:"from_name"`
	Plan        string `json:"plan"`
	Days        int    `json:"days"`
	CreatedAt   int64  `json:"created_at"`
	Claimed     bool   `json:"claimed"`
	ClaimedBy   ids.ID `json:"claimed_by"`
	ClaimedName string `json:"claimed_name"`
	ClaimedAt   int64  `json:"claimed_at"`
}

// ErrGiftClaimed is a gift link someone has already claimed.
var ErrGiftClaimed = errors.New("store: gift link already claimed")

// giftLinkCodeLen is long enough that a link can't be guessed.
const giftLinkCodeLen = 20

// AddGiftLink makes the link a paid Checkout session bought, once; a session
// seen before answers the link it already made.
func (s *Store) AddGiftLink(ctx context.Context, sessionID string, from ids.ID, plan string, days int) (code string, err error) {
	err = s.Tx(ctx, func(tx *db.Tx) error {
		err := tx.QueryRowContext(ctx, `SELECT code FROM gift_links WHERE session_id = ?`, sessionID).Scan(&code)
		if err == nil || !errors.Is(err, sql.ErrNoRows) {
			return err
		}
		code = randomCode(giftLinkCodeLen)
		_, err = tx.ExecContext(ctx, `INSERT INTO gift_links (code, session_id, from_id, plan, days, created_at) VALUES (?,?,?,?,?,?)`, code, sessionID, int64(from), plan, days, nowMs())
		return err
	})
	return code, err
}

const giftLinkCols = `g.code, g.from_id, coalesce(f.display_name, ''), g.plan, g.days, g.created_at, g.claimed_by, coalesce(c.display_name, ''), g.claimed_at
	FROM gift_links g LEFT JOIN users f ON f.id = g.from_id LEFT JOIN users c ON c.id = g.claimed_by AND g.claimed_by <> 0`

func scanGiftLink(r rowScanner) (*GiftLink, error) {
	var g GiftLink
	var from, by int64
	if err := r.Scan(&g.Code, &from, &g.FromName, &g.Plan, &g.Days, &g.CreatedAt, &by, &g.ClaimedName, &g.ClaimedAt); err != nil {
		return nil, err
	}
	g.FromID, g.ClaimedBy, g.Claimed = ids.ID(from), ids.ID(by), g.ClaimedAt > 0
	return &g, nil
}

// GiftLinkByCode reads a gift link.
func (s *Store) GiftLinkByCode(ctx context.Context, code string) (*GiftLink, error) {
	g, err := scanGiftLink(s.db.R.QueryRowContext(ctx, `SELECT `+giftLinkCols+` WHERE g.code = ?`, code))
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return g, err
}

// GiftLinksFrom lists the gift links an account bought, newest first.
func (s *Store) GiftLinksFrom(ctx context.Context, uid ids.ID) ([]GiftLink, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT `+giftLinkCols+` WHERE g.from_id = ? ORDER BY g.created_at DESC LIMIT 100`, int64(uid))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []GiftLink{}
	for rows.Next() {
		g, err := scanGiftLink(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, *g)
	}
	return out, rows.Err()
}

// ClaimGiftLink gives uid the plan a link holds, if nobody has claimed it
// yet (ErrGiftClaimed otherwise), as a gift from whoever bought it.
func (s *Store) ClaimGiftLink(ctx context.Context, code string, uid ids.ID) (g *GiftLink, changed bool, unboosted []ids.ID, err error) {
	err = s.Tx(ctx, func(tx *db.Tx) error {
		var from int64
		var plan string
		var days int
		var at int64
		if err := tx.QueryRowContext(ctx, `SELECT from_id, plan, days, claimed_at FROM gift_links WHERE code = ?`, code).Scan(&from, &plan, &days, &at); err != nil {
			if errors.Is(err, sql.ErrNoRows) {
				return ErrNotFound
			}
			return err
		}
		res, err := tx.ExecContext(ctx, `UPDATE gift_links SET claimed_by = ?, claimed_at = ? WHERE code = ? AND claimed_at = 0`, int64(uid), nowMs(), code)
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 || at > 0 {
			return ErrGiftClaimed
		}
		g = &GiftLink{Code: code, FromID: ids.ID(from), Plan: plan, Days: days}
		changed, unboosted, err = addGiftTx(ctx, tx, "link:"+code, ids.ID(from), uid, plan, days)
		return err
	})
	return g, changed, unboosted, err
}

// GiftsFor lists the gifts an account has that have not ended, soonest first.
func (s *Store) GiftsFor(ctx context.Context, uid ids.ID) ([]Gift, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT g.from_id, coalesce(u.display_name, ''), g.plan, g.starts_at, g.ends_at FROM plan_gifts g LEFT JOIN users u ON u.id = g.from_id WHERE g.to_id = ? AND g.ends_at > ? ORDER BY g.ends_at`, int64(uid), nowMs())
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Gift{}
	for rows.Next() {
		var g Gift
		if err := rows.Scan(&g.FromID, &g.FromName, &g.Plan, &g.StartsAt, &g.EndsAt); err != nil {
			return nil, err
		}
		out = append(out, g)
	}
	return out, rows.Err()
}

// RecomputePlans puts every account whose gift ended since a time (ms) on the
// plan it has now, returning those whose plan changed with the servers that
// lost their boosts.
func (s *Store) RecomputePlans(ctx context.Context, since int64) (map[ids.ID][]ids.ID, error) {
	var who []ids.ID
	rows, err := s.db.R.QueryContext(ctx, `SELECT DISTINCT to_id FROM plan_gifts WHERE ends_at <= ? AND ends_at > ?`, nowMs(), since)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return nil, err
		}
		who = append(who, ids.ID(id))
	}
	rows.Close()
	out := map[ids.ID][]ids.ID{}
	for _, uid := range who {
		err := s.Tx(ctx, func(tx *db.Tx) error {
			changed, unboosted, err := recomputePlanTx(ctx, tx, uid)
			if changed {
				out[uid] = unboosted
			}
			return err
		})
		if err != nil {
			return out, err
		}
	}
	return out, nil
}

// dropBoostsTx removes the boosts where says and takes them off their
// servers' counts, returning the servers whose count went down.
func dropBoostsTx(ctx context.Context, tx *db.Tx, where string, args ...any) ([]ids.ID, error) {
	rows, err := tx.QueryContext(ctx, `SELECT server_id FROM server_boosts WHERE `+where, args...)
	if err != nil {
		return nil, err
	}
	var servers []ids.ID
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return nil, err
		}
		servers = append(servers, ids.ID(id))
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM server_boosts WHERE `+where, args...); err != nil {
		return nil, err
	}
	seen := map[ids.ID]bool{}
	var out []ids.ID
	for _, id := range servers {
		if !seen[id] {
			seen[id] = true
			out = append(out, id)
		}
		if _, err := tx.ExecContext(ctx, `UPDATE servers SET boosts = (SELECT count(*) FROM server_boosts WHERE server_id = ?), updated_at = ? WHERE id = ?`, int64(id), nowMs(), int64(id)); err != nil {
			return nil, err
		}
	}
	return out, nil
}

// UploadedSince is how many bytes of files an account has put up since a
// time (ms) and are still kept, and when the oldest of them went up (0 for
// none): what a plan's allowance counts. A file deleted no longer counts.
func (s *Store) UploadedSince(ctx context.Context, uid ids.ID, since int64) (total, oldest int64, err error) {
	err = s.db.R.QueryRowContext(ctx, `SELECT coalesce(sum(size), 0), coalesce(min(created_at), 0) FROM blobs WHERE owner_id = ? AND kind = 'attachment' AND created_at >= ?`, int64(uid), since).Scan(&total, &oldest)
	return total, oldest, err
}

// Boost is one of an account's boosts, with the server it is on.
type Boost struct {
	ID         ids.ID  `json:"id"`
	ServerID   ids.ID  `json:"server_id"`
	ServerName string  `json:"server_name"`
	IconID     *ids.ID `json:"server_icon_id"`
	CreatedAt  int64   `json:"created_at"`
}

// BoostsOf lists an account's boosts, oldest first.
func (s *Store) BoostsOf(ctx context.Context, uid ids.ID) ([]Boost, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT b.id, b.server_id, s.name, s.icon_id, b.created_at FROM server_boosts b JOIN servers s ON s.id = b.server_id WHERE b.user_id = ? ORDER BY b.id`, int64(uid))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Boost{}
	for rows.Next() {
		var b Boost
		var icon sql.NullInt64
		if err := rows.Scan(&b.ID, &b.ServerID, &b.ServerName, &icon, &b.CreatedAt); err != nil {
			return nil, err
		}
		b.IconID = idPtr(icon)
		out = append(out, b)
	}
	return out, rows.Err()
}

// AddBoost puts one of the account's boosts on a server, if it has fewer
// than max already.
func (s *Store) AddBoost(ctx context.Context, uid, serverID ids.ID, max int) error {
	return s.Tx(ctx, func(tx *db.Tx) error {
		var have int
		if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM server_boosts WHERE user_id = ?`, int64(uid)).Scan(&have); err != nil {
			return err
		}
		if have >= max {
			return ErrBoostLimit
		}
		now := nowMs()
		if _, err := tx.ExecContext(ctx, `INSERT INTO server_boosts (id, user_id, server_id, created_at) VALUES (?,?,?,?)`, int64(ids.New()), int64(uid), int64(serverID), now); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, `UPDATE servers SET boosts = boosts + 1, updated_at = ? WHERE id = ?`, now, int64(serverID))
		return err
	})
}

// RemoveBoost takes one of the account's boosts back, returning the server it was on.
func (s *Store) RemoveBoost(ctx context.Context, uid, boostID ids.ID) (ids.ID, error) {
	var server ids.ID
	err := s.Tx(ctx, func(tx *db.Tx) error {
		var id int64
		err := tx.QueryRowContext(ctx, `SELECT server_id FROM server_boosts WHERE id = ? AND user_id = ?`, int64(boostID), int64(uid)).Scan(&id)
		if errors.Is(err, sql.ErrNoRows) {
			return ErrNotFound
		}
		if err != nil {
			return err
		}
		server = ids.ID(id)
		_, err = dropBoostsTx(ctx, tx, `id = ?`, int64(boostID))
		return err
	})
	return server, err
}
