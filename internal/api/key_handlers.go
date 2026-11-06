package api

import (
	"context"
	"errors"
	"net/http"
	"slices"
	"time"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/perms"
	"evilion.xyz/chat/internal/store"
)

const (
	maxGrantBytes  = 512
	maxGrantsBatch = 200
	// rotationReason is what clients send when answering key_rotation_needed.
	rotationReason = "membership change"
	// manualRotationReason is what the Encryption page's Rotate button sends.
	manualRotationReason = "manual"
	// keyCommitmentBytes is the size of an epoch's fingerprint of its key.
	keyCommitmentBytes = 32
	// lostKeyStrike is how long someone whose key was lost cannot answer another
	// rotation in that conversation, so a key handed out to nobody costs an
	// account one try. Anyone else's app answers meanwhile.
	lostKeyStrike = time.Hour
)

// oldAppAnswerWait is how long a rotation stays with apps that make a fingerprint
// before one from an app too old to is taken. Current apps answer in about a
// second, so without one a key is only taken when no current app is around.
// Remove it, and refuse those keys outright, once old apps are gone.
var oldAppAnswerWait = 15 * time.Second

// keyDeliveryGrace is how long a new key has to reach conv's people before it
// counts as lost. Apps upload their first grants within about a second for 350
// people, and guessing too low costs only one more rotation.
func keyDeliveryGrace(people int) time.Duration {
	return 10*time.Second + time.Duration(people)*20*time.Millisecond
}

// errKeyInPlace answers a request for a new key when the conversation has one
// and nothing asks for another. It is a 409 because that is what apps already
// take as "someone else saw to it": they look for their grants and wait.
var errKeyInPlace = E(http.StatusConflict, "key_in_place", "This conversation already has a key, and nothing needs it replaced.")

// errEpochBusy answers someone who has used up their turns at replacing this key.
var errEpochBusy = E(http.StatusConflict, "epoch_busy", "Someone else needs to replace this key. Try again in a moment.")

func (s *Server) keyRoutes(mux *http.ServeMux) {
	mux.Handle("GET /api/v1/keys/grants", s.authed(s.handleKeyGrants))
	mux.Handle("GET /api/v1/keys/needs", s.authed(s.handleKeyNeeds))
	mux.Handle("POST /api/v1/keys/grants", s.authed(s.handleKeyGrantsPut))
	mux.Handle("POST /api/v1/keys/epochs", s.authed(s.handleKeyEpochCreate))
	mux.Handle("GET /api/v1/keys/epochs", s.authed(s.handleKeyEpochs))
	mux.Handle("POST /api/v1/keys/request", s.authed(s.handleKeyRequest))
}

// handleKeyRequest is a device still waiting for a conversation's keys asking
// for them again: the online members holding them are told who lacks them,
// as they were when that person came in. The first ask can be missed (nobody
// holding the key was online, or their app had not heard of the newcomer
// yet), and nothing else asked again until a holder next connected.
func (s *Server) handleKeyRequest(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	var req struct {
		ConvID        ids.ID `json:"conv_id"`
		BadEpoch      *int   `json:"bad_epoch"`
		BadWrappedKey []byte `json:"bad_wrapped_key"`
	}
	if err := ReadJSON(w, r, &req, 8<<10); err != nil {
		WriteError(w, err)
		return
	}
	set, err := s.entitledSet(r, req.ConvID)
	if err != nil || !set[me.ID] {
		WriteError(w, ErrNotFound)
		return
	}
	if ok, retry := s.limKeyAsk.Allow("keyask:" + me.ID.String() + ":" + req.ConvID.String()); !ok {
		w.Header().Set("Retry-After", retryAfter(retry))
		WriteError(w, ErrRateLimited)
		return
	}
	if req.BadEpoch != nil {
		if *req.BadEpoch < 0 || len(req.BadWrappedKey) == 0 || len(req.BadWrappedKey) > 4096 {
			WriteError(w, ErrBadJSON)
			return
		}
		// Counted for only a few a person an hour: enough to catch a key people
		// cannot open, too few to make a good one keep being replaced on purpose.
		// One person is enough only among a few, where they may be all that is left.
		need := 2
		if len(set) <= 3 {
			need = 1
		}
		if ok, _ := s.limKeyBad.Allow("keyreject:" + me.ID.String() + ":" + req.ConvID.String()); !ok {
			need = 0
		}
		lost, err := s.Store.RejectGrant(r.Context(), req.ConvID, *req.BadEpoch, me.ID, req.BadWrappedKey, need)
		if err != nil {
			WriteError(w, err)
			return
		}
		if lost {
			s.askToReplaceKey(req.ConvID, set)
		}
	}
	// Someone waiting asks here now and then, so a key that was never handed out
	// gets replaced even if nobody tries to send.
	if s.noticeLostKey(r.Context(), me, req.ConvID, set) {
		s.askToReplaceKey(req.ConvID, set)
	}
	s.Bcast.KeysMaybeNeeded(req.ConvID)
	w.WriteHeader(http.StatusNoContent)
}

