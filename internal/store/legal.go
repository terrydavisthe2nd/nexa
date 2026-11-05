package store

import (
	"context"
	"database/sql"
	"errors"

	"evilion.xyz/chat/internal/ids"
)

// This file answers one question and only one: what does Nexa already
// hold about a single account? It is read-only by design. An operator served
// with legal process has to be able to say exactly what they have and exactly
// what they do not, and the honest way to do that is to read the rows back
// rather than to describe them from memory — a description drifts as the schema
// moves, a SELECT cannot.
//
// Nothing here writes, nothing here decrypts, and nothing here reaches for a
// column that is not already being stored for the product to work.

// LegalListCap is where a list in the record stops and says how many more there
// were. A report has to be readable by a person; a hundred thousand message ids
// would not be, and the counts above each list carry the whole truth anyway.
// The renderer needs the number too, to say when a list it printed was capped.
const LegalListCap = 500

// AccountRecord is every row Nexa holds that is keyed to one account,
// grouped by the table it came from. The credential columns of the user row are
// deliberately not copied into it: they are secrets that protect the account,
// they are not evidence about it, and a struct that never carries them cannot
// leak them.
type AccountRecord struct {
	// TakenAt is the instant the snapshot was read, in Unix milliseconds.
	TakenAt int64

	User *User // users

	Settings *LegalSettings // user_settings
	MFA      *LegalMFA      // user_mfa, mfa_backup_codes

	Sessions []LegalSession // sessions
	Unlocks  int            // channel_unlocks, through those sessions
	// Addresses are every address the account has used, kept for security.
	Addresses []LegalAddress // user_ips

	Outgoing []LegalRelationship // relationships, user_id = them
	Incoming []LegalRelationship // relationships, target_id = them

	Memberships   []LegalMembership   // server_members, member_roles, servers
	Owned         []LegalServer       // servers, owner_id = them
	Conversations []LegalConversation // dm_participants, channels
	OwnedChannels []LegalChannel      // channels, owner_id = them
	Overwrites    int                 // channel_overwrites, target_id = them

	Messages    LegalMessages       // messages, author_id = them
	PerChannel  []LegalChannelCount // the same, grouped by channel
	Attachments LegalAttachments    // attachments, uploader_id = them
	BlobKinds   []LegalBlobKind     // blobs, owner_id = them, grouped
	PlainBlobs  []LegalBlob         // the same, the ones that are not encrypted
	Reactions   LegalReactions      // reactions, user_id = them
	Emoji       []LegalEmojiUse     // the same, grouped by emoji
	Pins        int                 // pins, pinned_by = them
	ReadStates  LegalReads          // read_states, user_id = them
	PollVotes   int                 // poll_votes, user_id = them

	Invites    []LegalInvite    // invites, inviter_id = them
	GuestLinks []LegalGuestLink // guest_links, creator_id = them
	Uploads    []LegalUpload    // uploads, user_id = them

	BansAgainst  []LegalBan   // bans, user_id = them
	BansIssued   int          // bans, banned_by = them
	AuditAsActor int          // audit_log, actor_id = them
	AuditOnThem  []LegalAudit // audit_log, target_id = them

	ModCases        []LegalModCase // mod_cases, user_id = them
	ModThreadLines  int            // mod_messages in their cases
	ModActionsTaken int            // mod_log, moderator_id = them
	UsernameHolds   []string       // username_holds, user_id = them

	Emojis   []LegalExpression // emojis, creator_id = them
	Packs    []LegalPack       // sticker_packs, owner_user_id = them
	Stickers int               // stickers, creator_id = them
	Installs []LegalInstall    // sticker_installs, user_id = them

	KeyEpochs int // key_epochs, created_by = them
	KeyGrants int // key_grants, user_id = them

	Crashes []LegalCrash // client_errors, user_id = them

	BugReports     []LegalBugReport // bug_reports, user_id = them, with bug_attachments
	BugThreadLines int              // bug_messages in their reports

	SharedGames []SharedGame // game_submissions, user_id = them

	Discord *LegalDiscord // discord_links, user_id = them

	CallDays     []CallUsageDay // call_usage, user_id = them, newest first
	CallSessions int            // realtime_sessions, user_id = them
	RelayCreds   int            // turn_credentials, user_id = them
}

