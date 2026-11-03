package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"strings"

	"evilion.xyz/chat/internal/db"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/perms"
)

const serverCols = `id, name, icon_id, banner_id, owner_id, description, system_channel_id, default_notifications, flags, created_at`

func scanServer(r rowScanner) (*Server, error) {
	var s Server
	var icon, banner, sys sql.NullInt64
	if err := r.Scan(&s.ID, &s.Name, &icon, &banner, &s.OwnerID, &s.Description, &sys, &s.DefaultNotifications, &s.Flags, &s.CreatedAt); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	s.IconID, s.BannerID, s.SystemChannelID = idPtr(icon), idPtr(banner), idPtr(sys)
	return &s, nil
}

// DefaultNames is what a new server's first categories and channels are
// called. They are records, not interface text, so the client sends them in
// the language the person is reading; anything left empty falls back to
// English.
type DefaultNames struct {
	TextCategory  string
	VoiceCategory string
	TextChannel   string
	VoiceChannel  string
}

func or(v, fallback string) string {
	if v == "" {
		return fallback
	}
	return v
}

// CreateServer creates a server with its @everyone role (id = server id),
// default categories and channels, and the owner as first member.
func (s *Store) CreateServer(ctx context.Context, owner ids.ID, name string, defaults DefaultNames) (*Server, error) {
	now := nowMs()
	srv := &Server{ID: ids.New(), Name: name, OwnerID: owner, CreatedAt: now}
	textCat := &Channel{ServerID: &srv.ID, Type: "category", Name: or(defaults.TextCategory, "Text Channels"), Position: 0}
	voiceCat := &Channel{ServerID: &srv.ID, Type: "category", Name: or(defaults.VoiceCategory, "Voice Channels"), Position: 1}
	general := &Channel{ServerID: &srv.ID, Type: "text", Name: or(defaults.TextChannel, "general"), Position: 0}
	voice := &Channel{ServerID: &srv.ID, Type: "voice", Name: or(defaults.VoiceChannel, "General"), Position: 0}
	err := s.Tx(ctx, func(tx *db.Tx) error {
		if _, err := tx.ExecContext(ctx, `INSERT INTO servers (`+serverCols+`, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)`, int64(srv.ID), srv.Name, nil, nil, int64(owner), "", nil, 0, 0, now, now); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO roles (id, server_id, name, color, hoist, mentionable, position, permissions, created_at) VALUES (?,?,?,?,?,?,?,?,?)`, int64(srv.ID), int64(srv.ID), "@everyone", 0, 0, 0, 0, int64(perms.DefaultEveryone), now); err != nil {
			return err
		}
		for _, c := range []*Channel{textCat, voiceCat} {
			if err := s.insertChannel(ctx, tx, c); err != nil {
				return err
			}
		}
		general.ParentID = &textCat.ID
		voice.ParentID = &voiceCat.ID
		for _, c := range []*Channel{general, voice} {
			if err := s.insertChannel(ctx, tx, c); err != nil {
				return err
			}
		}
		if _, err := tx.ExecContext(ctx, `UPDATE servers SET system_channel_id = ? WHERE id = ?`, int64(general.ID), int64(srv.ID)); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, `INSERT INTO server_members (server_id, user_id, nickname, joined_at, flags, join_method) VALUES (?,?,?,?,0,'owner')`, int64(srv.ID), int64(owner), "", now)
		return err
	})
	if err != nil {
		return nil, err
	}
	s.serverChanged(srv.ID)
	srv.SystemChannelID = &general.ID
	return srv, nil
}

// TemplateSpec is a server snapshot to build a new server from: its name and
// description, its roles (@everyone's permissions plus custom roles from
// lowest to highest), its categories and channels with their role overwrites,
// and the index in Channels of the text channel join notices go to (-1 for none).
type TemplateSpec struct {
	Name                 string
	Description          string
	DefaultNotifications int
	EveryonePerms        perms.Perm
	Roles                []TemplateRole
	Channels             []TemplateChannel
	SystemChannelIndex   int
}

// TemplateRole is one custom role in a TemplateSpec.
type TemplateRole struct {
	Name        string
	Color       int
	Color2      *int
	Color3      *int
	Hoist       bool
	Mentionable bool
	Permissions perms.Perm
	IconEmoji   string
}

// TemplateChannel is one category or channel in a TemplateSpec.
type TemplateChannel struct {
	Type               string
	Name               string
	Topic              string
	Position           int
	ParentIndex        int // -1 when not in a category, otherwise index in Channels
	NSFW               bool
	SlowmodeSeconds    int
	UserLimit          int
	Glyph              string
	DefaultAutoArchive int
	Forum              *ForumSettings
	Overwrites         []TemplateOverwrite
}

// TemplateOverwrite is one role overwrite on a TemplateChannel.
type TemplateOverwrite struct {
	RoleIndex int // -1 for @everyone, otherwise index in Roles
	Allow     perms.Perm
	Deny      perms.Perm
}

// CreateServerFromTemplate builds a server, its roles, its categories and
// channels, and their permission overwrites in one transaction, flagging any
// channel @everyone cannot view so it gets its own conversation key.
func (s *Store) CreateServerFromTemplate(ctx context.Context, owner ids.ID, spec TemplateSpec) (*Server, error) {
	now := nowMs()
	srv := &Server{
		ID:                   ids.New(),
		Name:                 spec.Name,
		Description:          spec.Description,
		DefaultNotifications: spec.DefaultNotifications,
		OwnerID:              owner,
		CreatedAt:            now,
	}
	roleIDs := make([]ids.ID, len(spec.Roles))
	permRoles := make([]perms.Role, 0, len(spec.Roles)+1)
	permRoles = append(permRoles, perms.Role{ID: srv.ID, Position: 0, Permissions: spec.EveryonePerms})
	for i, r := range spec.Roles {
		roleIDs[i] = ids.New()
		permRoles = append(permRoles, perms.Role{ID: roleIDs[i], Position: i + 1, Permissions: r.Permissions})
	}
	chanIDs := make([]ids.ID, len(spec.Channels))
	for i := range spec.Channels {
		chanIDs[i] = ids.New()
	}
	var sysID *ids.ID
	if spec.SystemChannelIndex >= 0 && spec.SystemChannelIndex < len(spec.Channels) && spec.Channels[spec.SystemChannelIndex].Type == "text" {
		id := chanIDs[spec.SystemChannelIndex]
		sysID = &id
	}
	err := s.Tx(ctx, func(tx *db.Tx) error {
		if _, err := tx.ExecContext(ctx, `INSERT INTO servers (`+serverCols+`, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)`,
			int64(srv.ID), srv.Name, nil, nil, int64(owner), srv.Description, nil, srv.DefaultNotifications, 0, now, now); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO roles (`+roleCols+`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)`,
			int64(srv.ID), int64(srv.ID), "@everyone", 0, 0, 0, 0, int64(spec.EveryonePerms), nil, now, nil, nil, ""); err != nil {
			return err
		}
		for i, r := range spec.Roles {
			if _, err := tx.ExecContext(ctx, `INSERT INTO roles (`+roleCols+`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)`,
				int64(roleIDs[i]), int64(srv.ID), r.Name, r.Color, boolInt(r.Hoist), boolInt(r.Mentionable), i+1, int64(r.Permissions), nil, now, nullInt(r.Color2), nullInt(r.Color3), r.IconEmoji); err != nil {
				return err
			}
		}
		insertOne := func(i int) error {
			tc := spec.Channels[i]
			var parentID *ids.ID
			if tc.Type != "category" && tc.ParentIndex >= 0 && tc.ParentIndex < len(chanIDs) && spec.Channels[tc.ParentIndex].Type == "category" {
				pid := chanIDs[tc.ParentIndex]
				parentID = &pid
			}
			var forum *ForumSettings
			if tc.Forum != nil {
				cp := *tc.Forum
				cp.Tags = make([]ForumTag, len(tc.Forum.Tags))
				for j, t := range tc.Forum.Tags {
					if t.ID.IsZero() {
						t.ID = ids.New()
					}
					cp.Tags[j] = t
				}
				forum = &cp
			}
			ch := &Channel{
				ID:                 chanIDs[i],
				ServerID:           &srv.ID,
				Type:               tc.Type,
				Name:               tc.Name,
				Topic:              tc.Topic,
				Position:           tc.Position,
				ParentID:           parentID,
				NSFW:               tc.NSFW,
				SlowmodeSeconds:    tc.SlowmodeSeconds,
				UserLimit:          tc.UserLimit,
				Glyph:              tc.Glyph,
				DefaultAutoArchive: tc.DefaultAutoArchive,
				Forum:              forum,
				CreatedAt:          now,
			}
			if err := s.insertChannel(ctx, tx, ch); err != nil {
				return err
			}
			permOWs := make([]perms.Overwrite, 0, len(tc.Overwrites))
			for _, ow := range tc.Overwrites {
				target := srv.ID
				if ow.RoleIndex >= 0 && ow.RoleIndex < len(roleIDs) {
					target = roleIDs[ow.RoleIndex]
				}
				allow, deny := ow.Allow&^ow.Deny, ow.Deny
				if _, err := tx.ExecContext(ctx, `INSERT INTO channel_overwrites (channel_id, target_id, target_type, allow, deny) VALUES (?,?,'role',?,?) ON CONFLICT(channel_id, target_id) DO UPDATE SET allow = excluded.allow, deny = excluded.deny`,
					int64(ch.ID), int64(target), int64(allow), int64(deny)); err != nil {
					return err
				}
				permOWs = append(permOWs, perms.Overwrite{TargetID: target, Kind: "role", Allow: allow, Deny: deny})
			}
			if tc.Type != "category" && !perms.Compute(srv.ID, permRoles, perms.Member{}, false, permOWs).Has(perms.ViewChannel) {
				if _, err := tx.ExecContext(ctx, `INSERT INTO key_flags (conv_id, needs_rotation, needs_own_key, updated_at) VALUES (?,0,1,?) ON CONFLICT(conv_id) DO UPDATE SET needs_own_key = 1, updated_at = excluded.updated_at`,
					int64(ch.ID), now); err != nil {
					return err
				}
			}
			return nil
		}
		// Categories first so every child channel's parent_id already exists.
		for i, tc := range spec.Channels {
			if tc.Type == "category" {
				if err := insertOne(i); err != nil {
					return err
				}
			}
		}
		for i, tc := range spec.Channels {
			if tc.Type != "category" {
				if err := insertOne(i); err != nil {
					return err
				}
			}
		}
		if sysID != nil {
			if _, err := tx.ExecContext(ctx, `UPDATE servers SET system_channel_id = ? WHERE id = ?`, int64(*sysID), int64(srv.ID)); err != nil {
				return err
			}
		}
		_, err := tx.ExecContext(ctx, `INSERT INTO server_members (server_id, user_id, nickname, joined_at, flags, join_method) VALUES (?,?,?,?,0,'owner')`,
			int64(srv.ID), int64(owner), "", now)
		return err
	})
	if err != nil {
		return nil, err
	}
	s.serverChanged(srv.ID)
	srv.SystemChannelID = sysID
	return srv, nil
}

