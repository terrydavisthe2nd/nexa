package realtime

import (
	"context"
	"sync"
	"time"
)

// Kinds of track a call device publishes.
const (
	KindAudio       = "audio"
	KindVideo       = "video"
	KindScreen      = "screen"
	KindScreenAudio = "screen_audio"
)

// ValidKind reports whether k is one of the four kinds.
func ValidKind(k string) bool {
	return k == KindAudio || k == KindVideo || k == KindScreen || k == KindScreenAudio
}

// IsVideo reports whether a kind is paused when the allowance runs low.
func IsVideo(k string) bool { return k == KindVideo || k == KindScreen }

// The rates a pulled track is estimated at, in bits per second: the most each
// kind carries from a Nexa app (the camera sender is capped to match), so the
// estimate errs high. Cloudflare's own measurement, when the meter has one,
// replaces the estimate for everything but its last few minutes.
const (
	AudioBps = 64_000
	// StreamAudioBps is a screen share's sound: stereo for music and games,
	// asked for at 128 kbps (web voice/opus.ts STREAM_AUDIO_BITRATE).
	StreamAudioBps   = 128_000
	CameraBps        = 1_500_000
	DefaultScreenBps = 5_000_000
	minScreenBps     = 1_000_000
	// Pro shares up to 20 Mbit/s (api/billing.go)
	maxScreenBps = 20_000_000
)

// ClampVoiceBps is what a voice is estimated at from the bitrate its app
// declared: AudioBps, or as much as a Dual Mono or True Stereo voice sends
// (web voice/opus.ts VOICE_STEREO_BITRATE). An app can understate this, and
// Cloudflare's measurement then corrects the month within minutes.
func ClampVoiceBps(bps int64) int64 {
	return min(StreamAudioBps, max(AudioBps, bps))
}

// ClampScreenBps bounds a screen share's bitrate.
func ClampScreenBps(bps int64) int64 {
	if bps <= 0 {
		return DefaultScreenBps
	}
	return min(maxScreenBps, max(minScreenBps, bps))
}

// RateFor returns the bits per second a pulled track of kind is estimated at.
// For a screen share, screenBps is the most the sharer's plan lets a share
// send, which the server knows, never the bitrate the sender declared.
func RateFor(kind string, screenBps int64) int64 {
	switch kind {
	case KindVideo:
		return CameraBps
	case KindScreen:
		return ClampScreenBps(screenBps)
	case KindScreenAudio:
		return StreamAudioBps
	default:
		return AudioBps
	}
}

// MonthRecord is what is kept of a month: the running estimate, and the latest
// measurement from Cloudflare with the estimate's value at the moment that
// measurement ends (MeasuredUntil, zero when there is none).
type MonthRecord struct {
	Bytes         int64
	MeasuredSFU   int64
	MeasuredTURN  int64
	MeasuredUntil int64
	EstimateBase  int64
	MeasuredAt    int64
}

// CallUsage is what one account's calls cost over a stretch of a day: the
// estimated SFU egress to its devices, the estimated egress its own tracks
// caused, and the time it spent in calls.
type CallUsage struct {
	User     int64
	Received int64
	Sent     int64
	Seconds  int64
}

// UsageStore keeps the monthly totals and each account's daily usage.
type UsageStore interface {
	AddRealtimeUsage(ctx context.Context, month string, bytes int64) (int64, error)
	RealtimeMonth(ctx context.Context, month string) (MonthRecord, error)
	SaveRealtimeMeasurement(ctx context.Context, month string, rec MonthRecord) error
	AddCallUsage(ctx context.Context, day string, rows []CallUsage) error
}

type pull struct {
	// source is the published track pulled: its SFU session and kind
	source string
	bps    int64
	since  time.Time
	// viewer is the account whose device pulls it, publisher the account
	// whose track it is (zero when unknown)
	viewer    int64
	publisher int64
}

// Source names a published track for the meter.
func Source(session, kind string) string { return session + ":" + kind }

// snap is the month's estimate as written at one moment.
type snap struct {
	at    time.Time
	month string
	total int64
}

