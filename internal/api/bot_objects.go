package api

import (
	"context"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"

	"evilion.xyz/chat/internal/botproto"
	"evilion.xyz/chat/internal/gateway"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/perms"
	"evilion.xyz/chat/internal/store"
)

// The objects of the bot protocol, built from Nexa's. Every id goes out as a
// botproto.Snow; pictures go out as hashes ("a_" before one that moves) that
// the CDN routes (bot_cdn.go) turn back into the picture.

type snow = botproto.Snow

// parseBotID reads an id a bot sent, in either form.
func parseBotID(s string) (ids.ID, error) { return botproto.ParseID(s) }

// permString writes permission bits as the protocol does, as a decimal string.
func permString(p perms.Perm) string { return strconv.FormatUint(uint64(p), 10) }

// dBuilder builds objects for one request or one event, remembering what it
// looked up on the way.
type dBuilder struct {
	s   *Server
	ctx context.Context
	// viewer is who the objects are for: the bot, or the person behind an
	// OAuth2 token (what a message says, and what may be shown, depend on it)
	viewer *store.User
	// app is the viewer's application, when the viewer is a bot
	app      *store.Application
	animated map[ids.ID]bool
	users    map[ids.ID]*store.User
}

func (s *Server) builder(ctx context.Context, viewer *store.User, app *store.Application) *dBuilder {
	return &dBuilder{s: s, ctx: ctx, viewer: viewer, app: app, animated: map[ids.ID]bool{}, users: map[ids.ID]*store.User{}}
}

// builderFor is the builder for a bot API request.
func (s *Server) builderFor(r interface{ Context() context.Context }, b *botCtx) *dBuilder {
	return s.builder(r.Context(), b.user, b.app)
}

// preloadBlobs learns at once whether each of the pictures moves.
func (b *dBuilder) preloadBlobs(list []ids.ID) {
	var want []ids.ID
	for _, id := range list {
		if _, ok := b.animated[id]; !ok && !id.IsZero() {
			want = append(want, id)
		}
	}
	if len(want) == 0 {
		return
	}
	got, err := b.s.Store.BlobsAnimated(b.ctx, want)
	if err != nil {
		return
	}
	for _, id := range want {
		b.animated[id] = got[id]
	}
}

// hash is a picture's hash, or nil for none.
func (b *dBuilder) hash(blob *ids.ID) *string {
	if blob == nil || blob.IsZero() {
		return nil
	}
	animated, ok := b.animated[*blob]
	if !ok {
		b.preloadBlobs([]ids.ID{*blob})
		animated = b.animated[*blob]
	}
	h := blob.String()
	if animated {
		h = "a_" + h
	}
	return &h
}

// preloadUsers reads the listed accounts at once.
func (b *dBuilder) preloadUsers(list []ids.ID) {
	var want []ids.ID
	for _, id := range list {
		if _, ok := b.users[id]; !ok && !id.IsZero() {
			want = append(want, id)
		}
	}
	if len(want) == 0 {
		return
	}
	got, err := b.s.Store.UsersByIDs(b.ctx, want)
	if err != nil {
		return
	}
	var blobs []ids.ID
	for id, u := range got {
		b.users[id] = u
		if u.AvatarID != nil {
			blobs = append(blobs, *u.AvatarID)
		}
		if u.BannerID != nil {
			blobs = append(blobs, *u.BannerID)
		}
	}
	b.preloadBlobs(blobs)
}

// user is an account, or nil when it does not exist.
func (b *dBuilder) user(id ids.ID) *store.User {
	if u, ok := b.users[id]; ok {
		return u
	}
	b.preloadUsers([]ids.ID{id})
	return b.users[id]
}

// --- users ---------------------------------------------------------------------------

type dPrimaryGuild struct {
	IdentityGuildID *snow   `json:"identity_guild_id"`
	IdentityEnabled *bool   `json:"identity_enabled"`
	Tag             *string `json:"tag"`
	Badge           *string `json:"badge"`
}

type dUser struct {
	ID                   snow           `json:"id"`
	Username             string         `json:"username"`
	Discriminator        string         `json:"discriminator"`
	GlobalName           *string        `json:"global_name"`
	Avatar               *string        `json:"avatar"`
	Bot                  bool           `json:"bot,omitempty"`
	System               bool           `json:"system,omitempty"`
	Banner               *string        `json:"banner"`
	AccentColor          *int           `json:"accent_color"`
	PublicFlags          int64          `json:"public_flags"`
	Flags                int64          `json:"flags"`
	AvatarDecorationData any            `json:"avatar_decoration_data"`
	Collectibles         any            `json:"collectibles"`
	PrimaryGuild         *dPrimaryGuild `json:"primary_guild"`
	// what only the account itself is told (GET /users/@me)
	MFAEnabled  *bool   `json:"mfa_enabled,omitempty"`
	Locale      string  `json:"locale,omitempty"`
	Verified    *bool   `json:"verified,omitempty"`
	Email       *string `json:"email,omitempty"`
	PremiumType *int    `json:"premium_type,omitempty"`
	Bio         *string `json:"bio,omitempty"`
}

// Discord's public user flags that mean something here: a bot whose
// interactions go to an address of its own, rather than its gateway.
const userFlagBotHTTPInteractions = 1 << 19

// userObj is an account as the protocol shows it.
func (b *dBuilder) userObj(u *store.User) dUser {
	out := dUser{ID: snow(u.ID), Username: u.Username, Discriminator: "0", Avatar: b.hash(u.AvatarID), Banner: b.hash(u.BannerID), AccentColor: u.AccentColor}
	if u.Flags&store.FlagDeleted != 0 {
		out.Username, out.Avatar, out.Banner = "deleted_user_"+strconv.FormatInt(int64(u.ID)%1_000_000_000, 36), nil, nil
	} else if u.DisplayName != "" && u.DisplayName != u.Username {
		name := u.DisplayName
		out.GlobalName = &name
	}
	switch {
	case u.Flags&store.FlagBot != 0:
		out.Bot = true
	case u.Flags&store.FlagSystem != 0:
		out.System = true
		out.Discriminator = "0000"
	case u.Flags&store.FlagWebhook != 0:
		out.Bot = true
		out.Discriminator = "0000"
	}
	if u.Flags&store.FlagBot != 0 {
		if app, err := b.appOf(u.ID); err == nil && app.InteractionsURL != "" {
			out.PublicFlags |= userFlagBotHTTPInteractions
		}
	}
	out.Flags = out.PublicFlags
	if t := u.WornTag(); t != nil {
		enabled := true
		gid := snow(t.ServerID)
		text := t.Text
		pg := &dPrimaryGuild{IdentityGuildID: &gid, IdentityEnabled: &enabled, Tag: &text}
		if t.Badge != "" {
			badge := t.Badge
			pg.Badge = &badge
		}
		out.PrimaryGuild = pg
	}
	return out
}