// ServerByID loads a server.
func (s *Store) ServerByID(ctx context.Context, id ids.ID) (*Server, error) {
	return scanServerTagged(s.db.R.QueryRowContext(ctx, `SELECT `+serverCols+`, `+serverTagCols+`, `+serverCommunityCols+` FROM servers WHERE id = ?`, int64(id)))
}

// ServersForUser lists the servers a user belongs to, oldest membership first.
func (s *Store) ServersForUser(ctx context.Context, uid ids.ID) ([]*Server, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT `+prefixed("s", serverCols)+`, `+prefixed("s", serverTagCols)+`, `+prefixed("s", serverCommunityCols)+` FROM servers s JOIN server_members m ON m.server_id = s.id WHERE m.user_id = ? ORDER BY m.joined_at, s.id`, int64(uid))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*Server{}
	for rows.Next() {
		srv, err := scanServerTagged(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, srv)
	}
	return out, rows.Err()
}

func prefixed(alias, cols string) string {
	parts := strings.Split(cols, ", ")
	for i, p := range parts {
		parts[i] = alias + "." + p
	}
	return strings.Join(parts, ", ")
}

// UpdateServer applies p.
func (s *Store) UpdateServer(ctx context.Context, id ids.ID, p ServerPatch) error {
	var sets []string
	var args []any
	set := func(col string, v any) { sets = append(sets, col+" = ?"); args = append(args, v) }
	if p.NSFW != nil {
		set("nsfw", boolInt(*p.NSFW))
	}
	if p.Name != nil {
		set("name", *p.Name)
	}
	if p.Description != nil {
		set("description", *p.Description)
	}
	if p.IconID != nil {
		set("icon_id", nullID(*p.IconID))
	}
	if p.BannerID != nil {
		set("banner_id", nullID(*p.BannerID))
	}
	if p.SystemChannelID != nil {
		set("system_channel_id", nullID(*p.SystemChannelID))
	}
	if p.DefaultNotifications != nil {
		set("default_notifications", *p.DefaultNotifications)
	}
	if p.OwnerID != nil {
		set("owner_id", int64(*p.OwnerID))
	}
	if p.InvitesPausedUntil != nil {
		set("invites_paused_until", nullInt64(*p.InvitesPausedUntil))
	}
	if p.Discoverable != nil {
		set("discoverable", boolInt(*p.Discoverable))
		// listed since the first time it went in, and again after it came out
		if *p.Discoverable {
			sets = append(sets, "discovery_since = coalesce(discovery_since, ?)")
			args = append(args, nowMs())
		} else {
			sets = append(sets, "discovery_since = NULL")
		}
	}
	if p.DiscoveryCategory != nil {
		set("discovery_category", *p.DiscoveryCategory)
	}
	if p.DiscoveryTags != nil {
		b, err := json.Marshal(*p.DiscoveryTags)
		if err != nil {
			return err
		}
		set("discovery_tags", string(b))
	}
	if p.DiscoveryLanguage != nil {
		set("discovery_language", *p.DiscoveryLanguage)
	}
	if p.DiscoveryHidden != nil {
		set("discovery_hidden", boolInt(*p.DiscoveryHidden))
	}
	if p.VerifyAccountMinutes != nil {
		set("verify_account_minutes", *p.VerifyAccountMinutes)
	}
	if p.VerifyMemberMinutes != nil {
		set("verify_member_minutes", *p.VerifyMemberMinutes)
	}
	if len(sets) == 0 {
		return nil
	}
	set("updated_at", nowMs())
	args = append(args, int64(id))
	res, err := s.db.W.ExecContext(ctx, `UPDATE servers SET `+strings.Join(sets, ", ")+` WHERE id = ?`, args...)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	if p.OwnerID != nil {
		s.serverChanged(id)
	}
	return nil
}

// DeleteServer removes a server and its key material.
func (s *Store) DeleteServer(ctx context.Context, id ids.ID) error {
	defer s.serverChanged(id)
	return s.Tx(ctx, func(tx *db.Tx) error {
		// one reference per attachment row, however many rows share a blob
		if _, err := tx.ExecContext(ctx, `UPDATE blobs SET ref_count = `+refFloor(tx.Dialect(), "ref_count - (SELECT count(*) FROM attachments a WHERE a.blob_id = blobs.id AND a.channel_id IN (SELECT id FROM channels WHERE server_id = ?))")+` WHERE id IN (SELECT blob_id FROM attachments WHERE channel_id IN (SELECT id FROM channels WHERE server_id = ?))`, int64(id), int64(id)); err != nil {
			return err
		}
		// the icon, banner, emoji, channel pictures and server sticker packs go with the server
		// (by cascade), so their images must be let go of here
		if err := releaseBlobRefs(ctx, tx, `SELECT icon_id FROM servers WHERE id = ?
			UNION ALL SELECT banner_id FROM servers WHERE id = ?
			UNION ALL SELECT blob_id FROM emojis WHERE server_id = ?
			UNION ALL SELECT st.blob_id FROM stickers st JOIN sticker_packs p ON p.id = st.pack_id WHERE p.server_id = ?
			UNION ALL SELECT avatar_id FROM webhooks WHERE server_id = ?
			UNION ALL SELECT avatar_id FROM server_members WHERE server_id = ?
			UNION ALL SELECT banner_id FROM server_members WHERE server_id = ?
			UNION ALL SELECT icon_id FROM roles WHERE server_id = ?
			UNION ALL SELECT icon_id FROM channels WHERE server_id = ?`,
			int64(id), int64(id), int64(id), int64(id), int64(id), int64(id), int64(id), int64(id), int64(id)); err != nil {
			return err
		}
		if err := releaseWidgetRefs(ctx, tx, `SELECT widgets FROM server_members WHERE server_id = ? AND widgets IS NOT NULL`, int64(id)); err != nil {
			return err
		}
		// its tag's picture goes too, and nobody wears the tag of a server that is gone
		var badge string
		if err := tx.QueryRowContext(ctx, `SELECT tag_badge FROM servers WHERE id = ?`, int64(id)).Scan(&badge); err != nil && err != sql.ErrNoRows {
			return err
		}
		if img, ok := TagImage(badge); ok {
			if _, err := tx.ExecContext(ctx, `UPDATE blobs SET ref_count = `+refFloor(tx.Dialect(), "ref_count - 1")+` WHERE id = ?`, int64(img)); err != nil {
				return err
			}
		}
		if _, err := tx.ExecContext(ctx, `UPDATE users SET tag_server_id = NULL, tag_text = '', tag_badge = '', tag_color = NULL WHERE tag_server_id = ?`, int64(id)); err != nil {
			return err
		}
		for _, q := range []string{
			`DELETE FROM key_grants WHERE conv_id = ? OR conv_id IN (SELECT id FROM channels WHERE server_id = ?)`,
			`DELETE FROM key_epochs WHERE conv_id = ? OR conv_id IN (SELECT id FROM channels WHERE server_id = ?)`,
			`DELETE FROM key_flags WHERE conv_id = ? OR conv_id IN (SELECT id FROM channels WHERE server_id = ?)`,
		} {
			if _, err := tx.ExecContext(ctx, q, int64(id), int64(id)); err != nil {
				return err
			}
		}
		res, err := tx.ExecContext(ctx, `DELETE FROM servers WHERE id = ?`, int64(id))
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return ErrNotFound
		}
		return nil
	})
}

// releaseBlobRefs takes one reference off every blob id the query returns,
// counting repeats, so an image used twice loses both of its references.
func releaseBlobRefs(ctx context.Context, tx *db.Tx, query string, args ...any) error {
	rows, err := tx.QueryContext(ctx, query, args...)
	if err != nil {
		return err
	}
	counts := map[int64]int{}
	for rows.Next() {
		var id sql.NullInt64
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return err
		}
		if id.Valid {
			counts[id.Int64]++
		}
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	for id, n := range counts {
		if _, err := tx.ExecContext(ctx, `UPDATE blobs SET ref_count = `+refFloor(tx.Dialect(), "ref_count - ?")+` WHERE id = ?`, n, id); err != nil {
			return err
		}
	}
	return nil
}

// --- members -------------------------------------------------------------

const memberCols = `server_id, user_id, nickname, avatar_id, joined_at, timeout_until, flags, banner_id, bio, pronouns, join_method, join_code, invited_by, accent_color, accent_color_2`

func scanMember(r rowScanner) (*Member, error) {
	var m Member
	var avatar, timeout, banner, inviter, accent, accent2 sql.NullInt64
	if err := r.Scan(&m.ServerID, &m.UserID, &m.Nickname, &avatar, &m.JoinedAt, &timeout, &m.Flags, &banner, &m.Bio, &m.Pronouns, &m.JoinMethod, &m.JoinCode, &inviter, &accent, &accent2); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	m.AvatarID = idPtr(avatar)
	m.BannerID = idPtr(banner)
	m.InvitedBy = idPtr(inviter)
	m.TimeoutUntil = int64Ptr(timeout)
	m.AccentColor = intPtr(accent)
	m.AccentColor2 = intPtr(accent2)
	m.RoleIDs = []ids.ID{}
	return &m, nil
}

// JoinInfo is how someone joined a server, recorded with their membership.
type JoinInfo struct {
	Method    string
	Code      string
	InvitedBy *ids.ID
}

// ErrBanned is returned when a banned user tries to join a server.
var ErrBanned = errors.New("banned")

// AddMember joins a user to a server; ErrAlreadyExists when already a member,
// ErrBanned when banned, ErrJoinsPaused while it takes nobody new.
func (s *Store) AddMember(ctx context.Context, serverID, uid ids.ID) (*Member, error) {
	return s.Join(ctx, serverID, uid, JoinInfo{})
}

// Join is AddMember recording how the user came in.
func (s *Store) Join(ctx context.Context, serverID, uid ids.ID, how JoinInfo) (*Member, error) {
	var m *Member
	err := s.Tx(ctx, func(tx *db.Tx) error {
		var err error
		m, err = addMember(ctx, tx, serverID, uid, how)
		return err
	})
	if err == nil {
		s.serverChanged(serverID)
	}
	return m, err
}

// ErrJoinsPaused is returned when a server has paused its invites.
var ErrJoinsPaused = errors.New("joins paused")

// JoinByInvite is AddMember spending one use of an invite in the same
// transaction, so people joining at once cannot overrun its limit. It is
// ErrNotFound when the invite is used up or expired; a join that fails
// spends nothing. An account's first join through the invite it signed up
// with spends nothing either: its sign-up spent that use.
func (s *Store) JoinByInvite(ctx context.Context, serverID, uid ids.ID, code string) (*Member, error) {
	var m *Member
	err := s.Tx(ctx, func(tx *db.Tx) error {
		var inviter sql.NullInt64
		var vanity int
		if err := tx.QueryRowContext(ctx, `SELECT inviter_id, vanity FROM invites WHERE code = ?`, code).Scan(&inviter, &vanity); err != nil && !errors.Is(err, sql.ErrNoRows) {
			return err
		}
		how := JoinInfo{Method: "invite", Code: code, InvitedBy: idPtr(inviter)}
		if vanity == 1 {
			// nobody invited them: the server's own link did
			how = JoinInfo{Method: "vanity", Code: code}
		}
		var err error
		if m, err = addMember(ctx, tx, serverID, uid, how); err != nil {
			return err
		}
		_, err = spendInvite(ctx, tx, uid, code)
		return err
	})
	if err != nil {
		return nil, err
	}
	s.serverChanged(serverID)
	return m, nil
}

// addMember inserts the membership unless the user is banned or the server
// has paused joins, checking both in the same statement so a ban or a pause
// written a moment earlier always holds.
func addMember(ctx context.Context, tx *db.Tx, serverID, uid ids.ID, how JoinInfo) (*Member, error) {
	now := nowMs()
	if how.Method != "owner" {
		var paused sql.NullInt64
		if err := tx.QueryRowContext(ctx, `SELECT invites_paused_until FROM servers WHERE id = ?`, int64(serverID)).Scan(&paused); err != nil {
			if errors.Is(err, sql.ErrNoRows) {
				return nil, ErrNotFound
			}
			return nil, err
		}
		if paused.Valid && paused.Int64 > now {
			var already int
			if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM server_members WHERE server_id = ? AND user_id = ?`, int64(serverID), int64(uid)).Scan(&already); err != nil {
				return nil, err
			}
			if already > 0 {
				return nil, ErrAlreadyExists
			}
			return nil, ErrJoinsPaused
		}
	}
	res, err := tx.ExecContext(ctx, `INSERT INTO server_members (server_id, user_id, nickname, joined_at, flags, join_method, join_code, invited_by)
		SELECT ?, ?, '', ?, 0, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM bans WHERE server_id = ? AND user_id = ?) ON CONFLICT DO NOTHING`,
		int64(serverID), int64(uid), now, how.Method, how.Code, nullID(how.InvitedBy), int64(serverID), int64(uid))
	if err != nil {
		return nil, err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		var banned int
		if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM bans WHERE server_id = ? AND user_id = ?`, int64(serverID), int64(uid)).Scan(&banned); err != nil {
			return nil, err
		}
		if banned > 0 {
			return nil, ErrBanned
		}
		return nil, ErrAlreadyExists
	}
	return &Member{ServerID: serverID, UserID: uid, JoinedAt: now, RoleIDs: []ids.ID{}, JoinMethod: how.Method, JoinCode: how.Code, InvitedBy: how.InvitedBy}, nil
}

// RemoveMember removes a membership (roles cascade).
//
// The member's own permission overwrites in the server's channels go with
// them. Left behind, they would wait for the person to come back — through
// an invite, after a ban is lifted — and let them straight back into private
// channels, while nobody could edit or remove an overwrite for someone who
// is not a member.
func (s *Store) RemoveMember(ctx context.Context, serverID, uid ids.ID) error {
	defer s.serverChanged(serverID)
	return s.Tx(ctx, func(tx *db.Tx) error {
		// their pictures in this server go with them, the ones in its widgets too
		if err := releaseBlobRefs(ctx, tx, `SELECT avatar_id FROM server_members WHERE server_id = ? AND user_id = ?
			UNION ALL SELECT banner_id FROM server_members WHERE server_id = ? AND user_id = ?`, int64(serverID), int64(uid), int64(serverID), int64(uid)); err != nil {
			return err
		}
		if err := releaseWidgetRefs(ctx, tx, `SELECT widgets FROM server_members WHERE server_id = ? AND user_id = ? AND widgets IS NOT NULL`, int64(serverID), int64(uid)); err != nil {
			return err
		}
		res, err := tx.ExecContext(ctx, `DELETE FROM server_members WHERE server_id = ? AND user_id = ?`, int64(serverID), int64(uid))
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return ErrNotFound
		}
		if _, err := tx.ExecContext(ctx, `DELETE FROM thread_members WHERE user_id = ? AND thread_id IN (SELECT id FROM channels WHERE server_id = ?)`, int64(uid), int64(serverID)); err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, `DELETE FROM channel_overwrites WHERE target_id = ? AND target_type = 'member' AND channel_id IN (SELECT id FROM channels WHERE server_id = ?)`, int64(uid), int64(serverID))
		return err
	})
}

// IsMember reports membership.
func (s *Store) IsMember(ctx context.Context, serverID, uid ids.ID) (bool, error) {
	var n int
	err := s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM server_members WHERE server_id = ? AND user_id = ?`, int64(serverID), int64(uid)).Scan(&n)
	return n > 0, err
}

