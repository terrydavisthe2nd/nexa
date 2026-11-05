package api

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"net/http"
	"time"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/perms"
	"evilion.xyz/chat/internal/realtime"
	"evilion.xyz/chat/internal/store"
	"evilion.xyz/chat/internal/voice"
)

// Calls through Cloudflare Realtime SFU. chatd is the SFU's signalling proxy:
// it opens one session per call device, publishes that device's tracks under
// names only it knows, and lets a device pull only the tracks of people in its
// own call. Media is encrypted frame by frame in the browsers, so the SFU
// forwards what it cannot read, and nobody reaches anybody else's address.

const maxSDPBytes = 256 << 10

func (s *Server) sfuRoutes(mux *http.ServeMux) {
	// guests in a call run it through these too
	mux.Handle("POST /api/v1/voice/sfu/session", s.sfuAuthed(s.handleSFUSession))
	mux.Handle("POST /api/v1/voice/sfu/publish", s.sfuAuthed(s.handleSFUPublish))
	mux.Handle("POST /api/v1/voice/sfu/subscribe", s.sfuAuthed(s.handleSFUSubscribe))
	mux.Handle("PUT /api/v1/voice/sfu/renegotiate", s.sfuAuthed(s.handleSFURenegotiate))
	mux.Handle("PUT /api/v1/voice/sfu/close", s.sfuAuthed(s.handleSFUClose))
	mux.Handle("PUT /api/v1/voice/sfu/restart", s.sfuAuthed(s.handleSFURestart))
}

// Cloudflare requires mutations of a session to be serialized. The bounded
// stripes also prevent parallel publication from evading the per-kind cap.
func (s *Server) sfuAuthed(h http.HandlerFunc) http.Handler {
	return s.callAuthed(func(w http.ResponseWriter, r *http.Request) {
		uid := auth.UserFrom(r.Context()).ID
		lock := &s.sfuLocks[uint64(uid)%uint64(len(s.sfuLocks))]
		lock.Lock()
		defer lock.Unlock()
		h(w, r)
	})
}

// sfuCaller returns the caller and their call on this device, answering the
// request itself when there is none.
func (s *Server) sfuCaller(w http.ResponseWriter, r *http.Request) (*store.User, voice.State, bool) {
	if s.Realtime == nil {
		WriteError(w, E(http.StatusNotFound, "sfu_off", "Calls do not go through an SFU on this server."))
		return nil, voice.State{}, false
	}
	me := auth.UserFrom(r.Context())
	se := auth.SessionFrom(r.Context())
	if ok, retry := s.limVoice.Allow("sfu:" + me.ID.String()); !ok {
		w.Header().Set("Retry-After", retryAfter(retry))
		WriteError(w, ErrRateLimited)
		return nil, voice.State{}, false
	}
	st, ok := s.Voice.Get(me.ID)
	// the device making the request is the one in the call
	if !ok || se == nil || st.SessionID != se.ID {
		WriteError(w, E(http.StatusConflict, "not_in_voice", "Join the call first."))
		return nil, voice.State{}, false
	}
	fresh, err := s.sfuPolicyLocked(r.Context(), st)
	if err != nil {
		WriteError(w, err)
		return nil, voice.State{}, false
	}
	return me, fresh, true
}

// mayStream reports whether someone in a call may send a camera or a screen
// share there: Video (Stream) in a server's channel, including @everyone's
// permissions for guests, and always in a DM call. Asked again at every publish and pull, so a role
// that loses it mid-call stops being seen.
func (s *Server) mayStream(ctx context.Context, st voice.State) bool {
	ch, err := s.Store.ChannelByID(ctx, st.ChannelID)
	if err != nil {
		return false
	}
	if st.Guest {
		return s.guestVoicePerms(ctx, ch).Has(perms.Stream)
	}
	if ch.ServerID == nil {
		return true
	}
	p, err := s.Store.PermsIn(ctx, ch, st.UserID)
	return err == nil && p.Has(perms.Stream)
}

