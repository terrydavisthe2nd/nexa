package realtime

import (
	"context"
	"errors"
	"strconv"
	"sync"
	"testing"
	"time"
)

type memUsage struct {
	mu       sync.Mutex
	months   map[string]int64
	measured map[string]MonthRecord
	// days holds what AddCallUsage was given, by day and account
	days map[string]map[int64]CallUsage
	// failCalls makes the next AddCallUsage fail
	failCalls bool
}

func (u *memUsage) AddRealtimeUsage(_ context.Context, month string, b int64) (int64, error) {
	u.mu.Lock()
	defer u.mu.Unlock()
	u.months[month] += b
	return u.months[month], nil
}

func (u *memUsage) RealtimeMonth(_ context.Context, month string) (MonthRecord, error) {
	u.mu.Lock()
	defer u.mu.Unlock()
	rec := u.measured[month]
	rec.Bytes = u.months[month]
	return rec, nil
}

func (u *memUsage) SaveRealtimeMeasurement(_ context.Context, month string, rec MonthRecord) error {
	u.mu.Lock()
	defer u.mu.Unlock()
	u.measured[month] = rec
	return nil
}

func (u *memUsage) AddCallUsage(_ context.Context, day string, rows []CallUsage) error {
	u.mu.Lock()
	defer u.mu.Unlock()
	if u.failCalls {
		u.failCalls = false
		return errors.New("disk full")
	}
	if u.days[day] == nil {
		u.days[day] = map[int64]CallUsage{}
	}
	for _, r := range rows {
		c := u.days[day][r.User]
		c.User = r.User
		c.Received += r.Received
		c.Sent += r.Sent
		c.Seconds += r.Seconds
		u.days[day][r.User] = c
	}
	return nil
}

func newTestMeter(gb int64, start time.Time) (*Meter, *memUsage, *time.Time) {
	st := &memUsage{months: map[string]int64{}, measured: map[string]MonthRecord{}, days: map[string]map[int64]CallUsage{}}
	m := NewMeter(st, gb)
	now := start
	m.now = func() time.Time { return now }
	return m, st, &now
}

func TestMeterChargesPulledTracks(t *testing.T) {
	ctx := context.Background()
	m, st, now := newTestMeter(1000, time.Date(2026, 9, 13, 12, 0, 0, 0, time.UTC))

	m.Start("s1", "0", Source("p1", KindAudio), AudioBps, 0, 0)
	m.Start("s1", "1", Source("p1", KindVideo), CameraBps, 0, 0)
	*now = now.Add(10 * time.Second)
	// 10 s of audio (80 KB) and camera (1.875 MB)
	if got, want := m.Used(ctx), int64(80_000+1_875_000); got != want {
		t.Fatalf("running pulls: %d, want %d", got, want)
	}
	m.Stop("s1", "1")
	*now = now.Add(10 * time.Second)
	if err := m.Flush(ctx); err != nil {
		t.Fatal(err)
	}
	if got, want := st.months["2026-09"], int64(160_000+1_875_000); got != want {
		t.Fatalf("flushed: %d, want %d", got, want)
	}
	// flushing again adds only what ran since
	*now = now.Add(5 * time.Second)
	_ = m.Flush(ctx)
	if got, want := st.months["2026-09"], int64(200_000+1_875_000); got != want {
		t.Fatalf("second flush: %d, want %d", got, want)
	}
	m.StopSession("s1")
	*now = now.Add(time.Hour)
	_ = m.Flush(ctx)
	if got := st.months["2026-09"]; got != 200_000+1_875_000 {
		t.Errorf("nothing charged after the session stopped: %d", got)
	}
}

func TestMeterMonthsAndThresholds(t *testing.T) {
	ctx := context.Background()
	m, st, now := newTestMeter(1, time.Date(2026, 9, 30, 12, 0, 0, 0, time.UTC))
	st.months["2026-09"] = 899_000_000 // just under 90 % of 1 GB
	if !m.VideoAllowed(ctx) || !m.CallsAllowed(ctx) {
		t.Fatal("under 90 %: everything allowed")
	}
	m.Start("s", "0", Source("p", KindScreen), 8_000_000, 0, 0) // 1 MB a second
	*now = now.Add(2 * time.Second)
	if m.VideoAllowed(ctx) {
		t.Error("at 90 % video pauses")
	}
	if !m.CallsAllowed(ctx) {
		t.Error("calls go on until 99 %")
	}
	*now = now.Add(95 * time.Second)
	if m.CallsAllowed(ctx) {
		t.Error("at 99 % calls stop")
	}
	m.StopSession("s")
	_ = m.Flush(ctx)
	// a new month starts from what it has stored
	*now = time.Date(2026, 10, 1, 0, 5, 0, 0, time.UTC)
	if got := m.Used(ctx); got != 0 {
		t.Errorf("October starts at zero: %d", got)
	}
	if !m.VideoAllowed(ctx) {
		t.Error("video is back in a new month")
	}
}

