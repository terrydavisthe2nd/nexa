package voice

import (
	"sync"
	"sync/atomic"
	"testing"

	"evilion.xyz/chat/internal/ids"
)

func TestRegistry(t *testing.T) {
	r := New()
	a, b := ids.ID(1), ids.ID(2)
	ch1, ch2 := ids.ID(10), ids.ID(20)
	if old, _ := r.Join(State{UserID: a, ChannelID: ch1, SessionID: 100, ConnID: 1000}, false); old != nil {
		t.Fatal("first join returns no previous state")
	}
	r.Join(State{UserID: b, ChannelID: ch1, SessionID: 200, ConnID: 2000, SelfMute: true}, false)
	if r.Count(ch1) != 2 || len(r.Participants(ch1)) != 2 || r.Participants(ch1)[0].UserID != a {
		t.Fatalf("participants: %+v", r.Participants(ch1))
	}
	// moderation flags survive a move the caller marks as within one server
	if _, ok := r.Update(a, func(s *State) { s.ServerMute = true }); !ok {
		t.Fatal("update")
	}
	old, _ := r.Join(State{UserID: a, ChannelID: ch2, SessionID: 100, ConnID: 1000, Video: true}, true)
	if old == nil || old.ChannelID != ch1 {
		t.Fatalf("move returns old state: %+v", old)
	}
	st, _ := r.Get(a)
	if st.ChannelID != ch2 || !st.ServerMute || !st.Video || r.Count(ch1) != 1 || r.Count(ch2) != 1 {
		t.Fatalf("after move: %+v", st)
	}
	if len(r.In([]ids.ID{ch1, ch2})) != 2 {
		t.Error("In lists both channels")
	}
	// connection-scoped leave ignores other connections, even of the same session
	if _, ok := r.LeaveConn(a, 999); ok {
		t.Error("wrong connection must not leave")
	}
	if left, ok := r.LeaveConn(a, 1000); !ok || left.ChannelID != ch2 {
		t.Errorf("leave conn: %v %+v", ok, left)
	}
	if _, ok := r.Get(a); ok {
		t.Error("gone after leave")
	}
	if cleared := r.ClearChannel(ch1); len(cleared) != 1 || cleared[0].UserID != b || r.Count(ch1) != 0 {
		t.Errorf("clear: %+v", cleared)
	}
	if _, ok := r.Leave(b); ok {
		t.Error("b already cleared")
	}
}

// TestJoinDropsModerationElsewhere checks that a server mute does not follow
// someone into a channel where nobody could lift it.
func TestJoinDropsModerationElsewhere(t *testing.T) {
	r := New()
	a := ids.ID(1)
	r.Join(State{UserID: a, ChannelID: 10, ConnID: 1}, false)
	r.Update(a, func(s *State) { s.ServerMute, s.ServerDeaf = true, true })
	r.Join(State{UserID: a, ChannelID: 20, ConnID: 1}, false)
	if st, _ := r.Get(a); st.ServerMute || st.ServerDeaf {
		t.Errorf("moderation must not carry over: %+v", st)
	}
}

// TestLeaveConnAfterRejoin checks that a stale socket closing does not end
// the call a newer socket of the same session rejoined.
func TestLeaveConnAfterRejoin(t *testing.T) {
	r := New()
	a := ids.ID(1)
	r.Join(State{UserID: a, ChannelID: 10, SessionID: 5, ConnID: 1}, false)
	r.Join(State{UserID: a, ChannelID: 10, SessionID: 5, ConnID: 2}, true)
	if _, ok := r.LeaveConn(a, 1); ok {
		t.Fatal("the old socket no longer owns the state")
	}
	if st, ok := r.Get(a); !ok || st.ConnID != 2 {
		t.Errorf("state kept for the new socket: %v %+v", ok, st)
	}
}