func sfuUnavailable(w http.ResponseWriter, err error) {
	var apiErr *realtime.Error
	if errors.As(err, &apiErr) && apiErr.Status >= 400 && apiErr.Status < 500 {
		WriteError(w, E(http.StatusBadRequest, "sfu_refused", "The call service refused that: "+apiErr.Description))
		return
	}
	WriteError(w, E(http.StatusBadGateway, "sfu_unavailable", "The call service could not be reached. Try again in a moment."))
}

func validDescription(d *realtime.SessionDescription, want ...string) bool {
	if d == nil || d.SDP == "" || len(d.SDP) > maxSDPBytes {
		return false
	}
	for _, t := range want {
		if d.Type == t {
			return true
		}
	}
	return false
}

// handleSFUSession opens the SFU session for the caller's call, replacing any
// earlier one of it.
func (s *Server) handleSFUSession(w http.ResponseWriter, r *http.Request) {
	me, st, ok := s.sfuCaller(w, r)
	if !ok {
		return
	}
	id, err := s.Realtime.NewSession(r.Context())
	if err != nil {
		s.Log.Warn("sfu session", "err", err)
		sfuUnavailable(w, err)
		return
	}
	// which account the session is, so Cloudflare's measurement of it can be
	// put to a name on the staff's Calls page
	if err := s.Store.RecordRealtimeSession(r.Context(), id, me.ID); err != nil {
		s.Log.Warn("sfu session not recorded", "err", err)
	}
	old, ok := s.Voice.SetSFU(me.ID, st.ChannelID, st.ConnID, id)
	if !ok {
		// the call ended or moved while the session was being opened
		s.closeSFUSession(id)
		WriteError(w, E(http.StatusConflict, "not_in_voice", "Join the call first."))
		return
	}
	if old != "" {
		s.closeSFUSession(old)
	}
	WriteJSON(w, http.StatusOK, map[string]any{"session_id": id})
}

type sfuPublishReq struct {
	SessionID string                       `json:"session_id"`
	Offer     *realtime.SessionDescription `json:"offer"`
	Tracks    []struct {
		Mid     string `json:"mid"`
		Kind    string `json:"kind"`
		Bitrate int64  `json:"bitrate"`
	} `json:"tracks"`
}