// ShareServer reports whether a and b are members of at least one server
// together.
func (s *Store) ShareServer(ctx context.Context, a, b ids.ID) (bool, error) {
	var n int
	err := s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM (SELECT 1 FROM server_members m1
		JOIN server_members m2 ON m2.server_id = m1.server_id AND m2.user_id = ?
		WHERE m1.user_id = ? LIMIT 1) shared`, int64(b), int64(a)).Scan(&n)
	return n > 0, err
}

// Member loads one membership with its role ids.
func (s *Store) Member(ctx context.Context, serverID, uid ids.ID) (*Member, error) {
	m, err := scanMember(s.db.R.QueryRowContext(ctx, `SELECT `+memberCols+` FROM server_members WHERE server_id = ? AND user_id = ?`, int64(serverID), int64(uid)))
	if err != nil {
		return nil, err
	}
	rows, err := s.db.R.QueryContext(ctx, `SELECT role_id FROM member_roles WHERE server_id = ? AND user_id = ?`, int64(serverID), int64(uid))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var r int64
		if err := rows.Scan(&r); err != nil {
			return nil, err
		}
		m.RoleIDs = append(m.RoleIDs, ids.ID(r))
	}
	return m, rows.Err()
}

// Members lists memberships (with roles) after a user id, optionally filtered.
// ReadingBotIDs are the bots in a server whose application reads every
// message it can see (FlagBotReadsAll), which members' apps mark channels for.
func (s *Store) ReadingBotIDs(ctx context.Context, serverID ids.ID) ([]ids.ID, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT m.user_id FROM server_members m JOIN users u ON u.id = m.user_id WHERE m.server_id = ? AND (u.flags & ?) != 0`, int64(serverID), FlagBotReadsAll)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []ids.ID{}
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, ids.ID(id))
	}
	return out, rows.Err()
}