// LegalDiscord is the Discord account linked for showing Discord activity.
// The OAuth tokens are credentials sealed with the server secret, not records
// of use, and are not produced.
type LegalDiscord struct {
	DiscordID  string
	Username   string
	GlobalName string
	InGuild    bool
	LeftAt     int64
	CreatedAt  int64
	UpdatedAt  int64
}

// LegalSettings describes the client-encrypted settings blob without opening
// it. The server stores the bytes and the version it stored them at; what is
// inside — themes, mutes, the order of a sidebar — was sealed in the browser.
type LegalSettings struct {
	Bytes     int64
	Version   int64
	UpdatedAt int64
}

// LegalMFA is the second-factor state. The seed itself is sealed with the
// server secret and the backup codes are keyed hashes, so neither is here.
type LegalMFA struct {
	Enabled          bool
	CreatedAt        int64
	BackupCodesTotal int
	BackupCodesUsed  int
}

// LegalSession is one login: the sessions row without its token hash, which is
// a bearer credential rather than a record of anything the account did.
//
// IP is the address the session was opened from and is never updated
// afterwards, so a row is where a login happened, not where the account is now.
type LegalSession struct {
	ID         ids.ID
	UserAgent  string
	IP         string
	CreatedAt  int64
	LastSeenAt int64
}

// LegalAddress is one user_ips row.
type LegalAddress struct {
	IP          string
	FirstSeenAt int64
	LastSeenAt  int64
}

// LegalRelationship is one relationships row. Nickname is the private note the
// row's owner set, so it reads one way on an outgoing row and the other way on
// an incoming one: on the way in, the note is what somebody else called them.
type LegalRelationship struct {
	OtherID   ids.ID
	Type      string
	Nickname  string
	CreatedAt int64
}

// LegalMembership is one server_members row with the server's name and the
// roles that membership carries.
type LegalMembership struct {
	ServerID     ids.ID
	ServerName   string
	Nickname     string
	AvatarID     *ids.ID
	JoinedAt     int64
	TimeoutUntil *int64
	Flags        int64
	Roles        []string
}

// LegalServer is a community this account owns.
type LegalServer struct {
	ID        ids.ID
	Name      string
	CreatedAt int64
}

// LegalConversation is one dm_participants row: a direct message, a group, or a
// subchat inside one. Name is empty for a two-person DM and is whatever the
// group was called otherwise — group names are typed by people and stored as
// typed, unlike anything said inside the group.
type LegalConversation struct {
	ChannelID ids.ID
	Type      string
	Name      string
	AddedBy   *ids.ID
	AddedAt   int64
	Hidden    bool
}

// LegalChannel is a channel this account owns: a group DM it created, or a
// subchat it opened.
type LegalChannel struct {
	ID        ids.ID
	Type      string
	Name      string
	ServerID  *ids.ID
	CreatedAt int64
}

// LegalMessages counts what the account wrote. Not one word of it is readable
// here: the ciphertext column is what the sender's client sealed.
//
// Mentioned counts the other way round — messages by anyone that name this
// account. The mention list travels outside the ciphertext, because the server
// has to know who to wake up for a notification without being able to read what
// woke them, so it is one of the few things about a message that is legible.
type LegalMessages struct {
	Count          int
	First          int64
	Last           int64
	Edited         int
	WithAttachment int64
	Mentioned      int
}

// LegalChannelCount is how much of that fell in one channel.
type LegalChannelCount struct {
	ChannelID ids.ID
	Type      string
	Name      string
	ServerID  *ids.ID
	Count     int
	First     int64
	Last      int64
}

// LegalAttachments totals the files the account sent. Sizes are of the
// ciphertext as stored, which is a little larger than the file was.
type LegalAttachments struct {
	Count int
	Bytes int64
	First int64
	Last  int64
}

// LegalBlobKind groups the stored files this account uploaded by what they are
// for and whether they are encrypted.
type LegalBlobKind struct {
	Kind      string
	Encrypted bool
	Count     int
	Bytes     int64
}

// LegalBlob is one stored file that is not encrypted — an avatar, a banner, a
// server icon, an emoji, a sticker. These are the only files on Nexa
// the operator can actually produce, because they are the only ones stored as
// they were uploaded.
type LegalBlob struct {
	ID        ids.ID
	SHA256    string
	Size      int64
	Mime      string
	Kind      string
	Width     *int
	Height    *int
	Animated  bool
	CreatedAt int64
}

// LegalReactions counts the reactions the account left.
type LegalReactions struct {
	Count int
	First int64
	Last  int64
}