// handleSFUPublish publishes the caller's tracks with their offer.
func (s *Server) handleSFUPublish(w http.ResponseWriter, r *http.Request) {
	me, st, ok := s.sfuCaller(w, r)
	if !ok {
		return
	}
	var req sfuPublishReq
	if err := ReadJSON(w, r, &req, maxSDPBytes+8<<10); err != nil {
		WriteError(w, err)
		return
	}
	if req.SessionID == "" || req.SessionID != st.SFUSession {
		WriteError(w, E(http.StatusConflict, "sfu_session_gone", "That call session has ended. Start it again."))
		return
	}
	if !validDescription(req.Offer, "offer") || len(req.Tracks) == 0 || len(req.Tracks) > 4 {
		WriteError(w, E(http.StatusBadRequest, "bad_request", "Publishing needs an offer and one to four tracks."))
		return
	}
	seen := map[string]bool{}
	seenMids := map[string]bool{}
	media, validMedia := sfuMediaTypes(req.Offer.SDP)
	if !validMedia {
		WriteError(w, E(http.StatusBadRequest, "bad_request", "The offer needs unambiguous media sections."))
		return
	}
	tracks := make([]realtime.Track, 0, len(req.Tracks))
	names := map[string]string{}
	for _, t := range req.Tracks {
		if !realtime.ValidKind(t.Kind) || seen[t.Kind] || seenMids[t.Mid] || t.Mid == "" || len(t.Mid) > 16 || !sfuMediaMatches(media, t.Mid, t.Kind) {
			WriteError(w, E(http.StatusBadRequest, "bad_request", "Each track needs a mid and a kind of its own."))
			return
		}
		seen[t.Kind] = true
		seenMids[t.Mid] = true
		for _, kind := range []string{realtime.KindAudio, realtime.KindVideo, realtime.KindScreen, realtime.KindScreenAudio} {
			if st.TrackMids.Get(kind) == t.Mid {
				WriteError(w, E(http.StatusConflict, "already_published", "Close the existing track before reusing its mid."))
				return
			}
		}
		if st.Tracks.Get(t.Kind) != "" {
			WriteError(w, E(http.StatusConflict, "already_published", "Close the existing track before publishing another of this kind."))
			return
		}
		if (t.Kind == realtime.KindAudio || t.Kind == realtime.KindScreenAudio) && (st.ServerMute || st.ServerDeaf || st.Suppress) {
			WriteError(w, ErrForbidden)
			return
		}
		if t.Kind != realtime.KindAudio && !s.mayStream(r.Context(), st) {
			WriteError(w, E(http.StatusForbidden, "missing_permission", "You cannot share video in this channel."))
			return
		}
		if realtime.IsVideo(t.Kind) && !s.Meter.VideoAllowed(r.Context()) {
			WriteError(w, E(http.StatusForbidden, "video_paused", "Video is paused until next month to keep calls free."))
			return
		}
		name := me.ID.String() + "-" + t.Kind + "-" + randomHex(6)
		names[t.Mid] = name
		tracks = append(tracks, realtime.Track{Location: "local", Mid: t.Mid, TrackName: name})
	}
	res, err := s.Realtime.AddTracks(r.Context(), req.SessionID, req.Offer, tracks)
	if err != nil {
		s.Log.Warn("sfu publish", "err", err)
		sfuUnavailable(w, err)
		return
	}
	failed := map[string]bool{}
	for _, t := range res.Tracks {
		if t.ErrorCode != "" {
			failed[t.Mid] = true
		}
	}
	var latest voice.State
	published := false
	for _, t := range req.Tracks {
		if failed[t.Mid] {
			continue
		}
		bps := int64(0)
		switch t.Kind {
		case realtime.KindScreen:
			bps = realtime.ClampScreenBps(t.Bitrate)
		case realtime.KindAudio:
			bps = realtime.ClampVoiceBps(t.Bitrate)
		}
		if next, ok := s.Voice.SetPublishedTrack(me.ID, req.SessionID, t.Kind, names[t.Mid], t.Mid, bps); ok {
			latest, published = next, true
		}
	}
	fresh, err := s.sfuPolicyLocked(r.Context(), st)
	if err != nil || fresh.SFUSession != req.SessionID {
		s.closeSFUSession(req.SessionID)
		WriteError(w, E(http.StatusConflict, "sfu_session_gone", "That call session has ended. Start it again."))
		return
	}
	if published {
		latest = fresh
		s.sfuSyncLive(latest)
		if ch, err := s.Store.ChannelByID(r.Context(), latest.ChannelID); err == nil {
			s.Bcast.EmitUsers(s.voiceAudience(r.Context(), ch), "voice_state", statePayload(latest))
		}
	}
	WriteJSON(w, http.StatusOK, map[string]any{"answer": res.Description, "failed": len(failed)})
}

type sfuSubscribeReq struct {
	SessionID string `json:"session_id"`
	Tracks    []struct {
		UserID ids.ID `json:"user_id"`
		Kind   string `json:"kind"`
	} `json:"tracks"`
}

type sfuPulled struct {
	Mid    string `json:"mid,omitempty"`
	UserID ids.ID `json:"user_id"`
	Kind   string `json:"kind"`
	// Tag is the pulled track's tag, as voice states carry it
	Tag   string `json:"tag,omitempty"`
	Error string `json:"error,omitempty"`
}

