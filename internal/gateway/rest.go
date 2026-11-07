package gateway

import (
	"context"
	"slices"
	"time"

	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/store"
)

// A phone's radio stays at full power for several seconds after every packet,
// so what wears a battery down is how often anything moves, not how much. The
// rest op lets a device say how much of the connection it wants: browsers never
// send it, and an app that does not know it gets everything as before.
const (
	// RestNone is the whole connection (and a call, whatever else is going on).
	RestNone int32 = 0
	// RestSlowPing is everything, with pings far enough apart for the radio to
	// sleep between them: an app on screen.
	RestSlowPing int32 = 1
	// RestDeep is slow pings, and only what someone would want to be woken for
	// straight away: an app out of sight.
	RestDeep int32 = 2

	// restPingEvery stays under the 100 seconds Cloudflare lets a socket sit idle.
	restPingEvery = 75 * time.Second
	// restPingTimeout allows for a radio that has to wake, on a poor signal.
	restPingTimeout = 30 * time.Second
	// restBeatTimeout is a few missed pings, since an answered ping counts as a beat.
	restBeatTimeout = 3 * time.Minute
	// holdWindow is how often events that can wait may wake a resting device: the
	// first goes at once, so a quiet conversation loses nothing, and the rest of
	// a busy one go together at most this often.
	holdWindow = 30 * time.Second
	// maxHeld keeps a very busy community from filling memory, or the send
	// buffer all at once when the window ends.
	maxHeld = 128
)

type restData struct {
	Level int32 `json:"level"`
}

// presenceSync is what EvPresenceSync carries.
type presenceSync struct {
	Presences []Presence `json:"presences"`
}

// canWait lists what a device out of sight can hear of a little later. Anything
// not named here goes at once, so a new kind of event is never held by mistake.
var canWait = map[string]bool{
	EvMessageCreate: true, EvMessageUpdate: true, EvMessageDelete: true, EvMessageDeleteBulk: true,
	EvReactionAdd: true, EvReactionRemove: true, EvReactionRemoveAll: true, EvReactionRemoveEmoji: true,
	EvPinUpdate: true, EvPollVoteAdd: true, EvPollVoteRemove: true, EvReadState: true,
	EvChannelCreate: true, EvChannelUpdate: true, EvChannelDelete: true, EvChannelPositions: true,
	EvThreadCreate: true, EvThreadDelete: true, EvThreadMembersUpdate: true,
	EvServerUpdate: true, EvMemberAdd: true, EvMemberUpdate: true, EvMemberRemove: true,
	EvRoleCreate: true, EvRoleUpdate: true, EvRoleDelete: true,
	EvEmojiUpdate: true, EvStickerUpdate: true, EvStickerRecent: true, EvStickerRecentClear: true,
	EvUserUpdate: true, EvVoiceState: true, EvSettingsUpdate: true,
	EvNoteUpdate: true, EvNoteCRDT: true, EvAuditLogEntry: true, EvAutomodEvent: true, EvAutomodRulesUpdate: true,
	EvInviteCreate: true, EvInviteDelete: true, EvWebhooksUpdate: true, EvFollowsUpdate: true,
	EvAppCommandsUpdate: true, EvIntegrationsUpdate: true,
}

// outOfDate lists what is only worth anything live; it is not sent at all.
// Typing and a note's cursors run out by themselves; presence comes back as one
// EvPresenceSync.
func outOfDate(t string) bool {
	return t == EvTyping || t == EvPresence || t == EvNoteCursor || t == EvNotePresence
}

// mentions reports whether a new message speaks to this account. Roles are not
// looked up per message: a message naming any role goes at once.
func mentions(f ServerFrame, uid ids.ID) bool {
	m, ok := f.D.(*store.Message)
	if !ok {
		return true
	}
	return m.MentionEveryone || len(m.MentionRoles) > 0 || slices.Contains(m.Mentions, uid)
}

// heldBack takes a frame a resting device should not get now, and says whether
// it did. What goes on is sent after anything already waiting, in order.
func (c *conn) heldBack(f ServerFrame) bool {
	if c.rest.Load() != RestDeep {
		return false
	}
	if outOfDate(f.T) {
		if f.T == EvPresence {
			c.missedPresence.Store(true)
		}
		return true
	}
	urgent := !canWait[f.T] || (f.T == EvMessageCreate && (f.direct || mentions(f, c.userID)))
	if urgent {
		c.flushHeld(true)
		return false
	}
	c.heldMu.Lock()
	if len(c.held) == 0 && time.Since(c.lastSent) >= holdWindow {
		c.lastSent = time.Now()
		c.heldMu.Unlock()
		return false
	}
	c.held = append(c.held, f)
	full := len(c.held) >= maxHeld
	c.heldMu.Unlock()
	if full {
		c.flushHeld(true)
	}
	return true
}

// flushHeld sends what waited: now, or once its window has passed.
func (c *conn) flushHeld(now bool) {
	c.heldMu.Lock()
	defer c.heldMu.Unlock()
	if len(c.held) == 0 || (!now && time.Since(c.lastSent) < holdWindow) {
		return
	}
	for _, f := range c.held {
		c.push(f)
	}
	c.held = nil
	c.lastSent = time.Now()
}

// setRest changes how much of the connection this device wants.
func (c *conn) setRest(ctx context.Context, level int32) {
	if level < RestNone || level > RestDeep {
		return
	}
	c.rest.Store(level)
	if level == RestDeep {
		return
	}
	c.flushHeld(true)
	if c.missedPresence.Swap(false) {
		c.enqueue(ServerFrame{T: EvPresenceSync, S: c.hub.nextSeq(), D: presenceSync{c.hub.presencesFor(ctx, c)}})
	}
}

// presencesFor is who this account sees as here right now, as ready says it.
func (h *Hub) presencesFor(ctx context.Context, c *conn) []Presence {
	out := []Presence{}
	u := c.currentUser()
	if u == nil {
		return out
	}
	// the rule works both ways: whoever would be told of uid is whom uid sees
	users, err := h.st.UsersByIDs(ctx, h.OnlineAmong(h.presenceAudience(ctx, u.ID)))
	if err == nil {
		for id, other := range users {
			if id == u.ID {
				continue
			}
			if pr := h.PresenceOf(ctx, other); pr.Status != "offline" {
				out = append(out, pr)
			}
		}
	}
	return append(out, h.selfPresence(ctx, u))
}
