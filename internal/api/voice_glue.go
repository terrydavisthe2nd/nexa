package api

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"slices"
	"time"

	"evilion.xyz/chat/internal/gateway"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/perms"
	"evilion.xyz/chat/internal/realtime"
	"evilion.xyz/chat/internal/store"
	"evilion.xyz/chat/internal/voice"
)

const maxSignalBytes = 64 << 10

// voiceGrace is how long a call through the SFU outlives the gateway
// connection that held it. Media does not go through the gateway, so a phone
// changing networks or a socket that stalled keeps talking meanwhile, and its
// device picks the call up on the new connection with a resume. It sits well
// past the client's longest wait between reconnects (web/src/gateway/client.ts),
// or a call would end while its sound was still going through.
var voiceGrace = 45 * time.Second

// voiceStatePayload is what clients receive; channel_id is null when a user leaves.
type voiceStatePayload struct {
	UserID       ids.ID  `json:"user_id"`
	ChannelID    *ids.ID `json:"channel_id"`
	OldChannelID *ids.ID `json:"old_channel_id,omitempty"`
	SessionID    ids.ID  `json:"session_id"`
	SelfMute     bool    `json:"self_mute"`
	SelfDeaf     bool    `json:"self_deaf"`
	Video        bool    `json:"video"`
	Streaming    bool    `json:"streaming"`
	ServerMute   bool    `json:"server_mute"`
	ServerDeaf   bool    `json:"server_deaf"`
	// Suppress is set when the channel denies Speak. server_mute carries it
	// too, for apps that know nothing else; this says which of the two it is,
	// so a moderator's menu does not offer to lift a mute nobody gave.
	Suppress bool `json:"suppress,omitempty"`
	// PrioritySpeaker: talking as Priority Speaker now; the others are turned down while they are heard.
	PrioritySpeaker bool  `json:"priority_speaker,omitempty"`
	JoinedAt        int64 `json:"joined_at"`
	// CallSince is when the call in this channel began, the same for everyone
	// in it, so the apps can show how long it has been going.
	CallSince int64 `json:"call_since,omitempty"`
	// Media maps the kinds this participant publishes through the SFU to a
	// tag that changes whenever that track is published again.
	Media         map[string]string `json:"media,omitempty"`
	SFUGeneration uint64            `json:"sfu_generation,omitempty"`
	// Codecs are the video codecs besides VP8 the participant's device can
	// watch, so a phone knows when it may share its screen in H.264.
	Codecs []string `json:"codecs,omitempty"`
	// User is enough of the participant to show them in the call. Guests are
	// told nothing else about anyone, and nobody else knows a guest otherwise.
	User *callUser `json:"user,omitempty"`
	// Guest marks someone in through a guest link, with the key epoch their
	// link carried, so the people in the call can hand them newer keys.
	Guest *guestMark `json:"guest,omitempty"`
	// Reason is "moved" on the leave sent to a device whose call another
	// device of the same account picked up, so it can say where it went, and
	// "disconnected" when a moderator put the person out of the call.
	Reason string `json:"reason,omitempty"`
}

type callUser struct {
	ID          ids.ID  `json:"id"`
	DisplayName string  `json:"display_name"`
	AvatarID    *ids.ID `json:"avatar_id"`
	Flags       int64   `json:"flags"`
}

type guestMark struct {
	Epoch int `json:"epoch"`
}

func statePayload(st voice.State) voiceStatePayload {
	ch := st.ChannelID
	out := voiceStatePayload{UserID: st.UserID, ChannelID: &ch, SessionID: st.SessionID, SelfMute: st.SelfMute, SelfDeaf: st.SelfDeaf, Video: st.Video, Streaming: st.Streaming, ServerMute: st.ServerMute || st.Suppress, ServerDeaf: st.ServerDeaf, Suppress: st.Suppress, JoinedAt: st.JoinedAt, CallSince: st.CallSince, Media: st.Tracks.Tags(), SFUGeneration: st.SFUGeneration, Codecs: st.Codecs, PrioritySpeaker: st.Priority,
		User: &callUser{ID: st.UserID, DisplayName: st.Name, AvatarID: st.AvatarID}}
	if st.Guest {
		out.User.Flags = store.FlagGuest
		out.Guest = &guestMark{Epoch: st.GuestEpoch}
	}
	return out
}

func leavePayload(st voice.State) voiceStatePayload {
	old := st.ChannelID
	return voiceStatePayload{UserID: st.UserID, ChannelID: nil, OldChannelID: &old, SessionID: st.SessionID}
}

func (s *Server) voiceRoutes(mux *http.ServeMux) {
	mux.Handle("GET /api/v1/voice/ice", s.callAuthed(s.handleVoiceICE))
	mux.Handle("GET /api/v1/channels/{id}/voice", s.authed(s.handleVoiceParticipants))
	mux.Handle("POST /api/v1/channels/{id}/call/decline", s.authed(s.handleCallDecline))
	mux.Handle("PUT /api/v1/channels/{id}/messages/{mid}/call", s.authed(s.handleCallSummary))
	mux.Handle("PATCH /api/v1/servers/{id}/members/{uid}/voice", s.authed(s.handleVoiceModerate))
	s.sfuRoutes(mux)
}

