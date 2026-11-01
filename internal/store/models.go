package store

import (
	"encoding/json"

	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/perms"
)

// User flag bits.
const (
	FlagAdmin    int64 = 1
	FlagDisabled int64 = 2
	FlagDeleted  int64 = 4
	// FlagGuest marks someone in a call through a guest link. No account row
	// ever carries it: guests live in their own table, and the flag only
	// travels with the stand-in user the call code works with.
	FlagGuest int64 = 8
	// FlagSystem marks the account Nexa itself speaks as: Nexa, which
	// delivers moderation notices and announcements. Nobody signs in as it,
	// and nobody can message, befriend or moderate it.
	FlagSystem int64 = 16
	// FlagWebhook marks the account every webhook's messages are filed under.
	// It stands in for the webhooks in the author column, which names an
	// account; who a message really came from rides with the message itself.
	// Nobody signs in as it, and nobody can message, befriend or block it.
	FlagWebhook int64 = 32
	// FlagBot marks an application's bot: an account a program runs through
	// the bot API (internal/api/bot_*.go), signed in with its token alone.
	FlagBot int64 = 64
	// FlagBotReadsAll marks a bot whose application has the Message Content
	// intent. Only such a bot is entitled to the keys of the servers it is in,
	// so the server can decrypt what it sees to hand it over; everyone in
	// those servers is told. Other bots read only what is addressed to them.
	FlagBotReadsAll int64 = 128
	// FlagStaffAdmin and FlagStaffMod are the two lower ranks of Nexa's
	// staff (internal/api/staff.go). FlagAdmin is the rank above them,
	// Developer, and the oldest account holding it is the Owner. An account
	// holds at most one of the three.
	FlagStaffAdmin int64 = 256
	FlagStaffMod   int64 = 512
)

// StaffFlags are the bits of every staff rank: whoever holds one can open the
// moderation panel.
const StaffFlags = FlagAdmin | FlagStaffAdmin | FlagStaffMod

// NotPeople are the flags of accounts no person holds: Nexa's and the
// webhooks'. Anything that counts or lists people leaves them out.
const NotPeople = FlagSystem | FlagWebhook

// publicFlags are the bits other people may see: whether an account is staff
// and at which rank, whether it is the system account rather than a person, and whether it is a
// bot and reads every message it can see.
const publicFlags = StaffFlags | FlagSystem | FlagWebhook | FlagBot | FlagBotReadsAll

// Who may start a direct message with an account (users.dm_privacy): anyone,
// friends and people who share a server with it, friends, or nobody. A new
// account takes DefaultDMPrivacy, so a stranger has to send a friend request
// or be in a server with someone before they can message them.
const (
	DMEveryone       = "everyone"
	DMFriendsServers = "friends_servers"
	DMFriends        = "friends"
	DMNobody         = "nobody"
	DefaultDMPrivacy = DMFriendsServers
)

// User is a full account row. Fields tagged "-" never leave the server.
// GroupLinkOnly is set when friends may not add the account to a group chat
// directly, so it joins groups only from an invite link someone sends it.
type User struct {
	ID              ids.ID  `json:"id"`
	Username        string  `json:"username"`
	DisplayName     string  `json:"display_name"`
	AvatarID        *ids.ID `json:"avatar_id"`
	BannerID        *ids.ID `json:"banner_id"`
	AboutMe         string  `json:"about_me"`
	Pronouns        string  `json:"pronouns"`
	AccentColor     *int    `json:"accent_color"`
	AccentColor2    *int    `json:"accent_color_2"`
	StatusText      string  `json:"status_text"`
	StatusEmoji     string  `json:"status_emoji"`
	StatusExpiresAt *int64  `json:"status_expires_at"`
	PresencePref    string  `json:"presence_pref"`
	DMPrivacy       string  `json:"dm_privacy"`
	GroupLinkOnly   bool    `json:"group_link_only"`
	Flags           int64   `json:"flags"`
	PublicKey       []byte  `json:"public_key"`
	CreatedAt       int64   `json:"created_at"`
	UpdatedAt       int64   `json:"-"`
	// TypingStyle is how "is typing…" looks for this account to everyone
	// else, as the JSON the server writes (api/typing_style.go), or "".
	TypingStyle string `json:"-"`
	// NameStyle is how the display name is drawn (font, effect, colours), as
	// the JSON the server writes (api/name_style.go), or "".
	NameStyle string `json:"-"`
	// The server tag worn, copied from its server when it was put on and kept
	// in step by the server (api/server_tags.go). TagServerID nil wears none.
	TagServerID *ids.ID `json:"-"`
	TagText     string  `json:"-"`
	TagBadge    string  `json:"-"`
	TagColor    *int    `json:"-"`
	// Plan is the subscription the account has now: "" (free), "supporter"
	// or "pro" (api/billing.go). Public, as the badge it wears.
	Plan string `json:"-"`

	AuthHash              string `json:"-"`
	KDFSalt               []byte `json:"-"`
	KDFParams             string `json:"-"`
	EncPrivateKey         []byte `json:"-"`
	EncPrivateKeyRecovery []byte `json:"-"`
	RecoveryAuthHash      string `json:"-"`
}