// askToReplaceKey asks the online people of conv to make it a new key.
func (s *Server) askToReplaceKey(conv ids.ID, people map[ids.ID]bool) {
	targets := make([]ids.ID, 0, len(people))
	for id := range people {
		targets = append(targets, id)
	}
	s.Bcast.EmitUsers(targets, "key_rotation_needed", map[string]any{"conv_id": conv})
}

func (s *Server) handleKeyGrants(w http.ResponseWriter, r *http.Request) {
	list, err := s.Store.GrantsForUser(r.Context(), auth.UserFrom(r.Context()).ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, list)
}

func (s *Server) handleKeyNeeds(w http.ResponseWriter, r *http.Request) {
	needs, err := s.Store.Needs(r.Context(), auth.UserFrom(r.Context()).ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, needs)
}

func (s *Server) entitledSet(r *http.Request, conv ids.ID) (map[ids.ID]bool, error) {
	list, err := s.Store.Entitled(r.Context(), conv)
	if err != nil {
		return nil, err
	}
	set := make(map[ids.ID]bool, len(list))
	for _, id := range list {
		set[id] = true
	}
	return set, nil
}

func (s *Server) handleKeyGrantsPut(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	var req []struct {
		ConvID     ids.ID `json:"conv_id"`
		Epoch      int    `json:"epoch"`
		UserID     ids.ID `json:"user_id"`
		WrappedKey []byte `json:"wrapped_key"`
	}
	if err := ReadJSON(w, r, &req, 512<<10); err != nil {
		WriteError(w, err)
		return
	}
	if len(req) == 0 || len(req) > maxGrantsBatch {
		WriteError(w, E(http.StatusBadRequest, "bad_batch", "Send 1–200 grants."))
		return
	}
	ctx := r.Context()
	entitledCache := map[ids.ID]map[ids.ID]bool{}
	holdCache := map[[2]int64]bool{}
	var grants []store.KeyGrant
	for _, g := range req {
		if len(g.WrappedKey) == 0 || len(g.WrappedKey) > maxGrantBytes || g.Epoch < 0 || g.ConvID.IsZero() || g.UserID.IsZero() {
			WriteError(w, E(http.StatusBadRequest, "bad_grant", "A grant is malformed."))
			return
		}
		set, ok := entitledCache[g.ConvID]
		if !ok {
			var err error
			if set, err = s.entitledSet(r, g.ConvID); err != nil {
				WriteError(w, err)
				return
			}
			entitledCache[g.ConvID] = set
		}
		// someone who left or was removed keeps their old grants but has no
		// business handing keys to the people still in the conversation
		if !set[me.ID] {
			WriteError(w, E(http.StatusForbidden, "not_entitled", "You are not part of that conversation."))
			return
		}
		key := [2]int64{int64(g.ConvID), int64(g.Epoch)}
		var holds bool
		if g.UserID == me.ID {
			// A self-grant only affects the caller (e.g. a key received through
			// an invite link). It says nothing about whether the caller really
			// holds the key, so it does not make them a holder for the rest of
			// the batch.
			if _, err := s.Store.Epoch(ctx, g.ConvID, g.Epoch); err != nil {
				WriteError(w, ErrNotFound)
				return
			}
			holds = true
		} else if cached, seen := holdCache[key]; seen {
			holds = cached
		} else {
			// A holder is someone another member granted the key to, or the
			// epoch's creator bootstrapping the first grants. A grant the caller
			// wrapped for themselves does not count: anyone can plant one, and
			// junk shared from it would block the real key, since the first
			// grant stored wins.
			if e, err := s.Store.Epoch(ctx, g.ConvID, g.Epoch); err == nil && e.CreatedBy == me.ID {
				holds = true
			} else if own, err := s.Store.Grant(ctx, g.ConvID, g.Epoch, me.ID); err == nil && own.WrappedBy != me.ID {
				holds = true
			}
			holdCache[key] = holds
		}
		if !holds {
			WriteError(w, E(http.StatusForbidden, "not_holder", "You do not hold that key, so you cannot share it."))
			return
		}
		if !set[g.UserID] {
			WriteError(w, E(http.StatusForbidden, "not_entitled", "That user is not part of the conversation."))
			return
		}
		grants = append(grants, store.KeyGrant{ConvID: g.ConvID, Epoch: g.Epoch, UserID: g.UserID, WrappedKey: g.WrappedKey, WrappedBy: me.ID})
	}
	inserted, err := s.Store.PutGrants(ctx, grants)
	if err != nil {
		WriteError(w, err)
		return
	}
	for _, g := range grants {
		if stored, err := s.Store.Grant(ctx, g.ConvID, g.Epoch, g.UserID); err == nil && stored.WrappedBy == me.ID {
			s.Bcast.EmitUsers([]ids.ID{g.UserID}, "key_grant", stored)
		}
	}
	WriteJSON(w, http.StatusOK, map[string]any{"inserted": inserted})
}

