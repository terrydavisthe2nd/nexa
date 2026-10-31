package api

import (
	"context"
	"net/http"
	"strconv"
	"strings"
	"time"

	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/realtime"
	"evilion.xyz/chat/internal/store"
)

// What calls cost, and who they cost it for. Every five minutes, with
// REALTIME_ACCOUNT_ID and REALTIME_ANALYTICS_TOKEN set, the meter takes
// Cloudflare's measurement of the month (SFU and TURN egress, which share the
// allowance), and the day's egress is put to names for the staff's Calls
// page: SFU egress by the account that opened the session it went to, and by
// the account whose published track it carried (track names start with it),
// and TURN egress by the account its credential was minted for. What cannot
// be named (a session from before this was kept, or anything using the keys
// from elsewhere) is kept as account 0. Without the settings the page shows
// the meter's own estimate of the same, from the tracks each device pulls.

const (
	realtimePollEvery = 5 * time.Minute
	// realtimeFirstPoll lets the server settle before its first measurement.
	realtimeFirstPoll = 30 * time.Second
	// callUsageKeepDays is how long each account's daily usage is kept: the
	// month the allowance runs over and the one before it.
	callUsageKeepDays   = 62
	realtimeSessionKeep = 3 * 24 * time.Hour
)

// StartRealtime runs, until ctx ends, the TURN sweep, the measurement and the
// clearing out of old records.
func (s *Server) StartRealtime(ctx context.Context) {
	if s.Meter == nil && s.turnAPI() == nil {
		return
	}
	go func() {
		sweep := time.NewTicker(turnSweepEvery)
		defer sweep.Stop()
		first := time.NewTimer(realtimeFirstPoll)
		defer first.Stop()
		poll := time.NewTicker(realtimePollEvery)
		defer poll.Stop()
		tidy := time.NewTicker(time.Hour)
		defer tidy.Stop()
		s.tidyRealtime(ctx)
		measure := func() {
			if err := s.pollRealtime(ctx); err != nil && ctx.Err() == nil {
				s.Log.Warn("realtime measurement", "err", err)
			}
		}
		for {
			select {
			case <-ctx.Done():
				return
			case <-sweep.C:
				s.sweepTURN(ctx)
			case <-first.C:
				measure()
			case <-poll.C:
				measure()
			case <-tidy.C:
				s.tidyRealtime(ctx)
			}
		}
	}()
}

// pollRealtime takes Cloudflare's measurement: the month for the meter, the
// day (and the day before, for its last minutes) for each account, and cuts
// off the relay of accounts past the day's limit.
func (s *Server) pollRealtime(ctx context.Context) error {
	if s.Analytics == nil || s.Meter == nil {
		return nil
	}
	now := time.Now().UTC()
	monthStart := realtime.MonthStart(now)
	until := now.Add(-realtime.AnalyticsLag).Truncate(time.Minute)
	if until.Before(monthStart) {
		until = monthStart
	}
	dayStart := now.Truncate(24 * time.Hour)
	days := []realtime.Window{{From: dayStart, To: now}}
	if now.Sub(dayStart) < 2*time.Hour {
		days = append(days, realtime.Window{From: dayStart.Add(-24 * time.Hour), To: dayStart})
	}
	qctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	totals, attributions, err := s.Analytics.Read(qctx, realtime.Window{From: monthStart, To: until}, days...)
	cancel()
	if err != nil {
		return err
	}
	if _, err := s.Meter.Measure(ctx, until, totals.SFU, totals.TURN); err != nil {
		return err
	}
	for i, a := range attributions {
		relay, err := s.saveCallMeasurements(ctx, a)
		if err != nil {
			return err
		}
		if i == 0 {
			if limit := s.turnDailyLimit(); limit > 0 {
				for uid, n := range relay {
					if n >= limit {
						s.blockTURN(uid, n, now)
					}
				}
			}
		}
	}
	return nil
}

// trackOwner is the account a published track belongs to: its name starts
// with it (sfu_handlers.go names tracks account-kind-random).
func trackOwner(track string) (ids.ID, bool) {
	head, _, ok := strings.Cut(track, "-")
	if !ok {
		return 0, false
	}
	uid, err := ids.Parse(head)
	return uid, err == nil && uid != 0
}

// saveCallMeasurements puts a day's measured egress to names and keeps it,
// returning what each account relayed through TURN.
func (s *Server) saveCallMeasurements(ctx context.Context, a realtime.Attribution) (map[ids.ID]int64, error) {
	rows := map[ids.ID]*store.CallMeasurement{}
	row := func(uid ids.ID) *store.CallMeasurement {
		if rows[uid] == nil {
			rows[uid] = &store.CallMeasurement{User: uid}
		}
		return rows[uid]
	}
	row(0)
	sessions := make([]string, 0, len(a.Sessions))
	for sid := range a.Sessions {
		sessions = append(sessions, sid)
	}
	owners, err := s.Store.RealtimeSessionUsers(ctx, sessions)
	if err != nil {
		return nil, err
	}
	var named int64
	for sid, n := range a.Sessions {
		if uid, ok := owners[sid]; ok {
			row(uid).Received += n
			named += n
		}
	}
	row(0).Received += max(0, a.Totals.SFU-named)
	named = 0
	for track, n := range a.Tracks {
		if uid, ok := trackOwner(track); ok {
			row(uid).Sent += n
			named += n
		}
	}
	row(0).Sent += max(0, a.Totals.SFU-named)
	named = 0
	relay := map[ids.ID]int64{}
	for ident, n := range a.TURN {
		if uid, err := ids.Parse(ident); err == nil && uid != 0 {
			row(uid).Relay += n
			relay[uid] += n
			named += n
		}
	}
	row(0).Relay += max(0, a.Totals.TURN-named)
	list := make([]store.CallMeasurement, 0, len(rows))
	for _, r := range rows {
		list = append(list, *r)
	}
	return relay, s.Store.SaveCallMeasurements(ctx, realtime.DayOf(a.Window.From), list, time.Now().UnixMilli())
}