// WireVoice installs the voice registry, the gateway voice ops and cleanup hooks.
// Call it after WireServers so the ready payload gains voice states last.
func (s *Server) WireVoice(hub *gateway.Hub) {
	if s.Voice == nil {
		s.Voice = voice.New()
	}
	if s.Realtime == nil {
		s.Realtime = realtime.New(s.Cfg.RealtimeAppID, s.Cfg.RealtimeAppSecret)
	}
	if s.Realtime != nil && s.Meter == nil {
		s.Meter = realtime.NewMeter(s.Store, s.Cfg.RealtimeMonthlyGB)
	}
	if s.Meter != nil {
		// time in calls is counted for the staff's Calls page
		s.Meter.SetParticipants(func() []int64 {
			users := s.Voice.SFUUsers()
			out := make([]int64, len(users))
			for i, u := range users {
				out[i] = int64(u)
			}
			return out
		})
	}
	if s.Analytics == nil {
		s.Analytics = realtime.NewAnalytics(s.Cfg.RealtimeAccountID, s.Cfg.RealtimeAnalyticsToken)
	}
	if hub == nil {
		return
	}
	s.hub = hub
	hub.VoiceOp = s.handleVoiceOp
	hub.GuestReady = s.guestReady
	// Only the socket that holds the call ends it by closing. Another tab of
	// the same session, or a dead socket reaped after a reconnect already
	// rejoined, must leave the live call alone.
	hub.OnConnClose = func(ctx context.Context, uid, connID ids.ID) {
		if st, ok := s.Voice.Get(uid); ok && st.ConnID == connID && st.SFUSession != "" {
			if held, ok := s.Voice.Detach(uid, connID); ok {
				time.AfterFunc(voiceGrace, func() {
					if left, ok := s.Voice.LeaveDetached(uid, held.Detached); ok {
						s.broadcastVoiceLeave(context.Background(), left)
					}
				})
				return
			}
		}
		if st, ok := s.Voice.LeaveConn(uid, connID); ok {
			s.broadcastVoiceLeave(ctx, st)
		}
	}
	prev := hub.ReadyExtras
	hub.ReadyExtras = func(ctx context.Context, u *store.User, p *gateway.ReadyPayload) error {
		if prev != nil {
			if err := prev(ctx, u, p); err != nil {
				return err
			}
		}
		chans := []ids.ID{}
		for _, c := range p.Channels {
			chans = append(chans, c.ID)
		}
		if more, err := s.Store.ChannelsForUser(ctx, u.ID); err == nil {
			chans = append(chans, more...)
		}
		for _, st := range s.Voice.In(chans) {
			p.VoiceStates = append(p.VoiceStates, statePayload(st))
		}
		return nil
	}
}

// voiceAudience lists who should hear about a channel's voice changes: whoever
// can see the channel, and the guests in its call, who hear about nothing else.
func (s *Server) voiceAudience(ctx context.Context, ch *store.Channel) []ids.ID {
	var out []ids.ID
	if s.isDM(ch) {
		out = append(out, ch.Recipients...)
	} else {
		viewers, err := s.Store.ChannelViewers(ctx, ch.ID)
		if err != nil {
			// EmitUsers on an empty list returns at once, so a swallowed error
			// here means nobody is told someone joined or left and every live
			// client shows a stale roster until it reconnects. There is no
			// periodic reconcile to put it right, so say so instead.
			s.Log.Warn("voice audience", "channel", ch.ID.String(), "err", err)
			return nil
		}
		out = viewers
	}
	for _, st := range s.Voice.Participants(ch.ID) {
		if st.Guest {
			out = append(out, st.UserID)
		}
	}
	return out
}

func (s *Server) broadcastVoiceLeave(ctx context.Context, st voice.State) {
	s.broadcastVoiceLeaveFor(ctx, st, "")
}

// broadcastVoiceLeaveFor is broadcastVoiceLeave with a reason on the leave.
func (s *Server) broadcastVoiceLeaveFor(ctx context.Context, st voice.State, reason string) {
	s.closeSFUSession(st.SFUSession)
	ch, err := s.Store.ChannelByID(ctx, st.ChannelID)
	if err != nil {
		return
	}
	audience := s.voiceAudience(ctx, ch)
	if st.Guest {
		// a guest who was put out of the call hears it too
		audience = append(audience, st.UserID)
	}
	leave := leavePayload(st)
	leave.Reason = reason
	s.Bcast.EmitUsers(audience, "voice_state", leave)
	if s.isDM(ch) && s.Voice.Count(ch.ID) == 0 {
		s.Bcast.EmitUsers(audience, "call_end", map[string]any{"channel_id": ch.ID, "reason": "ended"})
	}
}

// emitConn sends to one connection, or to all of the user's when the
// broadcaster cannot address a single one.
func (s *Server) emitConn(uid, connID ids.ID, event string, data any) {
	if h, ok := s.Bcast.(interface {
		EmitConn(uid, connID ids.ID, event string, data any)
	}); ok {
		h.EmitConn(uid, connID, event, data)
		return
	}
	s.Bcast.EmitUsers([]ids.ID{uid}, event, data)
}