// handleSFUSubscribe pulls tracks of people in the caller's call.
func (s *Server) handleSFUSubscribe(w http.ResponseWriter, r *http.Request) {
	me, st, ok := s.sfuCaller(w, r)
	if !ok {
		return
	}
	var req sfuSubscribeReq
	if err := ReadJSON(w, r, &req, 16<<10); err != nil {
		WriteError(w, err)
		return
	}
	if req.SessionID == "" || req.SessionID != st.SFUSession {
		WriteError(w, E(http.StatusConflict, "sfu_session_gone", "That call session has ended. Start it again."))
		return
	}
	if len(req.Tracks) == 0 || len(req.Tracks) > 64 {
		WriteError(w, E(http.StatusBadRequest, "bad_request", "Ask for one to 64 tracks."))
		return
	}
	out := make([]sfuPulled, 0, len(req.Tracks))
	type want struct {
		target voice.State
		kind   string
		name   string
	}
	var wants []want
	videoAllowed := true
	checkedVideo := false
	asked := map[string]bool{}
	streams := map[ids.ID]bool{}
	mayStream := func(target voice.State) bool {
		ok, seen := streams[target.UserID]
		if !seen {
			ok = s.mayStream(r.Context(), target)
			streams[target.UserID] = ok
		}
		return ok
	}
	for _, t := range req.Tracks {
		p := sfuPulled{UserID: t.UserID, Kind: t.Kind}
		target, ok := s.Voice.Get(t.UserID)
		key := t.UserID.String() + ":" + t.Kind
		switch {
		case asked[key]:
			// the same track twice in one request is pulled once
			p.Error = "duplicate"
		case !realtime.ValidKind(t.Kind):
			p.Error = "bad_kind"
		case (t.Kind == realtime.KindAudio || t.Kind == realtime.KindScreenAudio) && st.ServerDeaf:
			p.Error = "server_deaf"
		case (t.Kind == realtime.KindAudio || t.Kind == realtime.KindScreenAudio) && (target.ServerMute || target.ServerDeaf || target.Suppress):
			p.Error = "server_mute"
		case t.UserID == me.ID || !ok || target.ChannelID != st.ChannelID || target.SFUSession == "":
			// only people in the same call, and never yourself
			p.Error = "not_in_call"
		case target.Tracks.Get(t.Kind) == "":
			p.Error = "not_published"
		case t.Kind != realtime.KindAudio && !mayStream(target):
			p.Error = "missing_permission"
		default:
			if realtime.IsVideo(t.Kind) {
				if !checkedVideo {
					videoAllowed, checkedVideo = s.Meter.VideoAllowed(r.Context()), true
				}
				if !videoAllowed {
					p.Error = "video_paused"
					break
				}
			}
			asked[key] = true
			if mid, exists := s.Meter.ActiveMid(req.SessionID, realtime.Source(target.SFUSession, t.Kind)); exists {
				p.Mid, p.Tag = mid, voice.TrackTag(target.Tracks.Get(t.Kind))
				out = append(out, p)
				continue
			}
			wants = append(wants, want{target: target, kind: t.Kind, name: target.Tracks.Get(t.Kind)})
			continue
		}
		out = append(out, p)
	}
	if len(wants) == 0 {
		// nothing to pull: each entry says why, and there is nothing to negotiate
		WriteJSON(w, http.StatusOK, map[string]any{"offer": nil, "renegotiate": false, "tracks": out})
		return
	}
	tracks := make([]realtime.Track, 0, len(wants))
	for _, x := range wants {
		tracks = append(tracks, realtime.Track{Location: "remote", SessionID: x.target.SFUSession, TrackName: x.name})
	}
	res, err := s.Realtime.AddTracks(r.Context(), req.SessionID, nil, tracks)
	if err != nil {
		s.Log.Warn("sfu subscribe", "err", err)
		sfuUnavailable(w, err)
		return
	}
	// Moderation can change while the provider is negotiating. Do not return
	// that offer or start accounting against a stale authorization snapshot.
	fresh, err := s.sfuPolicyLocked(r.Context(), st)
	if err != nil || fresh.SFUSession != req.SessionID {
		s.closeSFUSession(req.SessionID)
		WriteError(w, E(http.StatusConflict, "sfu_session_gone", "That call session has ended. Start it again."))
		return
	}
	for _, x := range wants {
		target, exists := s.Voice.Get(x.target.UserID)
		audio := x.kind == realtime.KindAudio || x.kind == realtime.KindScreenAudio
		allowed := exists && target.ChannelID == fresh.ChannelID && target.SFUSession == x.target.SFUSession && target.Tracks.Get(x.kind) == x.name
		if audio && (fresh.ServerDeaf || target.ServerMute || target.ServerDeaf || target.Suppress) {
			allowed = false
		}
		if x.kind != realtime.KindAudio && !s.mayStream(r.Context(), target) {
			allowed = false
		}
		if !allowed {
			s.retireSFUStateLocked(r.Context(), fresh)
			WriteError(w, E(http.StatusConflict, "sfu_session_gone", "That call session has ended. Start it again."))
			return
		}
	}
	byName := map[string]realtime.Track{}
	for _, t := range res.Tracks {
		byName[t.TrackName] = t
	}
	for _, x := range wants {
		p := sfuPulled{UserID: x.target.UserID, Kind: x.kind}
		got, ok := byName[x.name]
		switch {
		case !ok:
			p.Error = "not_returned"
		case got.ErrorCode != "":
			p.Error = got.ErrorCode
		default:
			p.Mid = got.Mid
			p.Tag = voice.TrackTag(x.name)
			rate := realtime.RateFor(x.kind, 0)
			switch x.kind {
			case realtime.KindScreen:
				rate = realtime.RateFor(x.kind, s.shareMaxBps(r.Context(), x.target))
			case realtime.KindAudio:
				rate = realtime.ClampVoiceBps(x.target.AudioBps)
			}
			s.Meter.Start(req.SessionID, got.Mid, realtime.Source(x.target.SFUSession, x.kind), rate, int64(me.ID), int64(x.target.UserID))
		}
		out = append(out, p)
	}
	WriteJSON(w, http.StatusOK, map[string]any{"offer": res.Description, "renegotiate": res.Renegotiate, "tracks": out})
}