func (s *Store) Members(ctx context.Context, serverID ids.ID, after ids.ID, limit int, q string) ([]*Member, error) {
	if limit <= 0 || limit > 1000 {
		limit = 100
	}
	query := `SELECT ` + prefixed("m", memberCols) + ` FROM server_members m JOIN users u ON u.id = m.user_id WHERE m.server_id = ? AND m.user_id > ?`
	args := []any{int64(serverID), int64(after)}
	if q = strings.ToLower(strings.TrimSpace(q)); q != "" {
		like := escapeLike(q) + "%"
		query += ` AND (u.username LIKE ? ESCAPE '\' OR ` + s.db.Dialect.Lower("u.display_name") + ` LIKE ? ESCAPE '\' OR ` + s.db.Dialect.Lower("m.nickname") + ` LIKE ? ESCAPE '\')`
		args = append(args, like, like, like)
	}
	query += ` ORDER BY m.user_id LIMIT ?`
	args = append(args, limit)
	rows, err := s.db.R.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	out := []*Member{}
	byUser := map[ids.ID]*Member{}
	for rows.Next() {
		m, err := scanMember(rows)
		if err != nil {
			rows.Close()
			return nil, err
		}
		out = append(out, m)
		byUser[m.UserID] = m
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if len(out) == 0 {
		return out, nil
	}
	list := make([]ids.ID, 0, len(out))
	for _, m := range out {
		list = append(list, m.UserID)
	}
	rrows, err := s.db.R.QueryContext(ctx, `SELECT user_id, role_id FROM member_roles WHERE server_id = ? AND user_id IN (`+placeholders(len(list))+`)`, append([]any{int64(serverID)}, idArgs(list)...)...)
	if err != nil {
		return nil, err
	}
	defer rrows.Close()
	for rrows.Next() {
		var u, r int64
		if err := rrows.Scan(&u, &r); err != nil {
			return nil, err
		}
		if m := byUser[ids.ID(u)]; m != nil {
			m.RoleIDs = append(m.RoleIDs, ids.ID(r))
		}
	}
	return out, rrows.Err()
}

// MemberIDs lists every member's user id (none for a server that is gone).
// Every server event is sent to this list, so it comes from the server's
// permission snapshot rather than a query each time.
func (s *Store) MemberIDs(ctx context.Context, serverID ids.ID) ([]ids.ID, error) {
	out, err := s.serverMemberIDs(ctx, serverID)
	if errors.Is(err, ErrNotFound) {
		return []ids.ID{}, nil
	}
	return out, err
}

// MemberCount counts members.
func (s *Store) MemberCount(ctx context.Context, serverID ids.ID) (int, error) {
	var n int
	err := s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM server_members WHERE server_id = ?`, int64(serverID)).Scan(&n)
	return n, err
}

// MemberFlags reads a member's flags alone, for checks made on every voice
// state change, where loading the whole member would be waste.
func (s *Store) MemberFlags(ctx context.Context, serverID, uid ids.ID) (int64, error) {
	var flags int64
	err := s.db.R.QueryRowContext(ctx, `SELECT flags FROM server_members WHERE server_id = ? AND user_id = ?`, int64(serverID), int64(uid)).Scan(&flags)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, ErrNotFound
	}
	return flags, err
}

// SetMemberFlags turns the bits in on and off on a member's flags in one
// statement, so two changes to different bits at the same moment (AutoMod
// holding someone while a moderator mutes them in a call) both stick.
func (s *Store) SetMemberFlags(ctx context.Context, serverID, uid ids.ID, on, off int64) error {
	res, err := s.db.W.ExecContext(ctx, `UPDATE server_members SET flags = (flags & ~CAST(? AS BIGINT)) | CAST(? AS BIGINT) WHERE server_id = ? AND user_id = ?`, off|on, on, int64(serverID), int64(uid))
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	// a hold changes what the member may do (permcache.go reads the flags)
	s.serverChanged(serverID)
	return nil
}

// UpdateMember applies p.
func (s *Store) UpdateMember(ctx context.Context, serverID, uid ids.ID, p MemberPatch) error {
	var sets []string
	var args []any
	set := func(col string, v any) { sets = append(sets, col+" = ?"); args = append(args, v) }
	if p.Nickname != nil {
		set("nickname", *p.Nickname)
	}
	if p.AvatarID != nil {
		set("avatar_id", nullID(*p.AvatarID))
	}
	if p.TimeoutUntil != nil {
		set("timeout_until", nullInt64(*p.TimeoutUntil))
	}
	if p.BannerID != nil {
		set("banner_id", nullID(*p.BannerID))
	}
	if p.Bio != nil {
		set("bio", *p.Bio)
	}
	if p.Pronouns != nil {
		set("pronouns", *p.Pronouns)
	}
	if p.AccentColor != nil {
		set("accent_color", nullInt(*p.AccentColor))
	}
	if p.AccentColor2 != nil {
		set("accent_color_2", nullInt(*p.AccentColor2))
	}
	if p.Flags != nil {
		set("flags", *p.Flags)
	}
	if len(sets) == 0 {
		return nil
	}
	args = append(args, int64(serverID), int64(uid))
	res, err := s.db.W.ExecContext(ctx, `UPDATE server_members SET `+strings.Join(sets, ", ")+` WHERE server_id = ? AND user_id = ?`, args...)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	// a timeout and a quarantine both change what the member may do
	if p.TimeoutUntil != nil || p.Flags != nil {
		s.serverChanged(serverID)
	}
	return nil
}

// AddMemberRole assigns a role; idempotent.
func (s *Store) AddMemberRole(ctx context.Context, serverID, uid, roleID ids.ID) error {
	defer s.serverChanged(serverID)
	_, err := s.db.W.ExecContext(ctx, `INSERT INTO member_roles (server_id, user_id, role_id) VALUES (?,?,?) ON CONFLICT DO NOTHING`, int64(serverID), int64(uid), int64(roleID))
	return err
}

// RemoveMemberRole unassigns a role; idempotent.
func (s *Store) RemoveMemberRole(ctx context.Context, serverID, uid, roleID ids.ID) error {
	defer s.serverChanged(serverID)
	_, err := s.db.W.ExecContext(ctx, `DELETE FROM member_roles WHERE server_id = ? AND user_id = ? AND role_id = ?`, int64(serverID), int64(uid), int64(roleID))
	return err
}

// SetMemberRoles replaces a member's roles.
func (s *Store) SetMemberRoles(ctx context.Context, serverID, uid ids.ID, roleIDs []ids.ID) error {
	defer s.serverChanged(serverID)
	return s.Tx(ctx, func(tx *db.Tx) error {
		if _, err := tx.ExecContext(ctx, `DELETE FROM member_roles WHERE server_id = ? AND user_id = ?`, int64(serverID), int64(uid)); err != nil {
			return err
		}
		for _, r := range roleIDs {
			if _, err := tx.ExecContext(ctx, `INSERT INTO member_roles (server_id, user_id, role_id) VALUES (?,?,?) ON CONFLICT DO NOTHING`, int64(serverID), int64(uid), int64(r)); err != nil {
				return err
			}
		}
		return nil
	})
}

// --- roles ---------------------------------------------------------------

const roleCols = `id, server_id, name, color, hoist, mentionable, position, permissions, icon_id, created_at, color2, color3, icon_emoji`

// roleReadCols are what a role is read with: roleCols and, for a role a bot
// brought with it, the bot (written only by CreateManagedRole).
const roleReadCols = roleCols + `, managed_by`

func scanRole(r rowScanner) (*Role, error) {
	var ro Role
	var hoist, mention, permsRaw int64
	var icon, color2, color3, managed sql.NullInt64
	if err := r.Scan(&ro.ID, &ro.ServerID, &ro.Name, &ro.Color, &hoist, &mention, &ro.Position, &permsRaw, &icon, &ro.CreatedAt, &color2, &color3, &ro.IconEmoji, &managed); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	ro.Hoist, ro.Mentionable = hoist == 1, mention == 1
	ro.Permissions = perms.Perm(uint64(permsRaw))
	ro.IconID = idPtr(icon)
	ro.Color2, ro.Color3 = intPtr(color2), intPtr(color3)
	ro.ManagedBy = idPtr(managed)
	return &ro, nil
}

// CreateRole adds a role at the top of the hierarchy (below nothing but the owner).
func (s *Store) CreateRole(ctx context.Context, serverID ids.ID, name string, permsBits perms.Perm, color int) (*Role, error) {
	return s.createRole(ctx, serverID, name, permsBits, color, nil)
}

// CreateManagedRole adds the role a bot brings with it when it is added to a
// server: named after it, holding the permissions it asked for, and given to
// nobody but the bot.
func (s *Store) CreateManagedRole(ctx context.Context, serverID, botID ids.ID, name string, permsBits perms.Perm) (*Role, error) {
	return s.createRole(ctx, serverID, name, permsBits, 0, &botID)
}

func (s *Store) createRole(ctx context.Context, serverID ids.ID, name string, permsBits perms.Perm, color int, managedBy *ids.ID) (*Role, error) {
	ro := &Role{ID: ids.New(), ServerID: serverID, Name: name, Color: color, Permissions: permsBits, CreatedAt: nowMs(), ManagedBy: managedBy}
	defer s.serverChanged(serverID)
	err := s.Tx(ctx, func(tx *db.Tx) error {
		// Postgres runs these side by side; one at a time, so two cannot both
		// read the same "before" and act on it
		if err := tx.Serialise(ctx, "roles", int64(serverID)); err != nil {
			return err
		}
		// new roles start at the bottom of the hierarchy (just above @everyone),
		// so the creator always outranks them
		if _, err := tx.ExecContext(ctx, `UPDATE roles SET position = position + 1 WHERE server_id = ? AND position >= 1`, int64(serverID)); err != nil {
			return err
		}
		var n int
		if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM roles WHERE server_id = ?`, int64(serverID)).Scan(&n); err != nil {
			return err
		}
		if n >= MaxServerRoles {
			return ErrTooMany
		}
		ro.Position = 1
		_, err := tx.ExecContext(ctx, `INSERT INTO roles (`+roleReadCols+`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)`, int64(ro.ID), int64(serverID), ro.Name, ro.Color, 0, 0, ro.Position, int64(ro.Permissions), nil, ro.CreatedAt, nil, nil, "", nullID(managedBy))
		return err
	})
	if err != nil {
		return nil, err
	}
	return ro, nil
}