// PublicUser is what other users may see.
//
// GroupLinkOnly says the account joins group chats only from an invite link
// (users.group_link_only): friends picking people for a group see it as Link
// Only. It is sent when false too, so a user_update that turns it off
// replaces the old value instead of leaving it standing. TypingStyle,
// NameStyle and ServerTag go out as null when there is none, for the same reason.
type PublicUser struct {
	ID              ids.ID          `json:"id"`
	Username        string          `json:"username"`
	DisplayName     string          `json:"display_name"`
	AvatarID        *ids.ID         `json:"avatar_id"`
	BannerID        *ids.ID         `json:"banner_id"`
	AboutMe         string          `json:"about_me"`
	Pronouns        string          `json:"pronouns"`
	AccentColor     *int            `json:"accent_color"`
	AccentColor2    *int            `json:"accent_color_2"`
	StatusText      string          `json:"status_text"`
	StatusEmoji     string          `json:"status_emoji"`
	StatusExpiresAt *int64          `json:"status_expires_at"`
	PublicKey       []byte          `json:"public_key"`
	Flags           int64           `json:"flags"`
	GroupLinkOnly   bool            `json:"group_link_only"`
	CreatedAt       int64           `json:"created_at"`
	TypingStyle     json.RawMessage `json:"typing_style"`
	NameStyle       json.RawMessage `json:"name_style"`
	ServerTag       *WornTag        `json:"server_tag"`
	Plan            string          `json:"plan"`
}

// WornTag is the server tag someone wears, as everyone sees it beside their name.
type WornTag struct {
	ServerID ids.ID `json:"server_id"`
	Text     string `json:"text"`
	Badge    string `json:"badge"`
	Color    *int   `json:"color"`
}

// Public returns the shareable view of u.
func (u *User) Public() PublicUser {
	p := PublicUser{ID: u.ID, Username: u.Username, DisplayName: u.DisplayName, AvatarID: u.AvatarID, BannerID: u.BannerID,
		AboutMe: u.AboutMe, Pronouns: u.Pronouns, AccentColor: u.AccentColor, AccentColor2: u.AccentColor2, StatusText: u.StatusText, StatusEmoji: u.StatusEmoji,
		StatusExpiresAt: u.StatusExpiresAt, PublicKey: u.PublicKey, Flags: u.Flags & publicFlags, GroupLinkOnly: u.GroupLinkOnly, CreatedAt: u.CreatedAt, Plan: u.Plan}
	if u.TypingStyle != "" {
		p.TypingStyle = json.RawMessage(u.TypingStyle)
	}
	if u.NameStyle != "" {
		p.NameStyle = json.RawMessage(styleForPlan(u.NameStyle, u.Plan))
	}
	p.ServerTag = u.WornTag()
	return p
}

// WornTag is the tag u wears, or nil.
func (u *User) WornTag() *WornTag {
	if u.TagServerID == nil || u.TagText == "" {
		return nil
	}
	return &WornTag{ServerID: *u.TagServerID, Text: u.TagText, Badge: u.TagBadge, Color: u.TagColor}
}

// ProfilePatch describes optional profile changes. A nil pointer means "leave
// unchanged"; for the double pointers, a pointer to nil means "clear".
type ProfilePatch struct {
	Username        *string
	DisplayName     *string
	AboutMe         *string
	Pronouns        *string
	AccentColor     **int
	AccentColor2    **int
	AvatarID        **ids.ID
	BannerID        **ids.ID
	StatusText      *string
	StatusEmoji     *string
	StatusExpiresAt **int64
	PresencePref    *string
	DMPrivacy       *string
	GroupLinkOnly   *bool
	// TypingStyle replaces the style with this JSON; "" is the plain one.
	TypingStyle *string
	// NameStyle replaces the name's style with this JSON; "" is the plain one.
	NameStyle *string
}