type sfuRenegotiateReq struct {
	SessionID   string                       `json:"session_id"`
	Description *realtime.SessionDescription `json:"description"`
}

// handleSFURenegotiate answers the SFU's offer, or sends the caller's own
// (an ICE restart) and returns the SFU's answer.
func (s *Server) handleSFURenegotiate(w http.ResponseWriter, r *http.Request) {
	_, st, ok := s.sfuCaller(w, r)
	if !ok {
		return
	}
	var req sfuRenegotiateReq
	if err := ReadJSON(w, r, &req, maxSDPBytes+4<<10); err != nil {
		WriteError(w, err)
		return
	}
	if req.SessionID == "" || req.SessionID != st.SFUSession {
		WriteError(w, E(http.StatusConflict, "sfu_session_gone", "That call session has ended. Start it again."))
		return
	}
	if !validDescription(req.Description, "offer", "answer") {
		WriteError(w, E(http.StatusBadRequest, "bad_request", "A session description is needed."))
		return
	}
	d, err := s.Realtime.Renegotiate(r.Context(), req.SessionID, *req.Description)
	if err != nil {
		s.Log.Warn("sfu renegotiate", "err", err)
		sfuUnavailable(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"description": d})
}

type sfuRestartReq struct {
	SessionID string                       `json:"session_id"`
	Offer     *realtime.SessionDescription `json:"offer"`
	// Mid and Kind name one track the session already publishes. Cloudflare
	// takes an ICE restart's offer only along with a track, and the same
	// track announced again changes nothing for the people pulling it.
	Mid  string `json:"mid"`
	Kind string `json:"kind"`
}