func TestSFUSessionFollowsTheCall(t *testing.T) {
	r := New()
	u, ch, other := ids.ID(1), ids.ID(10), ids.ID(20)
	r.Join(State{UserID: u, ChannelID: ch, SessionID: 100, ConnID: 1000}, false)
	if _, ok := r.SetSFU(u, other, 1000, "s1"); ok {
		t.Fatal("a session is only given to the call as it is")
	}
	if old, ok := r.SetSFU(u, ch, 1000, "s1"); !ok || old != "" {
		t.Fatalf("set session: %q %v", old, ok)
	}
	if _, ok := r.SetTrack(u, "stale", "audio", "a1", 0); ok {
		t.Error("a track from another session is ignored")
	}
	st, ok := r.SetTrack(u, "s1", "screen", "sc1", 8_000_000)
	if !ok || st.Tracks.Screen != "sc1" || st.ScreenBps != 8_000_000 {
		t.Fatalf("track: %+v", st)
	}
	if st, _ := r.SetTrack(u, "s1", "audio", "a1", 128_000); st.AudioBps != 128_000 || st.ScreenBps != 8_000_000 {
		t.Errorf("a voice's bitrate is its own: %+v", st)
	}
	if got, _ := r.Get(u); len(got.Tracks.Tags()) != 2 || got.Tracks.Tags()["audio"] != TrackTag(got.Tracks.Audio) || got.Tracks.Tags()["screen"] == "" {
		t.Errorf("tags: %v", got.Tracks.Tags())
	}
	// a mute toggle rejoins the same channel on the same connection: the session stays
	r.Join(State{UserID: u, ChannelID: ch, SessionID: 100, ConnID: 1000, SelfMute: true}, false)
	if got, _ := r.Get(u); got.SFUSession != "s1" || got.Tracks.Audio != "a1" || !got.SelfMute {
		t.Errorf("a toggle keeps the session: %+v", got)
	}
	// another device picking up the call starts without it
	old, _ := r.Join(State{UserID: u, ChannelID: ch, SessionID: 100, ConnID: 2000}, false)
	if got, _ := r.Get(u); got.SFUSession != "" || old == nil || old.SFUSession != "s1" {
		t.Errorf("another device: %+v, old %+v", got, old)
	}
	// and so does a move
	r.SetSFU(u, ch, 2000, "s2")
	old, _ = r.Join(State{UserID: u, ChannelID: other, SessionID: 100, ConnID: 2000}, true)
	if got, _ := r.Get(u); got.SFUSession != "" || old.SFUSession != "s2" {
		t.Errorf("a move: %+v, old %+v", got, old)
	}
	if old, ok := r.SetSFU(u, other, 2000, "s3"); !ok || old != "" {
		t.Errorf("replacing: %q %v", old, ok)
	}
	if old, ok := r.SetSFU(u, other, 2000, "s4"); !ok || old != "s3" {
		t.Errorf("a new session hands back the one it replaces: %q %v", old, ok)
	}
}

func TestDetachAndResume(t *testing.T) {
	r := New()
	u, ch := ids.ID(1), ids.ID(10)
	r.Join(State{UserID: u, ChannelID: ch, SessionID: 100, ConnID: 1000}, false)
	r.SetSFU(u, ch, 1000, "s1")
	r.SetTrack(u, "s1", "audio", "a1", 0)
	if _, ok := r.Detach(u, 999); ok {
		t.Fatal("a connection that does not hold the call cannot detach it")
	}
	first, ok := r.Detach(u, 1000)
	if !ok || !first.ConnID.IsZero() {
		t.Fatalf("detach: %+v %v", first, ok)
	}
	// a call waiting for its device keeps the session it had
	if _, ok := r.SetSFU(u, ch, 0, "s9"); ok {
		t.Error("a detached call was given another SFU session")
	}
	// still in the call, with its session and tracks, for everyone else
	if got, ok := r.Get(u); !ok || got.SFUSession != "s1" || got.Tracks.Audio != "a1" || r.Count(ch) != 1 {
		t.Fatalf("detached call: %+v", got)
	}
	// only the device holding the SFU session picks it up
	if _, ok := r.Resume(u, 100, "s2", 3000); ok {
		t.Error("a wrong SFU session resumed the call")
	}
	if _, ok := r.Resume(u, 101, "s1", 3000); ok {
		t.Error("another session resumed the call")
	}
	if st, ok := r.Resume(u, 100, "s1", 3000); !ok || st.ConnID != 3000 || st.Tracks.Audio != "a1" {
		t.Fatalf("resume: %+v %v", st, ok)
	}
	// a resumed call is not ended by the grace running out
	if _, ok := r.LeaveDetached(u, first.Detached); ok {
		t.Error("a resumed call was ended")
	}
	second, _ := r.Detach(u, 3000)
	// the first drop's timer, running out during the second drop, leaves it be
	if _, ok := r.LeaveDetached(u, first.Detached); ok {
		t.Error("an earlier drop's grace ended the call")
	}
	if st, ok := r.LeaveDetached(u, second.Detached); !ok || st.UserID != u || r.Count(ch) != 0 {
		t.Errorf("grace over: %+v %v", st, ok)
	}
}

// A mute or camera toggle is a fresh state for the same call; it keeps the
// person's place in it.
func TestTogglingKeepsYourPlaceInTheCall(t *testing.T) {
	r := New()
	u, ch := ids.ID(1), ids.ID(10)
	r.Join(State{UserID: u, ChannelID: ch, ConnID: 1, JoinedAt: 500}, false)
	r.Join(State{UserID: u, ChannelID: ch, ConnID: 1, SelfMute: true}, false)
	if st, _ := r.Get(u); st.JoinedAt != 500 || !st.SelfMute {
		t.Errorf("after a toggle: %+v", st)
	}
	r.Join(State{UserID: u, ChannelID: 11, ConnID: 1}, false)
	if st, _ := r.Get(u); st.JoinedAt == 500 {
		t.Error("a move kept the old channel's join time")
	}
}