// LegalEmojiUse is one emoji the account reacted with, and how often. Reactions
// are not encrypted: the emoji is stored as the character or name it is.
type LegalEmojiUse struct {
	Emoji string
	Count int
}

// LegalReads is how many channels the account has a read position in.
type LegalReads struct {
	Channels int
	LastAck  int64
}

// LegalInvite is an invite link this account created. There is no record of who
// used one: the row counts uses and does not name them.
type LegalInvite struct {
	Code      string
	ServerID  *ids.ID
	ChannelID *ids.ID
	MaxUses   int
	Uses      int
	ExpiresAt *int64
	CreatedAt int64
}

// LegalGuestLink is a link this account made to let someone without an account
// into one call.
type LegalGuestLink struct {
	Code      string
	ChannelID ids.ID
	Epoch     int
	CreatedAt int64
	ExpiresAt int64
}

// LegalUpload is a transfer that was still arriving, or whose row has not been
// swept yet. Nothing about what it carried is stored beyond its size.
type LegalUpload struct {
	ID        ids.ID
	Size      int64
	Received  int64
	BlobID    *ids.ID
	CreatedAt int64
	UpdatedAt int64
}

// LegalBan is a ban against this account in one community.
type LegalBan struct {
	ServerID   ids.ID
	ServerName string
	Reason     string
	BannedBy   ids.ID
	CreatedAt  int64
}

// LegalModCase is an action Nexa's own staff took against this
// account (the moderation panel), which unlike a community's audit log is
// Nexa-wide.
type LegalModCase struct {
	ID          ids.ID
	Number      int64
	Action      string
	Reason      string
	ModeratorID ids.ID
	ExpiresAt   *int64
	LiftedAt    *int64
	Status      string
	CreatedAt   int64
}

// LegalAudit is a moderation action taken against this account inside one
// community. The audit log is per-community and is written by whoever moderates
// that community, not by Nexa.
type LegalAudit struct {
	ID        ids.ID
	ServerID  ids.ID
	ActorID   ids.ID
	Action    string
	Reason    string
	CreatedAt int64
}

// LegalExpression is a custom emoji this account uploaded to a community.
type LegalExpression struct {
	ID        ids.ID
	Name      string
	ServerID  ids.ID
	CreatedAt int64
}

// LegalPack is an emoji or sticker pack this account owns.
type LegalPack struct {
	ID        ids.ID
	Name      string
	Kind      string
	Public    bool
	CreatedAt int64
}

// LegalInstall is a pack this account added to its own picker.
type LegalInstall struct {
	PackID      ids.ID
	Name        string
	InstalledAt int64
}

// LegalCrash is one crash the account's browser reported. Message and stack are
// the error the app threw; no conversation ever reaches this table.
type LegalCrash struct {
	ID        ids.ID
	Kind      string
	Message   string
	Path      string
	UserAgent string
	Version   string
	CreatedAt int64
}

// LegalBugReport is a bug report the account sent Nexa's staff. Its
// text and files are not end-to-end encrypted: the staff have to read them.
type LegalBugReport struct {
	ID        ids.ID
	Number    int64
	Subject   string
	Status    string
	Files     int
	CreatedAt int64
}

// AccountRecord reads back everything keyed to one account, in one pass over
// the reader pool. It is a snapshot rather than a transaction on purpose: the
// report says the instant it was taken and the counts are as of then, which is
// what a records custodian has to be able to attest to.
func (s *Store) AccountRecord(ctx context.Context, id ids.ID) (*AccountRecord, error) {
	u, err := s.UserByID(ctx, id)
	if err != nil {
		return nil, err
	}
	rec := &AccountRecord{TakenAt: nowMs(), User: u}
	uid := int64(id)

	// Each step is its own read so a table that is empty for this account costs
	// one row and says so, rather than being lost in a join.
	for _, step := range []func(*Store, context.Context, *AccountRecord, int64) error{
		(*Store).legalSettings,
		(*Store).legalMFA,
		(*Store).legalSessions,
		(*Store).legalRelationships,
		(*Store).legalServers,
		(*Store).legalConversations,
		(*Store).legalMessages,
		(*Store).legalFiles,
		(*Store).legalEngagement,
		(*Store).legalLinks,
		(*Store).legalModeration,
		(*Store).legalExpressions,
		(*Store).legalKeys,
		(*Store).legalCrashes,
		(*Store).legalBugReports,
		(*Store).legalDiscord,
		(*Store).legalCalls,
	} {
		if err := step(s, ctx, rec, uid); err != nil {
			return nil, err
		}
	}
	return rec, nil
}