// epochPlan is how a new epoch may be written, once allowed.
type epochPlan struct {
	create func(context.Context, *store.KeyEpoch) error
	reason string
	// free is for those who run the conversation: no limit on how often, which a
	// moderator clearing out a raid with nobody else online needs.
	free bool
}

// epochCreator decides whether me may start a new key for conv now. The newest
// epoch is the only one messages can use from the moment it exists, before anyone
// holds its key, so a new one stops a whole conversation until its key arrives.
// It therefore takes a reason the server itself knows about, never what the
// caller says:
//
//   - a pending rotation: any member, whoever answers first, since it has to be
//     possible with only ordinary members online;
//   - the first key of a conversation that needs one;
//   - someone who runs the server replacing it by hand.
func (s *Server) epochCreator(ctx context.Context, me *store.User, conv ids.ID, reason string, fingerprinted, alone bool) (*epochPlan, error) {
	flags, err := s.Store.KeyFlags(ctx, conv)
	if err != nil {
		return nil, err
	}
	_, exists, err := s.Store.LatestEpoch(ctx, conv)
	if err != nil {
		return nil, err
	}
	ch, err := s.Store.ChannelForAccess(ctx, conv)
	if errors.Is(err, store.ErrNotFound) {
		ch = nil // a server's own conversation
	} else if err != nil {
		return nil, err
	}
	runs := alone
	if !runs {
		switch {
		case ch != nil && ch.ServerID == nil:
			runs = managesGroup(ch, me)
		default:
			serverID := conv
			if ch != nil {
				serverID = *ch.ServerID
			}
			p, err := s.Store.ServerPerms(ctx, serverID, me.ID)
			if err != nil {
				return nil, err
			}
			runs = p.Has(perms.ManageServer)
		}
	}
	switch {
	case flags.NeedsRotation:
		if !fingerprinted && exists && time.Since(time.UnixMilli(flags.UpdatedAt)) < oldAppAnswerWait {
			return nil, E(http.StatusConflict, "commitment_needed", "A new key needs its fingerprint.")
		}
		if !runs {
			struck, err := s.Store.MadeLostEpochSince(ctx, conv, me.ID, time.Now().Add(-lostKeyStrike).UnixMilli())
			if err != nil {
				return nil, err
			}
			if struck {
				return nil, errEpochBusy
			}
		}
		return &epochPlan{s.Store.CreatePendingEpoch, rotationReason, runs}, nil
	case !exists:
		// A public channel shares its server's key, and one of its own would move
		// it onto a key nobody holds. A private channel is flagged when it turns
		// private, and a private thread always has its own.
		if ch != nil && ch.ServerID != nil && !flags.NeedsOwnKey && ch.Type != "private_thread" {
			return nil, errKeyInPlace
		}
		return &epochPlan{s.Store.CreateEpoch, "", runs}, nil
	case reason != manualRotationReason:
		return nil, errKeyInPlace
	case !runs || (ch != nil && ch.ServerID == nil):
		return nil, ErrForbidden
	}
	return &epochPlan{s.Store.CreateEpoch, manualRotationReason, true}, nil
}

