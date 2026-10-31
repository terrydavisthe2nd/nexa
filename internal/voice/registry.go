// Package voice keeps the in-memory registry of who is in which voice channel.
// Media never touches the server: peers connect directly, or through
// Cloudflare's SFU, so the registry only tells clients who is in a call, what
// each device publishes there, and carries moderation flags.
package voice

import (
	"crypto/sha256"
	"encoding/hex"
	"sort"
	"sync"
	"time"

	"evilion.xyz/chat/internal/ids"
)

// State is one user's presence in a voice channel.
type State struct {
	UserID     ids.ID `json:"user_id"`
	ChannelID  ids.ID `json:"channel_id"`
	SessionID  ids.ID `json:"session_id"`
	SelfMute   bool   `json:"self_mute"`
	SelfDeaf   bool   `json:"self_deaf"`
	Video      bool   `json:"video"`
	Streaming  bool   `json:"streaming"`
	ServerMute bool   `json:"server_mute"`
	ServerDeaf bool   `json:"server_deaf"`
	JoinedAt   int64  `json:"joined_at"`
	// CallSince is when the call in this channel began: the moment it went
	// from empty to occupied. Everyone in the channel carries the same value,
	// and it outlives the person who started the call.
	CallSince int64 `json:"call_since"`
	// ConnID is the gateway connection that owns this state. A session is
	// shared by every tab and reconnect of a device, so it cannot say which
	// socket is actually in the call.
	ConnID ids.ID `json:"-"`
	// Suppress is set when the channel denies Speak; it silences the user
	// like a server mute but belongs to the channel, not to a moderator.
	Suppress bool `json:"-"`
	// Priority is set while the person talks as Priority Speaker (the key
	// held, and the channel allows it).
	Priority bool `json:"-"`
	// SFUSession is this device's Cloudflare Realtime session when calls go
	// through the SFU, Tracks what it publishes there, and ScreenBps the
	// bitrate its screen share declared.
	SFUSession    string `json:"-"`
	SFUGeneration uint64 `json:"-"`
	SFUPolicy     uint8  `json:"-"`
	TrackMids     Tracks `json:"-"`
	Tracks        Tracks `json:"-"`
	ScreenBps     int64  `json:"-"`
	// Detached counts the times this call lost its connection. A grace
	// timer ends the call only if it is still detached from the drop that
	// set it, not from a later one it would cut short.
	Detached uint64 `json:"-"`
	// Codecs are the video codecs besides VP8 the device can watch ("h264"):
	// a phone shares its screen in H.264 only when everyone in the call can.
	Codecs []string `json:"-"`
	// Name and AvatarID are how the participant looked on joining, for the
	// guests in the call, who are told nothing else about anyone.
	Name     string  `json:"-"`
	AvatarID *ids.ID `json:"-"`
	// Guest is set for someone in through a guest link, with GuestEpoch the
	// key epoch that link carried.
	Guest      bool `json:"-"`
	GuestEpoch int  `json:"-"`
}

// Tracks names what a call device publishes through the SFU, by kind. Only
// the server knows the names; everyone else learns which kinds exist, and a
// tag for each that changes when the track does.
type Tracks struct {
	Audio, Video, Screen, ScreenAudio string
}

// Get returns the name published for a kind, or "".
func (t Tracks) Get(kind string) string {
	switch kind {
	case "audio":
		return t.Audio
	case "video":
		return t.Video
	case "screen":
		return t.Screen
	case "screen_audio":
		return t.ScreenAudio
	}
	return ""
}

// With returns the tracks with a kind named.
func (t Tracks) With(kind, name string) Tracks {
	switch kind {
	case "audio":
		t.Audio = name
	case "video":
		t.Video = name
	case "screen":
		t.Screen = name
	case "screen_audio":
		t.ScreenAudio = name
	}
	return t
}

// Tags maps each published kind to its track's tag, or is nil.
func (t Tracks) Tags() map[string]string {
	var out map[string]string
	for _, k := range []string{"audio", "video", "screen", "screen_audio"} {
		if name := t.Get(k); name != "" {
			if out == nil {
				out = map[string]string{}
			}
			out[k] = TrackTag(name)
		}
	}
	return out
}

// TrackTag names a published track to other devices without giving its name
// away: the same track always has the same tag, and a republished one a new one.
func TrackTag(name string) string {
	sum := sha256.Sum256([]byte("chat-track-tag:" + name))
	return hex.EncodeToString(sum[:6])
}

// Registry is safe for concurrent use.
type Registry struct {
	mu        sync.RWMutex
	byUser    map[ids.ID]*State
	byChannel map[ids.ID]map[ids.ID]*State
	// since is each occupied channel's CallSince, gone once it empties.
	since map[ids.ID]int64
}