func TestRates(t *testing.T) {
	if RateFor(KindAudio, 0) != AudioBps || RateFor(KindScreenAudio, 0) != StreamAudioBps || RateFor(KindVideo, 0) != CameraBps {
		t.Error("audio and camera rates")
	}
	// a share is estimated at the sharer's plan limit, which the server
	// knows, kept within the bounds a share can have
	if RateFor(KindScreen, 6_000_000) != 6_000_000 || RateFor(KindScreen, 20_000_000) != maxScreenBps {
		t.Error("screen rates follow the plan's limit")
	}
	if RateFor(KindScreen, 0) != DefaultScreenBps || RateFor(KindScreen, 100) != minScreenBps || RateFor(KindScreen, 50_000_000) != maxScreenBps {
		t.Error("screen rates stay within bounds")
	}
	if !ValidKind("screen_audio") || ValidKind("data") || !IsVideo(KindScreen) || IsVideo(KindScreenAudio) {
		t.Error("kinds")
	}
}

func TestMeterSkipsTracksThatSendNothing(t *testing.T) {
	ctx := context.Background()
	m, _, now := newTestMeter(1000, time.Date(2026, 9, 13, 12, 0, 0, 0, time.UTC))
	camera := Source("pub", KindVideo)
	m.SetSourceLive(camera, false)
	m.Start("sub", "3", camera, CameraBps, 0, 0)
	*now = now.Add(time.Minute)
	if got := m.Used(ctx); got != 0 {
		t.Fatalf("a camera that is off costs nothing: %d", got)
	}
	m.SetSourceLive(camera, true)
	*now = now.Add(8 * time.Second)
	if got, want := m.Used(ctx), int64(1_500_000); got != want {
		t.Fatalf("on again, it is charged: %d, want %d", got, want)
	}
	m.SetSourceLive(camera, false)
	*now = now.Add(time.Hour)
	if got, want := m.Used(ctx), int64(1_500_000); got != want {
		t.Fatalf("off again, the charge stops: %d, want %d", got, want)
	}
	m.StopSession("pub")
	if len(m.off) != 0 {
		t.Errorf("the publisher leaving forgets its tracks: %v", m.off)
	}
}

// Pulling one track over and over is charged as one pull, and a track stops
// being charged when the session publishing it ends, whoever was pulling it.
func TestMeterChargesATrackOncePerSession(t *testing.T) {
	ctx := context.Background()
	m, _, now := newTestMeter(1000, time.Date(2026, 9, 13, 12, 0, 0, 0, time.UTC))
	src := Source("pub", KindAudio)
	for i := 0; i < 50; i++ {
		m.Start("watcher", strconv.Itoa(i), src, AudioBps, 0, 0)
	}
	*now = now.Add(10 * time.Second)
	if got, want := m.Used(ctx), int64(80_000); got != want {
		t.Fatalf("fifty pulls of one track: %d, want %d", got, want)
	}
	m.StopSession("pub")
	before := m.Used(ctx)
	*now = now.Add(time.Hour)
	if got := m.Used(ctx); got != before {
		t.Errorf("a track whose publisher left is still charged: %d then %d", before, got)
	}
}

func TestConfirmedSourceClosureStopsAllItsPulls(t *testing.T) {
	ctx := context.Background()
	m, _, now := newTestMeter(1000, time.Date(2026, 9, 13, 12, 0, 0, 0, time.UTC))
	source := Source("publisher", KindAudio)
	m.Start("a", "0", source, AudioBps, 0, 0)
	m.Start("b", "1", source, AudioBps, 0, 0)
	if mid, exists := m.ActiveMid("a", source); !exists || mid != "0" {
		t.Fatal("subscription not reusable")
	}
	*now = now.Add(time.Second)
	m.StopSource(source)
	used := m.Used(ctx)
	*now = now.Add(time.Minute)
	if m.Used(ctx) != used {
		t.Fatal("confirmed closed publication kept charging")
	}
	if _, exists := m.ActiveMid("a", source); exists {
		t.Fatal("closed subscription reused")
	}
}

