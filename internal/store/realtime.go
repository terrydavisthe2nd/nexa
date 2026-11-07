package store

import (
	"context"
	"database/sql"
	"errors"
	"strings"

	"evilion.xyz/chat/internal/db"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/realtime"
)

// AddRealtimeUsage adds to a month's estimated call egress and returns the new total.
func (s *Store) AddRealtimeUsage(ctx context.Context, month string, bytes int64) (int64, error) {
	var total int64
	err := s.db.W.QueryRowContext(ctx, `INSERT INTO realtime_usage (month, bytes, updated_at) VALUES (?, ?, ?)
		ON CONFLICT(month) DO UPDATE SET bytes = realtime_usage.bytes + excluded.bytes, updated_at = excluded.updated_at
		RETURNING bytes`, month, bytes, nowMs()).Scan(&total)
	return total, err
}

// RealtimeUsage returns a month's estimated call egress.
func (s *Store) RealtimeUsage(ctx context.Context, month string) (int64, error) {
	rec, err := s.RealtimeMonth(ctx, month)
	return rec.Bytes, err
}

// RealtimeMonth returns what is kept of a month: the estimate, and the latest
// measurement from Cloudflare (MeasuredUntil zero when there is none).
func (s *Store) RealtimeMonth(ctx context.Context, month string) (realtime.MonthRecord, error) {
	var r realtime.MonthRecord
	err := s.db.R.QueryRowContext(ctx, `SELECT bytes FROM realtime_usage WHERE month = ?`, month).Scan(&r.Bytes)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return realtime.MonthRecord{}, err
	}
	err = s.db.R.QueryRowContext(ctx, `SELECT sfu, turn, measured_until, estimate_base, measured_at FROM realtime_measurements WHERE month = ?`, month).
		Scan(&r.MeasuredSFU, &r.MeasuredTURN, &r.MeasuredUntil, &r.EstimateBase, &r.MeasuredAt)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return realtime.MonthRecord{}, err
	}
	return r, nil
}

// SaveRealtimeMeasurement keeps a month's latest measurement from Cloudflare.
func (s *Store) SaveRealtimeMeasurement(ctx context.Context, month string, rec realtime.MonthRecord) error {
	_, err := s.db.W.ExecContext(ctx, `INSERT INTO realtime_measurements (month, sfu, turn, measured_until, estimate_base, measured_at)
		VALUES (?, ?, ?, ?, ?, ?)
		ON CONFLICT(month) DO UPDATE SET sfu = excluded.sfu, turn = excluded.turn, measured_until = excluded.measured_until,
			estimate_base = excluded.estimate_base, measured_at = excluded.measured_at`,
		month, rec.MeasuredSFU, rec.MeasuredTURN, rec.MeasuredUntil, rec.EstimateBase, rec.MeasuredAt)
	return err
}

// AddCallUsage adds a stretch of each account's calls to its day.
func (s *Store) AddCallUsage(ctx context.Context, day string, rows []realtime.CallUsage) error {
	if len(rows) == 0 {
		return nil
	}
	return s.db.Tx(ctx, func(tx *db.Tx) error {
		for _, r := range rows {
			if r.User == 0 {
				continue
			}
			if _, err := tx.ExecContext(ctx, `INSERT INTO call_usage (user_id, day, received, sent, seconds) VALUES (?, ?, ?, ?, ?)
				ON CONFLICT(user_id, day) DO UPDATE SET received = call_usage.received + excluded.received,
					sent = call_usage.sent + excluded.sent, seconds = call_usage.seconds + excluded.seconds`,
				r.User, day, r.Received, r.Sent, r.Seconds); err != nil {
				return err
			}
		}
		return nil
	})
}

// CallMeasurement is what Cloudflare measured of one account's calls on a
// day: SFU egress to its devices, SFU egress its own tracks caused, and TURN
// egress relayed to it. User 0 is what no account could be named for.
type CallMeasurement struct {
	User     ids.ID
	Received int64
	Sent     int64
	Relay    int64
}

// SaveCallMeasurements makes a day's measured columns exactly the measurement:
// every account kept for the day is marked measured, at zero unless listed.
func (s *Store) SaveCallMeasurements(ctx context.Context, day string, rows []CallMeasurement, at int64) error {
	return s.db.Tx(ctx, func(tx *db.Tx) error {
		if _, err := tx.ExecContext(ctx, `UPDATE call_usage SET m_received = 0, m_sent = 0, m_relay = 0, measured_at = ? WHERE day = ?`, at, day); err != nil {
			return err
		}
		// user 0 marks the day as measured, even when nothing went unnamed
		listed := false
		for _, r := range rows {
			listed = listed || r.User == 0
			if _, err := tx.ExecContext(ctx, `INSERT INTO call_usage (user_id, day, m_received, m_sent, m_relay, measured_at) VALUES (?, ?, ?, ?, ?, ?)
				ON CONFLICT(user_id, day) DO UPDATE SET m_received = excluded.m_received, m_sent = excluded.m_sent,
					m_relay = excluded.m_relay, measured_at = excluded.measured_at`,
				int64(r.User), day, r.Received, r.Sent, r.Relay, at); err != nil {
				return err
			}
		}
		if !listed {
			_, err := tx.ExecContext(ctx, `INSERT INTO call_usage (user_id, day, measured_at) VALUES (0, ?, ?)
				ON CONFLICT(user_id, day) DO UPDATE SET measured_at = excluded.measured_at`, day, at)
			return err
		}
		return nil
	})
}