func (s *Store) legalCalls(ctx context.Context, rec *AccountRecord, uid int64) error {
	var err error
	if rec.CallDays, err = s.CallUsageDays(ctx, ids.ID(uid), 100); err != nil {
		return err
	}
	if rec.CallSessions, err = s.RealtimeSessionCount(ctx, ids.ID(uid)); err != nil {
		return err
	}
	rec.RelayCreds, err = s.TurnCredentialCount(ctx, ids.ID(uid))
	return err
}

func (s *Store) legalDiscord(ctx context.Context, rec *AccountRecord, uid int64) error {
	l, err := s.DiscordLinkOf(ctx, ids.ID(uid))
	if errors.Is(err, ErrNotFound) {
		return nil
	}
	if err != nil {
		return err
	}
	rec.Discord = &LegalDiscord{DiscordID: l.DiscordID, Username: l.Username, GlobalName: l.GlobalName, InGuild: l.InGuild, LeftAt: l.LeftAt, CreatedAt: l.CreatedAt, UpdatedAt: l.UpdatedAt}
	return nil
}

func (s *Store) legalSettings(ctx context.Context, rec *AccountRecord, uid int64) error {
	var set LegalSettings
	err := s.db.R.QueryRowContext(ctx, `SELECT length(blob), version, updated_at FROM user_settings WHERE user_id = ?`, uid).
		Scan(&set.Bytes, &set.Version, &set.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil
	}
	if err != nil {
		return err
	}
	rec.Settings = &set
	return nil
}

func (s *Store) legalMFA(ctx context.Context, rec *AccountRecord, uid int64) error {
	var m LegalMFA
	var enabled int64
	err := s.db.R.QueryRowContext(ctx, `SELECT enabled, created_at FROM user_mfa WHERE user_id = ?`, uid).Scan(&enabled, &m.CreatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil
	}
	if err != nil {
		return err
	}
	m.Enabled = enabled != 0
	if err := s.db.R.QueryRowContext(ctx, `SELECT count(*), count(used_at) FROM mfa_backup_codes WHERE user_id = ?`, uid).
		Scan(&m.BackupCodesTotal, &m.BackupCodesUsed); err != nil {
		return err
	}
	rec.MFA = &m
	return nil
}