// userByID is userObj for an id, standing in a deleted account for one gone.
func (b *dBuilder) userByID(id ids.ID) dUser {
	if u := b.user(id); u != nil {
		return b.userObj(u)
	}
	return dUser{ID: snow(id), Username: "deleted_user", Discriminator: "0"}
}

// appOf is the application of a bot account, looked up once per builder.
func (b *dBuilder) appOf(botID ids.ID) (*store.Application, error) {
	if b.app != nil && b.app.ID == botID {
		return b.app, nil
	}
	return b.s.Store.ApplicationByID(b.ctx, botID)
}

// --- members ---------------------------------------------------------------------------

type dMember struct {
	User                       *dUser  `json:"user,omitempty"`
	Nick                       *string `json:"nick"`
	Avatar                     *string `json:"avatar"`
	Banner                     *string `json:"banner"`
	Roles                      []snow  `json:"roles"`
	JoinedAt                   string  `json:"joined_at"`
	PremiumSince               *string `json:"premium_since"`
	Deaf                       bool    `json:"deaf"`
	Mute                       bool    `json:"mute"`
	Flags                      int64   `json:"flags"`
	Pending                    bool    `json:"pending"`
	CommunicationDisabledUntil *string `json:"communication_disabled_until"`
	AvatarDecorationData       any     `json:"avatar_decoration_data"`
	Collectibles               any     `json:"collectibles"`
	// in an interaction's member and in resolved members: what they may do
	Permissions *string `json:"permissions,omitempty"`
}

// Discord's member flags Nexa's member flags map onto.
const memberFlagAutomodQuarantined = 128

// memberObj is a membership; withUser includes the account itself.
func (b *dBuilder) memberObj(m *store.Member, withUser bool) dMember {
	out := dMember{Avatar: b.hash(m.AvatarID), Banner: b.hash(m.BannerID), Roles: botproto.Snows(m.RoleIDs), JoinedAt: botproto.Timestamp(m.JoinedAt),
		Deaf: m.Flags&store.MemberVoiceDeafened != 0, Mute: m.Flags&store.MemberVoiceMuted != 0}
	if out.Roles == nil {
		out.Roles = []snow{}
	}
	if m.Nickname != "" {
		nick := m.Nickname
		out.Nick = &nick
	}
	if m.TimeoutUntil != nil && *m.TimeoutUntil > time.Now().UnixMilli() {
		out.CommunicationDisabledUntil = botproto.TimestampP(m.TimeoutUntil)
	}
	if m.Flags&store.MemberQuarantined != 0 {
		out.Flags |= memberFlagAutomodQuarantined
	}
	if withUser {
		u := b.userByID(m.UserID)
		out.User = &u
	}
	return out
}

// preloadMembers prepares the accounts and pictures of a list of members.
func (b *dBuilder) preloadMembers(list []*store.Member) {
	uids := make([]ids.ID, 0, len(list))
	var blobs []ids.ID
	for _, m := range list {
		uids = append(uids, m.UserID)
		if m.AvatarID != nil {
			blobs = append(blobs, *m.AvatarID)
		}
		if m.BannerID != nil {
			blobs = append(blobs, *m.BannerID)
		}
	}
	b.preloadUsers(uids)
	b.preloadBlobs(blobs)
}

// --- roles ------------------------------------------------------------------------------

type dRoleColors struct {
	Primary   int  `json:"primary_color"`
	Secondary *int `json:"secondary_color"`
	Tertiary  *int `json:"tertiary_color"`
}

type dRoleTags struct {
	BotID *snow `json:"bot_id,omitempty"`
}

type dRole struct {
	ID           snow        `json:"id"`
	Name         string      `json:"name"`
	Color        int         `json:"color"`
	Colors       dRoleColors `json:"colors"`
	Hoist        bool        `json:"hoist"`
	Icon         *string     `json:"icon"`
	UnicodeEmoji *string     `json:"unicode_emoji"`
	Position     int         `json:"position"`
	Permissions  string      `json:"permissions"`
	Managed      bool        `json:"managed"`
	Mentionable  bool        `json:"mentionable"`
	Tags         *dRoleTags  `json:"tags,omitempty"`
	Flags        int         `json:"flags"`
}

func (b *dBuilder) roleObj(r *store.Role) dRole {
	out := dRole{ID: snow(r.ID), Name: r.Name, Color: r.Color, Colors: dRoleColors{Primary: r.Color, Secondary: r.Color2, Tertiary: r.Color3},
		Hoist: r.Hoist, Icon: b.hash(r.IconID), Position: r.Position, Permissions: permString(r.Permissions), Mentionable: r.Mentionable}
	if r.IconEmoji != "" && !strings.HasPrefix(r.IconEmoji, "custom:") {
		e := r.IconEmoji
		out.UnicodeEmoji = &e
	}
	if r.ManagedBy != nil {
		out.Managed = true
		bot := snow(*r.ManagedBy)
		out.Tags = &dRoleTags{BotID: &bot}
	}
	return out
}

func (b *dBuilder) rolesObj(list []*store.Role) []dRole {
	out := make([]dRole, 0, len(list))
	for _, r := range list {
		out = append(out, b.roleObj(r))
	}
	return out
}

// --- channels -------------------------------------------------------------------------

// Channel types as the protocol numbers them.
const (
	dChanText               = 0
	dChanDM                 = 1
	dChanVoice              = 2
	dChanGroupDM            = 3
	dChanCategory           = 4
	dChanAnnouncement       = 5
	dChanAnnouncementThread = 10
	dChanPublicThread       = 11
	dChanPrivateThread      = 12
	dChanStage              = 13
	dChanForum              = 15
	dChanMedia              = 16
)

