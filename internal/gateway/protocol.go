// Package gateway is the realtime WebSocket side of chatd: connections,
// presence, typing, voice signalling and event fan-out.
package gateway

import (
	"encoding/json"
	"sync"

	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/store"
)

// ClientFrame is what clients send.
type ClientFrame struct {
	Op string          `json:"op"`
	D  json.RawMessage `json:"d"`
}

// ServerFrame is what the server sends.
type ServerFrame struct {
	T string `json:"t"`
	S uint64 `json:"s,omitempty"`
	D any    `json:"d,omitempty"`
	// shared, when set, is the frame's encoding, made by whichever of its
	// connections writes it first and reused by all the others: an event for
	// two thousand people is encoded once, not two thousand times.
	shared *frameBytes
	// direct marks a new message in a direct or group conversation, which a
	// resting device gets at once (rest.go).
	direct bool
}

type frameBytes struct {
	once sync.Once
	b    []byte
	err  error
}

// fanOut marks a frame as going to many connections, so its encoding is shared.
func (f ServerFrame) fanOut() ServerFrame {
	f.shared = &frameBytes{}
	return f
}

// encode is the frame as JSON, made once when the frame is shared.
func (f *ServerFrame) encode() ([]byte, error) {
	if f.shared == nil {
		return json.Marshal(f)
	}
	f.shared.once.Do(func() { f.shared.b, f.shared.err = json.Marshal(f) })
	return f.shared.b, f.shared.err
}

// Client ops.
const (
	OpIdentify    = "identify"
	OpHeartbeat   = "heartbeat"
	OpPresence    = "presence"
	OpTyping      = "typing"
	OpVoiceState  = "voice_state"
	OpVoiceSignal = "voice_signal"
	OpSubscribe   = "subscribe_members"
	// OpActivities replaces this connection's activities (from the desktop app's Rich Presence server).
	OpActivities = "activities"
	// OpAway says whether the person has left this connection alone for a
	// while. An account showing as online shows as idle while every one of its
	// connections says so; nothing about it is stored.
	OpAway = "away"
	// OpNoteCursor is where someone's caret and selection are in a note they
	// have open, sealed with the note's key, for the others in it to draw.
	// Passed on and forgotten: nothing about it is stored.
	OpNoteCursor = "note_cursor"
	// OpRest says how much of the connection this device wants right now, so a
	// phone's radio can sleep (rest.go).
	OpRest = "rest"
)