type voiceStateReq struct {
	ChannelID *ids.ID `json:"channel_id"`
	SelfMute  bool    `json:"self_mute"`
	SelfDeaf  bool    `json:"self_deaf"`
	Video     bool    `json:"video"`
	Streaming bool    `json:"streaming"`
	// Priority is set while the person holds their Priority Speaker key; it
	// counts only in a server's channel where they have Priority Speaker.
	Priority bool `json:"priority"`
	// Resume is set when a client rejoins the call it was in after a
	// reconnect; that is not a new call and deserves no new call message.
	Resume bool `json:"resume"`
	// SFU says the device connects through the SFU rather than peer to peer.
	SFU bool `json:"sfu"`
	// SFUSession, with Resume, is the SFU session the device still holds: the
	// proof that it is the device whose call waits for it after a reconnect.
	SFUSession string `json:"sfu_session"`
	// Codecs are the video codecs besides VP8 the device can watch.
	Codecs []string `json:"codecs"`
}

// callCodecs keeps, once each, the codecs a device says it can watch that
// calls know of: H.264, which phones share their screens in.
func callCodecs(in []string) []string {
	var out []string
	for _, c := range in {
		if c == "h264" && !slices.Contains(out, c) {
			out = append(out, c)
		}
	}
	return out
}

type voiceSignalReq struct {
	To      ids.ID          `json:"to"`
	Payload json.RawMessage `json:"payload"`
}

func voiceErr(c *gateway.Conn, code, msg string) {
	c.Send("voice_error", map[string]any{"code": code, "message": msg})
}