// RoleByID loads a role.
func (s *Store) RoleByID(ctx context.Context, id ids.ID) (*Role, error) {
	return scanRole(s.db.R.QueryRowContext(ctx, `SELECT `+roleReadCols+` FROM roles WHERE id = ?`, int64(id)))
}

// Roles lists a server's roles, lowest position first (@everyone at 0).
func (s *Store) Roles(ctx context.Context, serverID ids.ID) ([]*Role, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT `+roleReadCols+` FROM roles WHERE server_id = ? ORDER BY position, id`, int64(serverID))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*Role{}
	for rows.Next() {
		ro, err := scanRole(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, ro)
	}
	return out, rows.Err()
}

// UpdateRole applies p.
func (s *Store) UpdateRole(ctx context.Context, id ids.ID, p RolePatch) error {
	var sets []string
	var args []any
	set := func(col string, v any) { sets = append(sets, col+" = ?"); args = append(args, v) }
	if p.Name != nil {
		set("name", *p.Name)
	}
	if p.Color != nil {
		set("color", *p.Color)
	}
	if p.Hoist != nil {
		set("hoist", boolInt(*p.Hoist))
	}
	if p.Mentionable != nil {
		set("mentionable", boolInt(*p.Mentionable))
	}
	if p.Permissions != nil {
		set("permissions", int64(*p.Permissions))
	}
	if p.IconID != nil {
		set("icon_id", nullID(*p.IconID))
	}
	if p.Color2 != nil {
		set("color2", nullInt(*p.Color2))
	}
	if p.Color3 != nil {
		set("color3", nullInt(*p.Color3))
	}
	if p.IconEmoji != nil {
		set("icon_emoji", *p.IconEmoji)
	}
	if len(sets) == 0 {
		return nil
	}
	args = append(args, int64(id))
	var serverID ids.ID
	err := s.db.W.QueryRowContext(ctx, `UPDATE roles SET `+strings.Join(sets, ", ")+` WHERE id = ? RETURNING server_id`, args...).Scan(&serverID)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	s.serverChanged(serverID)
	return nil
}

// DeleteRole removes a role, its assignments and its overwrites. @everyone cannot be deleted.
func (s *Store) DeleteRole(ctx context.Context, id ids.ID) error {
	var serverID int64
	err := s.Tx(ctx, func(tx *db.Tx) error {
		if err := tx.QueryRowContext(ctx, `SELECT server_id FROM roles WHERE id = ?`, int64(id)).Scan(&serverID); err != nil {
			if errors.Is(err, sql.ErrNoRows) {
				return ErrNotFound
			}
			return err
		}
		if serverID == int64(id) {
			return ErrConflict
		}
		if _, err := tx.ExecContext(ctx, `DELETE FROM channel_overwrites WHERE target_type = 'role' AND target_id = ?`, int64(id)); err != nil {
			return err
		}
		if err := releaseBlobRefs(ctx, tx, `SELECT icon_id FROM roles WHERE id = ?`, int64(id)); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, `DELETE FROM roles WHERE id = ?`, int64(id))
		return err
	})
	if serverID != 0 {
		s.serverChanged(ids.ID(serverID))
	}
	return err
}

// SetRolePositions rewrites positions (@everyone stays at 0).
func (s *Store) SetRolePositions(ctx context.Context, serverID ids.ID, positions map[ids.ID]int) error {
	defer s.serverChanged(serverID)
	return s.Tx(ctx, func(tx *db.Tx) error {
		for id, pos := range positions {
			if id == serverID {
				continue
			}
			if pos < 1 {
				pos = 1
			}
			if _, err := tx.ExecContext(ctx, `UPDATE roles SET position = ? WHERE id = ? AND server_id = ?`, pos, int64(id), int64(serverID)); err != nil {
				return err
			}
		}
		return nil
	})
}

// --- server channels ------------------------------------------------------

// CreateServerChannel adds a channel at the end of its parent.
// A server holds at most this many channels (threads not counted) and roles
// (@everyone and apps' roles counted), as on Discord.
const (
	MaxServerChannels = 500
	MaxServerRoles    = 250
)

func (s *Store) CreateServerChannel(ctx context.Context, serverID ids.ID, typ, name string, parent *ids.ID, url string) (*Channel, error) {
	c := &Channel{ServerID: &serverID, Type: typ, Name: name, ParentID: parent, URL: url}
	err := s.Tx(ctx, func(tx *db.Tx) error {
		// categories are ordered among categories, channels among the other
		// channels under the same parent
		if err := tx.Serialise(ctx, "channels", int64(serverID)); err != nil {
			return err
		}
		var n int
		if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM channels WHERE server_id = ? AND type NOT IN ('thread','private_thread')`, int64(serverID)).Scan(&n); err != nil {
			return err
		}
		if n >= MaxServerChannels {
			return ErrTooMany
		}
		kind := `type = 'category'`
		if typ != "category" {
			kind = `type != 'category'`
		}
		var max sql.NullInt64
		if err := tx.QueryRowContext(ctx, `SELECT max(position) FROM channels WHERE server_id = ? AND `+kind+` AND coalesce(parent_id, 0) = ?`, int64(serverID), idOrZero(parent)).Scan(&max); err != nil {
			return err
		}
		if max.Valid {
			c.Position = int(max.Int64) + 1
		}
		return s.insertChannel(ctx, tx, c)
	})
	if err != nil {
		return nil, err
	}
	s.serverChanged(serverID)
	return s.ChannelByID(ctx, c.ID)
}