// CallUsageTotal is an account's calls over a span of days: Cloudflare's
// measurement for the days it has measured, and the estimate for the rest
// (Relay is measured only). Measured says whether every day in it was.
type CallUsageTotal struct {
	User     ids.ID `json:"-"`
	Received int64  `json:"received_bytes"`
	Sent     int64  `json:"sent_bytes"`
	Relay    int64  `json:"relay_bytes"`
	Seconds  int64  `json:"seconds"`
	Measured bool   `json:"measured"`
	LastDay  string `json:"last_day"`
}

// what each row of call_usage counts as: the measurement once its day has one
const callUsageCols = `user_id,
	SUM(CASE WHEN measured_at > 0 THEN m_received ELSE received END) AS r,
	SUM(CASE WHEN measured_at > 0 THEN m_sent ELSE sent END) AS s,
	SUM(m_relay) AS relay,
	SUM(seconds) AS secs,
	MIN(CASE WHEN measured_at > 0 THEN 1 ELSE 0 END) AS measured,
	MAX(day) AS last_day`

func scanCallTotals(rows *db.Rows) ([]CallUsageTotal, error) {
	defer rows.Close()
	var out []CallUsageTotal
	for rows.Next() {
		var t CallUsageTotal
		var measured int64
		if err := rows.Scan(&t.User, &t.Received, &t.Sent, &t.Relay, &t.Seconds, &measured, &t.LastDay); err != nil {
			return nil, err
		}
		t.Measured = measured == 1
		out = append(out, t)
	}
	return out, rows.Err()
}

// Orders for CallUsageTop.
const (
	CallOrderUsage = "usage"
	CallOrderSent  = "sent"
	CallOrderTime  = "time"
)

// CallUsageTop lists accounts by what their calls used over the days from
// first to last (YYYY-MM-DD, both included), largest first: by what reached
// their own devices (SFU and relay), by what their tracks sent others, or by
// time in calls. Only accounts that used something are listed.
func (s *Store) CallUsageTop(ctx context.Context, first, last, order string, limit, offset int) ([]CallUsageTotal, error) {
	if limit <= 0 || limit > 200 {
		limit = 100
	}
	orderBy := "r + relay DESC"
	switch order {
	case CallOrderSent:
		orderBy = "s DESC"
	case CallOrderTime:
		orderBy = "secs DESC"
	}
	rows, err := s.db.R.QueryContext(ctx, `SELECT * FROM (SELECT `+callUsageCols+` FROM call_usage
		WHERE user_id <> 0 AND day >= ? AND day <= ? GROUP BY user_id) AS t
		WHERE r + s + relay + secs > 0
		ORDER BY `+orderBy+`, user_id LIMIT ? OFFSET ?`, first, last, limit, max(0, offset))
	if err != nil {
		return nil, err
	}
	return scanCallTotals(rows)
}