// channelType is a channel's type as the protocol numbers it; ok is false
// for a kind bots never see (a subchat of a direct message).
func (b *dBuilder) channelType(ch *store.Channel) (int, bool) {
	switch ch.Type {
	case "text":
		return dChanText, true
	case "dm":
		return dChanDM, true
	case "voice":
		return dChanVoice, true
	case "group_dm":
		return dChanGroupDM, true
	case "category":
		return dChanCategory, true
	case store.ChannelAnnouncement:
		return dChanAnnouncement, true
	case store.ChannelForum:
		return dChanForum, true
	case store.ChannelMedia:
		return dChanMedia, true
	case store.ChannelPrivateThread:
		return dChanPrivateThread, true
	case store.ChannelThread:
		if ch.ParentID != nil {
			if parent, err := b.s.Store.ChannelByID(b.ctx, *ch.ParentID); err == nil && parent.Type == store.ChannelAnnouncement {
				return dChanAnnouncementThread, true
			}
		}
		return dChanPublicThread, true
	}
	return 0, false
}

// nexaChannelType is the Nexa type a protocol channel type makes.
func nexaChannelType(t int) (string, bool) {
	switch t {
	case dChanText:
		return "text", true
	case dChanVoice, dChanStage:
		return "voice", true
	case dChanCategory:
		return "category", true
	case dChanAnnouncement:
		return store.ChannelAnnouncement, true
	case dChanForum:
		return store.ChannelForum, true
	case dChanMedia:
		return store.ChannelMedia, true
	case dChanPublicThread, dChanAnnouncementThread:
		return store.ChannelThread, true
	case dChanPrivateThread:
		return store.ChannelPrivateThread, true
	}
	return "", false
}

type dOverwrite struct {
	ID    snow   `json:"id"`
	Type  int    `json:"type"`
	Allow string `json:"allow"`
	Deny  string `json:"deny"`
}

type dThreadMetadata struct {
	Archived            bool   `json:"archived"`
	AutoArchiveDuration int    `json:"auto_archive_duration"`
	ArchiveTimestamp    string `json:"archive_timestamp"`
	Locked              bool   `json:"locked"`
	Invitable           *bool  `json:"invitable,omitempty"`
	CreateTimestamp     string `json:"create_timestamp"`
}

type dThreadMember struct {
	ID            *snow    `json:"id,omitempty"`
	UserID        *snow    `json:"user_id,omitempty"`
	JoinTimestamp string   `json:"join_timestamp"`
	Flags         int      `json:"flags"`
	Member        *dMember `json:"member,omitempty"`
}

type dForumTag struct {
	ID        snow    `json:"id"`
	Name      string  `json:"name"`
	Moderated bool    `json:"moderated"`
	EmojiID   *snow   `json:"emoji_id"`
	EmojiName *string `json:"emoji_name"`
}

type dDefaultReaction struct {
	EmojiID   *snow   `json:"emoji_id"`
	EmojiName *string `json:"emoji_name"`
}

// dChannel is every kind of channel; which fields mean anything depends on
// its type, as in the protocol.
type dChannel struct {
	ID                            snow              `json:"id"`
	Type                          int               `json:"type"`
	GuildID                       *snow             `json:"guild_id,omitempty"`
	Position                      *int              `json:"position,omitempty"`
	PermissionOverwrites          []dOverwrite      `json:"permission_overwrites,omitempty"`
	Name                          *string           `json:"name"`
	Topic                         *string           `json:"topic,omitempty"`
	NSFW                          *bool             `json:"nsfw,omitempty"`
	LastMessageID                 *snow             `json:"last_message_id,omitempty"`
	Bitrate                       *int              `json:"bitrate,omitempty"`
	UserLimit                     *int              `json:"user_limit,omitempty"`
	RateLimitPerUser              *int              `json:"rate_limit_per_user,omitempty"`
	Recipients                    []dUser           `json:"recipients,omitempty"`
	Icon                          *string           `json:"icon,omitempty"`
	OwnerID                       *snow             `json:"owner_id,omitempty"`
	ApplicationID                 *snow             `json:"application_id,omitempty"`
	ParentID                      *snow             `json:"parent_id,omitempty"`
	LastPinTimestamp              *string           `json:"last_pin_timestamp,omitempty"`
	RTCRegion                     *string           `json:"rtc_region,omitempty"`
	VideoQualityMode              *int              `json:"video_quality_mode,omitempty"`
	MessageCount                  *int              `json:"message_count,omitempty"`
	MemberCount                   *int              `json:"member_count,omitempty"`
	ThreadMetadata                *dThreadMetadata  `json:"thread_metadata,omitempty"`
	Member                        *dThreadMember    `json:"member,omitempty"`
	DefaultAutoArchiveDuration    *int              `json:"default_auto_archive_duration,omitempty"`
	Permissions                   *string           `json:"permissions,omitempty"`
	Flags                         int               `json:"flags"`
	TotalMessageSent              *int              `json:"total_message_sent,omitempty"`
	AvailableTags                 []dForumTag       `json:"available_tags,omitempty"`
	AppliedTags                   []snow            `json:"applied_tags,omitempty"`
	DefaultReactionEmoji          *dDefaultReaction `json:"default_reaction_emoji,omitempty"`
	DefaultThreadRateLimitPerUser *int              `json:"default_thread_rate_limit_per_user,omitempty"`
	DefaultSortOrder              *int              `json:"default_sort_order,omitempty"`
	DefaultForumLayout            *int              `json:"default_forum_layout,omitempty"`
}

// Channel flags.
const (
	dChanFlagPinned     = 1 << 1
	dChanFlagRequireTag = 1 << 4
	// dChanFlagHideMediaDownloads is a media channel that hides the download options on its media.
	dChanFlagHideMediaDownloads = 1 << 15
)

func intp(v int) *int { return &v }
func strp(v string) *string {
	return &v
}
func boolp(v bool) *bool { return &v }