func (s *Store) legalSessions(ctx context.Context, rec *AccountRecord, uid int64) error {
	rows, err := s.db.R.QueryContext(ctx, `SELECT id, user_agent, ip, created_at, last_seen_at FROM sessions WHERE user_id = ? ORDER BY created_at`, uid)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var se LegalSession
		if err := rows.Scan(&se.ID, &se.UserAgent, &se.IP, &se.CreatedAt, &se.LastSeenAt); err != nil {
			return err
		}
		rec.Sessions = append(rec.Sessions, se)
	}
	if err := rows.Err(); err != nil {
		return err
	}
	addrs, err := s.db.R.QueryContext(ctx, `SELECT ip, first_seen_at, last_seen_at FROM user_ips WHERE user_id = ? ORDER BY first_seen_at`, uid)
	if err != nil {
		return err
	}
	defer addrs.Close()
	for addrs.Next() {
		var a LegalAddress
		if err := addrs.Scan(&a.IP, &a.FirstSeenAt, &a.LastSeenAt); err != nil {
			return err
		}
		rec.Addresses = append(rec.Addresses, a)
	}
	if err := addrs.Err(); err != nil {
		return err
	}
	return s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM channel_unlocks WHERE session_id IN (SELECT id FROM sessions WHERE user_id = ?)`, uid).Scan(&rec.Unlocks)
}

func (s *Store) legalRelationships(ctx context.Context, rec *AccountRecord, uid int64) error {
	for _, side := range []struct {
		query string
		into  *[]LegalRelationship
	}{
		{`SELECT target_id, type, nickname, created_at FROM relationships WHERE user_id = ? ORDER BY created_at`, &rec.Outgoing},
		{`SELECT user_id, type, nickname, created_at FROM relationships WHERE target_id = ? ORDER BY created_at`, &rec.Incoming},
	} {
		rows, err := s.db.R.QueryContext(ctx, side.query, uid)
		if err != nil {
			return err
		}
		for rows.Next() {
			var r LegalRelationship
			if err := rows.Scan(&r.OtherID, &r.Type, &r.Nickname, &r.CreatedAt); err != nil {
				rows.Close()
				return err
			}
			*side.into = append(*side.into, r)
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return err
		}
	}
	return nil
}

func (s *Store) legalServers(ctx context.Context, rec *AccountRecord, uid int64) error {
	// The roles come back in one read and are attached to their membership
	// below, so a person in thirty communities costs two queries, not thirty.
	roles := map[ids.ID][]string{}
	rows, err := s.db.R.QueryContext(ctx, `SELECT mr.server_id, r.name FROM member_roles mr JOIN roles r ON r.id = mr.role_id WHERE mr.user_id = ? ORDER BY r.position DESC`, uid)
	if err != nil {
		return err
	}
	for rows.Next() {
		var sid ids.ID
		var name string
		if err := rows.Scan(&sid, &name); err != nil {
			rows.Close()
			return err
		}
		roles[sid] = append(roles[sid], name)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}

	rows, err = s.db.R.QueryContext(ctx, `SELECT m.server_id, sv.name, m.nickname, m.avatar_id, m.joined_at, m.timeout_until, m.flags
		FROM server_members m JOIN servers sv ON sv.id = m.server_id WHERE m.user_id = ? ORDER BY m.joined_at`, uid)
	if err != nil {
		return err
	}
	for rows.Next() {
		var m LegalMembership
		var avatar, timeout sql.NullInt64
		if err := rows.Scan(&m.ServerID, &m.ServerName, &m.Nickname, &avatar, &m.JoinedAt, &timeout, &m.Flags); err != nil {
			rows.Close()
			return err
		}
		m.AvatarID, m.TimeoutUntil = idPtr(avatar), int64Ptr(timeout)
		m.Roles = roles[m.ServerID]
		rec.Memberships = append(rec.Memberships, m)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}

	rows, err = s.db.R.QueryContext(ctx, `SELECT id, name, created_at FROM servers WHERE owner_id = ? ORDER BY id`, uid)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var sv LegalServer
		if err := rows.Scan(&sv.ID, &sv.Name, &sv.CreatedAt); err != nil {
			return err
		}
		rec.Owned = append(rec.Owned, sv)
	}
	return rows.Err()
}

func (s *Store) legalConversations(ctx context.Context, rec *AccountRecord, uid int64) error {
	rows, err := s.db.R.QueryContext(ctx, `SELECT p.channel_id, c.type, c.name, p.added_by, p.added_at, p.hidden
		FROM dm_participants p JOIN channels c ON c.id = p.channel_id WHERE p.user_id = ? ORDER BY p.added_at`, uid)
	if err != nil {
		return err
	}
	for rows.Next() {
		var c LegalConversation
		var addedBy sql.NullInt64
		var hidden int64
		if err := rows.Scan(&c.ChannelID, &c.Type, &c.Name, &addedBy, &c.AddedAt, &hidden); err != nil {
			rows.Close()
			return err
		}
		c.AddedBy, c.Hidden = idPtr(addedBy), hidden != 0
		rec.Conversations = append(rec.Conversations, c)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}

	rows, err = s.db.R.QueryContext(ctx, `SELECT id, type, name, server_id, created_at FROM channels WHERE owner_id = ? ORDER BY id`, uid)
	if err != nil {
		return err
	}
	for rows.Next() {
		var c LegalChannel
		var server sql.NullInt64
		if err := rows.Scan(&c.ID, &c.Type, &c.Name, &server, &c.CreatedAt); err != nil {
			rows.Close()
			return err
		}
		c.ServerID = idPtr(server)
		rec.OwnedChannels = append(rec.OwnedChannels, c)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}

	return s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM channel_overwrites WHERE target_id = ? AND target_type = 'member'`, uid).Scan(&rec.Overwrites)
}