// Session is a login on one device.
type Session struct {
	ID         ids.ID `json:"id"`
	UserID     ids.ID `json:"-"`
	TokenHash  string `json:"-"`
	UserAgent  string `json:"user_agent"`
	IP         string `json:"ip"`
	CreatedAt  int64  `json:"created_at"`
	LastSeenAt int64  `json:"last_seen_at"`
}

// Settings is the client-encrypted settings blob with its optimistic version.
type Settings struct {
	Blob      []byte `json:"blob"`
	Version   int64  `json:"version"`
	UpdatedAt int64  `json:"updated_at"`
}

// Blob is a stored file.
type Blob struct {
	ID        ids.ID  `json:"id"`
	SHA256    string  `json:"-"`
	Size      int64   `json:"size"`
	Mime      string  `json:"mime"`
	Kind      string  `json:"kind"`
	Encrypted bool    `json:"encrypted"`
	RefCount  int     `json:"-"`
	OwnerID   *ids.ID `json:"-"`
	Width     *int    `json:"width,omitempty"`
	Height    *int    `json:"height,omitempty"`
	Animated  bool    `json:"animated"`
	CreatedAt int64   `json:"created_at"`
	// Blocked says the staff took this picture down: it is served to nobody
	// (internal/media), and nobody can upload it again (blocked_images).
	Blocked bool `json:"-"`
}

// Relationship links two users from user_id's point of view. Note is the note
// an incoming request came with, sealed to user_id by the sender's device
// (base64); it is empty on every other row.
type Relationship struct {
	UserID    ids.ID `json:"-"`
	TargetID  ids.ID `json:"user_id"`
	Type      string `json:"type"` // friend | outgoing | incoming | blocked
	Nickname  string `json:"nickname"`
	Note      string `json:"note,omitempty"`
	CreatedAt int64  `json:"created_at"`
}

// RelationshipView is a relationship as clients see it: the other user inline.
type RelationshipView struct {
	User      PublicUser `json:"user"`
	Type      string     `json:"type"`
	Nickname  string     `json:"nickname"`
	Note      string     `json:"note,omitempty"`
	CreatedAt int64      `json:"created_at"`
}

// View is r as its owner's clients see it, with other (the target) inline.
func (r *Relationship) View(other PublicUser) RelationshipView {
	return RelationshipView{User: other, Type: r.Type, Nickname: r.Nickname, Note: r.Note, CreatedAt: r.CreatedAt}
}

// Channel is any conversation container: DM, group DM, or server channel.
type Channel struct {
	ID              ids.ID   `json:"id"`
	ServerID        *ids.ID  `json:"server_id"`
	Type            string   `json:"type"`
	Name            string   `json:"name"`
	Topic           string   `json:"topic"`
	Position        int      `json:"position"`
	ParentID        *ids.ID  `json:"parent_id"`
	NSFW            bool     `json:"nsfw"`
	SlowmodeSeconds int      `json:"slowmode_seconds"`
	UserLimit       int      `json:"user_limit"`
	OwnerID         *ids.ID  `json:"owner_id"`
	IconID          *ids.ID  `json:"icon_id"`
	LastMessageID   *ids.ID  `json:"last_message_id"`
	CreatedAt       int64    `json:"created_at"`
	Recipients      []ids.ID `json:"recipient_ids"`
	// KeyConvID is the conversation whose keys encrypt this channel: itself
	// for DMs and private channels, the server otherwise.
	KeyConvID ids.ID `json:"key_conv_id"`
	// Overwrites is filled for server channels.
	Overwrites []Overwrite `json:"overwrites,omitempty"`
	// Locked marks a subchat that asks for a password before it opens. The
	// password material itself is never part of a channel as sent.
	Locked bool `json:"locked,omitempty"`
	// Glyph is a server channel's own icon, drawn in place of its # or
	// speaker: "icon:<name>", "emoji:<emoji>" or "custom:<emoji id>".
	// Empty keeps the default.
	Glyph string `json:"glyph,omitempty"`
	// Thread is how a thread stands (types thread and private_thread); nil
	// on every other channel.
	Thread *ThreadMeta `json:"thread,omitempty"`
	// Forum is a forum or media channel's own settings; nil on every other channel.
	Forum *ForumSettings `json:"forum,omitempty"`
	// URL is where a link channel leads; empty on every other channel.
	URL string `json:"url,omitempty"`
	// DefaultAutoArchive is, on a channel threads start in, how many quiet
	// minutes close a new one (0: three days).
	DefaultAutoArchive int `json:"default_auto_archive,omitempty"`
}