// channelObj is a channel as the protocol shows it; ok is false for one
// bots never see.
func (b *dBuilder) channelObj(ch *store.Channel) (dChannel, bool) {
	t, ok := b.channelType(ch)
	if !ok {
		return dChannel{}, false
	}
	out := dChannel{ID: snow(ch.ID), Type: t, LastMessageID: botproto.SP(ch.LastMessageID)}
	if ch.ServerID != nil {
		gid := snow(*ch.ServerID)
		out.GuildID = &gid
		name := ch.Name
		out.Name = &name
		out.NSFW = boolp(ch.NSFW)
		out.ParentID = botproto.SP(ch.ParentID)
		if !store.IsThread(ch.Type) {
			out.Position = intp(ch.Position)
			out.PermissionOverwrites = make([]dOverwrite, 0, len(ch.Overwrites))
			for _, o := range ch.Overwrites {
				typ := 0
				if o.TargetType == "member" {
					typ = 1
				}
				out.PermissionOverwrites = append(out.PermissionOverwrites, dOverwrite{ID: snow(o.TargetID), Type: typ, Allow: permString(o.Allow), Deny: permString(o.Deny)})
			}
		}
	}
	switch t {
	case dChanText, dChanAnnouncement, dChanForum, dChanMedia:
		topic := ch.Topic
		out.Topic = &topic
		out.RateLimitPerUser = intp(ch.SlowmodeSeconds)
		archive := ch.DefaultAutoArchive
		if archive == 0 {
			archive = 4320
		}
		out.DefaultAutoArchiveDuration = &archive
		out.DefaultThreadRateLimitPerUser = intp(0)
	case dChanVoice:
		out.Bitrate = intp(64000)
		out.UserLimit = intp(ch.UserLimit)
		out.RateLimitPerUser = intp(ch.SlowmodeSeconds)
		out.VideoQualityMode = intp(1)
		var region *string
		out.RTCRegion = region
	case dChanDM, dChanGroupDM:
		out.Recipients = make([]dUser, 0, len(ch.Recipients))
		b.preloadUsers(ch.Recipients)
		for _, id := range ch.Recipients {
			if b.viewer != nil && id == b.viewer.ID {
				continue
			}
			out.Recipients = append(out.Recipients, b.userByID(id))
		}
		if t == dChanDM {
			out.Name = nil
		} else {
			name := ch.Name
			out.Name = &name
			out.Icon = b.hash(ch.IconID)
			out.OwnerID = botproto.SP(ch.OwnerID)
		}
	case dChanPublicThread, dChanPrivateThread, dChanAnnouncementThread:
		out.RateLimitPerUser = intp(ch.SlowmodeSeconds)
		out.OwnerID = botproto.SP(ch.OwnerID)
		if th := ch.Thread; th != nil {
			archivedAt := ch.CreatedAt
			if th.ArchivedAt != nil {
				archivedAt = *th.ArchivedAt
			}
			auto := th.AutoArchive
			if auto == 0 {
				auto = 4320
			}
			out.ThreadMetadata = &dThreadMetadata{Archived: th.Archived, AutoArchiveDuration: auto, ArchiveTimestamp: botproto.Timestamp(archivedAt),
				Locked: th.Locked, CreateTimestamp: botproto.Timestamp(ch.CreatedAt)}
			if t == dChanPrivateThread {
				out.ThreadMetadata.Invitable = boolp(true)
			}
			out.MessageCount = intp(th.Messages)
			out.TotalMessageSent = intp(th.Messages)
			out.AppliedTags = botproto.Snows(th.AppliedTags)
		}
		if n, err := b.s.Store.ThreadMemberIDs(b.ctx, ch.ID); err == nil {
			out.MemberCount = intp(min(len(n), 50))
			if b.viewer != nil {
				for _, uid := range n {
					if uid == b.viewer.ID {
						vid := snow(b.viewer.ID)
						tid := snow(ch.ID)
						out.Member = &dThreadMember{ID: &tid, UserID: &vid, JoinTimestamp: botproto.Timestamp(ch.CreatedAt), Flags: 1}
						break
					}
				}
			}
		}
	}
	if t == dChanForum || t == dChanMedia {
		out.Topic = nil
		topic := ch.Topic
		out.Topic = &topic
		out.AvailableTags = []dForumTag{}
		layout, sortOrder := 0, 0
		if f := ch.Forum; f != nil {
			for _, tag := range f.Tags {
				dt := dForumTag{ID: snow(tag.ID), Name: tag.Name, Moderated: tag.Moderated}
				b.emojiFields(tag.Emoji, &dt.EmojiID, &dt.EmojiName)
				out.AvailableTags = append(out.AvailableTags, dt)
			}
			if f.DefaultReaction != "" {
				dr := &dDefaultReaction{}
				b.emojiFields(f.DefaultReaction, &dr.EmojiID, &dr.EmojiName)
				out.DefaultReactionEmoji = dr
			}
			switch f.Layout {
			case "list":
				layout = 1
			case "gallery":
				layout = 2
			}
			if f.Sort == "created" || f.Sort == "creation" {
				sortOrder = 1
			}
			if f.RequireTag {
				out.Flags |= dChanFlagRequireTag
			}
			if t == dChanMedia && f.HideDownloads {
				out.Flags |= dChanFlagHideMediaDownloads
			}
		}
		out.DefaultForumLayout = &layout
		out.DefaultSortOrder = &sortOrder
	}
	if store.IsThread(ch.Type) && ch.ParentID != nil {
		if parent, err := b.s.Store.ChannelByID(b.ctx, *ch.ParentID); err == nil && parent.Forum != nil && parent.Forum.PinnedPost != nil && *parent.Forum.PinnedPost == ch.ID {
			out.Flags |= dChanFlagPinned
		}
	}
	return out, true
}

// emojiFields writes an emoji kept as Nexa writes them (a character, or
// "custom:<blob>" or "<:name:blob>") into the protocol's id and name pair.
func (b *dBuilder) emojiFields(e string, id **snow, name **string) {
	if e == "" {
		return
	}
	if rest, ok := strings.CutPrefix(e, "custom:"); ok {
		if n, err := ids.Parse(rest); err == nil {
			sid := snow(n)
			*id = &sid
			return
		}
	}
	if m := reEmojiToken.FindStringSubmatch(e); m != nil {
		if n, err := ids.Parse(m[3]); err == nil {
			sid := snow(n)
			*id = &sid
			nm := m[2]
			*name = &nm
			return
		}
	}
	v := e
	*name = &v
}

// channelsObj is the channels of a list a bot may see, leaving out the rest.
func (b *dBuilder) channelsObj(list []*store.Channel) []dChannel {
	out := make([]dChannel, 0, len(list))
	for _, ch := range list {
		if c, ok := b.channelObj(ch); ok {
			out = append(out, c)
		}
	}
	return out
}

