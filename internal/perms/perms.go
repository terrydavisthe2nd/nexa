// Package perms implements Discord's permission model: role bitflags, the
// @everyone base, channel overwrites, timeouts, and role hierarchy checks.
package perms

import (
	"encoding/json"
	"strconv"

	"evilion.xyz/chat/internal/ids"
)

// Perm is a permission bitset. It serialises as a decimal string in JSON so
// JavaScript clients never lose precision.
type Perm uint64

const (
	CreateInstantInvite Perm = 1 << 0
	KickMembers         Perm = 1 << 1
	BanMembers          Perm = 1 << 2
	Administrator       Perm = 1 << 3
	ManageChannels      Perm = 1 << 4
	ManageServer        Perm = 1 << 5
	AddReactions        Perm = 1 << 6
	ViewAuditLog        Perm = 1 << 7
	PrioritySpeaker     Perm = 1 << 8
	Stream              Perm = 1 << 9
	ViewChannel         Perm = 1 << 10
	SendMessages        Perm = 1 << 11
	ManageMessages      Perm = 1 << 13
	EmbedLinks          Perm = 1 << 14
	AttachFiles         Perm = 1 << 15
	ReadMessageHistory  Perm = 1 << 16
	MentionEveryone     Perm = 1 << 17
	Connect             Perm = 1 << 20
	Speak               Perm = 1 << 21
	MuteMembers         Perm = 1 << 22
	DeafenMembers       Perm = 1 << 23
	MoveMembers         Perm = 1 << 24
	UseVAD              Perm = 1 << 25
	ChangeNickname      Perm = 1 << 26
	ManageNicknames     Perm = 1 << 27
	ManageRoles         Perm = 1 << 28
	ManageWebhooks      Perm = 1 << 29
	ManageEmojis        Perm = 1 << 30
	UseExternalEmojis   Perm = 1 << 18
	ModerateMembers     Perm = 1 << 40
	UseSoundboard       Perm = 1 << 42
	CreatePolls         Perm = 1 << 49
	// Threads: whoever may see a channel may see its public threads; these say
	// who may start them, talk in them, and look after them (rename, close,
	// lock, delete, see every private one).
	ManageThreads         Perm = 1 << 34
	CreatePublicThreads   Perm = 1 << 35
	CreatePrivateThreads  Perm = 1 << 36
	UseExternalStickers   Perm = 1 << 37
	SendMessagesInThreads Perm = 1 << 38
	// CreateExpressions lets a member add emoji and stickers, and change or
	// remove the ones they added; ManageEmojis covers everyone's.
	CreateExpressions Perm = 1 << 43
	// PinMessages is pinning and unpinning, apart from Manage Messages.
	PinMessages Perm = 1 << 51
	// BypassSlowmode sends without waiting out a channel's slow mode.
	BypassSlowmode Perm = 1 << 52
	// UseApplicationCommands is using the commands of the applications
	// added to a server: their slash commands and their menu entries.
	UseApplicationCommands Perm = 1 << 31
	// UseExternalApps lets what someone's own applications (added to their
	// account rather than to the server) answer in a channel be seen by
	// everyone; without it only they see it.
	UseExternalApps Perm = 1 << 50
	// UseCustomTyping is Nexa's own, kept far above Discord's bits: whether
	// someone's own typing style (their emoji and words) is shown when they
	// type in a channel, rather than the plain dots. The server never sees a
	// style being drawn, so every reader's app holds it.
	UseCustomTyping Perm = 1 << 60
)

// All is every permission this server knows.
const All = CreateInstantInvite | KickMembers | BanMembers | Administrator | ManageChannels | ManageServer | AddReactions | ViewAuditLog |
	PrioritySpeaker | Stream | ViewChannel | SendMessages | ManageMessages | EmbedLinks | AttachFiles | ReadMessageHistory | MentionEveryone |
	UseExternalEmojis | Connect | Speak | MuteMembers | DeafenMembers | MoveMembers | UseVAD | ChangeNickname | ManageNicknames | ManageRoles |
	ManageWebhooks | ManageEmojis | ManageThreads | CreatePublicThreads | CreatePrivateThreads | UseExternalStickers | SendMessagesInThreads |
	ModerateMembers | UseSoundboard | CreateExpressions | CreatePolls | PinMessages | BypassSlowmode | UseApplicationCommands | UseExternalApps | UseCustomTyping