// handleVoiceOp serves the voice_state and voice_signal gateway ops.
func (s *Server) handleVoiceOp(ctx context.Context, c *gateway.Conn, op string, data []byte) {
	me := c.User()
	guest := c.Guest()
	switch op {
	case gateway.OpVoiceState:
		var req voiceStateReq
		if err := json.Unmarshal(data, &req); err != nil {
			voiceErr(c, "bad_request", "Malformed voice state.")
			return
		}
		if req.ChannelID == nil {
			// a hang-up only counts from the connection in the call, so a
			// second device or a displaced tab cannot end it for the other
			if st, ok := s.Voice.LeaveConn(me.ID, c.ID()); ok {
				s.broadcastVoiceLeave(ctx, st)
			}
			return
		}
		// a guest's link is for one call, and no other channel exists for them
		if guest != nil && *req.ChannelID != guest.ChannelID {
			voiceErr(c, "not_found", "That channel does not exist.")
			return
		}
		ch, err := s.Store.ChannelByID(ctx, *req.ChannelID)
		if err != nil {
			voiceErr(c, "not_found", "That channel does not exist.")
			return
		}
		p, ok, code, msg := s.canJoinVoice(ctx, me, ch)
		if !ok {
			voiceErr(c, code, msg)
			return
		}
		// Priority Speaker is a server's permission: never in a DM call or for a guest
		priority := req.Priority && guest == nil && ch.ServerID != nil && p.Has(perms.PrioritySpeaker)
		// a camera or a screen share is Video (Stream) in the channel
		if !p.Has(perms.Stream) {
			req.Video, req.Streaming = false, false
		}
		// guests waiting in a call do not make it one: the first account to join still starts it
		wasEmpty := s.Voice.MemberCount(ch.ID) == 0
		// Only a device rejoining the call it was in (after a reconnect or a
		// restart) carries on the call before. Calling again after a call
		// ended is a new call with its own line: folded into the last one, a
		// second try that was answered left history saying only that the
		// first went unanswered.
		resumed := req.Resume
		cur, had := s.Voice.Get(me.ID)
		inSame := had && cur.ChannelID == ch.ID
		// Everyone in a call has to connect the same way. A page set up for
		// the other kind (opened before calls changed, or an older version of
		// the app) is turned away rather than let into a call it cannot hear.
		if (!inSame || cur.ConnID != c.ID()) && req.SFU != (s.Realtime != nil) {
			if s.Realtime != nil {
				voiceErr(c, "sfu_required", "Calls here have changed. Reload the page to join.")
			} else {
				voiceErr(c, "sfu_off", "Calls here have changed. Join again.")
			}
			return
		}
		// A member's server mute and deafen are kept on the member, so hanging
		// up and joining again does not lift them: what is written there holds,
		// in every voice channel of the server. Read before the page hears how
		// a resume went, so nothing waits on the database between that and the
		// join below.
		var memberFlags int64
		haveFlags := false
		if guest == nil && ch.ServerID != nil {
			if flags, err := s.Store.MemberFlags(ctx, *ch.ServerID, me.ID); err == nil {
				memberFlags, haveFlags = flags, true
			}
		}
		// The device holding this call through the SFU, back on a new gateway
		// connection: the call is handed to it as it is, media and all, rather
		// than ended and started over. The page hears first whether that worked.
		if req.Resume && req.SFUSession != "" {
			if inSame {
				if st, ok := s.Voice.Resume(me.ID, c.SessionID(), req.SFUSession, c.ID()); ok {
					// Update answers a zero State when the person has already
					// hung up, and broadcasting that puts a participant with
					// id "0" in every viewer's roster that nothing ever
					// removes. What Resume gave us is the last real thing.
					if fresh, ok := s.Voice.Update(me.ID, func(x *voice.State) {
						x.SelfMute, x.SelfDeaf, x.Video, x.Streaming = req.SelfMute, req.SelfDeaf, req.Video, req.Streaming
						x.Priority = priority
						x.Codecs = callCodecs(req.Codecs)
					}); ok {
						st = fresh
					}
					fresh, valid := s.recheckSFUState(ctx, st)
					if !valid {
						c.Send(gateway.EvVoiceResumed, map[string]any{"channel_id": ch.ID, "kept": false})
						return
					}
					st = fresh
					c.Send(gateway.EvVoiceResumed, map[string]any{"channel_id": ch.ID, "kept": st.SFUSession == req.SFUSession})
					s.sfuSyncLive(st)
					s.Bcast.EmitUsers(s.voiceAudience(ctx, ch), "voice_state", statePayload(st))
					return
				}
			}
			c.Send(gateway.EvVoiceResumed, map[string]any{"channel_id": ch.ID, "kept": false})
		}
		// server mutes belong to one server: they follow a move inside it,
		// but nobody could lift them in a DM call or in another server
		keep := inSame
		if had && !inSame && ch.ServerID != nil {
			if prev, err := s.Store.ChannelByID(ctx, cur.ChannelID); err == nil && prev.ServerID != nil && *prev.ServerID == *ch.ServerID {
				keep = true
			}
		}
		next := voice.State{Priority: priority, UserID: me.ID, ChannelID: ch.ID, SessionID: c.SessionID(), ConnID: c.ID(), SelfMute: req.SelfMute, SelfDeaf: req.SelfDeaf, Video: req.Video, Streaming: req.Streaming, Suppress: !p.Has(perms.Speak), Name: me.DisplayName, AvatarID: me.AvatarID, Codecs: callCodecs(req.Codecs)}
		if guest != nil {
			next.Guest, next.GuestEpoch = true, guest.Epoch
		} else if haveFlags {
			next.ServerMute = memberFlags&store.MemberVoiceMuted != 0
			next.ServerDeaf = memberFlags&store.MemberVoiceDeafened != 0
			keep = false
		}
		old, started := s.Voice.Join(next, keep)
		if old != nil && old.ChannelID != ch.ID {
			s.broadcastVoiceLeave(ctx, *old)
		} else if old != nil && old.ConnID != c.ID() {
			// the call moved to this connection (another device or tab picked
			// it up): the one that held it hears that it is no longer in it
			s.closeSFUSession(old.SFUSession)
			moved := leavePayload(*old)
			moved.Reason = "moved"
			s.emitConn(me.ID, old.ConnID, "voice_state", moved)
		}
		st, ok := s.Voice.Get(me.ID)
		if !ok {
			// they hung up between the join and this read; the leave that
			// overtook us has already told everyone
			return
		}
		if st.ChannelID != ch.ID || st.ConnID != c.ID() || st.SessionID != c.SessionID() {
			return
		}
		st, valid := s.recheckSFUState(ctx, st)
		if !valid {
			return
		}
		s.sfuSyncLive(st)
		if guest == nil && s.isDM(ch) && started {
			// A call is something new in the conversation, like a message: the
			// people it rings have to see who is calling and where to answer,
			// even if they closed it or were never shown it.
			s.revealDM(ctx, ch)
		}
		audience := s.voiceAudience(ctx, ch)
		s.Bcast.EmitUsers(audience, "voice_state", statePayload(st))
		if guest == nil && s.isDM(ch) && !inSame && !wasEmpty {
			// answering: this user's other devices stop ringing
			s.Bcast.EmitUsers([]ids.ID{me.ID}, "call_end", map[string]any{"channel_id": ch.ID, "user_id": me.ID, "reason": "answered"})
		}
		// a guest never rings anyone: the link lets them wait in the call, not
		// call people. Whether this join started the call comes from Join
		// itself, so two tabs of one person racing into an empty DM cannot
		// both decide they were the first.
		if s.isDM(ch) && started {
			// joining an empty DM starts a call: ring the others, except
			// anyone who blocked the caller
			others := []ids.ID{}
			for _, id := range ch.Recipients {
				if id == me.ID {
					continue
				}
				if _, blockedMe, err := s.Store.Blocked(ctx, me.ID, id); err != nil || blockedMe {
					continue
				}
				others = append(others, id)
			}
			// A device that still holds the call's SFU session is picking up a
			// call already going on, after the server restarted and forgot it:
			// ringing then buzzed the others' phones on every deploy. A resume
			// without one is a call that ended while the device was away, and
			// rings like any other.
			if !(req.Resume && req.SFUSession != "") {
				s.Bcast.EmitUsers(others, "call_ring", map[string]any{"channel_id": ch.ID, "from": me.ID})
			}
			// a rejoin after a reconnect or a restart is the same call
			if !resumed {
				m := &store.Message{ChannelID: ch.ID, AuthorID: me.ID, Type: "system_call", Mentions: []ids.ID{}}
				if err := s.Store.InsertMessage(ctx, m); err == nil {
					m.MentionRoles = []ids.ID{}
					m.Attachments = []store.Attachment{}
					m.Reactions = []store.ReactionSummary{}
					s.Bcast.EmitChannel(ch.ID, "message_create", m)
				}
			}
		}
	case gateway.OpVoiceSignal:
		if len(data) > maxSignalBytes {
			voiceErr(c, "too_large", "Signal payload is too large.")
			return
		}
		var req voiceSignalReq
		if err := json.Unmarshal(data, &req); err != nil {
			voiceErr(c, "bad_request", "Malformed signal.")
			return
		}
		mine, ok := s.Voice.Get(me.ID)
		if !ok {
			voiceErr(c, "not_in_voice", "Join a voice channel first.")
			return
		}
		if mine.ConnID != c.ID() {
			// a device that no longer holds the call; its leave is on the way
			return
		}
		target, ok := s.Voice.Get(req.To)
		if !ok || target.ChannelID != mine.ChannelID {
			voiceErr(c, "peer_gone", "That user is not in your voice channel.")
			return
		}
		// only the target's device in the call may answer; another device of
		// the same person would negotiate a second connection under one id.
		// The numbers a signal carries to the other side are held to sane ranges.
		s.emitConn(req.To, target.ConnID, "voice_signal", map[string]any{"from": me.ID, "channel_id": mine.ChannelID, "payload": voice.CleanSignal(req.Payload)})
	}
}