// New creates an empty registry.
func New() *Registry {
	return &Registry{byUser: map[ids.ID]*State{}, byChannel: map[ids.ID]map[ids.ID]*State{}, since: map[ids.ID]int64{}}
}

// Get returns a user's state.
func (r *Registry) Get(uid ids.ID) (State, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	st, ok := r.byUser[uid]
	if !ok {
		return State{}, false
	}
	return *st, true
}

// Participants lists a channel's states in join order.
func (r *Registry) Participants(ch ids.ID) []State {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]State, 0, len(r.byChannel[ch]))
	for _, st := range r.byChannel[ch] {
		out = append(out, *st)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].JoinedAt != out[j].JoinedAt {
			return out[i].JoinedAt < out[j].JoinedAt
		}
		return out[i].UserID < out[j].UserID
	})
	return out
}

// SFUUsers lists the accounts whose devices are in calls through the SFU.
func (r *Registry) SFUUsers() []ids.ID {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]ids.ID, 0, len(r.byUser))
	for uid, st := range r.byUser {
		if st.SFUSession != "" {
			out = append(out, uid)
		}
	}
	return out
}

// Count returns how many users are in a channel.
func (r *Registry) Count(ch ids.ID) int {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return len(r.byChannel[ch])
}

// MemberCount is Count without the guests: whether anyone with an account is in the call.
func (r *Registry) MemberCount(ch ids.ID) int {
	r.mu.RLock()
	defer r.mu.RUnlock()
	n := 0
	for _, st := range r.byChannel[ch] {
		if !st.Guest {
			n++
		}
	}
	return n
}

// Join puts a user in a channel (moving them if they were elsewhere) and
// returns the state they left, if any. Moderation flags survive only when
// keepModeration is set, which the caller does for moves within one server:
// nobody elsewhere could lift them. Self flags come from the new state.
// started reports that this join is the one that turned an empty channel into
// a call, so the caller rings the other side and writes the system message
// exactly once. It is decided here, under the one lock, because the same
// person joining from two tabs within a few milliseconds used to have both
// connections read "nobody is in it" from separate snapshots: the other party's
// phone rang twice and the transcript showed the call starting twice.
func (r *Registry) Join(st State, keepModeration bool) (old *State, started bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if !st.Guest {
		// Guests waiting in a call do not make it one; the first account to
		// join still starts it. Somebody already in this channel means it had
		// started, whether that is the other party or an earlier connection of
		// this same person.
		started = true
		for _, other := range r.byChannel[st.ChannelID] {
			if !other.Guest {
				started = false
				break
			}
		}
	}
	// read before the user's old state goes, which empties the channel when
	// they were alone in it and only toggled a button
	since := r.since[st.ChannelID]
	if prev, ok := r.byUser[st.UserID]; ok {
		cp := *prev
		old = &cp
		r.removeLocked(prev)
		if keepModeration {
			st.ServerMute, st.ServerDeaf = prev.ServerMute, prev.ServerDeaf
		}
		// a mute or camera toggle comes through here as a fresh state; the
		// call's order is by when people joined it, not when they last
		// touched a button
		if prev.ChannelID == st.ChannelID && st.JoinedAt == 0 {
			st.JoinedAt = prev.JoinedAt
		}
		// The same device staying in the same call keeps its SFU session (a
		// mute or camera toggle comes through here too). A move or another
		// device starts afresh, and the caller closes the old session.
		if prev.ChannelID == st.ChannelID && prev.ConnID == st.ConnID {
			st.SFUSession, st.Tracks, st.ScreenBps = prev.SFUSession, prev.Tracks, prev.ScreenBps
			st.TrackMids, st.SFUGeneration, st.SFUPolicy = prev.TrackMids, prev.SFUGeneration, prev.SFUPolicy
		}
	}
	if st.JoinedAt == 0 {
		st.JoinedAt = time.Now().UnixMilli()
	}
	if since == 0 {
		since = time.Now().UnixMilli()
	}
	r.since[st.ChannelID] = since
	st.CallSince = since
	cp := st
	r.byUser[st.UserID] = &cp
	if r.byChannel[st.ChannelID] == nil {
		r.byChannel[st.ChannelID] = map[ids.ID]*State{}
	}
	r.byChannel[st.ChannelID][st.UserID] = &cp
	return old, started
}

// SetSFU gives a user's call its SFU session, as long as the call is still in
// channelID on connID, and returns the session it replaces. What the old
// session published is forgotten.
func (r *Registry) SetSFU(uid, channelID, connID ids.ID, session string) (string, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	st, ok := r.byUser[uid]
	// A call with no connection is waiting for its device to resume it, with
	// the session it had; giving it another would strand the grace timer.
	if !ok || st.ChannelID != channelID || connID.IsZero() || st.ConnID != connID {
		return "", false
	}
	old := st.SFUSession
	st.SFUSession, st.Tracks, st.TrackMids, st.ScreenBps = session, Tracks{}, Tracks{}, 0
	return old, true
}