// Thread channel types: a public thread (also every forum post) and a
// private one, seen only by the people in it and by moderators.
const (
	ChannelThread        = "thread"
	ChannelPrivateThread = "private_thread"
	ChannelAnnouncement  = "announcement"
	ChannelForum         = "forum"
	// ChannelMedia is a forum whose posts are pictures and videos, drawn as a gallery.
	ChannelMedia = "media"
	// ChannelLink is an entry in the channel list that opens an address; it holds no messages.
	ChannelLink = "link"
)

// IsForum reports whether a channel only holds posts: a forum or a media channel.
func IsForum(t string) bool { return t == ChannelForum || t == ChannelMedia }

// HoldsNoMessages reports whether a server channel has no messages of its
// own: a category, which holds channels, and a link channel, which opens an
// address.
func HoldsNoMessages(t string) bool { return t == "category" || t == ChannelLink }

// IsThread reports whether a channel type is a thread.
func IsThread(t string) bool { return t == ChannelThread || t == ChannelPrivateThread }

// HoldsThreads reports whether threads can be started in a channel of this type.
func HoldsThreads(t string) bool { return t == "text" || t == ChannelAnnouncement || IsForum(t) }

// Talkable reports whether people write messages straight into a channel of
// this type: a forum only holds posts, which are threads.
func Talkable(t string) bool { return t == "text" || t == ChannelAnnouncement || IsThread(t) }

// ThreadMeta is a thread's state.
type ThreadMeta struct {
	Archived    bool     `json:"archived"`
	ArchivedAt  *int64   `json:"archived_at"`
	Locked      bool     `json:"locked"`
	AutoArchive int      `json:"auto_archive"`
	StarterID   *ids.ID  `json:"starter_id"`
	Messages    int      `json:"message_count"`
	AppliedTags []ids.ID `json:"applied_tags"`
}

// ForumSettings are a forum or media channel's own: the tags its posts can
// wear, the reaction offered on every post, how posts are sorted and drawn at
// first (a media channel is always a gallery), whether a post needs a tag,
// the one post pinned at the top, and, in a media channel, whether the apps
// leave out the download buttons on its pictures and videos.
type ForumSettings struct {
	Tags            []ForumTag `json:"tags"`
	DefaultReaction string     `json:"default_reaction"`
	Sort            string     `json:"sort"`
	Layout          string     `json:"layout"`
	RequireTag      bool       `json:"require_tag"`
	PinnedPost      *ids.ID    `json:"pinned_post"`
	HideDownloads   bool       `json:"hide_downloads,omitempty"`
}

// ForumTag is one tag a forum's posts can wear. A moderated one only people
// who manage threads may put on a post.
type ForumTag struct {
	ID        ids.ID `json:"id"`
	Name      string `json:"name"`
	Emoji     string `json:"emoji"`
	Moderated bool   `json:"moderated"`
}

// Server is a community with channels, roles and members.
type Server struct {
	NSFW                 bool    `json:"nsfw"`
	ID                   ids.ID  `json:"id"`
	Name                 string  `json:"name"`
	IconID               *ids.ID `json:"icon_id"`
	BannerID             *ids.ID `json:"banner_id"`
	OwnerID              ids.ID  `json:"owner_id"`
	Description          string  `json:"description"`
	SystemChannelID      *ids.ID `json:"system_channel_id"`
	DefaultNotifications int     `json:"default_notifications"`
	Flags                int64   `json:"flags"`
	CreatedAt            int64   `json:"created_at"`
	// Tag is the tag its members can wear (server_tags.go), or nil. Read
	// by ServerByID and ServersForUser; other server reads leave it nil.
	Tag *ServerTag `json:"tag"`
	// InvitesPausedUntil is when the server takes new members again; nil
	// while it takes them.
	InvitesPausedUntil *int64 `json:"invites_paused_until"`
	// How the server shows in Discovery, where it is listed when
	// Discoverable and not DiscoveryHidden (taken off by Nexa's staff).
	Discoverable      bool     `json:"discoverable"`
	DiscoveryCategory string   `json:"discovery_category"`
	DiscoveryTags     []string `json:"discovery_tags"`
	DiscoveryLanguage string   `json:"discovery_language"`
	DiscoverySince    *int64   `json:"discovery_since"`
	DiscoveryHidden   bool     `json:"discovery_hidden"`
	// How old an account, and how long a membership, has to be before it may
	// talk here; 0 for no wait.
	VerifyAccountMinutes int `json:"verify_account_minutes"`
	VerifyMemberMinutes  int `json:"verify_member_minutes"`
	// AutomodAt is when the AutoMod rules members' apps apply last changed,
	// or 0 when there are none: apps fetch them only where there are some.
	AutomodAt int64 `json:"automod_at"`
	// Boosts is how many Pro accounts boost the server (api/billing.go);
	// one or more lets every member send bigger files there.
	Boosts int `json:"boosts"`
}