// canJoinVoice checks channel type, membership, blocks, Connect, timeouts and
// the user limit, and returns the caller's permissions in the channel.
func (s *Server) canJoinVoice(ctx context.Context, me *store.User, ch *store.Channel) (perms.Perm, bool, string, string) {
	if me.Flags&store.FlagGuest != 0 {
		return s.canGuestJoinVoice(ctx, me, ch)
	}
	// a muted account is in no call: it was taken out of the one it was in
	// when the mute began, and cannot start or join another until it ends
	if mute, _, err := s.Store.ActiveRestrictions(ctx, me.ID, time.Now().UnixMilli()); err == nil && mute != nil {
		return 0, false, "account_muted", mutedError(mute).Message
	}
	if ch.Type == "subchat" {
		return 0, false, "no_call", "A subchat has no call of its own. Use the conversation it belongs to."
	}
	if cur, ok := s.Voice.Get(me.ID); s.Meter != nil && !(ok && cur.ChannelID == ch.ID) && !s.Meter.CallsAllowed(ctx) {
		// toggles inside a running call still pass; new calls wait for next month
		return 0, false, "calls_paused", "Calls are paused until next month to keep them free."
	}
	if s.isDM(ch) {
		member := false
		for _, id := range ch.Recipients {
			if id == me.ID {
				member = true
			}
		}
		if !member {
			return 0, false, "not_found", "That channel does not exist."
		}
		cur, ok := s.Voice.Get(me.ID)
		if ch.Type == "dm" && !(ok && cur.ChannelID == ch.ID) {
			// a block in either direction stops new calls, like it stops
			// messaging; mute toggles inside a call already running still pass
			for _, other := range ch.Recipients {
				if other == me.ID {
					continue
				}
				if ab, ba, err := s.Store.Blocked(ctx, me.ID, other); err != nil || ab || ba {
					return 0, false, "blocked", "You cannot call this user."
				}
			}
		}
		return perms.All, true, "", ""
	}
	if ch.Type != "voice" {
		return 0, false, "not_voice", "That is not a voice channel."
	}
	p, _, err := s.Store.ChannelPerms(ctx, ch.ID, me.ID)
	if err != nil || p == 0 {
		return 0, false, "not_found", "That channel does not exist."
	}
	if !p.Has(perms.Connect) {
		return p, false, "missing_permission", "You cannot connect to that channel."
	}
	if cur, ok := s.Voice.Get(me.ID); ok && cur.ChannelID == ch.ID {
		return p, true, "", ""
	}
	if ch.UserLimit > 0 && s.Voice.Count(ch.ID) >= ch.UserLimit && !p.Has(perms.MoveMembers) {
		return p, false, "channel_full", "That voice channel is full."
	}
	return p, true, "", ""
}

func (s *Server) handleVoiceICE(w http.ResponseWriter, r *http.Request) {
	servers := []map[string]any{}
	if len(s.Cfg.StunURLs) > 0 {
		servers = append(servers, map[string]any{"urls": s.Cfg.StunURLs})
	}
	if len(s.Cfg.TurnURLs) > 0 {
		entry := map[string]any{"urls": s.Cfg.TurnURLs}
		if s.Cfg.TurnUsername != "" {
			entry["username"] = s.Cfg.TurnUsername
			entry["credential"] = s.Cfg.TurnCredential
		}
		servers = append(servers, entry)
	}
	if relay := s.cloudflareTURN(r); relay != nil {
		servers = append(servers, relay...)
	}
	WriteJSON(w, http.StatusOK, map[string]any{"ice_servers": servers, "sfu": s.Realtime != nil, "guests": s.guestsOn()})
}