// tidyRealtime deletes what is kept no longer: daily usage past
// callUsageKeepDays, session owners past realtimeSessionKeep, expired TURN
// credentials.
func (s *Server) tidyRealtime(ctx context.Context) {
	now := time.Now()
	if err := s.Store.DeleteCallUsageBefore(ctx, realtime.DayOf(now.AddDate(0, 0, -callUsageKeepDays))); err != nil {
		s.Log.Warn("call usage clean-up", "err", err)
	}
	if err := s.Store.DeleteRealtimeSessionsBefore(ctx, now.Add(-realtimeSessionKeep).UnixMilli()); err != nil {
		s.Log.Warn("realtime session clean-up", "err", err)
	}
	if err := s.Store.DeleteExpiredTurnCredentials(ctx, now.UnixMilli()); err != nil {
		s.Log.Warn("turn credential clean-up", "err", err)
	}
}

// Periods the Calls page shows, in UTC days, as the allowance counts them.
const (
	callPeriodToday = "today"
	callPeriodWeek  = "week"
	callPeriodMonth = "month"
)

// callPeriod is a period's first and last day.
func callPeriod(period string, now time.Time) (string, string, string) {
	today := now.UTC()
	switch period {
	case callPeriodWeek:
		return period, realtime.DayOf(today.AddDate(0, 0, -6)), realtime.DayOf(today)
	case callPeriodMonth:
		return period, realtime.DayOf(realtime.MonthStart(today)), realtime.DayOf(today)
	}
	return callPeriodToday, realtime.DayOf(today), realtime.DayOf(today)
}

// modCallRow is one account on the Calls page.
type modCallRow struct {
	UserID ids.ID `json:"user_id"`
	// User is nil for someone with no account (a guest) or one deleted since.
	User *store.PublicUser `json:"user"`
	store.CallUsageTotal
	InCall      bool `json:"in_call"`
	RelayCutOff bool `json:"relay_cut_off"`
}

const callPageSize = 50

// handleModCalls lists accounts by what their calls used over a period.
func (s *Server) handleModCalls(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	q := r.URL.Query()
	period, first, last := callPeriod(q.Get("period"), time.Now())
	order := q.Get("sort")
	if order != store.CallOrderSent && order != store.CallOrderTime {
		order = store.CallOrderUsage
	}
	offset, _ := strconv.Atoi(q.Get("offset"))
	list, err := s.Store.CallUsageTop(ctx, first, last, order, callPageSize+1, offset)
	if err != nil {
		WriteError(w, err)
		return
	}
	more := len(list) > callPageSize
	if more {
		list = list[:callPageSize]
	}
	rows := make([]modCallRow, 0, len(list))
	for _, t := range list {
		row := modCallRow{UserID: t.User, CallUsageTotal: t, RelayCutOff: s.turnCutOff(t.User)}
		if u, err := s.Store.UserByID(ctx, t.User); err == nil {
			pu := u.Public()
			row.User = &pu
		}
		_, row.InCall = s.Voice.Get(t.User)
		rows = append(rows, row)
	}
	unnamed, err := s.Store.CallUsageFor(ctx, 0, first, last)
	if err != nil {
		WriteError(w, err)
		return
	}
	out := map[string]any{
		"period": period, "first_day": first, "last_day": last, "sort": order,
		"accounts": rows, "has_more": more, "unattributed": unnamed,
		"measurements":           s.Analytics != nil,
		"turn_daily_limit_bytes": s.turnDailyLimit(),
	}
	if s.Meter != nil {
		out["meter"] = s.Meter.Status(ctx)
	}
	WriteJSON(w, http.StatusOK, out)
}

// handleModUserCalls is one account's calls today, over the last seven days
// and this month.
func (s *Server) handleModUserCalls(w http.ResponseWriter, r *http.Request) {
	u := s.modTarget(w, r)
	if u == nil {
		return
	}
	ctx := r.Context()
	now := time.Now()
	out := map[string]any{"measurements": s.Analytics != nil, "relay_cut_off": s.turnCutOff(u.ID)}
	for _, p := range []string{callPeriodToday, callPeriodWeek, callPeriodMonth} {
		_, first, last := callPeriod(p, now)
		t, err := s.Store.CallUsageFor(ctx, u.ID, first, last)
		if err != nil {
			WriteError(w, err)
			return
		}
		out[p] = t
	}
	_, inCall := s.Voice.Get(u.ID)
	out["in_call"] = inCall
	WriteJSON(w, http.StatusOK, out)
}