// snapKeep is how long the estimate's history is kept, to tell what it added
// after a measurement ends; well past AnalyticsLag and a missed poll or two.
const snapKeep = 3 * time.Hour

// Meter keeps the month's Realtime egress against the allowance. It
// estimates SFU egress from the tracks devices pull, and when Cloudflare's
// measurement of the account (SFU and TURN) is fed in, the month's usage is
// that measurement plus the estimate for the minutes after it. Video pauses
// at 90 % of the allowance and new calls stop at 99 %.
type Meter struct {
	mu    sync.Mutex
	st    UsageStore
	now   func() time.Time
	limit int64
	pulls map[string]map[string]pull
	// off holds the sources sending nothing just now (a camera turned off, a
	// share ended); pulls of them are not charged meanwhile
	off map[string]bool
	// month and used are the stored estimate the counts below add to
	month     string
	used      int64
	loaded    bool
	unflushed int64
	// what each account's calls cost since the last write
	received map[int64]int64
	sent     map[int64]int64
	seconds  map[int64]int64
	// participants lists the accounts in calls through the SFU just now
	participants func() []int64
	lastTick     time.Time
	// measured is the month's latest measurement, and snaps the estimate as
	// written since this process started
	measured    MonthRecord
	measuredFor string
	snaps       []snap
}

// NewMeter meters against a monthly allowance in gigabytes.
func NewMeter(st UsageStore, monthlyGB int64) *Meter {
	return &Meter{
		st: st, now: time.Now, limit: monthlyGB * 1_000_000_000,
		pulls: map[string]map[string]pull{}, off: map[string]bool{},
		received: map[int64]int64{}, sent: map[int64]int64{}, seconds: map[int64]int64{},
	}
}

func monthOf(t time.Time) string { return t.UTC().Format("2006-01") }

// DayOf is the UTC day a moment falls in, as daily usage is kept.
func DayOf(t time.Time) string { return t.UTC().Format("2006-01-02") }

// MonthStart is the first moment of t's month, in UTC, as Cloudflare bills it.
func MonthStart(t time.Time) time.Time {
	u := t.UTC()
	return time.Date(u.Year(), u.Month(), 1, 0, 0, 0, 0, time.UTC)
}

func bytesFor(bps int64, d time.Duration) int64 {
	if d <= 0 {
		return 0
	}
	return bps * d.Milliseconds() / 8000
}

// charge is what a pull has used since it was last counted.
func (m *Meter) charge(p pull, now time.Time) int64 {
	if m.off[p.source] {
		return 0
	}
	return bytesFor(p.bps, now.Sub(p.since))
}

// settle counts what a pull has used up to now, for the month and for the
// two accounts it belongs to.
func (m *Meter) settle(p pull, now time.Time) {
	n := m.charge(p, now)
	if n == 0 {
		return
	}
	m.unflushed += n
	if p.viewer != 0 {
		m.received[p.viewer] += n
	}
	if p.publisher != 0 {
		m.sent[p.publisher] += n
	}
}

// SetParticipants tells the meter how to list the accounts in calls through
// the SFU, whose time in calls it counts at every write.
func (m *Meter) SetParticipants(fn func() []int64) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.participants = fn
}

// Start begins charging a pulled track of source, which viewer's device pulls
// from publisher.
func (m *Meter) Start(session, mid, source string, bps, viewer, publisher int64) {
	m.mu.Lock()
	defer m.mu.Unlock()
	now := m.now()
	byMid := m.pulls[session]
	if byMid == nil {
		byMid = map[string]pull{}
		m.pulls[session] = byMid
	}
	if old, ok := byMid[mid]; ok {
		m.settle(old, now)
	}
	// One session watching one track twice sees it once: a second pull of
	// the same source replaces the first rather than being charged beside it,
	// so asking for a track over and over cannot run the allowance down.
	for other, p := range byMid {
		if other != mid && p.source == source {
			m.settle(p, now)
			delete(byMid, other)
		}
	}
	byMid[mid] = pull{source: source, bps: bps, since: now, viewer: viewer, publisher: publisher}
}