func (b *dBuilder) threadMemberObj(tm store.ThreadMember, withMember *store.Member) dThreadMember {
	tid, uid := snow(tm.ThreadID), snow(tm.UserID)
	out := dThreadMember{ID: &tid, UserID: &uid, JoinTimestamp: botproto.Timestamp(tm.JoinedAt), Flags: 1}
	if withMember != nil {
		m := b.memberObj(withMember, true)
		out.Member = &m
	}
	return out
}

// --- emoji and stickers ------------------------------------------------------------------

type dEmoji struct {
	ID            *snow   `json:"id"`
	Name          *string `json:"name"`
	Roles         []snow  `json:"roles,omitempty"`
	User          *dUser  `json:"user,omitempty"`
	RequireColons bool    `json:"require_colons"`
	Managed       bool    `json:"managed"`
	Animated      bool    `json:"animated"`
	Available     bool    `json:"available"`
}

// emojiObj is a server's emoji. Its id is its picture's, which is what
// message text and reactions name it by.
func (b *dBuilder) emojiObj(e *store.Emoji, withUser bool) dEmoji {
	id := snow(e.BlobID)
	name := e.Name
	out := dEmoji{ID: &id, Name: &name, Roles: []snow{}, RequireColons: true, Animated: e.Animated, Available: true}
	if withUser {
		u := b.userByID(e.CreatorID)
		out.User = &u
	}
	return out
}

// appEmojiObj is an application's emoji.
func (b *dBuilder) appEmojiObj(e *store.ApplicationEmoji, app *store.Application) dEmoji {
	id := snow(e.BlobID)
	name := e.Name
	out := dEmoji{ID: &id, Name: &name, Roles: []snow{}, RequireColons: true, Animated: e.Animated, Available: true}
	u := b.userByID(app.ID)
	out.User = &u
	return out
}

// dPartialEmoji is an emoji as a reaction or a component names it.
type dPartialEmoji struct {
	ID       *snow   `json:"id"`
	Name     *string `json:"name"`
	Animated bool    `json:"animated,omitempty"`
}

// reactionEmoji turns a Nexa reaction key (a character, or a?:name:blob) into
// the protocol's partial emoji.
func reactionEmoji(key string) dPartialEmoji {
	if m := reReactionKey.FindStringSubmatch(key); m != nil {
		if n, err := ids.Parse(m[3]); err == nil {
			id := snow(n)
			name := m[2]
			return dPartialEmoji{ID: &id, Name: &name, Animated: m[1] == "a"}
		}
	}
	name := key
	return dPartialEmoji{Name: &name}
}

type dSticker struct {
	ID          snow    `json:"id"`
	PackID      *snow   `json:"pack_id,omitempty"`
	Name        string  `json:"name"`
	Description *string `json:"description"`
	Tags        string  `json:"tags"`
	Type        int     `json:"type"`
	FormatType  int     `json:"format_type"`
	Available   bool    `json:"available"`
	GuildID     *snow   `json:"guild_id,omitempty"`
	User        *dUser  `json:"user,omitempty"`
	SortValue   *int    `json:"sort_value,omitempty"`
}

type dStickerItem struct {
	ID         snow   `json:"id"`
	Name       string `json:"name"`
	FormatType int    `json:"format_type"`
}

// stickerFormatType numbers a sticker's picture format as the protocol does:
// 1 PNG, 2 APNG, 3 Lottie, 4 GIF. WebP has no number; it is served as PNG.
func stickerFormatType(format string) int {
	switch format {
	case "apng":
		return 2
	case "gif":
		return 4
	}
	return 1
}

// stickerObj is a sticker of a server's pack (type 2, a guild sticker) or
// of anyone's pack (type 1, standard: not owned by a server).
func (b *dBuilder) stickerObj(st *store.Sticker, pack *store.StickerPack) dSticker {
	out := dSticker{ID: snow(st.ID), Name: st.Name, Tags: st.Tags, Type: 1, FormatType: stickerFormatType(st.Format), Available: true}
	if out.Tags == "" {
		out.Tags = st.Name
	}
	if pack != nil && pack.ServerID != nil {
		out.Type = 2
		gid := snow(*pack.ServerID)
		out.GuildID = &gid
		u := b.userByID(st.CreatorID)
		out.User = &u
	} else {
		pid := snow(st.PackID)
		out.PackID = &pid
		out.SortValue = intp(st.Position)
	}
	return out
}

// --- guilds -------------------------------------------------------------------------------

type dIncidents struct {
	InvitesDisabledUntil *string `json:"invites_disabled_until"`
	DMsDisabledUntil     *string `json:"dms_disabled_until"`
	DMSpamDetectedAt     *string `json:"dm_spam_detected_at"`
	RaidDetectedAt       *string `json:"raid_detected_at"`
}

type dGuild struct {
	ID                          snow        `json:"id"`
	Name                        string      `json:"name"`
	Icon                        *string     `json:"icon"`
	IconHash                    *string     `json:"icon_hash,omitempty"`
	Splash                      *string     `json:"splash"`
	DiscoverySplash             *string     `json:"discovery_splash"`
	Owner                       *bool       `json:"owner,omitempty"`
	OwnerID                     snow        `json:"owner_id"`
	Permissions                 *string     `json:"permissions,omitempty"`
	Region                      *string     `json:"region"`
	AFKChannelID                *snow       `json:"afk_channel_id"`
	AFKTimeout                  int         `json:"afk_timeout"`
	WidgetEnabled               bool        `json:"widget_enabled"`
	WidgetChannelID             *snow       `json:"widget_channel_id"`
	VerificationLevel           int         `json:"verification_level"`
	DefaultMessageNotifications int         `json:"default_message_notifications"`
	ExplicitContentFilter       int         `json:"explicit_content_filter"`
	Roles                       []dRole     `json:"roles"`
	Emojis                      []dEmoji    `json:"emojis"`
	Features                    []string    `json:"features"`
	MFALevel                    int         `json:"mfa_level"`
	ApplicationID               *snow       `json:"application_id"`
	SystemChannelID             *snow       `json:"system_channel_id"`
	SystemChannelFlags          int         `json:"system_channel_flags"`
	RulesChannelID              *snow       `json:"rules_channel_id"`
	MaxPresences                *int        `json:"max_presences"`
	MaxMembers                  int         `json:"max_members"`
	VanityURLCode               *string     `json:"vanity_url_code"`
	Description                 *string     `json:"description"`
	Banner                      *string     `json:"banner"`
	PremiumTier                 int         `json:"premium_tier"`
	PremiumSubscriptionCount    int         `json:"premium_subscription_count"`
	PreferredLocale             string      `json:"preferred_locale"`
	PublicUpdatesChannelID      *snow       `json:"public_updates_channel_id"`
	MaxVideoChannelUsers        int         `json:"max_video_channel_users"`
	MaxStageVideoChannelUsers   int         `json:"max_stage_video_channel_users"`
	ApproximateMemberCount      *int        `json:"approximate_member_count,omitempty"`
	ApproximatePresenceCount    *int        `json:"approximate_presence_count,omitempty"`
	NSFWLevel                   int         `json:"nsfw_level"`
	Stickers                    []dSticker  `json:"stickers"`
	PremiumProgressBarEnabled   bool        `json:"premium_progress_bar_enabled"`
	HubType                     *int        `json:"hub_type"`
	SafetyAlertsChannelID       *snow       `json:"safety_alerts_channel_id"`
	IncidentsData               *dIncidents `json:"incidents_data"`
	NSFW                        bool        `json:"nsfw"`
}