// A call's start belongs to the channel: it outlives whoever started the call,
// survives toggles and a dropped connection, and starts again once the channel
// has emptied.
func TestCallSinceBelongsToTheChannel(t *testing.T) {
	r := New()
	a, b, ch := ids.ID(1), ids.ID(2), ids.ID(10)
	r.Join(State{UserID: a, ChannelID: ch, ConnID: 1}, false)
	start := r.since[ch]
	if st, _ := r.Get(a); start == 0 || st.CallSince != start {
		t.Fatalf("first join: since %d, state %+v", start, st)
	}
	// pretend the call began long ago, so a fresh start would show
	r.since[ch] = 1000
	r.Join(State{UserID: b, ChannelID: ch, ConnID: 2}, false)
	if st, _ := r.Get(b); st.CallSince != 1000 {
		t.Errorf("second join: %d", st.CallSince)
	}
	r.Leave(a)
	r.Join(State{UserID: b, ChannelID: ch, ConnID: 2, SelfMute: true}, false)
	if st, _ := r.Get(b); st.CallSince != 1000 {
		t.Errorf("alone after a toggle: %d", st.CallSince)
	}
	r.Detach(b, 2)
	r.Resume(b, 0, "", 3)
	if st, _ := r.Get(b); st.CallSince != 1000 {
		t.Errorf("after a drop: %d", st.CallSince)
	}
	r.Leave(b)
	if _, ok := r.since[ch]; ok {
		t.Error("an empty channel kept its call")
	}
	r.Join(State{UserID: a, ChannelID: ch, ConnID: 1}, false)
	if st, _ := r.Get(a); st.CallSince == 1000 || st.CallSince == 0 {
		t.Errorf("a new call: %d", st.CallSince)
	}
	r.ClearChannel(ch)
	if _, ok := r.since[ch]; ok {
		t.Error("a cleared channel kept its call")
	}
}

// Putting someone out of a channel does not depend on which of their
// connections holds the call.
func TestLeaveChannelWhateverTheConnection(t *testing.T) {
	r := New()
	u, ch := ids.ID(1), ids.ID(10)
	r.Join(State{UserID: u, ChannelID: ch, ConnID: 1}, false)
	r.Join(State{UserID: u, ChannelID: ch, ConnID: 2}, false)
	if _, ok := r.LeaveChannel(u, 99); ok {
		t.Error("left a channel they were not in")
	}
	if _, ok := r.LeaveChannel(u, ch); !ok || r.Count(ch) != 0 {
		t.Error("still in the call")
	}
}

// Whether a join started the call is decided under the registry's own lock.
// Read from separate snapshots, two connections of the same person entering an
// empty DM within a few milliseconds both concluded they were first: the other
// party's phone rang twice and the transcript showed the call starting twice.
func TestOnlyOneJoinStartsACall(t *testing.T) {
	r := New()
	const ch ids.ID = 900
	me, them, g := ids.ID(1), ids.ID(2), ids.ID(3)

	if _, started := r.Join(State{UserID: me, ChannelID: ch, ConnID: 1}, false); !started {
		t.Error("the first account into an empty channel starts the call")
	}
	if _, started := r.Join(State{UserID: me, ChannelID: ch, ConnID: 2}, false); started {
		t.Error("a second connection of the same person must not start it again")
	}
	if _, started := r.Join(State{UserID: them, ChannelID: ch, ConnID: 3}, false); started {
		t.Error("the person answering must not start it either")
	}

	// a guest waiting alone does not make it a call; the first account still does
	r2 := New()
	if _, started := r2.Join(State{UserID: g, ChannelID: ch, ConnID: 4, Guest: true}, false); started {
		t.Error("a guest must never start a call")
	}
	if _, started := r2.Join(State{UserID: me, ChannelID: ch, ConnID: 5}, false); !started {
		t.Error("the first account to join a channel holding only guests starts the call")
	}
}

// Two connections racing into the same empty channel: exactly one of them is
// told it started the call, whichever wins.
func TestConcurrentJoinsStartTheCallOnce(t *testing.T) {
	r := New()
	const ch ids.ID = 901
	var wg sync.WaitGroup
	var starts atomic.Int32
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			if _, started := r.Join(State{UserID: ids.ID(1), ChannelID: ch, ConnID: ids.ID(100 + i)}, false); started {
				starts.Add(1)
			}
		}(i)
	}
	wg.Wait()
	if n := starts.Load(); n != 1 {
		t.Errorf("%d of the racing joins said they started the call, want exactly 1", n)
	}
}