// SetTrack records a track published by the call's SFU session. It does
// nothing once the session was replaced or the user left.
func (r *Registry) SetTrack(uid ids.ID, session, kind, name string, screenBps int64) (State, bool) {
	return r.SetPublishedTrack(uid, session, kind, name, "", screenBps)
}

func (r *Registry) SetPublishedTrack(uid ids.ID, session, kind, name, mid string, screenBps int64) (State, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	st, ok := r.byUser[uid]
	if !ok || session == "" || st.SFUSession != session {
		return State{}, false
	}
	st.Tracks = st.Tracks.With(kind, name)
	st.TrackMids = st.TrackMids.With(kind, mid)
	if kind == "screen" {
		st.ScreenBps = screenBps
	}
	return *st, true
}

// Update mutates a user's state in place and returns the result.
func (r *Registry) Update(uid ids.ID, fn func(*State)) (State, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	st, ok := r.byUser[uid]
	if !ok {
		return State{}, false
	}
	fn(st)
	return *st, true
}

// Leave removes a user and returns the state they had.
func (r *Registry) Leave(uid ids.ID) (State, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.leaveLocked(uid)
}

// Detach lets go of the connection holding a call without ending the call:
// its SFU session and tracks stay, with no connection (ConnID 0), for the same
// device to pick up with Resume. It reports whether connID held it.
func (r *Registry) Detach(uid, connID ids.ID) (State, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	st, ok := r.byUser[uid]
	if !ok || connID.IsZero() || st.ConnID != connID {
		return State{}, false
	}
	st.ConnID = 0
	st.Detached++
	return *st, true
}

// Resume hands a call to a new connection of the device that holds it: the
// same session, proving itself with the SFU session only that device knows.
func (r *Registry) Resume(uid, sessionID ids.ID, sfuSession string, connID ids.ID) (State, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	st, ok := r.byUser[uid]
	if !ok || sfuSession == "" || st.SessionID != sessionID || st.SFUSession != sfuSession {
		return State{}, false
	}
	st.ConnID = connID
	return *st, true
}

// LeaveDetached ends a call nobody picked up: it is still without a
// connection, from the same drop (detached, as Detach returned it). A call
// resumed and dropped again waits out its own grace, not what was left of
// the first one's.
func (r *Registry) LeaveDetached(uid ids.ID, detached uint64) (State, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	st, ok := r.byUser[uid]
	if !ok || !st.ConnID.IsZero() || st.Detached != detached {
		return State{}, false
	}
	return r.leaveLocked(uid)
}

// LeaveChannel removes a user if they are still in channelID, whichever of
// their connections holds the call: for putting someone out of a channel they
// lost, where a join from another tab in the meantime must not save them.
func (r *Registry) LeaveChannel(uid, channelID ids.ID) (State, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	st, ok := r.byUser[uid]
	if !ok || st.ChannelID != channelID {
		return State{}, false
	}
	return r.leaveLocked(uid)
}

// LeaveConn removes a user only when the given connection holds their state.
func (r *Registry) LeaveConn(uid, connID ids.ID) (State, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	st, ok := r.byUser[uid]
	if !ok || st.ConnID != connID {
		return State{}, false
	}
	return r.leaveLocked(uid)
}

func (r *Registry) leaveLocked(uid ids.ID) (State, bool) {
	st, ok := r.byUser[uid]
	if !ok {
		return State{}, false
	}
	delete(r.byUser, uid)
	r.removeLocked(st)
	return *st, true
}

// removeLocked takes a state out of its channel, and the channel out of the
// map once nobody is left in it, which ends its call.
func (r *Registry) removeLocked(st *State) {
	delete(r.byChannel[st.ChannelID], st.UserID)
	if len(r.byChannel[st.ChannelID]) == 0 {
		delete(r.byChannel, st.ChannelID)
		delete(r.since, st.ChannelID)
	}
}

// In lists every state in any of the given channels.
func (r *Registry) In(chIDs []ids.ID) []State {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := []State{}
	for _, ch := range chIDs {
		for _, st := range r.byChannel[ch] {
			out = append(out, *st)
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].ChannelID != out[j].ChannelID {
			return out[i].ChannelID < out[j].ChannelID
		}
		return out[i].JoinedAt < out[j].JoinedAt
	})
	return out
}

// ClearChannel drops everyone from a channel (it was deleted) and returns them.
func (r *Registry) ClearChannel(ch ids.ID) []State {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := []State{}
	for uid, st := range r.byChannel[ch] {
		out = append(out, *st)
		delete(r.byUser, uid)
	}
	delete(r.byChannel, ch)
	delete(r.since, ch)
	return out
}