func (s *Server) handleVoiceParticipants(w http.ResponseWriter, r *http.Request) {
	ch, _ := s.channel(w, r, NeedView)
	if ch == nil {
		return
	}
	out := []voiceStatePayload{}
	for _, st := range s.Voice.Participants(ch.ID) {
		out = append(out, statePayload(st))
	}
	WriteJSON(w, http.StatusOK, out)
}

// handleCallDecline tells the callers that this recipient will not pick up.
func (s *Server) handleCallDecline(w http.ResponseWriter, r *http.Request) {
	ch, me := s.channel(w, r, NeedView)
	if ch == nil {
		return
	}
	if !s.isDM(ch) {
		WriteError(w, E(http.StatusBadRequest, "not_dm", "Only DM calls can be declined."))
		return
	}
	s.Bcast.EmitUsers(ch.Recipients, "call_end", map[string]any{"channel_id": ch.ID, "user_id": me.ID, "reason": "declined"})
	w.WriteHeader(http.StatusNoContent)
}

// maxCallSummaryBytes bounds a call summary's envelope: an outcome, a length
// and the ids of who picked up or said no, in a group of fifty.
const maxCallSummaryBytes = 8 << 10

// handleCallSummary takes the caller's encrypted summary of a call that has
// ended (answered or not, for how long, who picked up) and seals it onto the
// line that says the call started, so the conversation can show "Missed call
// from Alice" or "Call with Bob · 12 minutes". The server keeps no call
// records: it cannot read the summary, the line is not marked edited, and when
// it arrived is not written down.
func (s *Server) handleCallSummary(w http.ResponseWriter, r *http.Request) {
	ch, me := s.channel(w, r, NeedView)
	if ch == nil {
		return
	}
	if !s.isDM(ch) {
		WriteError(w, E(http.StatusBadRequest, "not_dm", "Only calls in conversations have a summary."))
		return
	}
	mid, err := ids.Parse(r.PathValue("mid"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	var body messageBody
	if err := ReadJSON(w, r, &body, maxCallSummaryBytes+1<<10); err != nil {
		WriteError(w, err)
		return
	}
	if len(body.Ciphertext) > maxCallSummaryBytes {
		WriteError(w, E(http.StatusBadRequest, "too_large", "The call summary is too large."))
		return
	}
	ctx := r.Context()
	m, err := s.Store.MessageByID(ctx, mid, me.ID)
	if err != nil || m.ChannelID != ch.ID || m.Type != "system_call" {
		WriteError(w, ErrNotFound)
		return
	}
	if m.AuthorID != me.ID {
		WriteError(w, E(http.StatusForbidden, "not_caller", "Only whoever started the call can say how it went."))
		return
	}
	env, ok := s.checkEnvelope(w, r, ch, &body)
	if !ok {
		return
	}
	if err := s.Store.SealCallSummary(ctx, mid, me.ID, body.Ciphertext, env.Conv, env.Epoch); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			WriteError(w, E(http.StatusConflict, "already_summarized", "This call already has its summary."))
			return
		}
		WriteError(w, err)
		return
	}
	if shared, err := s.Store.MessageByID(ctx, mid, 0); err == nil {
		s.Bcast.EmitChannel(ch.ID, "message_update", messageUpdateEvent{shared, mentionsBefore{m.Mentions, m.MentionRoles, m.MentionEveryone}})
	}
	w.WriteHeader(http.StatusNoContent)
}