// ActiveMid reuses an existing provider subscription. Allocating a duplicate
// while merely deduplicating its meter entry would leave real egress uncharged.
func (m *Meter) ActiveMid(session, source string) (string, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for mid, p := range m.pulls[session] {
		if p.source == source {
			return mid, true
		}
	}
	return "", false
}

// StopSource follows a confirmed publication closure, which also stops all its
// subscriptions at the provider.
func (m *Meter) StopSource(source string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	now := m.now()
	for session, byMid := range m.pulls {
		for mid, p := range byMid {
			if p.source == source {
				m.settle(p, now)
				delete(byMid, mid)
			}
		}
		if len(byMid) == 0 {
			delete(m.pulls, session)
		}
	}
	delete(m.off, source)
}

// Stop ends charging one pulled track.
func (m *Meter) Stop(session, mid string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if p, ok := m.pulls[session][mid]; ok {
		m.settle(p, m.now())
		delete(m.pulls[session], mid)
		if len(m.pulls[session]) == 0 {
			delete(m.pulls, session)
		}
	}
}

// StopSession ends charging everything a session pulls, and forgets what it published.
func (m *Meter) StopSession(session string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	now := m.now()
	for _, p := range m.pulls[session] {
		m.settle(p, now)
	}
	delete(m.pulls, session)
	ofSession := func(source string) bool {
		return len(source) > len(session) && source[:len(session)+1] == session+":"
	}
	// What others pulled from this session ends with it: a track that no
	// longer exists costs nothing, whether or not their clients have closed
	// it yet.
	for other, byMid := range m.pulls {
		for mid, p := range byMid {
			if ofSession(p.source) {
				m.settle(p, now)
				delete(byMid, mid)
			}
		}
		if len(byMid) == 0 {
			delete(m.pulls, other)
		}
	}
	for source := range m.off {
		if ofSession(source) {
			delete(m.off, source)
		}
	}
}

// Charging reports whether anything a session pulls, or anything pulled from
// it, is still being charged.
func (m *Meter) Charging(session string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	if len(m.pulls[session]) > 0 {
		return true
	}
	prefix := session + ":"
	for _, byMid := range m.pulls {
		for _, p := range byMid {
			if len(p.source) > len(prefix) && p.source[:len(prefix)] == prefix {
				return true
			}
		}
	}
	return false
}

// SetSourceLive says whether a published track is sending. Pulls of a track
// that is off (a camera turned off) cost nothing until it is on again.
func (m *Meter) SetSourceLive(source string, live bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if !m.off[source] == live {
		return
	}
	now := m.now()
	for _, byMid := range m.pulls {
		for mid, p := range byMid {
			if p.source != source {
				continue
			}
			m.settle(p, now)
			p.since = now
			byMid[mid] = p
		}
	}
	if live {
		delete(m.off, source)
	} else {
		m.off[source] = true
	}
}

// ensureMonth loads the stored month when it is new to the meter.
func (m *Meter) ensureMonth(ctx context.Context, now time.Time) {
	month := monthOf(now)
	if m.loaded && m.month == month {
		return
	}
	rec, err := m.st.RealtimeMonth(ctx, month)
	if err != nil {
		return
	}
	m.month, m.used, m.loaded = month, rec.Bytes, true
	if rec.MeasuredUntil > 0 {
		m.measured, m.measuredFor = rec, month
	} else if m.measuredFor != month {
		m.measured, m.measuredFor = MonthRecord{}, ""
	}
}

// takeUsage hands over what each account's calls cost since the last write.
func (m *Meter) takeUsage() []CallUsage {
	users := map[int64]*CallUsage{}
	row := func(id int64) *CallUsage {
		if users[id] == nil {
			users[id] = &CallUsage{User: id}
		}
		return users[id]
	}
	for id, n := range m.received {
		row(id).Received += n
	}
	for id, n := range m.sent {
		row(id).Sent += n
	}
	for id, n := range m.seconds {
		row(id).Seconds += n
	}
	m.received, m.sent, m.seconds = map[int64]int64{}, map[int64]int64{}, map[int64]int64{}
	out := make([]CallUsage, 0, len(users))
	for _, u := range users {
		out = append(out, *u)
	}
	return out
}