// verificationLevel reads Nexa's waits as the protocol's levels: a wait for
// new accounts as Medium (registered for five minutes), a wait for new
// members as High (a member for ten).
func verificationLevel(srv *store.Server) int {
	switch {
	case srv.VerifyMemberMinutes > 0:
		return 3
	case srv.VerifyAccountMinutes > 0:
		return 2
	}
	return 0
}

// guildFeatures are what a server can do here, by the protocol's names.
func guildFeatures(srv *store.Server) []string {
	out := []string{"AUTO_MODERATION", "ROLE_ICONS", "PRIVATE_THREADS", "NEWS", "ANIMATED_ICON", "ANIMATED_BANNER", "BANNER", "ENHANCED_ROLE_COLORS", "APPLICATION_COMMAND_PERMISSIONS_V2"}
	if srv.Discoverable && !srv.DiscoveryHidden {
		out = append(out, "COMMUNITY", "DISCOVERABLE")
	}
	if srv.Tag != nil && !srv.NSFW {
		out = append(out, "GUILD_TAGS")
	}
	if srv.InvitesPausedUntil != nil && *srv.InvitesPausedUntil > time.Now().UnixMilli() {
		out = append(out, "INVITES_DISABLED")
	}
	return out
}

// guildObj is a server as the protocol shows it, with its roles, emoji and
// stickers.
func (b *dBuilder) guildObj(srv *store.Server) dGuild {
	out := dGuild{ID: snow(srv.ID), Name: srv.Name, Icon: b.hash(srv.IconID), OwnerID: snow(srv.OwnerID), AFKTimeout: 300,
		VerificationLevel: verificationLevel(srv), DefaultMessageNotifications: srv.DefaultNotifications, Features: guildFeatures(srv),
		SystemChannelID: botproto.SP(srv.SystemChannelID), MaxMembers: 500000, Banner: b.hash(srv.BannerID), PreferredLocale: "en-US",
		MaxVideoChannelUsers: 25, MaxStageVideoChannelUsers: 50, Roles: []dRole{}, Emojis: []dEmoji{}, Stickers: []dSticker{}}
	out.NSFW = srv.NSFW
	if srv.NSFW {
		out.NSFWLevel = 1
	}
	out.IconHash = out.Icon
	if srv.Description != "" {
		d := srv.Description
		out.Description = &d
	}
	if srv.InvitesPausedUntil != nil && *srv.InvitesPausedUntil > time.Now().UnixMilli() {
		out.IncidentsData = &dIncidents{InvitesDisabledUntil: botproto.TimestampP(srv.InvitesPausedUntil)}
	}
	if roles, err := b.s.Store.Roles(b.ctx, srv.ID); err == nil {
		var blobs []ids.ID
		for _, r := range roles {
			if r.IconID != nil {
				blobs = append(blobs, *r.IconID)
			}
		}
		b.preloadBlobs(blobs)
		out.Roles = b.rolesObj(roles)
	}
	if emojis, err := b.s.Store.ServerEmojis(b.ctx, srv.ID); err == nil {
		for _, e := range emojis {
			out.Emojis = append(out.Emojis, b.emojiObj(e, false))
		}
	}
	out.Stickers = b.guildStickers(srv.ID)
	if b.viewer != nil {
		if p, err := b.s.Store.ServerPerms(b.ctx, srv.ID, b.viewer.ID); err == nil {
			ps := permString(p)
			out.Permissions = &ps
		}
		owner := srv.OwnerID == b.viewer.ID
		out.Owner = &owner
	}
	return out
}

// guildStickers are the stickers of a server's own packs.
func (b *dBuilder) guildStickers(serverID ids.ID) []dSticker {
	out := []dSticker{}
	packs, err := b.s.Store.ServerPacks(b.ctx, serverID)
	if err != nil {
		return out
	}
	for _, p := range packs {
		if p.Kind == "emoji" {
			continue
		}
		for _, st := range p.Stickers {
			out = append(out, b.stickerObj(st, p))
		}
	}
	return out
}

// --- invites, bans, webhooks ---------------------------------------------------------------

type dInvite struct {
	Type                     int       `json:"type"`
	Code                     string    `json:"code"`
	Guild                    any       `json:"guild,omitempty"`
	Channel                  *dChannel `json:"channel"`
	Inviter                  *dUser    `json:"inviter,omitempty"`
	ApproximateMemberCount   *int      `json:"approximate_member_count,omitempty"`
	ApproximatePresenceCount *int      `json:"approximate_presence_count,omitempty"`
	ExpiresAt                *string   `json:"expires_at"`
	// metadata, for those who manage invites
	Uses      *int    `json:"uses,omitempty"`
	MaxUses   *int    `json:"max_uses,omitempty"`
	MaxAge    *int    `json:"max_age,omitempty"`
	Temporary *bool   `json:"temporary,omitempty"`
	CreatedAt *string `json:"created_at,omitempty"`
}