func (s *Server) handleKeyEpochCreate(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	var req struct {
		ConvID     ids.ID `json:"conv_id"`
		Reason     string `json:"reason"`
		Commitment []byte `json:"commitment"`
	}
	if err := ReadJSON(w, r, &req, 4<<10); err != nil {
		WriteError(w, err)
		return
	}
	if len(req.Commitment) != 0 && len(req.Commitment) != keyCommitmentBytes {
		WriteError(w, E(http.StatusBadRequest, "bad_commitment", "That is not a key's fingerprint."))
		return
	}
	ctx := r.Context()
	entitled, err := s.Store.Entitled(ctx, req.ConvID)
	if err != nil || !slices.Contains(entitled, me.ID) {
		WriteError(w, ErrNotFound)
		return
	}
	reason, _ := CleanText(req.Reason, 64)
	plan, err := s.epochCreator(ctx, me, req.ConvID, reason, len(req.Commitment) > 0, len(entitled) == 1)
	if err != nil {
		WriteError(w, err)
		return
	}
	// Only now, so a refused request spends nothing.
	if !plan.free {
		if ok, _ := s.limRotator.Allow("epoch:" + req.ConvID.String() + ":" + me.ID.String()); !ok {
			WriteError(w, errEpochBusy)
			return
		}
	}
	latest, exists, err := s.Store.LatestEpoch(ctx, req.ConvID)
	if err != nil {
		WriteError(w, err)
		return
	}
	next := 0
	if exists {
		next = latest + 1
	}
	epoch := &store.KeyEpoch{ConvID: req.ConvID, Epoch: next, CreatedBy: me.ID, Reason: plan.reason, Commitment: req.Commitment}
	// One go: losing the race means someone else has just done what was asked,
	// and trying again would replace their key too.
	if err := plan.create(ctx, epoch); errors.Is(err, store.ErrConflict) {
		WriteError(w, E(http.StatusConflict, "epoch_race", "Someone else just rotated this key; reload."))
		return
	} else if err != nil {
		WriteError(w, err)
		return
	}
	if epoch.Epoch == 0 {
		// a server channel that just got its own first epoch now encrypts under
		// itself; viewers must learn the new key_conv_id before they send
		if ch, err := s.Store.ChannelByID(ctx, epoch.ConvID); err == nil && ch.ServerID != nil {
			s.Bcast.EmitChannel(ch.ID, "channel_update", ch)
		}
	}
	WriteJSON(w, http.StatusCreated, map[string]any{"conv_id": epoch.ConvID, "epoch": epoch.Epoch, "user_ids": entitled})
}

// noticeLostKey checks conv's newest key from the side of someone stuck without
// it, and marks it lost if it was started and then not handed out: past the grace
// period most of its people lack it, so nobody can pass it on. Holding the key
// means nothing is wrong for that person, and the one who made it cannot call it
// lost, which would let an account replace its own key in a loop. It answers
// whether it just marked it.
func (s *Server) noticeLostKey(ctx context.Context, me *store.User, conv ids.ID, people map[ids.ID]bool) bool {
	latest, exists, err := s.Store.LatestEpoch(ctx, conv)
	if err != nil || !exists {
		return false
	}
	flags, err := s.Store.KeyFlags(ctx, conv)
	if err != nil || flags.NeedsRotation {
		return false
	}
	e, err := s.Store.Epoch(ctx, conv, latest)
	if err != nil || e.CreatedBy == me.ID || time.Since(time.UnixMilli(e.CreatedAt)) < keyDeliveryGrace(len(people)) {
		return false
	}
	if _, err := s.Store.Grant(ctx, conv, latest, me.ID); !errors.Is(err, store.ErrNotFound) {
		return false
	}
	holders, err := s.Store.SharersOf(ctx, conv, latest)
	if err != nil || len(holders)*2 >= len(people) {
		return false
	}
	lost, err := s.Store.MarkEpochLost(ctx, conv, latest)
	if err != nil {
		s.Log.Warn("marking a lost key", "conv", conv.String(), "err", err)
	}
	return lost
}

func (s *Server) handleKeyEpochs(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	conv, err := ids.Parse(r.URL.Query().Get("conv_id"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	entitled, err := s.Store.Entitled(r.Context(), conv)
	if err != nil || !slices.Contains(entitled, me.ID) {
		WriteError(w, ErrNotFound)
		return
	}
	epochs, err := s.Store.Epochs(r.Context(), conv)
	if err != nil {
		WriteError(w, err)
		return
	}
	flags, err := s.Store.KeyFlags(r.Context(), conv)
	if err != nil {
		WriteError(w, err)
		return
	}
	// Every send asks here first and replaces the key when told to, so a key that
	// never arrived holds a conversation up only until someone next tries to talk.
	people := make(map[ids.ID]bool, len(entitled))
	for _, id := range entitled {
		people[id] = true
	}
	if !flags.NeedsRotation && s.noticeLostKey(r.Context(), me, conv, people) {
		flags.NeedsRotation = true
	}
	WriteJSON(w, http.StatusOK, map[string]any{"epochs": epochs, "flags": flags, "entitled": entitled})
}