func (s *Store) legalMessages(ctx context.Context, rec *AccountRecord, uid int64) error {
	if err := s.db.R.QueryRowContext(ctx, `SELECT count(*), coalesce(min(created_at), 0), coalesce(max(created_at), 0), count(edited_at), coalesce(sum(has_attachments), 0)
		FROM messages WHERE author_id = ?`, uid).
		Scan(&rec.Messages.Count, &rec.Messages.First, &rec.Messages.Last, &rec.Messages.Edited, &rec.Messages.WithAttachment); err != nil {
		return err
	}
	if err := s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM messages m WHERE `+s.db.Dialect.JSONArrayHas("m.mentions"), ids.ID(uid).String()).
		Scan(&rec.Messages.Mentioned); err != nil {
		return err
	}
	rows, err := s.db.R.QueryContext(ctx, `SELECT m.channel_id, c.type, c.name, c.server_id, count(*), min(m.created_at), max(m.created_at)
		FROM messages m JOIN channels c ON c.id = m.channel_id WHERE m.author_id = ?
		GROUP BY m.channel_id, c.type, c.name, c.server_id ORDER BY count(*) DESC, m.channel_id LIMIT ?`, uid, LegalListCap)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var c LegalChannelCount
		var server sql.NullInt64
		if err := rows.Scan(&c.ChannelID, &c.Type, &c.Name, &server, &c.Count, &c.First, &c.Last); err != nil {
			return err
		}
		c.ServerID = idPtr(server)
		rec.PerChannel = append(rec.PerChannel, c)
	}
	return rows.Err()
}

func (s *Store) legalFiles(ctx context.Context, rec *AccountRecord, uid int64) error {
	if err := s.db.R.QueryRowContext(ctx, `SELECT count(*), coalesce(sum(size), 0), coalesce(min(created_at), 0), coalesce(max(created_at), 0)
		FROM attachments WHERE uploader_id = ?`, uid).
		Scan(&rec.Attachments.Count, &rec.Attachments.Bytes, &rec.Attachments.First, &rec.Attachments.Last); err != nil {
		return err
	}
	rows, err := s.db.R.QueryContext(ctx, `SELECT kind, encrypted, count(*), coalesce(sum(size), 0) FROM blobs WHERE owner_id = ? GROUP BY kind, encrypted ORDER BY kind`, uid)
	if err != nil {
		return err
	}
	for rows.Next() {
		var k LegalBlobKind
		var enc int64
		if err := rows.Scan(&k.Kind, &enc, &k.Count, &k.Bytes); err != nil {
			rows.Close()
			return err
		}
		k.Encrypted = enc != 0
		rec.BlobKinds = append(rec.BlobKinds, k)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}

	rows, err = s.db.R.QueryContext(ctx, `SELECT id, sha256, size, mime, kind, width, height, animated, created_at
		FROM blobs WHERE owner_id = ? AND encrypted = 0 ORDER BY created_at LIMIT ?`, uid, LegalListCap)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var b LegalBlob
		var w, h sql.NullInt64
		var anim int64
		if err := rows.Scan(&b.ID, &b.SHA256, &b.Size, &b.Mime, &b.Kind, &w, &h, &anim, &b.CreatedAt); err != nil {
			return err
		}
		b.Width, b.Height, b.Animated = intPtr(w), intPtr(h), anim != 0
		rec.PlainBlobs = append(rec.PlainBlobs, b)
	}
	return rows.Err()
}

func (s *Store) legalEngagement(ctx context.Context, rec *AccountRecord, uid int64) error {
	if err := s.db.R.QueryRowContext(ctx, `SELECT count(*), coalesce(min(created_at), 0), coalesce(max(created_at), 0) FROM reactions WHERE user_id = ?`, uid).
		Scan(&rec.Reactions.Count, &rec.Reactions.First, &rec.Reactions.Last); err != nil {
		return err
	}
	rows, err := s.db.R.QueryContext(ctx, `SELECT emoji, count(*) FROM reactions WHERE user_id = ? GROUP BY emoji ORDER BY count(*) DESC, emoji LIMIT ?`, uid, LegalListCap)
	if err != nil {
		return err
	}
	for rows.Next() {
		var e LegalEmojiUse
		if err := rows.Scan(&e.Emoji, &e.Count); err != nil {
			rows.Close()
			return err
		}
		rec.Emoji = append(rec.Emoji, e)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}

	if err := s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM pins WHERE pinned_by = ?`, uid).Scan(&rec.Pins); err != nil {
		return err
	}
	if err := s.db.R.QueryRowContext(ctx, `SELECT count(*), coalesce(max(last_ack_at), 0) FROM read_states WHERE user_id = ?`, uid).
		Scan(&rec.ReadStates.Channels, &rec.ReadStates.LastAck); err != nil {
		return err
	}
	return s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM poll_votes WHERE user_id = ?`, uid).Scan(&rec.PollVotes)
}

func (s *Store) legalLinks(ctx context.Context, rec *AccountRecord, uid int64) error {
	rows, err := s.db.R.QueryContext(ctx, `SELECT code, server_id, channel_id, max_uses, uses, expires_at, created_at FROM invites WHERE inviter_id = ? ORDER BY created_at`, uid)
	if err != nil {
		return err
	}
	for rows.Next() {
		var in LegalInvite
		var server, channel, expires sql.NullInt64
		if err := rows.Scan(&in.Code, &server, &channel, &in.MaxUses, &in.Uses, &expires, &in.CreatedAt); err != nil {
			rows.Close()
			return err
		}
		in.ServerID, in.ChannelID, in.ExpiresAt = idPtr(server), idPtr(channel), int64Ptr(expires)
		rec.Invites = append(rec.Invites, in)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}

	rows, err = s.db.R.QueryContext(ctx, `SELECT code, channel_id, epoch, created_at, expires_at FROM guest_links WHERE creator_id = ? ORDER BY created_at`, uid)
	if err != nil {
		return err
	}
	for rows.Next() {
		var g LegalGuestLink
		if err := rows.Scan(&g.Code, &g.ChannelID, &g.Epoch, &g.CreatedAt, &g.ExpiresAt); err != nil {
			rows.Close()
			return err
		}
		rec.GuestLinks = append(rec.GuestLinks, g)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}

	rows, err = s.db.R.QueryContext(ctx, `SELECT id, size, received, blob_id, created_at, updated_at FROM uploads WHERE user_id = ? ORDER BY created_at`, uid)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var up LegalUpload
		var blob sql.NullInt64
		if err := rows.Scan(&up.ID, &up.Size, &up.Received, &blob, &up.CreatedAt, &up.UpdatedAt); err != nil {
			return err
		}
		up.BlobID = idPtr(blob)
		rec.Uploads = append(rec.Uploads, up)
	}
	return rows.Err()
}

func (s *Store) legalModeration(ctx context.Context, rec *AccountRecord, uid int64) error {
	rows, err := s.db.R.QueryContext(ctx, `SELECT b.server_id, sv.name, b.reason, b.banned_by, b.created_at
		FROM bans b JOIN servers sv ON sv.id = b.server_id WHERE b.user_id = ? ORDER BY b.created_at`, uid)
	if err != nil {
		return err
	}
	for rows.Next() {
		var b LegalBan
		if err := rows.Scan(&b.ServerID, &b.ServerName, &b.Reason, &b.BannedBy, &b.CreatedAt); err != nil {
			rows.Close()
			return err
		}
		rec.BansAgainst = append(rec.BansAgainst, b)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}

	if err := s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM bans WHERE banned_by = ?`, uid).Scan(&rec.BansIssued); err != nil {
		return err
	}
	if err := s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM audit_log WHERE actor_id = ?`, uid).Scan(&rec.AuditAsActor); err != nil {
		return err
	}
	rows, err = s.db.R.QueryContext(ctx, `SELECT id, server_id, actor_id, action, reason, created_at FROM audit_log WHERE target_id = ? AND target_type = 'user' ORDER BY id DESC LIMIT ?`, uid, LegalListCap)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var a LegalAudit
		if err := rows.Scan(&a.ID, &a.ServerID, &a.ActorID, &a.Action, &a.Reason, &a.CreatedAt); err != nil {
			return err
		}
		rec.AuditOnThem = append(rec.AuditOnThem, a)
	}
	if err := rows.Err(); err != nil {
		return err
	}
	return s.legalInstanceModeration(ctx, rec, uid)
}

// legalInstanceModeration reads what Nexa's own staff did about the
// account, and what it said back in its case threads.
func (s *Store) legalInstanceModeration(ctx context.Context, rec *AccountRecord, uid int64) error {
	rows, err := s.db.R.QueryContext(ctx, `SELECT id, number, action, reason, moderator_id, expires_at, lifted_at, status, created_at FROM mod_cases WHERE user_id = ? ORDER BY id LIMIT ?`, uid, LegalListCap)
	if err != nil {
		return err
	}
	for rows.Next() {
		var c LegalModCase
		var exp, lifted sql.NullInt64
		if err := rows.Scan(&c.ID, &c.Number, &c.Action, &c.Reason, &c.ModeratorID, &exp, &lifted, &c.Status, &c.CreatedAt); err != nil {
			rows.Close()
			return err
		}
		c.ExpiresAt, c.LiftedAt = int64Ptr(exp), int64Ptr(lifted)
		rec.ModCases = append(rec.ModCases, c)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	if err := s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM mod_messages m JOIN mod_cases c ON c.id = m.case_id WHERE c.user_id = ?`, uid).Scan(&rec.ModThreadLines); err != nil {
		return err
	}
	if err := s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM mod_log WHERE moderator_id = ?`, uid).Scan(&rec.ModActionsTaken); err != nil {
		return err
	}
	names, err := s.db.R.QueryContext(ctx, `SELECT username FROM username_holds WHERE user_id = ? ORDER BY created_at`, uid)
	if err != nil {
		return err
	}
	defer names.Close()
	for names.Next() {
		var n string
		if err := names.Scan(&n); err != nil {
			return err
		}
		rec.UsernameHolds = append(rec.UsernameHolds, n)
	}
	return names.Err()
}

func (s *Store) legalExpressions(ctx context.Context, rec *AccountRecord, uid int64) error {
	rows, err := s.db.R.QueryContext(ctx, `SELECT id, name, server_id, created_at FROM emojis WHERE creator_id = ? ORDER BY created_at LIMIT ?`, uid, LegalListCap)
	if err != nil {
		return err
	}
	for rows.Next() {
		var e LegalExpression
		if err := rows.Scan(&e.ID, &e.Name, &e.ServerID, &e.CreatedAt); err != nil {
			rows.Close()
			return err
		}
		rec.Emojis = append(rec.Emojis, e)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}

	rows, err = s.db.R.QueryContext(ctx, `SELECT id, name, kind, public, created_at FROM sticker_packs WHERE owner_user_id = ? ORDER BY created_at`, uid)
	if err != nil {
		return err
	}
	for rows.Next() {
		var p LegalPack
		var public int64
		if err := rows.Scan(&p.ID, &p.Name, &p.Kind, &public, &p.CreatedAt); err != nil {
			rows.Close()
			return err
		}
		p.Public = public != 0
		rec.Packs = append(rec.Packs, p)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}

	if err := s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM stickers WHERE creator_id = ?`, uid).Scan(&rec.Stickers); err != nil {
		return err
	}
	rows, err = s.db.R.QueryContext(ctx, `SELECT i.pack_id, p.name, i.installed_at FROM sticker_installs i JOIN sticker_packs p ON p.id = i.pack_id WHERE i.user_id = ? ORDER BY i.installed_at`, uid)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var in LegalInstall
		if err := rows.Scan(&in.PackID, &in.Name, &in.InstalledAt); err != nil {
			return err
		}
		rec.Installs = append(rec.Installs, in)
	}
	return rows.Err()
}