// ServerPatch is an optional set of server changes.
type ServerPatch struct {
	NSFW                 *bool
	Name                 *string
	Description          *string
	IconID               **ids.ID
	BannerID             **ids.ID
	SystemChannelID      **ids.ID
	DefaultNotifications *int
	OwnerID              *ids.ID
	InvitesPausedUntil   **int64
	Discoverable         *bool
	DiscoveryCategory    *string
	DiscoveryTags        *[]string
	DiscoveryLanguage    *string
	DiscoveryHidden      *bool
	VerifyAccountMinutes *int
	VerifyMemberMinutes  *int
}

// Member is a user's membership in a server. Nickname, avatar, banner, bio
// and pronouns are the member's profile in this server, shown there in place
// of their account's own. How they joined is kept from everyone but those who
// may see the server's invites (MemberJoin).
type Member struct {
	ServerID ids.ID  `json:"server_id"`
	UserID   ids.ID  `json:"user_id"`
	Nickname string  `json:"nickname"`
	AvatarID *ids.ID `json:"avatar_id"`
	BannerID *ids.ID `json:"banner_id"`
	Bio      string  `json:"bio"`
	Pronouns string  `json:"pronouns"`
	// AccentColor and AccentColor2 are the profile's theme in this server, as
	// the account's own are (nil uses those).
	AccentColor  *int     `json:"accent_color,omitempty"`
	AccentColor2 *int     `json:"accent_color_2,omitempty"`
	JoinedAt     int64    `json:"joined_at"`
	TimeoutUntil *int64   `json:"timeout_until"`
	Flags        int64    `json:"flags"`
	RoleIDs      []ids.ID `json:"role_ids"`
	JoinMethod   string   `json:"-"`
	JoinCode     string   `json:"-"`
	InvitedBy    *ids.ID  `json:"-"`
}

// Member flag bits.
const (
	// MemberQuarantined is a member AutoMod held for what their name or
	// profile in the server says: they can look, and do nothing else, until
	// a moderator lets them out or they change it.
	MemberQuarantined int64 = 1
	// MemberVoiceMuted and MemberVoiceDeafened are a moderator's server mute
	// and deafen. They stay on the member, so leaving the call and joining it
	// again does not lift them; only a moderator does.
	MemberVoiceMuted    int64 = 2
	MemberVoiceDeafened int64 = 4
)

// MemberJoin is how someone came into a server, for the people who may see
// its invites.
type MemberJoin struct {
	Method    string  `json:"join_method"`
	Code      string  `json:"join_code"`
	InvitedBy *ids.ID `json:"invited_by"`
}

// Join is m's MemberJoin.
func (m *Member) Join() MemberJoin {
	return MemberJoin{Method: m.JoinMethod, Code: m.JoinCode, InvitedBy: m.InvitedBy}
}

// MemberPatch is an optional set of membership changes.
type MemberPatch struct {
	Nickname     *string
	AvatarID     **ids.ID
	BannerID     **ids.ID
	Bio          *string
	Pronouns     *string
	AccentColor  **int
	AccentColor2 **int
	TimeoutUntil **int64
	Flags        *int64
}