// giveBack returns usage a failed write could not keep, for the next write.
func (m *Meter) giveBack(rows []CallUsage) {
	for _, r := range rows {
		if r.Received != 0 {
			m.received[r.User] += r.Received
		}
		if r.Sent != 0 {
			m.sent[r.User] += r.Sent
		}
		if r.Seconds != 0 {
			m.seconds[r.User] += r.Seconds
		}
	}
}

// Flush writes what running and finished pulls have used so far, and each
// account's share of it.
func (m *Meter) Flush(ctx context.Context) error {
	m.mu.Lock()
	list := m.participants
	m.mu.Unlock()
	var inCalls []int64
	if list != nil {
		inCalls = list()
	}

	m.mu.Lock()
	now := m.now()
	for _, byMid := range m.pulls {
		for mid, p := range byMid {
			m.settle(p, now)
			p.since = now
			byMid[mid] = p
		}
	}
	// time in calls since the last write, not counting a pause in between
	// (a process stopped or a write stuck) as anyone's call
	if !m.lastTick.IsZero() {
		if step := now.Sub(m.lastTick); step > 0 && step <= 3*time.Minute {
			for _, id := range inCalls {
				m.seconds[id] += int64(step / time.Second)
			}
		}
	}
	m.lastTick = now
	add := m.unflushed
	m.unflushed = 0
	usage := m.takeUsage()
	month := monthOf(now)
	day := DayOf(now)
	m.mu.Unlock()

	if len(usage) > 0 {
		if err := m.st.AddCallUsage(ctx, day, usage); err != nil {
			m.mu.Lock()
			m.giveBack(usage)
			m.unflushed += add
			m.mu.Unlock()
			return err
		}
	}
	if add == 0 {
		m.mu.Lock()
		m.ensureMonth(ctx, now)
		m.remember(now, month)
		m.mu.Unlock()
		return nil
	}
	total, err := m.st.AddRealtimeUsage(ctx, month, add)
	m.mu.Lock()
	defer m.mu.Unlock()
	if err != nil {
		m.unflushed += add
		return err
	}
	if m.month != month {
		m.loaded = false
		m.ensureMonth(ctx, now)
	}
	m.month, m.used, m.loaded = month, total, true
	m.remember(now, month)
	return nil
}

// remember keeps the estimate as it stands after a write, for measurements to
// be lined up against.
func (m *Meter) remember(now time.Time, month string) {
	if !m.loaded || m.month != month {
		return
	}
	m.snaps = append(m.snaps, snap{at: now, month: month, total: m.used})
	cut := 0
	for cut < len(m.snaps) && now.Sub(m.snaps[cut].at) > snapKeep {
		cut++
	}
	m.snaps = m.snaps[cut:]
}

// estimateAt is the month's estimate as it stood at t, from the latest write
// at or before it; false when this process has no write that early.
func (m *Meter) estimateAt(month string, t time.Time) (int64, bool) {
	if !t.After(MonthStart(t)) {
		return 0, true
	}
	found, total := false, int64(0)
	for _, s := range m.snaps {
		if s.month != month || s.at.After(t) {
			continue
		}
		found, total = true, s.total
	}
	return total, found
}