func (s *Store) legalKeys(ctx context.Context, rec *AccountRecord, uid int64) error {
	if err := s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM key_epochs WHERE created_by = ?`, uid).Scan(&rec.KeyEpochs); err != nil {
		return err
	}
	return s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM key_grants WHERE user_id = ?`, uid).Scan(&rec.KeyGrants)
}

func (s *Store) legalCrashes(ctx context.Context, rec *AccountRecord, uid int64) error {
	rows, err := s.db.R.QueryContext(ctx, `SELECT id, kind, message, path, user_agent, version, created_at FROM client_errors WHERE user_id = ? ORDER BY id DESC LIMIT ?`, uid, LegalListCap)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var c LegalCrash
		if err := rows.Scan(&c.ID, &c.Kind, &c.Message, &c.Path, &c.UserAgent, &c.Version, &c.CreatedAt); err != nil {
			return err
		}
		rec.Crashes = append(rec.Crashes, c)
	}
	return rows.Err()
}

func (s *Store) legalBugReports(ctx context.Context, rec *AccountRecord, uid int64) error {
	shared, err := s.MySharedGames(ctx, ids.ID(uid))
	if err != nil {
		return err
	}
	rec.SharedGames = shared
	rows, err := s.db.R.QueryContext(ctx, `SELECT id, number, subject, status, attachment_count, created_at FROM bug_reports WHERE user_id = ? ORDER BY id DESC LIMIT ?`, uid, LegalListCap)
	if err != nil {
		return err
	}
	for rows.Next() {
		var b LegalBugReport
		if err := rows.Scan(&b.ID, &b.Number, &b.Subject, &b.Status, &b.Files, &b.CreatedAt); err != nil {
			rows.Close()
			return err
		}
		rec.BugReports = append(rec.BugReports, b)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	return s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM bug_messages m JOIN bug_reports r ON r.id = m.report_id WHERE r.user_id = ?`, uid).Scan(&rec.BugThreadLines)
}
