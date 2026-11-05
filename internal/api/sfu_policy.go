package api

import (
	"context"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/perms"
	"evilion.xyz/chat/internal/voice"
)

// sfuPolicyLocked runs with the user's sfuLocks stripe held. Restriction
// changes retire the entire provider session: hiding a track in discovery
// alone does not stop an already-negotiated subscription from receiving it.
func (s *Server) sfuPolicyLocked(ctx context.Context, previous voice.State) (voice.State, error) {
	st, ok := s.Voice.Get(previous.UserID)
	if !ok || st.ChannelID != previous.ChannelID || st.ConnID != previous.ConnID || st.SessionID != previous.SessionID {
		return voice.State{}, ErrForbidden
	}
	ch, err := s.Store.ChannelByID(ctx, st.ChannelID)
	if err != nil {
		return voice.State{}, err
	}
	p := perms.All
	if st.Guest {
		p = s.guestVoicePerms(ctx, ch)
	} else if ch.ServerID != nil {
		p, err = s.Store.PermsIn(ctx, ch, st.UserID)
		if err != nil {
			return voice.State{}, err
		}
	}
	if !p.Has(perms.ViewChannel | perms.Connect) {
		if left, ok := s.Voice.LeaveChannel(st.UserID, st.ChannelID); ok {
			s.broadcastVoiceLeave(ctx, left)
		}
		return voice.State{}, ErrForbidden
	}
	oldSession := ""
	changed := false
	next, ok := s.Voice.Update(st.UserID, func(current *voice.State) {
		if current.ChannelID != st.ChannelID || current.ConnID != st.ConnID || current.SessionID != st.SessionID {
			return
		}
		suppress := !p.Has(perms.Speak)
		policy := uint8(0)
		if current.ServerMute || current.ServerDeaf || suppress {
			policy |= 1
		}
		if current.ServerDeaf {
			policy |= 2
		}
		if !p.Has(perms.Stream) {
			policy |= 4
		}
		monoOnly := !p.Has(perms.UseStereoVoice)
		changed = current.Suppress != suppress || current.MonoOnly != monoOnly || current.SFUPolicy != policy
		current.Suppress = suppress
		current.MonoOnly = monoOnly
		if !p.Has(perms.Stream) {
			current.Video, current.Streaming = false, false
		}
		if current.SFUPolicy != policy {
			current.SFUPolicy = policy
			oldSession = current.SFUSession
			if oldSession != "" {
				current.SFUSession, current.Tracks, current.TrackMids, current.ScreenBps = "", voice.Tracks{}, voice.Tracks{}, 0
				current.SFUGeneration++
			}
		}
	})
	if !ok || next.ChannelID != st.ChannelID || next.ConnID != st.ConnID {
		return voice.State{}, ErrForbidden
	}
	if oldSession != "" {
		s.closeSFUSession(oldSession)
	}
	if changed {
		s.Bcast.EmitUsers(s.voiceAudience(ctx, ch), "voice_state", statePayload(next))
	}
	return next, nil
}

// retireSFUStateLocked discards a negotiation that raced with a remote track's
// authorization or identity change. The caller holds this user's stripe.
func (s *Server) retireSFUStateLocked(ctx context.Context, st voice.State) {
	retired := false
	next, _ := s.Voice.Update(st.UserID, func(current *voice.State) {
		if current.SFUSession != st.SFUSession || st.SFUSession == "" {
			return
		}
		current.SFUSession, current.Tracks, current.TrackMids, current.ScreenBps = "", voice.Tracks{}, voice.Tracks{}, 0
		current.SFUGeneration++
		retired = true
	})
	s.closeSFUSession(st.SFUSession)
	if retired {
		if ch, err := s.Store.ChannelByID(ctx, st.ChannelID); err == nil {
			s.Bcast.EmitUsers(s.voiceAudience(ctx, ch), "voice_state", statePayload(next))
		}
	}
}

func (s *Server) recheckSFUState(ctx context.Context, st voice.State) (voice.State, bool) {
	if s.Voice == nil {
		return st, true
	}
	lock := &s.sfuLocks[uint64(st.UserID)%uint64(len(s.sfuLocks))]
	lock.Lock()
	defer lock.Unlock()
	fresh, err := s.sfuPolicyLocked(ctx, st)
	if err != nil {
		return voice.State{}, false
	}
	return fresh, true
}

func (s *Server) recheckSFUServer(ctx context.Context, serverID ids.ID) {
	if s.Voice == nil {
		return
	}
	channels, err := s.Store.ServerChannels(ctx, serverID)
	if err != nil {
		return
	}
	channelIDs := make([]ids.ID, 0, len(channels))
	for _, ch := range channels {
		channelIDs = append(channelIDs, ch.ID)
	}
	for _, st := range s.Voice.In(channelIDs) {
		s.recheckSFUState(ctx, st)
	}
}