// Role is a server role. With Color2 its name is drawn as a gradient from
// Color to Color2, and with Color3 as well, as a shimmer across all three.
// Its icon is a picture (IconID) or an emoji (IconEmoji: the character, or
// "custom:<emoji id>" for one of the server's own).
type Role struct {
	ID          ids.ID     `json:"id"`
	ServerID    ids.ID     `json:"server_id"`
	Name        string     `json:"name"`
	Color       int        `json:"color"`
	Color2      *int       `json:"color2"`
	Color3      *int       `json:"color3"`
	Hoist       bool       `json:"hoist"`
	Mentionable bool       `json:"mentionable"`
	Position    int        `json:"position"`
	Permissions perms.Perm `json:"permissions"`
	IconID      *ids.ID    `json:"icon_id"`
	IconEmoji   string     `json:"icon_emoji"`
	CreatedAt   int64      `json:"created_at"`
	// ManagedBy is the bot a role came with (CreateManagedRole): it holds
	// what the bot asked for when it was added, nobody else can be given it,
	// and it goes when the bot leaves. Nil on every other role.
	ManagedBy *ids.ID `json:"managed_by,omitempty"`
}

// RolePatch is an optional set of role changes.
type RolePatch struct {
	Name        *string
	Color       *int
	Color2      **int
	Color3      **int
	Hoist       *bool
	Mentionable *bool
	Permissions *perms.Perm
	IconID      **ids.ID
	IconEmoji   *string
}

// Overwrite is a channel permission overwrite.
type Overwrite struct {
	ChannelID  ids.ID     `json:"channel_id"`
	TargetID   ids.ID     `json:"target_id"`
	TargetType string     `json:"target_type"` // role | member
	Allow      perms.Perm `json:"allow"`
	Deny       perms.Perm `json:"deny"`
}

// ChannelPosition is one entry of a bulk reorder.
type ChannelPosition struct {
	ID        ids.ID  `json:"id"`
	Position  int     `json:"position"`
	ParentID  *ids.ID `json:"parent_id"`
	ParentSet bool    `json:"-"`
}

// Invite is a server invite link.
type Invite struct {
	Code string `json:"code"`
	// ServerID is nil for a group DM invite, where ChannelID carries the group.
	ServerID  *ids.ID `json:"server_id"`
	ChannelID *ids.ID `json:"channel_id"`
	InviterID ids.ID  `json:"inviter_id"`
	MaxUses   int     `json:"max_uses"`
	Uses      int     `json:"uses"`
	ExpiresAt *int64  `json:"expires_at"`
	CreatedAt int64   `json:"created_at"`
}

// Ban is a server ban.
type Ban struct {
	ServerID  ids.ID `json:"server_id"`
	UserID    ids.ID `json:"user_id"`
	Reason    string `json:"reason"`
	BannedBy  ids.ID `json:"banned_by"`
	CreatedAt int64  `json:"created_at"`
}

// AuditEntry records a moderation or configuration action.
type AuditEntry struct {
	ID         ids.ID          `json:"id"`
	ServerID   ids.ID          `json:"server_id"`
	ActorID    ids.ID          `json:"actor_id"`
	Action     string          `json:"action"`
	TargetType string          `json:"target_type"`
	TargetID   *ids.ID         `json:"target_id"`
	Changes    json.RawMessage `json:"changes"`
	Reason     string          `json:"reason"`
	CreatedAt  int64           `json:"created_at"`
}

// ChannelPatch is an optional set of channel changes.
type ChannelPatch struct {
	Name            *string
	Topic           *string
	IconID          **ids.ID
	Glyph           *string
	OwnerID         *ids.ID
	NSFW            *bool
	SlowmodeSeconds *int
	UserLimit       *int
	ParentID        **ids.ID
	Position        *int
	Type            *string
	// threads
	Archived    *bool
	Locked      *bool
	AutoArchive *int
	AppliedTags *[]ids.ID
	// forums and media channels
	Forum *ForumSettings
	// link channels
	URL *string
}

// Message flag bits, at the same values as Discord's.
const (
	MsgFlagPinned         int64 = 1
	MsgFlagSuppressEmbeds int64 = 4
	// MsgFlagSuppressNotifications is a message that makes no sound and
	// shows no notification, though it still counts as unread.
	MsgFlagSuppressNotifications int64 = 1 << 12
	// MsgFlagComponentsV2 is a webhook message laid out entirely by its
	// components: it has no content or embeds of its own.
	MsgFlagComponentsV2 int64 = 1 << 15
	// MsgFlagForwarded is a message forwarded from another (Discord's
	// HAS_SNAPSHOT bit): its files are the original's own, not uploaded again.
	MsgFlagForwarded int64 = 1 << 14
	// MsgFlagPublished is a post of an announcement channel that has been
	// published to the channels following it (Nexa's own bit).
	MsgFlagPublished int64 = 1 << 20
	// MsgFlagEphemeral is an application's answer only the person who used
	// it sees; such a message is never stored with the rest
	// (ephemeral_messages), and the apps are sent it with this bit set.
	MsgFlagEphemeral int64 = 1 << 6
	// MsgFlagLoading is an application's answer still on its way: the apps
	// show it thinking until the application fills it in.
	MsgFlagLoading int64 = 1 << 7
)