// DefaultEveryone is what a fresh server's @everyone role gets.
const DefaultEveryone = CreateInstantInvite | AddReactions | Stream | ViewChannel | SendMessages | EmbedLinks | AttachFiles |
	ReadMessageHistory | UseExternalEmojis | Connect | Speak | UseVAD | ChangeNickname | UseSoundboard | CreatePolls |
	CreatePublicThreads | CreatePrivateThreads | UseExternalStickers | SendMessagesInThreads | UseApplicationCommands | UseExternalApps | UseCustomTyping

// TimedOutKeep is all a timed-out member retains.
const TimedOutKeep = ViewChannel | ReadMessageHistory

// Names maps permission names (as the API and UI use them) to bits.
var Names = map[string]Perm{
	"CREATE_INSTANT_INVITE": CreateInstantInvite, "KICK_MEMBERS": KickMembers, "BAN_MEMBERS": BanMembers, "ADMINISTRATOR": Administrator,
	"MANAGE_CHANNELS": ManageChannels, "MANAGE_SERVER": ManageServer, "ADD_REACTIONS": AddReactions, "VIEW_AUDIT_LOG": ViewAuditLog,
	"PRIORITY_SPEAKER": PrioritySpeaker, "STREAM": Stream, "VIEW_CHANNEL": ViewChannel, "SEND_MESSAGES": SendMessages,
	"MANAGE_MESSAGES": ManageMessages, "EMBED_LINKS": EmbedLinks, "ATTACH_FILES": AttachFiles, "READ_MESSAGE_HISTORY": ReadMessageHistory,
	"MENTION_EVERYONE": MentionEveryone, "CONNECT": Connect, "SPEAK": Speak, "MUTE_MEMBERS": MuteMembers, "DEAFEN_MEMBERS": DeafenMembers,
	"MOVE_MEMBERS": MoveMembers, "USE_VAD": UseVAD, "CHANGE_NICKNAME": ChangeNickname, "MANAGE_NICKNAMES": ManageNicknames,
	"MANAGE_ROLES": ManageRoles, "MANAGE_WEBHOOKS": ManageWebhooks, "MANAGE_EMOJIS": ManageEmojis, "MODERATE_MEMBERS": ModerateMembers, "USE_SOUNDBOARD": UseSoundboard,
	"CREATE_POLLS": CreatePolls, "USE_EXTERNAL_EMOJIS": UseExternalEmojis, "MANAGE_THREADS": ManageThreads, "CREATE_PUBLIC_THREADS": CreatePublicThreads,
	"CREATE_PRIVATE_THREADS": CreatePrivateThreads, "USE_EXTERNAL_STICKERS": UseExternalStickers, "SEND_MESSAGES_IN_THREADS": SendMessagesInThreads,
	"CREATE_EXPRESSIONS": CreateExpressions, "PIN_MESSAGES": PinMessages, "BYPASS_SLOWMODE": BypassSlowmode,
	"USE_APPLICATION_COMMANDS": UseApplicationCommands, "USE_EXTERNAL_APPS": UseExternalApps, "USE_CUSTOM_TYPING": UseCustomTyping,
}

// MarshalJSON writes the bitset as a decimal string.
func (p Perm) MarshalJSON() ([]byte, error) {
	return []byte(`"` + strconv.FormatUint(uint64(p), 10) + `"`), nil
}

// UnmarshalJSON accepts a decimal string or a bare number.
func (p *Perm) UnmarshalJSON(b []byte) error {
	if len(b) > 0 && b[0] == '"' {
		var s string
		if err := json.Unmarshal(b, &s); err != nil {
			return err
		}
		n, err := strconv.ParseUint(s, 10, 64)
		if err != nil {
			return err
		}
		*p = Perm(n)
		return nil
	}
	var n uint64
	if err := json.Unmarshal(b, &n); err != nil {
		return err
	}
	*p = Perm(n)
	return nil
}

// Has reports whether every bit of need is set.
func (p Perm) Has(need Perm) bool { return p&need == need }

// Role is the part of a role the resolver needs.
type Role struct {
	ID          ids.ID
	Position    int
	Permissions Perm
}