// handleSFURestart answers an ICE restart: a device whose network changed
// keeps its session, its tracks and everyone's pulls, and only finds a new
// path to the SFU.
func (s *Server) handleSFURestart(w http.ResponseWriter, r *http.Request) {
	_, st, ok := s.sfuCaller(w, r)
	if !ok {
		return
	}
	var req sfuRestartReq
	if err := ReadJSON(w, r, &req, maxSDPBytes+4<<10); err != nil {
		WriteError(w, err)
		return
	}
	if req.SessionID == "" || req.SessionID != st.SFUSession {
		WriteError(w, E(http.StatusConflict, "sfu_session_gone", "That call session has ended. Start it again."))
		return
	}
	if !validDescription(req.Offer, "offer") || req.Mid == "" || len(req.Mid) > 16 || !realtime.ValidKind(req.Kind) {
		WriteError(w, E(http.StatusBadRequest, "bad_request", "A restart needs an offer and a published track's mid and kind."))
		return
	}
	name := st.Tracks.Get(req.Kind)
	if name == "" || req.Mid != st.TrackMids.Get(req.Kind) {
		WriteError(w, E(http.StatusConflict, "not_published", "That track is not published."))
		return
	}
	media, validMedia := sfuMediaTypes(req.Offer.SDP)
	if !validMedia || !sfuMediaMatches(media, req.Mid, req.Kind) {
		WriteError(w, E(http.StatusBadRequest, "bad_request", "The offer must match the published media type."))
		return
	}
	if (req.Kind == realtime.KindAudio || req.Kind == realtime.KindScreenAudio) && (st.ServerMute || st.ServerDeaf || st.Suppress) {
		WriteError(w, ErrForbidden)
		return
	}
	if req.Kind != realtime.KindAudio && !s.mayStream(r.Context(), st) {
		WriteError(w, E(http.StatusForbidden, "missing_permission", "You cannot share video in this channel."))
		return
	}
	res, err := s.Realtime.AddTracks(r.Context(), req.SessionID, req.Offer, []realtime.Track{{Location: "local", Mid: req.Mid, TrackName: name}})
	if err != nil {
		s.Log.Warn("sfu restart", "err", err)
		sfuUnavailable(w, err)
		return
	}
	if fresh, err := s.sfuPolicyLocked(r.Context(), st); err != nil || fresh.SFUSession != req.SessionID {
		s.closeSFUSession(req.SessionID)
		WriteError(w, E(http.StatusConflict, "sfu_session_gone", "That call session has ended. Start it again."))
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"answer": res.Description})
}

type sfuCloseReq struct {
	SessionID string                       `json:"session_id"`
	Mids      []string                     `json:"mids"`
	Offer     *realtime.SessionDescription `json:"offer"`
}

// handleSFUClose stops pulled tracks by mid, with the caller's offer.
func (s *Server) handleSFUClose(w http.ResponseWriter, r *http.Request) {
	_, st, ok := s.sfuCaller(w, r)
	if !ok {
		return
	}
	var req sfuCloseReq
	if err := ReadJSON(w, r, &req, maxSDPBytes+8<<10); err != nil {
		WriteError(w, err)
		return
	}
	if req.SessionID == "" || req.SessionID != st.SFUSession {
		WriteError(w, E(http.StatusConflict, "sfu_session_gone", "That call session has ended. Start it again."))
		return
	}
	if len(req.Mids) == 0 || len(req.Mids) > 64 || (req.Offer != nil && !validDescription(req.Offer, "offer")) {
		WriteError(w, E(http.StatusBadRequest, "bad_request", "Closing needs one to 64 mids and, optionally, an offer."))
		return
	}
	res, err := s.Realtime.CloseTracks(r.Context(), req.SessionID, req.Mids, req.Offer, req.Offer == nil)
	if err != nil {
		s.Log.Warn("sfu close", "err", err)
		sfuUnavailable(w, err)
		return
	}
	failed := map[string]bool{}
	for _, track := range res.Tracks {
		if track.ErrorCode != "" {
			failed[track.Mid] = true
		}
	}
	for _, mid := range req.Mids {
		if !failed[mid] {
			s.Meter.Stop(req.SessionID, mid)
		}
	}
	changed := false
	latest, _ := s.Voice.Update(st.UserID, func(current *voice.State) {
		if current.SFUSession != req.SessionID {
			return
		}
		for _, kind := range []string{realtime.KindAudio, realtime.KindVideo, realtime.KindScreen, realtime.KindScreenAudio} {
			mid := current.TrackMids.Get(kind)
			for _, closed := range req.Mids {
				if mid != "" && mid == closed && !failed[mid] {
					current.Tracks = current.Tracks.With(kind, "")
					current.TrackMids = current.TrackMids.With(kind, "")
					changed = true
				}
			}
		}
	})
	if changed {
		for _, kind := range []string{realtime.KindAudio, realtime.KindVideo, realtime.KindScreen, realtime.KindScreenAudio} {
			if st.Tracks.Get(kind) != "" && latest.Tracks.Get(kind) == "" {
				s.Meter.StopSource(realtime.Source(req.SessionID, kind))
			}
		}
		if ch, err := s.Store.ChannelByID(r.Context(), st.ChannelID); err == nil {
			s.Bcast.EmitUsers(s.voiceAudience(r.Context(), ch), "voice_state", statePayload(latest))
		}
	}
	if len(failed) > 0 {
		WriteError(w, E(http.StatusBadGateway, "sfu_refused", "The call service could not close every track."))
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"answer": res.Description, "renegotiate": res.Renegotiate})
}