// CallUsageFor returns one account's calls over the days from first to last;
// user 0 gives what Cloudflare measured that no account could be named for.
func (s *Store) CallUsageFor(ctx context.Context, user ids.ID, first, last string) (CallUsageTotal, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT `+callUsageCols+` FROM call_usage
		WHERE user_id = ? AND day >= ? AND day <= ? GROUP BY user_id`, int64(user), first, last)
	if err != nil {
		return CallUsageTotal{}, err
	}
	list, err := scanCallTotals(rows)
	if err != nil || len(list) == 0 {
		return CallUsageTotal{User: user}, err
	}
	return list[0], nil
}

// CallUsageDays lists an account's days, newest first, for the legal report.
func (s *Store) CallUsageDays(ctx context.Context, user ids.ID, limit int) ([]CallUsageDay, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT day, received, sent, seconds, m_received, m_sent, m_relay, measured_at
		FROM call_usage WHERE user_id = ? ORDER BY day DESC LIMIT ?`, int64(user), limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []CallUsageDay
	for rows.Next() {
		var d CallUsageDay
		if err := rows.Scan(&d.Day, &d.Received, &d.Sent, &d.Seconds, &d.MReceived, &d.MSent, &d.MRelay, &d.MeasuredAt); err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

// CallUsageDay is one row of call_usage as kept.
type CallUsageDay struct {
	Day        string
	Received   int64
	Sent       int64
	Seconds    int64
	MReceived  int64
	MSent      int64
	MRelay     int64
	MeasuredAt int64
}

// DeleteCallUsageBefore deletes the days before day (YYYY-MM-DD).
func (s *Store) DeleteCallUsageBefore(ctx context.Context, day string) error {
	_, err := s.db.W.ExecContext(ctx, `DELETE FROM call_usage WHERE day < ?`, day)
	return err
}

// RecordRealtimeSession notes which account an SFU session belongs to.
func (s *Store) RecordRealtimeSession(ctx context.Context, session string, user ids.ID) error {
	_, err := s.db.W.ExecContext(ctx, `INSERT INTO realtime_sessions (session_id, user_id, created_at) VALUES (?, ?, ?)
		ON CONFLICT(session_id) DO NOTHING`, session, int64(user), nowMs())
	return err
}

// RealtimeSessionUsers returns the account of each session it knows.
func (s *Store) RealtimeSessionUsers(ctx context.Context, sessions []string) (map[string]ids.ID, error) {
	out := map[string]ids.ID{}
	for start := 0; start < len(sessions); start += 400 {
		part := sessions[start:min(len(sessions), start+400)]
		args := make([]any, len(part))
		for i, sid := range part {
			args[i] = sid
		}
		rows, err := s.db.R.QueryContext(ctx, `SELECT session_id, user_id FROM realtime_sessions WHERE session_id IN (`+strings.TrimSuffix(strings.Repeat("?,", len(part)), ",")+`)`, args...)
		if err != nil {
			return nil, err
		}
		for rows.Next() {
			var sid string
			var uid int64
			if err := rows.Scan(&sid, &uid); err != nil {
				rows.Close()
				return nil, err
			}
			out[sid] = ids.ID(uid)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return nil, err
		}
	}
	return out, nil
}

// RealtimeSessionCount counts an account's recorded sessions, for the legal report.
func (s *Store) RealtimeSessionCount(ctx context.Context, user ids.ID) (int, error) {
	var n int
	err := s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM realtime_sessions WHERE user_id = ?`, int64(user)).Scan(&n)
	return n, err
}

// DeleteRealtimeSessionsBefore deletes sessions made before the time (Unix ms).
func (s *Store) DeleteRealtimeSessionsBefore(ctx context.Context, before int64) error {
	_, err := s.db.W.ExecContext(ctx, `DELETE FROM realtime_sessions WHERE created_at < ?`, before)
	return err
}

// TurnCredential is one TURN credential handed out, named by its username.
type TurnCredential struct {
	Username  string
	User      ids.ID
	CreatedAt int64
	ExpiresAt int64
}

// SaveTurnCredential records a credential as it is handed out.
func (s *Store) SaveTurnCredential(ctx context.Context, c TurnCredential) error {
	_, err := s.db.W.ExecContext(ctx, `INSERT INTO turn_credentials (username, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)
		ON CONFLICT(username) DO NOTHING`, c.Username, int64(c.User), c.CreatedAt, c.ExpiresAt)
	return err
}

// LiveTurnCredentials lists the credentials neither revoked nor expired by now (Unix ms).
func (s *Store) LiveTurnCredentials(ctx context.Context, now int64) ([]TurnCredential, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT username, user_id, created_at, expires_at FROM turn_credentials
		WHERE revoked_at = 0 AND expires_at > ? ORDER BY created_at`, now)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []TurnCredential
	for rows.Next() {
		var c TurnCredential
		var uid int64
		if err := rows.Scan(&c.Username, &uid, &c.CreatedAt, &c.ExpiresAt); err != nil {
			return nil, err
		}
		c.User = ids.ID(uid)
		out = append(out, c)
	}
	return out, rows.Err()
}

// MarkTurnCredentialRevoked records that a credential was revoked.
func (s *Store) MarkTurnCredentialRevoked(ctx context.Context, username string, at int64) error {
	_, err := s.db.W.ExecContext(ctx, `UPDATE turn_credentials SET revoked_at = ? WHERE username = ?`, at, username)
	return err
}

// TurnCredentialCount counts the credentials an account was handed that are
// still kept, for the legal report.
func (s *Store) TurnCredentialCount(ctx context.Context, user ids.ID) (int, error) {
	var n int
	err := s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM turn_credentials WHERE user_id = ?`, int64(user)).Scan(&n)
	return n, err
}

// DeleteExpiredTurnCredentials deletes the credentials that expired before now (Unix ms).
func (s *Store) DeleteExpiredTurnCredentials(ctx context.Context, now int64) error {
	_, err := s.db.W.ExecContext(ctx, `DELETE FROM turn_credentials WHERE expires_at <= ?`, now)
	return err
}
