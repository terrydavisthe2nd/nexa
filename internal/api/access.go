package api

import (
	"net/http"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/store"
)

// Access needs, passed to ChannelAccess for server channels.
const (
	NeedView    = "view"
	NeedSend    = "send"
	NeedManage  = "manage"
	NeedHistory = "history"
	NeedReact   = "react"
	NeedAttach  = "attach"
	NeedPin     = "pin"
	// NeedWebhooks is Manage Webhooks in the channel.
	NeedWebhooks = "webhooks"
	// NeedPolls is Create Polls in the channel.
	NeedPolls = "polls"
	// NeedExternalEmoji is Use External Emojis in the channel.
	NeedExternalEmoji = "external_emoji"
	// NeedEmbed is Embed Links in the channel.
	NeedEmbed = "embed"
)

// channel loads the channel from the path and checks the caller may act in it.
// It writes the error response itself and returns nil on failure.
func (s *Server) channel(w http.ResponseWriter, r *http.Request, need string) (*store.Channel, *store.User) {
	u := auth.UserFrom(r.Context())
	id, err := ids.Parse(r.PathValue("id"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil, nil
	}
	ch, err := s.Store.ChannelByID(r.Context(), id)
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil, nil
	}
	// A category only holds channels and a link channel only opens an
	// address. Anything posted to one would reach its viewers where no client
	// can show it, or ever mark it read.
	if store.HoldsNoMessages(ch.Type) && need != NeedView && need != NeedManage {
		WriteError(w, E(http.StatusBadRequest, "not_text", "This channel has no messages of its own."))
		return nil, nil
	}
	ok, err := s.canAccess(r, u, ch, need)
	if err != nil {
		WriteError(w, err)
		return nil, nil
	}
	if !ok {
		if need != NeedView {
			// a channel the caller can see but lacks a specific permission in is a 403
			if canView, _ := s.canAccess(r, u, ch, NeedView); canView {
				WriteError(w, E(http.StatusForbidden, "missing_permission", "You do not have permission to do that here."))
				return nil, nil
			}
		}
		WriteError(w, ErrNotFound) // hide existence
		return nil, nil
	}
	if !s.unlocked(r, ch) {
		WriteError(w, E(http.StatusForbidden, "locked", "This subchat asks for its password first."))
		return nil, nil
	}
	return ch, u
}

// channelIgnoringLock is `channel` for the two routes about the lock itself:
// asking how the password is stretched, and typing it. Everything else goes
// through `channel`, which refuses until the password has been typed.
func (s *Server) channelIgnoringLock(w http.ResponseWriter, r *http.Request) (*store.Channel, *store.User) {
	u := auth.UserFrom(r.Context())
	id, err := ids.Parse(r.PathValue("id"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil, nil
	}
	ch, err := s.Store.ChannelByID(r.Context(), id)
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil, nil
	}
	ok, err := s.canAccess(r, u, ch, NeedView)
	if err != nil || !ok {
		WriteError(w, ErrNotFound)
		return nil, nil
	}
	return ch, u
}

// unlocked reports whether this request may open the channel: either it has no
// password, or this session has already typed it. A password keeps a subchat
// shut on the screen; the messages in it are encrypted for the same people
// either way, which is what the client is told.
func (s *Server) unlocked(r *http.Request, ch *store.Channel) bool {
	if !ch.Locked {
		return true
	}
	sess := auth.SessionFrom(r.Context())
	if sess == nil {
		return false
	}
	ok, err := s.Store.IsUnlocked(r.Context(), ch.ID, sess.ID)
	return err == nil && ok
}

// canAccess resolves access for any channel type.
func (s *Server) canAccess(r *http.Request, u *store.User, ch *store.Channel, need string) (bool, error) {
	if store.IsDMLike(ch.Type) {
		for _, id := range ch.Recipients {
			if id == u.ID {
				return true, nil
			}
		}
		return false, nil
	}
	if s.ChannelAccess != nil {
		return s.ChannelAccess(r.Context(), u, ch, need)
	}
	return false, nil
}

// managesGroup says whether someone may do what a group's owner does: the
// owner alone, whatever their rank on Nexa.
func managesGroup(ch *store.Channel, me *store.User) bool {
	return ownsGroup(ch, me)
}

func ownsGroup(ch *store.Channel, me *store.User) bool {
	return ch.OwnerID != nil && *ch.OwnerID == me.ID
}

// isDM covers every conversation that is not part of a server, subchats
// included: they share the same membership rules.
func (s *Server) isDM(ch *store.Channel) bool { return store.IsDMLike(ch.Type) }

// isCallable excludes subchats, which ride on their parent's call.
func (s *Server) isCallable(ch *store.Channel) bool { return ch.Type == "dm" || ch.Type == "group_dm" }