// Overwrite is a channel permission overwrite for a role or a member.
type Overwrite struct {
	TargetID ids.ID
	Kind     string // "role" | "member"
	Allow    Perm
	Deny     Perm
}

// Member is the part of a membership the resolver needs. A member AutoMod
// has quarantined keeps only what a timed-out one does, until a moderator
// lets them out or what caught them is gone.
type Member struct {
	UserID      ids.ID
	RoleIDs     []ids.ID
	TimedOut    bool
	Quarantined bool
}

// restricted reports whether m keeps only TimedOutKeep.
func (m Member) restricted() bool { return m.TimedOut || m.Quarantined }

// Base computes server-level permissions: @everyone OR the member's roles,
// Administrator and ownership granting everything. A timed-out member keeps
// only TimedOutKeep here too, so a moderator in timeout cannot kick, ban or
// manage anything until it ends.
func Base(everyoneID ids.ID, roles []Role, m Member, isOwner bool) Perm {
	if isOwner {
		return All
	}
	var p Perm
	has := map[ids.ID]bool{everyoneID: true}
	for _, id := range m.RoleIDs {
		has[id] = true
	}
	for _, r := range roles {
		if has[r.ID] {
			p |= r.Permissions
		}
	}
	if p.Has(Administrator) {
		return All
	}
	if m.restricted() {
		p &= TimedOutKeep
	}
	return p
}

// Compute resolves a member's permissions in a channel with the given overwrites.
func Compute(everyoneID ids.ID, roles []Role, m Member, isOwner bool, overwrites []Overwrite) Perm {
	base := Base(everyoneID, roles, m, isOwner)
	if isOwner || base.Has(Administrator) {
		return All
	}
	p := base
	has := map[ids.ID]bool{}
	for _, id := range m.RoleIDs {
		has[id] = true
	}
	var everyoneOW *Overwrite
	var roleAllow, roleDeny Perm
	var memberOW *Overwrite
	for i := range overwrites {
		ow := &overwrites[i]
		switch {
		case ow.Kind == "role" && ow.TargetID == everyoneID:
			everyoneOW = ow
		case ow.Kind == "role" && has[ow.TargetID]:
			roleAllow |= ow.Allow
			roleDeny |= ow.Deny
		case ow.Kind == "member" && ow.TargetID == m.UserID:
			memberOW = ow
		}
	}
	if everyoneOW != nil {
		p = p&^everyoneOW.Deny | everyoneOW.Allow
	}
	p = p&^roleDeny | roleAllow
	if memberOW != nil {
		p = p&^memberOW.Deny | memberOW.Allow
	}
	if m.restricted() {
		p &= TimedOutKeep
	}
	if !p.Has(ViewChannel) {
		return 0
	}
	return p
}

// Thread is what someone may do in a thread, from what they may do in its
// parent channel: the same, except that talking in a thread is Send Messages
// in Threads rather than Send Messages, and a private thread is seen only by
// the people in it and by those who may manage threads.
func Thread(parent Perm, private, member bool) Perm {
	if !parent.Has(ViewChannel) {
		return 0
	}
	if parent.Has(Administrator) {
		return All
	}
	if private && !member && !parent.Has(ManageThreads) {
		return 0
	}
	p := parent &^ SendMessages
	if parent.Has(SendMessagesInThreads) {
		p |= SendMessages
	}
	return p
}

// HighestPosition returns the position of the member's highest role (0 = only @everyone).
func HighestPosition(roles []Role, m Member) int {
	best := 0
	for _, id := range m.RoleIDs {
		for _, r := range roles {
			if r.ID == id && r.Position > best {
				best = r.Position
			}
		}
	}
	return best
}

// CanManageRole reports whether an actor may edit/assign/delete a role: owners
// may manage anything; others only roles strictly below their highest.
func CanManageRole(actorHighest int, actorIsOwner bool, role Role) bool {
	return actorIsOwner || actorHighest > role.Position
}

// CanActOn reports whether an actor may kick/ban/timeout/manage a target.
func CanActOn(actorHighest, targetHighest int, actorIsOwner, targetIsOwner bool) bool {
	if targetIsOwner {
		return false
	}
	return actorIsOwner || actorHighest > targetHighest
}