// sfuCloseAfter is when each attempt to retire a session starts, after the
// first. Each attempt has realtime.CloseTimeout to hear back.
var sfuCloseAfter = []time.Duration{250 * time.Millisecond, time.Second, 5 * time.Second, 30 * time.Second, 2 * time.Minute, 5 * time.Minute}

// closeSFUSession force-closes a retired session in the background, and the
// meter charges it until that is confirmed (or Cloudflare says the session is
// gone or its connection has dropped, which leaves nothing flowing). Should
// every attempt fail, about eight minutes later the meter stops charging it
// anyway: a device that left closes its own connection, a session can never
// again be charged until the process restarts, and Cloudflare's measurement,
// when the meter has one, holds whatever did flow. Exhausted retries are
// logged as errors, since moderation cannot rely on the cut-off meanwhile.
func (s *Server) closeSFUSession(session string) {
	if session == "" || s.Realtime == nil {
		return
	}
	if _, loaded := s.sfuClosing.LoadOrStore(session, true); loaded {
		return
	}
	go func() {
		defer s.sfuClosing.Delete(session)
		var last error
		for attempt := 0; attempt <= len(sfuCloseAfter); attempt++ {
			if attempt > 0 {
				time.Sleep(sfuCloseAfter[attempt-1])
			}
			ctx, cancel := context.WithTimeout(context.Background(), realtime.CloseTimeout)
			last = s.Realtime.CloseSession(ctx, session)
			cancel()
			if last == nil {
				break
			}
		}
		if last != nil {
			s.Log.Error("sfu session could not be retired after retries", "session", session, "err", last)
		}
		if s.Meter != nil {
			s.Meter.StopSession(session)
		}
	}()
}

// shareMaxBps is the most a share from someone in a call can send: their
// plan's limit (a guest has none), which the meter estimates their share at.
func (s *Server) shareMaxBps(ctx context.Context, st voice.State) int64 {
	plan := store.PlanFree
	if !st.Guest {
		if u, err := s.Store.UserByID(ctx, st.UserID); err == nil {
			plan = u.Plan
		}
	}
	return s.planLimitsFor(plan).ShareMaxBps
}

// sfuSyncLive follows actual publication state, never self-reported camera or
// screen flags. An untrusted endpoint may send media while claiming it is off.
func (s *Server) sfuSyncLive(st voice.State) {
	if s.Meter == nil || st.SFUSession == "" {
		return
	}
	s.Meter.SetSourceLive(realtime.Source(st.SFUSession, realtime.KindVideo), st.Tracks.Video != "")
	s.Meter.SetSourceLive(realtime.Source(st.SFUSession, realtime.KindScreen), st.Tracks.Screen != "")
	s.Meter.SetSourceLive(realtime.Source(st.SFUSession, realtime.KindScreenAudio), st.Tracks.ScreenAudio != "")
}

func randomHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}