// Measure takes Cloudflare's measurement of the account's egress from the
// month's start to until (SFU and TURN). From then on the month's usage is
// that measurement plus what the estimate added after until. It reports
// false, keeping the measurement out, when this process has not run long
// enough to know what the estimate stood at by until.
func (m *Meter) Measure(ctx context.Context, until time.Time, sfu, turn int64) (bool, error) {
	m.mu.Lock()
	now := m.now()
	m.ensureMonth(ctx, now)
	month := monthOf(until)
	if month != monthOf(now) {
		m.mu.Unlock()
		return false, nil
	}
	base, ok := m.estimateAt(month, until)
	if !ok {
		m.mu.Unlock()
		return false, nil
	}
	rec := MonthRecord{MeasuredSFU: sfu, MeasuredTURN: turn, MeasuredUntil: until.UnixMilli(), EstimateBase: base, MeasuredAt: now.UnixMilli()}
	m.mu.Unlock()
	if err := m.st.SaveRealtimeMeasurement(ctx, month, rec); err != nil {
		return false, err
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	rec.Bytes = m.used
	m.measured, m.measuredFor = rec, month
	return true, nil
}

// estimateLocked is the month's estimate now, including what has not been written yet.
func (m *Meter) estimateLocked(now time.Time) int64 {
	total := m.used + m.unflushed
	for _, byMid := range m.pulls {
		for _, p := range byMid {
			total += m.charge(p, now)
		}
	}
	return total
}

// usedLocked is the month's usage: the latest measurement and what the
// estimate added since, or the estimate alone without one.
func (m *Meter) usedLocked(now time.Time) int64 {
	estimate := m.estimateLocked(now)
	if m.measuredFor == monthOf(now) && m.measured.MeasuredUntil > 0 {
		return m.measured.MeasuredSFU + m.measured.MeasuredTURN + max(0, estimate-m.measured.EstimateBase)
	}
	return estimate
}

// Used is this month's usage as the meter knows it.
func (m *Meter) Used(ctx context.Context) int64 {
	m.mu.Lock()
	defer m.mu.Unlock()
	now := m.now()
	m.ensureMonth(ctx, now)
	return m.usedLocked(now)
}

// Status is the meter as the staff see it.
type Status struct {
	Month    string `json:"month"`
	Used     int64  `json:"used_bytes"`
	Limit    int64  `json:"limit_bytes"`
	Estimate int64  `json:"estimate_bytes"`
	// Measured is true when Used rests on Cloudflare's measurement, which
	// covers SFU and TURN up to MeasuredUntil (Unix milliseconds).
	Measured      bool  `json:"measured"`
	MeasuredSFU   int64 `json:"measured_sfu_bytes"`
	MeasuredTURN  int64 `json:"measured_turn_bytes"`
	MeasuredUntil int64 `json:"measured_until"`
	MeasuredAt    int64 `json:"measured_at"`
	VideoPaused   bool  `json:"video_paused"`
	CallsPaused   bool  `json:"calls_paused"`
}

// Status describes the month for the staff.
func (m *Meter) Status(ctx context.Context) Status {
	m.mu.Lock()
	defer m.mu.Unlock()
	now := m.now()
	m.ensureMonth(ctx, now)
	used := m.usedLocked(now)
	st := Status{Month: monthOf(now), Used: used, Limit: m.limit, Estimate: m.estimateLocked(now), VideoPaused: used*100 >= m.limit*90, CallsPaused: used*100 >= m.limit*99}
	if m.measuredFor == st.Month && m.measured.MeasuredUntil > 0 {
		st.Measured = true
		st.MeasuredSFU, st.MeasuredTURN = m.measured.MeasuredSFU, m.measured.MeasuredTURN
		st.MeasuredUntil, st.MeasuredAt = m.measured.MeasuredUntil, m.measured.MeasuredAt
	}
	return st
}

// Limit is the monthly allowance in bytes.
func (m *Meter) Limit() int64 { return m.limit }

// Month is the month the meter is for, as YYYY-MM.
func (m *Meter) Month() string { return monthOf(m.now()) }

// VideoAllowed reports whether camera and screen tracks may still be used.
func (m *Meter) VideoAllowed(ctx context.Context) bool {
	return m.Used(ctx)*100 < m.limit*90
}

// CallsAllowed reports whether calls may still start.
func (m *Meter) CallsAllowed(ctx context.Context) bool {
	return m.Used(ctx)*100 < m.limit*99
}

// Run flushes every interval until ctx ends, and once more on the way out.
func (m *Meter) Run(ctx context.Context, every time.Duration, onErr func(error)) {
	t := time.NewTicker(every)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			flushCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			if err := m.Flush(flushCtx); err != nil && onErr != nil {
				onErr(err)
			}
			cancel()
			return
		case <-t.C:
			if err := m.Flush(ctx); err != nil && onErr != nil {
				onErr(err)
			}
		}
	}
}