// handleVoiceModerate server-mutes, deafens, moves or disconnects a member.
func (s *Server) handleVoiceModerate(w http.ResponseWriter, r *http.Request) {
	srv, me, _ := s.serverCtx(w, r, 0)
	if srv == nil {
		return
	}
	uid, err := ids.Parse(r.PathValue("uid"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	var raw map[string]json.RawMessage
	if err := ReadJSON(w, r, &raw, 4<<10); err != nil {
		WriteError(w, err)
		return
	}
	// A body naming none of the three does nothing, and the route needs only
	// membership. Left to run, it answered the target's full voice state — the
	// channel they are in, whether their camera is on — to any member of the
	// server, including for a channel the caller cannot view, and wrote an
	// audit entry for the privilege. Nothing to do is not a request.
	if _, hasMute := raw["mute"]; !hasMute {
		if _, hasDeaf := raw["deaf"]; !hasDeaf {
			if _, hasChannel := raw["channel_id"]; !hasChannel {
				WriteError(w, E(http.StatusBadRequest, "nothing_to_do", "Say what to change: mute, deaf or channel_id."))
				return
			}
		}
	}
	st, ok := s.Voice.Get(uid)
	if !ok {
		WriteError(w, E(http.StatusBadRequest, "not_in_voice", "That member is not in a voice channel."))
		return
	}
	cur, err := s.Store.ChannelByID(r.Context(), st.ChannelID)
	if err != nil || cur.ServerID == nil || *cur.ServerID != srv.ID {
		WriteError(w, E(http.StatusBadRequest, "not_in_voice", "That member is not in a voice channel of this server."))
		return
	}
	// Moderating someone in a channel you cannot see would also tell you they
	// are in it, which is the one thing a private voice channel hides. The
	// permissions are the ones in that channel, as for everything else said
	// there: an overwrite can give a channel's own moderators Mute Members.
	actorPerms, _, err := s.Store.ChannelPerms(r.Context(), cur.ID, me.ID)
	if err != nil || !actorPerms.Has(perms.ViewChannel) {
		WriteError(w, E(http.StatusBadRequest, "not_in_voice", "That member is not in a voice channel of this server."))
		return
	}
	// a guest holds no roles, so anyone with the permission may act on one
	if !st.Guest && !s.canActOn(r.Context(), srv, me.ID, uid) {
		WriteError(w, E(http.StatusForbidden, "role_hierarchy", "You cannot moderate this member."))
		return
	}
	changes := map[string]any{}
	var mute, deaf *bool
	if v, ok := raw["mute"]; ok {
		if !actorPerms.Has(perms.MuteMembers) {
			WriteError(w, E(http.StatusForbidden, "missing_permission", "You need Mute Members."))
			return
		}
		var b bool
		if json.Unmarshal(v, &b) != nil {
			WriteError(w, E(http.StatusBadRequest, "invalid_value", "mute must be true or false."))
			return
		}
		mute = &b
		changes["mute"] = b
	}
	if v, ok := raw["deaf"]; ok {
		if !actorPerms.Has(perms.DeafenMembers) {
			WriteError(w, E(http.StatusForbidden, "missing_permission", "You need Deafen Members."))
			return
		}
		var b bool
		if json.Unmarshal(v, &b) != nil {
			WriteError(w, E(http.StatusBadRequest, "invalid_value", "deaf must be true or false."))
			return
		}
		deaf = &b
		changes["deaf"] = b
	}
	// Validate the whole request before changing anything, so a refused move
	// cannot leave a mute applied with no audit entry.
	var dest *store.Channel
	var destPerms perms.Perm
	disconnect := false
	if v, ok := raw["channel_id"]; ok {
		if !actorPerms.Has(perms.MoveMembers) {
			WriteError(w, E(http.StatusForbidden, "missing_permission", "You need Move Members."))
			return
		}
		if string(v) == "null" {
			disconnect = true
			changes["channel_id"] = nil
		} else {
			var target ids.ID
			if json.Unmarshal(v, &target) != nil {
				WriteError(w, E(http.StatusBadRequest, "invalid_channel", "channel_id is malformed."))
				return
			}
			// a guest's link is for one call; anywhere else is a channel that does not exist for them
			if st.Guest {
				WriteError(w, E(http.StatusBadRequest, "guest_call", "A guest can only be in the call their link is for."))
				return
			}
			dest, err = s.Store.ChannelByID(r.Context(), target)
			if err != nil || dest.ServerID == nil || *dest.ServerID != srv.ID || dest.Type != "voice" {
				WriteError(w, E(http.StatusBadRequest, "invalid_channel", "The destination must be a voice channel of this server."))
				return
			}
			// nobody moves people into a channel they cannot see themselves:
			// that would also say a private channel is there
			if mine, _, err := s.Store.ChannelPerms(r.Context(), dest.ID, me.ID); err != nil || !mine.Has(perms.ViewChannel) {
				WriteError(w, E(http.StatusBadRequest, "invalid_channel", "The destination must be a voice channel of this server."))
				return
			}
			if destPerms, _, err = s.Store.ChannelPerms(r.Context(), dest.ID, uid); err != nil || !destPerms.Has(perms.Connect) {
				WriteError(w, E(http.StatusBadRequest, "cannot_connect", "That member cannot connect to the destination."))
				return
			}
			changes["channel_id"] = dest.ID
		}
	}
	if (mute != nil || deaf != nil) && !st.Guest {
		// kept on the member, where the next join reads it (handleVoiceOp)
		var on, off int64
		for _, x := range []struct {
			v   *bool
			bit int64
		}{{mute, store.MemberVoiceMuted}, {deaf, store.MemberVoiceDeafened}} {
			if x.v == nil {
				continue
			}
			if *x.v {
				on |= x.bit
			} else {
				off |= x.bit
			}
		}
		if err := s.Store.SetMemberFlags(r.Context(), srv.ID, uid, on, off); err != nil {
			WriteError(w, err)
			return
		}
	}
	if mute != nil || deaf != nil {
		// The target can hang up while the request body is being read. Update
		// then answers a zero State, whose payload names user "0" in channel
		// "0"; clients add that participant and nothing ever removes it,
		// because no leave for id 0 will arrive.
		if updated, ok := s.Voice.Update(uid, func(x *voice.State) {
			if x.ChannelID != st.ChannelID || x.ConnID != st.ConnID {
				return
			}
			if mute != nil {
				x.ServerMute = *mute
			}
			if deaf != nil {
				x.ServerDeaf = *deaf
			}
		}); ok && updated.ChannelID == st.ChannelID && updated.ConnID == st.ConnID {
			if fresh, valid := s.recheckSFUState(r.Context(), updated); valid {
				s.Bcast.EmitUsers(s.voiceAudience(r.Context(), cur), "voice_state", statePayload(fresh))
			}
		}
	}
	if disconnect {
		if g, err := s.Store.GuestByID(r.Context(), uid); st.Guest && err == nil {
			// a guest disconnected would only join again: they are let go
			s.endGuest(r.Context(), g, "removed")
			s.rotateAfterGuests(r.Context(), cur)
		} else if left, ok := s.Voice.Leave(uid); ok {
			// their app says a moderator did it, rather than leave them wondering
			s.broadcastVoiceLeaveFor(r.Context(), left, "disconnected")
		}
	} else if dest != nil && dest.ID != cur.ID {
		if latest, ok := s.Voice.Get(uid); ok && latest.ChannelID == cur.ID {
			old, _ := s.Voice.Join(voice.State{UserID: uid, ChannelID: dest.ID, SessionID: latest.SessionID, ConnID: latest.ConnID, SelfMute: latest.SelfMute, SelfDeaf: latest.SelfDeaf, Video: latest.Video && destPerms.Has(perms.Stream), Streaming: latest.Streaming && destPerms.Has(perms.Stream), JoinedAt: latest.JoinedAt, Suppress: !destPerms.Has(perms.Speak), Priority: latest.Priority && destPerms.Has(perms.PrioritySpeaker), Name: latest.Name, AvatarID: latest.AvatarID}, true)
			if old != nil {
				// what they pulled in the old channel stops; their client makes a new session
				s.closeSFUSession(old.SFUSession)
				// The moved member must not hear this leave: their client takes
				// a leave of its own channel as a disconnect and drops the call.
				// The join below moves them instead.
				s.Bcast.EmitUsers(withoutID(s.voiceAudience(r.Context(), cur), uid), "voice_state", leavePayload(*old))
			}
			if moved, ok := s.Voice.Get(uid); ok {
				s.Bcast.EmitUsers(append(withoutID(s.voiceAudience(r.Context(), dest), uid), uid), "voice_state", statePayload(moved))
			}
		}
	}
	s.audit(r, srv.ID, me.ID, "member_voice", "user", &uid, changes, "")
	final, ok := s.Voice.Get(uid)
	if !ok {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	WriteJSON(w, http.StatusOK, statePayload(final))
}

func withoutID(list []ids.ID, id ids.ID) []ids.ID {
	out := make([]ids.ID, 0, len(list))
	for _, x := range list {
		if x != id {
			out = append(out, x)
		}
	}
	return out
}

// voiceChannelDeleted kicks everyone out of a channel that no longer exists.
// A DM's recipients are on the channel itself, so this also works after the
// conversation was deleted.
func (s *Server) voiceChannelDeleted(ctx context.Context, ch *store.Channel) {
	if s.Voice == nil {
		return
	}
	audience := s.voiceAudience(ctx, ch)
	cleared := s.Voice.ClearChannel(ch.ID)
	for _, st := range cleared {
		s.closeSFUSession(st.SFUSession)
		s.Bcast.EmitUsers(audience, "voice_state", leavePayload(st))
	}
	if len(cleared) > 0 && s.isDM(ch) {
		s.Bcast.EmitUsers(audience, "call_end", map[string]any{"channel_id": ch.ID, "reason": "ended"})
	}
	// the channel's guests went with it; their sockets are told and closed
	for _, st := range cleared {
		if st.Guest {
			s.dropGuest(ctx, st.UserID, "ended")
		}
	}
}

// voiceServerDeleted kicks everyone out of the voice channels of a deleted
// server. The channels and members are read before the deletion.
func (s *Server) voiceServerDeleted(chans []*store.Channel, members []ids.ID) {
	if s.Voice == nil {
		return
	}
	for _, ch := range chans {
		if ch.Type != "voice" {
			continue
		}
		for _, st := range s.Voice.ClearChannel(ch.ID) {
			s.closeSFUSession(st.SFUSession)
			s.Bcast.EmitUsers(members, "voice_state", leavePayload(st))
			if st.Guest {
				s.dropGuest(context.Background(), st.UserID, "ended")
			}
		}
	}
}

// dropVoice ends a user's call when it is in a channel they are losing access
// to (a kick, ban, timeout or leaving a group). Run it while the user can still
// see the channel so they hear the leave with everyone else.
func (s *Server) dropVoice(ctx context.Context, uid ids.ID, losing func(*store.Channel) bool) {
	if s.Voice == nil {
		return
	}
	st, ok := s.Voice.Get(uid)
	if !ok {
		return
	}
	ch, err := s.Store.ChannelByID(ctx, st.ChannelID)
	if err != nil || !losing(ch) {
		return
	}
	if left, ok := s.Voice.LeaveChannel(uid, st.ChannelID); ok {
		s.broadcastVoiceLeave(ctx, left)
	}
}