// Message is an encrypted message row plus its attachments and reactions.
type Message struct {
	ID              ids.ID            `json:"id"`
	ChannelID       ids.ID            `json:"channel_id"`
	AuthorID        ids.ID            `json:"author_id"`
	Type            string            `json:"type"`
	Ciphertext      json.RawMessage   `json:"ciphertext"`
	KeyConvID       *ids.ID           `json:"key_conv_id"`
	KeyEpoch        *int              `json:"key_epoch"`
	ReplyToID       *ids.ID           `json:"reply_to_id"`
	Mentions        []ids.ID          `json:"mentions"`
	MentionRoles    []ids.ID          `json:"mention_roles"`
	MentionEveryone bool              `json:"mention_everyone"`
	Attachments     []Attachment      `json:"attachments"`
	Reactions       []ReactionSummary `json:"reactions"`
	Flags           int64             `json:"flags"`
	Nonce           string            `json:"nonce,omitempty"`
	EditedAt        *int64            `json:"edited_at"`
	CreatedAt       int64             `json:"created_at"`
	// WebhookID names the webhook a message came through, and Webhook holds
	// what it said, as sent: a webhook's messages are not encrypted (see
	// api/webhook_message.go for the shape). Both are absent otherwise.
	WebhookID *ids.ID         `json:"webhook_id,omitempty"`
	Webhook   json.RawMessage `json:"webhook,omitempty"`
	// Poll is how a message's poll stands, for whoever loaded it: a webhook's
	// poll, or a person's.
	Poll *PollTally `json:"poll_results,omitempty"`
	// NewPoll, on a message being inserted, opens its poll with it.
	NewPoll *NewPoll `json:"-"`
	// PollResult, on the line that closes a person's poll, is how it went. It
	// is kept where a webhook's words are (messages.plain): the server wrote
	// it, so there is nothing to seal.
	PollResult *PollOutcome `json:"poll_result,omitempty"`
	// Crosspost is, on a post published from an announcement channel into
	// this one, where it came from (api/follows.go): the words themselves
	// are sealed in Ciphertext, for the people of this channel.
	Crosspost json.RawMessage `json:"crosspost,omitempty"`
	// Automod is, on AutoMod's note in an alert channel, what it caught and
	// what it did; anything the member's app caught is sealed inside it.
	Automod json.RawMessage `json:"automod,omitempty"`
	// Published says a post of an announcement channel has been published.
	Published bool `json:"published,omitempty"`
	// Thread is the thread started from this message, for drawing it under
	// the message: its id, name, how much has been said and when last.
	Thread *ThreadSummary `json:"thread,omitempty"`
	// Referenced is the message a reply (or a pin's or a poll's notice)
	// points at, as it is now, sealed as it was sent: readers quote it from
	// this rather than from whatever part of the conversation they happen to
	// have loaded. The JSON null says it has been deleted. Absent on a message
	// that points at nothing, and wherever WithReferences was not asked.
	Referenced json.RawMessage `json:"referenced_message,omitempty"`
}

// ThreadSummary is a thread as the message it started from shows it.
type ThreadSummary struct {
	ID            ids.ID  `json:"id"`
	Name          string  `json:"name"`
	Messages      int     `json:"message_count"`
	LastMessageID *ids.ID `json:"last_message_id"`
	Archived      bool    `json:"archived"`
	Locked        bool    `json:"locked"`
	// LastMessage is the newest thing said in a public thread, sealed as it
	// was sent, for the line under the message it hangs from: who said what,
	// and when. A private thread's is left out, being its members' alone.
	LastMessage *Message `json:"last_message,omitempty"`
}

// PollOutcome is how a person's poll went, as the line that closes it carries
// it: numbers only. What the poll asked and offered is sealed inside its
// message, which the line answers, so readers put the two together. The
// winner is 0 when nobody voted or the top answers tied.
type PollOutcome struct {
	Counts      []PollOutcomeCount `json:"answer_counts"`
	TotalVotes  int                `json:"total_votes"`
	TotalVoters int                `json:"total_voters"`
	WinnerID    int                `json:"victor_answer_id"`
	WinnerVotes int                `json:"victor_answer_votes"`
}