// inviteObj is an invite; withMeta adds what managers see.
func (b *dBuilder) inviteObj(inv *store.Invite, withMeta bool) dInvite {
	out := dInvite{Code: inv.Code, ExpiresAt: botproto.TimestampP(inv.ExpiresAt)}
	u := b.userByID(inv.InviterID)
	out.Inviter = &u
	if inv.ChannelID != nil {
		if ch, err := b.s.Store.ChannelByID(b.ctx, *inv.ChannelID); err == nil {
			if c, ok := b.channelObj(ch); ok {
				out.Channel = &c
			}
			if inv.ServerID == nil {
				out.Type = 1
			}
		}
	}
	if inv.ServerID != nil {
		if srv, err := b.s.Store.ServerByID(b.ctx, *inv.ServerID); err == nil {
			nsfwLevel := 0
			if srv.NSFW {
				nsfwLevel = 1
			}
			out.Guild = map[string]any{"id": snow(srv.ID), "name": srv.Name, "icon": b.hash(srv.IconID), "splash": nil, "banner": b.hash(srv.BannerID),
				"description": srv.Description, "features": guildFeatures(srv), "verification_level": verificationLevel(srv), "vanity_url_code": nil, "nsfw_level": nsfwLevel, "nsfw": srv.NSFW, "premium_subscription_count": 0}
		}
	}
	if withMeta {
		maxAge := 0
		if inv.ExpiresAt != nil {
			maxAge = int((*inv.ExpiresAt - inv.CreatedAt) / 1000)
		}
		out.Uses, out.MaxUses, out.MaxAge = intp(inv.Uses), intp(inv.MaxUses), &maxAge
		out.Temporary = boolp(false)
		created := botproto.Timestamp(inv.CreatedAt)
		out.CreatedAt = &created
	}
	return out
}

type dBan struct {
	Reason *string `json:"reason"`
	User   dUser   `json:"user"`
}

func (b *dBuilder) banObj(ban *store.Ban) dBan {
	out := dBan{User: b.userByID(ban.UserID)}
	if ban.Reason != "" {
		r := ban.Reason
		out.Reason = &r
	}
	return out
}

// --- voice and presence ----------------------------------------------------------------------

type dVoiceState struct {
	GuildID                 *snow    `json:"guild_id,omitempty"`
	ChannelID               *snow    `json:"channel_id"`
	UserID                  snow     `json:"user_id"`
	Member                  *dMember `json:"member,omitempty"`
	SessionID               string   `json:"session_id"`
	Deaf                    bool     `json:"deaf"`
	Mute                    bool     `json:"mute"`
	SelfDeaf                bool     `json:"self_deaf"`
	SelfMute                bool     `json:"self_mute"`
	SelfStream              bool     `json:"self_stream,omitempty"`
	SelfVideo               bool     `json:"self_video"`
	Suppress                bool     `json:"suppress"`
	RequestToSpeakTimestamp *string  `json:"request_to_speak_timestamp"`
}

// voiceStateObj is someone in a call; channel nil says they left it.
func (b *dBuilder) voiceStateObj(guild *ids.ID, channel *ids.ID, userID, session ids.ID, selfMute, selfDeaf, video, stream, serverMute, serverDeaf bool, withMember bool) dVoiceState {
	out := dVoiceState{GuildID: botproto.SP(guild), ChannelID: botproto.SP(channel), UserID: snow(userID), SessionID: session.String(),
		Deaf: serverDeaf, Mute: serverMute, SelfDeaf: selfDeaf, SelfMute: selfMute, SelfVideo: video, SelfStream: stream}
	if withMember && guild != nil {
		if m, err := b.s.Store.Member(b.ctx, *guild, userID); err == nil {
			dm := b.memberObj(m, true)
			out.Member = &dm
		}
	}
	return out
}

type dActivity struct {
	Name          string         `json:"name"`
	Type          int            `json:"type"`
	URL           *string        `json:"url,omitempty"`
	CreatedAt     int64          `json:"created_at"`
	Timestamps    map[string]any `json:"timestamps,omitempty"`
	ApplicationID *string        `json:"application_id,omitempty"`
	Details       *string        `json:"details,omitempty"`
	State         *string        `json:"state,omitempty"`
	Emoji         *dPartialEmoji `json:"emoji,omitempty"`
	Party         map[string]any `json:"party,omitempty"`
	Assets        map[string]any `json:"assets,omitempty"`
	Instance      bool           `json:"instance,omitempty"`
	Buttons       []string       `json:"buttons,omitempty"`
}

type dPresence struct {
	User         map[string]any    `json:"user"`
	GuildID      *snow             `json:"guild_id,omitempty"`
	Status       string            `json:"status"`
	Activities   []dActivity       `json:"activities"`
	ClientStatus map[string]string `json:"client_status"`
}

// presenceObj is someone's presence: their status, their custom status as an
// activity of its own, and what they are doing.
func (b *dBuilder) presenceObj(p gateway.Presence, guild *ids.ID) dPresence {
	status := p.Status
	if status == "invisible" {
		status = "offline"
	}
	out := dPresence{User: map[string]any{"id": snow(p.UserID)}, GuildID: botproto.SP(guild), Status: status, Activities: []dActivity{}, ClientStatus: map[string]string{}}
	if status != "offline" {
		out.ClientStatus["desktop"] = status
	}
	if status == "offline" {
		return out
	}
	if p.StatusText != "" || p.StatusEmoji != "" {
		a := dActivity{Name: "Custom Status", Type: 4, CreatedAt: time.Now().UnixMilli()}
		if p.StatusText != "" {
			st := p.StatusText
			a.State = &st
		}
		if p.StatusEmoji != "" {
			e := reactionEmoji(p.StatusEmoji)
			a.Emoji = &e
		}
		out.Activities = append(out.Activities, a)
	}
	for _, act := range p.Activities {
		a := dActivity{Name: act.Name, Type: act.Type, CreatedAt: act.CreatedAt}
		if act.ApplicationID != "" {
			id := act.ApplicationID
			a.ApplicationID = &id
		}
		if act.Details != "" {
			d := act.Details
			a.Details = &d
		}
		if act.State != "" {
			st := act.State
			a.State = &st
		}
		if ts := act.Timestamps; ts != nil {
			a.Timestamps = map[string]any{}
			if ts.Start != nil {
				a.Timestamps["start"] = *ts.Start
			}
			if ts.End != nil {
				a.Timestamps["end"] = *ts.End
			}
		}
		if act.Party != nil {
			a.Party = map[string]any{}
			if act.Party.ID != "" {
				a.Party["id"] = act.Party.ID
			}
			if act.Party.Size != nil {
				a.Party["size"] = act.Party.Size
			}
		}
		if as := act.Assets; as != nil {
			a.Assets = map[string]any{}
			for k, v := range map[string]string{"large_image": as.LargeImage, "large_text": as.LargeText, "small_image": as.SmallImage, "small_text": as.SmallText} {
				if v != "" {
					a.Assets[k] = v
				}
			}
		}
		for _, btn := range act.Buttons {
			a.Buttons = append(a.Buttons, btn.Label)
		}
		a.Instance = act.Instance
		out.Activities = append(out.Activities, a)
	}
	return out
}