// ServerChannels lists a server's channels ordered by parent then position.
func (s *Store) ServerChannels(ctx context.Context, serverID ids.ID) ([]*Channel, error) {
	// threads are listed apart (Threads): a client that knows nothing of them
	// would draw each one as a channel of its own
	rows, err := s.db.R.QueryContext(ctx, `SELECT `+channelReadCols+` FROM channels WHERE server_id = ? AND type NOT IN ('thread','private_thread') ORDER BY coalesce(parent_id, 0), position, id`, int64(serverID))
	if err != nil {
		return nil, err
	}
	out := []*Channel{}
	for rows.Next() {
		c, err := scanChannel(rows)
		if err != nil {
			rows.Close()
			return nil, err
		}
		c.Recipients = []ids.ID{}
		c.KeyConvID = serverID
		out = append(out, c)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	// channels with their own epochs, or marked as needing their own key, encrypt under their own conversation
	own, err := s.db.R.QueryContext(ctx, `SELECT conv_id FROM key_epochs WHERE conv_id IN (SELECT id FROM channels WHERE server_id = ?) UNION SELECT conv_id FROM key_flags WHERE needs_own_key = 1 AND conv_id IN (SELECT id FROM channels WHERE server_id = ?)`, int64(serverID), int64(serverID))
	if err != nil {
		return nil, err
	}
	defer own.Close()
	ownSet := map[ids.ID]bool{}
	for own.Next() {
		var id int64
		if err := own.Scan(&id); err != nil {
			return nil, err
		}
		ownSet[ids.ID(id)] = true
	}
	for _, c := range out {
		if ownSet[c.ID] {
			c.KeyConvID = c.ID
		}
	}
	if err := own.Err(); err != nil {
		return nil, err
	}
	ows, err := s.OverwritesForServer(ctx, serverID)
	if err != nil {
		return nil, err
	}
	for _, c := range out {
		c.Overwrites = ows[c.ID]
		if c.Overwrites == nil {
			c.Overwrites = []Overwrite{}
		}
	}
	return out, nil
}

// SetChannelPositions applies a bulk reorder.
func (s *Store) SetChannelPositions(ctx context.Context, serverID ids.ID, list []ChannelPosition) error {
	return s.Tx(ctx, func(tx *db.Tx) error {
		for _, p := range list {
			if p.ParentSet {
				if _, err := tx.ExecContext(ctx, `UPDATE channels SET position = ?, parent_id = ?, updated_at = ? WHERE id = ? AND server_id = ?`, p.Position, nullID(p.ParentID), nowMs(), int64(p.ID), int64(serverID)); err != nil {
					return err
				}
			} else if _, err := tx.ExecContext(ctx, `UPDATE channels SET position = ?, updated_at = ? WHERE id = ? AND server_id = ?`, p.Position, nowMs(), int64(p.ID), int64(serverID)); err != nil {
				return err
			}
		}
		return nil
	})
}

// SetOverwrite upserts a channel overwrite.
func (s *Store) SetOverwrite(ctx context.Context, ow Overwrite) error {
	_, err := s.db.W.ExecContext(ctx, `INSERT INTO channel_overwrites (channel_id, target_id, target_type, allow, deny) VALUES (?,?,?,?,?) ON CONFLICT(channel_id, target_id) DO UPDATE SET target_type = excluded.target_type, allow = excluded.allow, deny = excluded.deny`,
		int64(ow.ChannelID), int64(ow.TargetID), ow.TargetType, int64(ow.Allow), int64(ow.Deny))
	s.changedIn(channelServer(ctx, s.db.R, ow.ChannelID))
	return err
}

// SetOverwrites replaces every overwrite of a channel with list, in one
// transaction: a channel synced with its category takes the category's.
func (s *Store) SetOverwrites(ctx context.Context, channelID ids.ID, list []Overwrite) error {
	err := s.Tx(ctx, func(tx *db.Tx) error {
		if _, err := tx.ExecContext(ctx, `DELETE FROM channel_overwrites WHERE channel_id = ?`, int64(channelID)); err != nil {
			return err
		}
		for _, ow := range list {
			if _, err := tx.ExecContext(ctx, `INSERT INTO channel_overwrites (channel_id, target_id, target_type, allow, deny) VALUES (?,?,?,?,?)`,
				int64(channelID), int64(ow.TargetID), ow.TargetType, int64(ow.Allow), int64(ow.Deny)); err != nil {
				return err
			}
		}
		return nil
	})
	s.changedIn(channelServer(ctx, s.db.R, channelID))
	return err
}

// DeleteOverwrite removes an overwrite.
func (s *Store) DeleteOverwrite(ctx context.Context, channelID, targetID ids.ID) error {
	res, err := s.db.W.ExecContext(ctx, `DELETE FROM channel_overwrites WHERE channel_id = ? AND target_id = ?`, int64(channelID), int64(targetID))
	s.changedIn(channelServer(ctx, s.db.R, channelID))
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// Overwrites lists a channel's overwrites.
func (s *Store) Overwrites(ctx context.Context, channelID ids.ID) ([]Overwrite, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT channel_id, target_id, target_type, allow, deny FROM channel_overwrites WHERE channel_id = ?`, int64(channelID))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Overwrite{}
	for rows.Next() {
		var ow Overwrite
		var a, d int64
		if err := rows.Scan(&ow.ChannelID, &ow.TargetID, &ow.TargetType, &a, &d); err != nil {
			return nil, err
		}
		ow.Allow, ow.Deny = perms.Perm(uint64(a)), perms.Perm(uint64(d))
		out = append(out, ow)
	}
	return out, rows.Err()
}

// OverwritesForServer returns every overwrite of a server keyed by channel.
func (s *Store) OverwritesForServer(ctx context.Context, serverID ids.ID) (map[ids.ID][]Overwrite, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT o.channel_id, o.target_id, o.target_type, o.allow, o.deny FROM channel_overwrites o JOIN channels c ON c.id = o.channel_id WHERE c.server_id = ?`, int64(serverID))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[ids.ID][]Overwrite{}
	for rows.Next() {
		var ow Overwrite
		var a, d int64
		if err := rows.Scan(&ow.ChannelID, &ow.TargetID, &ow.TargetType, &a, &d); err != nil {
			return nil, err
		}
		ow.Allow, ow.Deny = perms.Perm(uint64(a)), perms.Perm(uint64(d))
		out[ow.ChannelID] = append(out[ow.ChannelID], ow)
	}
	return out, rows.Err()
}

// SwapMemberImage points a member's avatar or banner in one server at newID
// (nil clears it), releasing the picture it pointed at before. The caller
// already holds a reference on newID.
func (s *Store) SwapMemberImage(ctx context.Context, serverID, uid ids.ID, banner bool, newID *ids.ID) error {
	col := "avatar_id"
	if banner {
		col = "banner_id"
	}
	return s.Tx(ctx, func(tx *db.Tx) error {
		if err := tx.Serialise(ctx, "image:server_members."+col, int64(serverID), int64(uid)); err != nil {
			return err
		}
		var old sql.NullInt64
		if err := tx.QueryRowContext(ctx, `SELECT `+col+` FROM server_members WHERE server_id = ? AND user_id = ?`, int64(serverID), int64(uid)).Scan(&old); err != nil {
			if errors.Is(err, sql.ErrNoRows) {
				return ErrNotFound
			}
			return err
		}
		if _, err := tx.ExecContext(ctx, `UPDATE server_members SET `+col+` = ? WHERE server_id = ? AND user_id = ?`, nullID(newID), int64(serverID), int64(uid)); err != nil {
			return err
		}
		if !old.Valid {
			return nil
		}
		_, err := tx.ExecContext(ctx, `UPDATE blobs SET ref_count = `+refFloor(tx.Dialect(), "ref_count - 1")+` WHERE id = ?`, old.Int64)
		return err
	})
}

// SwapRoleIcon points a role's picture at newID (nil clears it), releasing
// the one before. The caller already holds a reference on newID.
func (s *Store) SwapRoleIcon(ctx context.Context, roleID ids.ID, newID *ids.ID) error {
	var serverID int64
	err := s.Tx(ctx, func(tx *db.Tx) error {
		if err := tx.Serialise(ctx, "image:roles.icon_id", int64(roleID)); err != nil {
			return err
		}
		var old sql.NullInt64
		if err := tx.QueryRowContext(ctx, `SELECT icon_id, server_id FROM roles WHERE id = ?`, int64(roleID)).Scan(&old, &serverID); err != nil {
			if errors.Is(err, sql.ErrNoRows) {
				return ErrNotFound
			}
			return err
		}
		if _, err := tx.ExecContext(ctx, `UPDATE roles SET icon_id = ? WHERE id = ?`, nullID(newID), int64(roleID)); err != nil {
			return err
		}
		if !old.Valid {
			return nil
		}
		_, err := tx.ExecContext(ctx, `UPDATE blobs SET ref_count = `+refFloor(tx.Dialect(), "ref_count - 1")+` WHERE id = ?`, old.Int64)
		return err
	})
	if serverID != 0 {
		s.serverChanged(ids.ID(serverID))
	}
	return err
}

// SetJoinMethod says how a member came in, when the join itself could not.
func (s *Store) SetJoinMethod(ctx context.Context, serverID, uid ids.ID, method string) error {
	_, err := s.db.W.ExecContext(ctx, `UPDATE server_members SET join_method = ? WHERE server_id = ? AND user_id = ?`, method, int64(serverID), int64(uid))
	return err
}