// PollOutcomeCount is one answer's votes when its poll closed.
type PollOutcomeCount struct {
	ID    int `json:"id"`
	Count int `json:"count"`
}

// NewPoll is a poll opened with its message: how many answers it has (their
// ids run from 1 to Answers), whether more than one may be picked, and when
// it closes.
type NewPoll struct {
	Answers     int
	Multiselect bool
	ExpiresAt   int64
}

// PollTally is how a poll stands for one reader: the votes on each answer,
// which the reader picked, how many people voted, and whether it has closed.
type PollTally struct {
	Counts    []PollCount `json:"answer_counts"`
	Voters    int         `json:"total_voters"`
	ExpiresAt *int64      `json:"expires_at"`
	Finalized bool        `json:"is_finalized"`
}

// PollCount is one answer's votes.
type PollCount struct {
	ID      int  `json:"id"`
	Count   int  `json:"count"`
	MeVoted bool `json:"me_voted"`
}

// Webhook is an incoming webhook: a secret URL that posts into one channel.
// The token is never part of it as stored; the API opens it for managers.
type Webhook struct {
	ID          ids.ID  `json:"id"`
	Type        int     `json:"type"`
	ServerID    ids.ID  `json:"server_id"`
	ChannelID   ids.ID  `json:"channel_id"`
	CreatorID   *ids.ID `json:"creator_id"`
	Name        string  `json:"name"`
	AvatarID    *ids.ID `json:"avatar_id"`
	TokenHash   string  `json:"-"`
	TokenSealed []byte  `json:"-"`
	CreatedAt   int64   `json:"created_at"`
	UpdatedAt   int64   `json:"updated_at"`
}

// WebhookPatch is an optional set of webhook changes.
type WebhookPatch struct {
	Name      *string
	ChannelID *ids.ID
}

// Attachment is a file bound to a message: ciphertext, or for a webhook's
// message the file as it was sent.
type Attachment struct {
	ID         ids.ID `json:"id"`
	MessageID  ids.ID `json:"-"`
	ChannelID  ids.ID `json:"-"`
	UploaderID ids.ID `json:"-"`
	BlobID     ids.ID `json:"blob_id"`
	Size       int64  `json:"size"`
	CreatedAt  int64  `json:"-"`
}

// ReactionSummary aggregates one emoji on a message.
type ReactionSummary struct {
	Emoji string `json:"emoji"`
	Count int    `json:"count"`
	Me    bool   `json:"me"`
}

// ReadState is a user's position in a channel.
type ReadState struct {
	ChannelID         ids.ID  `json:"channel_id"`
	LastReadMessageID *ids.ID `json:"last_read_message_id"`
	LastAckAt         int64   `json:"last_ack_at"`
	MentionCount      int     `json:"mention_count"`
	// UnreadCount counts other people's unread messages in a DM, group DM or subchat,
	// up to MaxUnreadCount; it is zero for server channels.
	UnreadCount int `json:"unread_count"`
	// Manual is set on the state Mark Unread leaves, the one change that moves
	// the marker back: a client takes that from any device, and otherwise keeps
	// a marker it already has further on (an older ack answered late).
	Manual bool `json:"manual,omitempty"`
}

// MaxUnreadCount is where a conversation's unread count stops counting.
const MaxUnreadCount = 100

// KeyEpoch is one generation of a conversation's root key.
type KeyEpoch struct {
	ConvID    ids.ID `json:"conv_id"`
	Epoch     int    `json:"epoch"`
	CreatedBy ids.ID `json:"created_by"`
	CreatedAt int64  `json:"created_at"`
	Reason    string `json:"reason"`
}

// KeyGrant is a root key sealed for one user.
type KeyGrant struct {
	ConvID     ids.ID `json:"conv_id"`
	Epoch      int    `json:"epoch"`
	UserID     ids.ID `json:"user_id"`
	WrappedKey []byte `json:"wrapped_key"`
	WrappedBy  ids.ID `json:"wrapped_by"`
	CreatedAt  int64  `json:"created_at"`
}

// KeyFlags marks a conversation that needs client-side key work.
type KeyFlags struct {
	ConvID        ids.ID `json:"conv_id"`
	NeedsRotation bool   `json:"needs_rotation"`
	NeedsOwnKey   bool   `json:"needs_own_key"`
}