// --- applications -------------------------------------------------------------------------

type dInstallParams struct {
	Scopes      []string `json:"scopes"`
	Permissions string   `json:"permissions"`
}

type dApplication struct {
	ID                             snow                      `json:"id"`
	Name                           string                    `json:"name"`
	Icon                           *string                   `json:"icon"`
	Description                    string                    `json:"description"`
	RPCOrigins                     []string                  `json:"rpc_origins,omitempty"`
	BotPublic                      bool                      `json:"bot_public"`
	BotRequireCodeGrant            bool                      `json:"bot_require_code_grant"`
	Bot                            *dUser                    `json:"bot,omitempty"`
	TermsOfServiceURL              *string                   `json:"terms_of_service_url,omitempty"`
	PrivacyPolicyURL               *string                   `json:"privacy_policy_url,omitempty"`
	Owner                          *dUser                    `json:"owner,omitempty"`
	VerifyKey                      string                    `json:"verify_key"`
	Team                           any                       `json:"team"`
	GuildID                        *snow                     `json:"guild_id,omitempty"`
	CoverImage                     *string                   `json:"cover_image,omitempty"`
	Flags                          int64                     `json:"flags"`
	ApproximateGuildCount          *int                      `json:"approximate_guild_count,omitempty"`
	ApproximateUserInstallCount    *int                      `json:"approximate_user_install_count,omitempty"`
	RedirectURIs                   []string                  `json:"redirect_uris,omitempty"`
	InteractionsEndpointURL        *string                   `json:"interactions_endpoint_url"`
	RoleConnectionsVerificationURL *string                   `json:"role_connections_verification_url"`
	EventWebhooksURL               *string                   `json:"event_webhooks_url"`
	EventWebhooksStatus            int                       `json:"event_webhooks_status"`
	EventWebhooksTypes             []string                  `json:"event_webhooks_types,omitempty"`
	Tags                           []string                  `json:"tags,omitempty"`
	InstallParams                  *dInstallParams           `json:"install_params,omitempty"`
	IntegrationTypesConfig         map[string]map[string]any `json:"integration_types_config"`
	CustomInstallURL               *string                   `json:"custom_install_url,omitempty"`
	Summary                        string                    `json:"summary"`
	Hook                           bool                      `json:"hook"`
}

func strOrNil(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

// applicationObj is an application; full adds what only its owner and its
// bot are told.
func (b *dBuilder) applicationObj(app *store.Application, full bool) dApplication {
	out := dApplication{ID: snow(app.ID), Name: app.Name, Icon: b.hash(app.IconID), Description: app.Description, BotPublic: app.BotPublic,
		BotRequireCodeGrant: app.BotRequireCodeGrant, VerifyKey: app.VerifyKey, Flags: app.Flags, Tags: app.Tags, Summary: "", Hook: true,
		TermsOfServiceURL: strOrNil(app.TermsURL), PrivacyPolicyURL: strOrNil(app.PrivacyURL), CoverImage: b.hash(app.CoverID),
		InteractionsEndpointURL: strOrNil(app.InteractionsURL), RoleConnectionsVerificationURL: strOrNil(app.RoleConnectionsURL),
		CustomInstallURL: strOrNil(app.CustomInstallURL), IntegrationTypesConfig: map[string]map[string]any{}}
	if app.InstallParams != nil {
		out.InstallParams = &dInstallParams{Scopes: app.InstallParams.Scopes, Permissions: app.InstallParams.Permissions}
	}
	for k, v := range app.IntegrationTypes {
		entry := map[string]any{}
		if v.OAuth2InstallParams != nil {
			entry["oauth2_install_params"] = dInstallParams{Scopes: v.OAuth2InstallParams.Scopes, Permissions: v.OAuth2InstallParams.Permissions}
		}
		out.IntegrationTypesConfig[k] = entry
	}
	if bot := b.user(app.ID); bot != nil {
		u := b.userObj(bot)
		out.Bot = &u
	}
	if full {
		owner := b.userByID(app.OwnerID)
		out.Owner = &owner
		out.RedirectURIs = app.RedirectURIs
		if out.RedirectURIs == nil {
			out.RedirectURIs = []string{}
		}
		out.EventWebhooksURL = strOrNil(app.EventWebhooksURL)
		out.EventWebhooksStatus = app.EventWebhooksStatus
		out.EventWebhooksTypes = app.EventWebhooksTypes
		if n, err := b.s.Store.ApplicationGuildCount(b.ctx, app.ID); err == nil {
			out.ApproximateGuildCount = &n
		}
		if n, err := b.s.Store.ApplicationUserCount(b.ctx, app.ID); err == nil {
			out.ApproximateUserInstallCount = &n
		}
	}
	return out
}

// absoluteURL makes a path on this server an address bots can fetch.
func (s *Server) absoluteURL(path string) string {
	base := strings.TrimRight(s.Cfg.PublicURL, "/")
	if strings.HasPrefix(path, "http://") || strings.HasPrefix(path, "https://") {
		return path
	}
	return base + path
}

// queryValues is a request's query string, for handlers that pass it on.
func queryValues(pairs ...string) url.Values {
	v := url.Values{}
	for i := 0; i+1 < len(pairs); i += 2 {
		if pairs[i+1] != "" {
			v.Set(pairs[i], pairs[i+1])
		}
	}
	return v
}

var (
	// reEmojiToken is a custom emoji as text writes it: <name:blob>, <a:name:blob>.
	reEmojiToken = regexp.MustCompile(`^<(a)?:([A-Za-z0-9_]{1,32}):([0-9]{1,20})>$`)
	// reReactionKey is a custom emoji as a reaction key: name:blob, a:name:blob.
	reReactionKey = regexp.MustCompile(`^(a)?:?([A-Za-z0-9_]{1,32}):([0-9]{1,20})$`)
)