// Server events.
const (
	EvReady             = "ready"
	EvHeartbeatAck      = "heartbeat_ack"
	EvMessageCreate     = "message_create"
	EvMessageUpdate     = "message_update"
	EvMessageDelete     = "message_delete"
	EvMessageDeleteBulk = "message_delete_bulk"
	EvReactionAdd       = "reaction_add"
	EvReactionRemove    = "reaction_remove"
	EvTyping            = "typing"
	EvChannelCreate     = "channel_create"
	EvChannelUpdate     = "channel_update"
	EvChannelDelete     = "channel_delete"
	EvChannelPositions  = "channel_positions"
	EvServerCreate      = "server_create"
	EvServerUpdate      = "server_update"
	EvServerDelete      = "server_delete"
	EvMemberAdd         = "member_add"
	EvMemberUpdate      = "member_update"
	EvMemberRemove      = "member_remove"
	EvRoleCreate        = "role_create"
	EvRoleUpdate        = "role_update"
	EvRoleDelete        = "role_delete"
	EvPresence          = "presence"
	// EvPresenceSync replaces what a device knows of who is here, once it comes
	// back from a rest that left presence changes out (rest.go).
	EvPresenceSync = "presence_sync"
	EvUserUpdate        = "user_update"
	// EvBillingUpdate tells an account its subscription or boosts changed (api/billing.go); the app reads them again.
	EvBillingUpdate      = "billing_update"
	EvRelationshipAdd    = "relationship_add"
	EvRelationshipRemove = "relationship_remove"
	EvReadState          = "read_state"
	EvVoiceState         = "voice_state"
	EvVoiceSignal        = "voice_signal"
	EvCallRing           = "call_ring"
	EvCallEnd            = "call_end"
	EvKeyGrant           = "key_grant"
	EvKeysNeeded         = "keys_needed"
	EvEmojiUpdate        = "emoji_update"
	EvStickerUpdate      = "sticker_update"
	EvPinUpdate          = "pin_update"
	EvBanAdd             = "ban_add"
	EvBanRemove          = "ban_remove"
	EvInviteCreate       = "invite_create"
	EvInviteDelete       = "invite_delete"
	EvWebhooksUpdate     = "webhooks_update"
	EvPollVoteAdd        = "poll_vote_add"
	EvPollVoteRemove     = "poll_vote_remove"
	EvSettingsUpdate     = "settings_update"
	EvSessionExpired     = "session_expired"
	// EvGuestEnded tells a guest why their time in the call is over, just before the socket closes.
	EvGuestEnded = "guest_ended"
	// EvVoiceResumed answers a device resuming its call after a reconnect: kept, or start over.
	EvVoiceResumed = "voice_resumed"
	// EvVoiceError says why a voice op this device sent was refused.
	EvVoiceError = "voice_error"
	// EvKeyRotationNeeded asks holders of a conversation's key to replace it:
	// someone lost access.
	EvKeyRotationNeeded = "key_rotation_needed"
	// EvStickerRecent carries the account's recently used stickers to its other devices.
	EvStickerRecent = "sticker_recent"
	// EvStickerRecentClear empties the recent row on the account's other devices.
	EvStickerRecentClear = "sticker_recent_clear"
	// Moderation, delivered through Nexa: the account's own state, a notice to
	// show, one of its cases, announcements, and for staff the queue and the log.
	EvNexaState              = "nexa_state"
	EvNexaNotice             = "nexa_notice"
	EvNexaCase               = "nexa_case"
	EvNexaAnnouncement       = "nexa_announcement"
	EvNexaAnnouncementDelete = "nexa_announcement_delete"
	EvModCase                = "mod_case"
	EvModLog                 = "mod_log"
	// Bug reports, the same two ways: to the reporter through Nexa, and to staff.
	EvNexaBug = "nexa_bug"
	EvModBug  = "mod_bug"
	// EvModUnread tells one member of staff which case threads and bug
	// reports waiting on the staff they have not read (moderation_reads.go).
	EvModUnread = "mod_unread"
	// EvModReport carries new or changed safety reports, and the queue's counts,
	// to every member of staff; EvReportUpdate carries a report's receipt (its new
	// status, never staff notes) to the person who sent it.
	EvModReport    = "mod_report"
	// EvModGames tells admins how many shared games wait on them.
	EvModGames = "mod_games"
	EvReportUpdate = "report_update"
	// Nexa's note to someone a friend put in a group directly, and its
	// dismissal on another of their devices. Only that person is sent either.
	EvGroupAddNotice       = "group_add_notice"
	EvGroupAddNoticeDelete = "group_add_notice_delete"
	// Notes. A note's members are told when it is saved, re-shared or sealed
	// under a new key (its new head rides along, for their lists) and when it
	// is gone for them; someone invited to one is told of the invitation and
	// of its withdrawal.
	EvNoteUpdate       = "note_update"
	EvNoteDelete       = "note_delete"
	EvNoteInvite       = "note_invite"
	EvNoteInviteDelete = "note_invite_delete"
	// EvNotePresence says someone has a note open, or has closed it, to the
	// others it is shared with.
	EvNotePresence = "note_presence"
	// EvNoteCursor passes on where someone's caret and selection are in a
	// note (OpNoteCursor), sealed as they sent it.
	EvNoteCursor = "note_cursor"
	// EvNoteCRDT is one change to a note written together, numbered by the
	// server and sealed as its writer sent it; a big one goes by its number
	// alone, for the others to fetch.
	EvNoteCRDT = "note_crdt"

	// EvAuditLogEntry is a new line in a server's audit log, for those who may read it.
	EvAuditLogEntry = "audit_log_entry"
	// EvAutomodEvent is AutoMod acting in a server, for those who may read its log.
	EvAutomodEvent = "automod_event"
	// EvAutomodRulesUpdate says a server's AutoMod rules changed, so members' apps fetch them again.
	EvAutomodRulesUpdate = "automod_rules_update"
	// EvFollowsUpdate says which announcement channels a channel follows changed.
	EvFollowsUpdate = "follows_update"
	// EvThreadCreate is a thread someone can now see: started, or opened to them.
	EvThreadCreate = "thread_create"
	// EvThreadDelete is a thread gone, or no longer visible to whoever gets it.
	EvThreadDelete = "thread_delete"
	// EvThreadMembersUpdate is people joining or leaving a thread, with how many are in it now.
	EvThreadMembersUpdate = "thread_members_update"
	// EvReactionRemoveAll is every reaction taken off a message at once.
	EvReactionRemoveAll = "reaction_remove_all"
	// EvReactionRemoveEmoji is every reaction of one emoji taken off a message.
	EvReactionRemoveEmoji = "reaction_remove_emoji"
	// EvAppCommandsUpdate is an application's commands (or who may use them)
	// changing, in one server or wherever it is: an app holding a list of
	// them reads it again.
	EvAppCommandsUpdate = "application_commands_update"
	// EvIntegrationsUpdate is the applications installed in a server changing.
	EvIntegrationsUpdate = "integrations_update"
)