// Each account is told what reached its devices, what its own tracks sent
// to others, and how long it spent in calls.
func TestMeterAttributesUsageToAccounts(t *testing.T) {
	ctx := context.Background()
	m, st, now := newTestMeter(1000, time.Date(2026, 10, 31, 12, 0, 0, 0, time.UTC))
	inCalls := []int64{1, 2, 3}
	m.SetParticipants(func() []int64 { return inCalls })
	_ = m.Flush(ctx) // the first write starts the clock for call time
	share := Source("pub", KindScreen)
	m.Start("viewer-a", "0", share, 8_000_000, 2, 1) // 1 MB a second
	m.Start("viewer-b", "0", share, 8_000_000, 3, 1)
	m.Start("pub", "0", Source("viewer-a", KindAudio), AudioBps, 1, 2)
	*now = now.Add(time.Minute)
	if err := m.Flush(ctx); err != nil {
		t.Fatal(err)
	}
	day := st.days["2026-10-31"]
	if day[2].Received != 60_000_000 || day[3].Received != 60_000_000 {
		t.Errorf("each viewer received a minute of the share: %+v %+v", day[2], day[3])
	}
	if day[1].Sent != 120_000_000 {
		t.Errorf("the sharer sent it to two people: %+v", day[1])
	}
	if day[1].Received != 480_000 || day[2].Sent != 480_000 {
		t.Errorf("the audio the other way: %+v %+v", day[1], day[2])
	}
	for _, id := range inCalls {
		if day[id].Seconds != 60 {
			t.Errorf("account %d was in the call for a minute: %+v", id, day[id])
		}
	}
	// a write that fails loses nothing: it goes with the next
	st.failCalls = true
	*now = now.Add(time.Minute)
	if err := m.Flush(ctx); err == nil {
		t.Fatal("the failed write should be reported")
	}
	*now = now.Add(time.Minute)
	if err := m.Flush(ctx); err != nil {
		t.Fatal(err)
	}
	if got := st.days["2026-10-31"][2].Received; got != 180_000_000 {
		t.Errorf("after a failed write, three minutes are counted: %d", got)
	}
	if got := st.months["2026-10"]; got != 3*(120_000_000+480_000) {
		t.Errorf("the month's estimate: %d", got)
	}
	// a long gap between writes (a process stopped) is nobody's call time
	*now = now.Add(time.Hour)
	_ = m.Flush(ctx)
	if got := st.days["2026-10-31"][1].Seconds; got != 180 {
		t.Errorf("a stopped hour is not call time: %d", got)
	}
}

// Cloudflare's measurement replaces the estimate up to where it ends, and the
// estimate covers only what came after.
func TestMeterRestsOnTheMeasurement(t *testing.T) {
	ctx := context.Background()
	start := time.Date(2026, 10, 31, 12, 0, 0, 0, time.UTC)
	m, st, now := newTestMeter(1000, start)
	st.months["2026-10"] = 2_000_000_000_000 // an estimate run far too high
	m.Start("viewer", "0", Source("pub", KindVideo), CameraBps, 2, 1)
	_ = m.Flush(ctx)
	// with no write at or before where the measurement ends, it is kept out
	if ok, err := m.Measure(ctx, start.Add(-10*time.Minute), 100_000_000_000, 2_000_000_000); err != nil || ok {
		t.Fatalf("a measurement this process cannot line up was taken: %v %v", ok, err)
	}
	for i := 0; i < 15; i++ {
		*now = now.Add(time.Minute)
		_ = m.Flush(ctx)
	}
	until := start.Add(5 * time.Minute)
	if ok, err := m.Measure(ctx, until, 100_000_000_000, 2_000_000_000); err != nil || !ok {
		t.Fatalf("measurement not taken: %v %v", ok, err)
	}
	// ten minutes of camera (1.5 Mbit/s) were estimated after it ended
	want := int64(102_000_000_000 + 10*60*187_500)
	if got := m.Used(ctx); got != want {
		t.Fatalf("measured plus the estimate since: %d, want %d", got, want)
	}
	if !m.VideoAllowed(ctx) || !m.CallsAllowed(ctx) {
		t.Fatal("the measurement, not the estimate, decides")
	}
	st2 := m.Status(ctx)
	if !st2.Measured || st2.MeasuredSFU != 100_000_000_000 || st2.MeasuredTURN != 2_000_000_000 || st2.Used != want {
		t.Fatalf("status: %+v", st2)
	}
	// a restart keeps resting on it, adding what the estimate wrote since
	m.StopSession("viewer")
	_ = m.Flush(ctx)
	again := NewMeter(st, 1000)
	again.now = func() time.Time { return *now }
	if got := again.Used(ctx); got != want {
		t.Fatalf("after a restart: %d, want %d", got, want)
	}
	// the measurement belongs to its month
	*now = time.Date(2026, 11, 1, 0, 1, 0, 0, time.UTC)
	if got := again.Used(ctx); got != 0 {
		t.Fatalf("November starts at zero: %d", got)
	}
}

// The first minutes of a month have nothing to measure yet: the measurement
// ends where the month starts, and the estimate covers it all.
func TestMeterMeasurementAtMonthStart(t *testing.T) {
	ctx := context.Background()
	start := time.Date(2026, 11, 1, 0, 3, 0, 0, time.UTC)
	m, _, now := newTestMeter(1000, start)
	m.Start("viewer", "0", Source("pub", KindAudio), AudioBps, 2, 1)
	*now = now.Add(time.Minute)
	_ = m.Flush(ctx)
	if ok, err := m.Measure(ctx, MonthStart(start), 0, 0); err != nil || !ok {
		t.Fatalf("a month's start lines up with nothing: %v %v", ok, err)
	}
	if got, want := m.Used(ctx), int64(60*8_000); got != want {
		t.Fatalf("the estimate since the month began: %d, want %d", got, want)
	}
}