// Presence is a user's visible status.
type Presence struct {
	UserID          ids.ID `json:"user_id"`
	Status          string `json:"status"` // online | idle | dnd | offline
	StatusText      string `json:"status_text"`
	StatusEmoji     string `json:"status_emoji"`
	StatusExpiresAt *int64 `json:"status_expires_at"`
	// Activities are what the user is doing right now (games, music, video), newest connection's first.
	Activities []Activity `json:"activities,omitempty"`
}

// ReadyPayload is sent once after a successful identify.
type ReadyPayload struct {
	SessionID     ids.ID                   `json:"session_id"`
	User          any                      `json:"user"`
	Users         []store.PublicUser       `json:"users"`
	Channels      []*store.Channel         `json:"channels"`
	Relationships []store.RelationshipView `json:"relationships"`
	ReadStates    []*store.ReadState       `json:"read_states"`
	Presences     []Presence               `json:"presences"`
	KeyGrants     []*store.KeyGrant        `json:"key_grants"`
	Settings      *store.Settings          `json:"settings"`
	Servers       []any                    `json:"servers"`
	Emojis        []*store.Emoji           `json:"emojis"`
	StickerPacks  []*store.StickerPack     `json:"sticker_packs"`
	VoiceStates   []any                    `json:"voice_states"`
	// GroupAddNotices are this account's own notes from Nexa about being
	// added to a group by a friend, not yet dismissed. Nobody else is sent them.
	GroupAddNotices []store.GroupAddNotice `json:"group_add_notices"`
	// Guest says which call a guest is in; only a guest's ready has it.
	Guest any `json:"guest,omitempty"`
	// Nexa is the account's moderation state: restrictions in force, notices
	// not yet dismissed, what is unread from Nexa, and for staff the queue.
	Nexa any `json:"nexa,omitempty"`
	// Now is the server's clock (Unix ms) as the ready goes out, so an app
	// can tell how far its own clock is off before it shows server times as
	// running clocks (a call's timer).
	Now   int64          `json:"now"`
	Extra map[string]any `json:"-"`
}

// identifyData is the payload of the identify op.
type identifyData struct {
	Token string `json:"token"`
}

// presenceData is the payload of the presence op.
type presenceData struct {
	Status          *string `json:"status"`
	StatusText      *string `json:"status_text"`
	StatusEmoji     *string `json:"status_emoji"`
	StatusExpiresAt *int64  `json:"status_expires_at"`
	ClearStatus     bool    `json:"clear_status"`
}

// awayData is the payload of the away op.
type awayData struct {
	Away bool `json:"away"`
}

// activitiesData is the payload of the activities op; an empty list clears them.
type activitiesData struct {
	Activities []json.RawMessage `json:"activities"`
}

type typingData struct {
	ChannelID ids.ID `json:"channel_id"`
	// Stop ends the indicator at once instead of waiting for it to expire.
	Stop bool `json:"stop"`
}
