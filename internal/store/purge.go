package store

import (
	"context"
	"database/sql"
	"errors"

	"evilion.xyz/chat/internal/db"
	"evilion.xyz/chat/internal/ids"
)

// Deleting an account takes everything it made with it, whoever deletes it;
// someone deleting their own takes their direct message conversations too,
// for both people in them. The api package runs the steps in order, telling
// everyone as each lands; these are the pieces it runs them with. Every one of
// them can be run again after a failure part way, and finds only what is
// left.

// PurgeCounts is what deleting an account would take with it, shown before
// anyone confirms.
type PurgeCounts struct {
	Messages        int   `json:"messages"`
	Attachments     int   `json:"attachments"`
	AttachmentBytes int64 `json:"attachment_bytes"`
	OwnedServers    int   `json:"owned_servers"`
	OwnedGroups     int   `json:"owned_groups"`
	Servers         int   `json:"servers"`
	Groups          int   `json:"groups"`
	Packs           int   `json:"packs"`
	Reactions       int   `json:"reactions"`
	// DirectMessages is its one-to-one conversations, which go whole when
	// someone deletes their own account.
	DirectMessages int `json:"direct_messages"`
	// Notes is the notes it owns, which go for everyone they are shared with.
	Notes int `json:"notes"`
}

// PurgePreview counts what deleting the account would remove.
func (s *Store) PurgePreview(ctx context.Context, uid ids.ID) (PurgeCounts, error) {
	var c PurgeCounts
	id := int64(uid)
	err := s.db.R.QueryRowContext(ctx, `SELECT
		(SELECT count(*) FROM messages WHERE author_id = ?),
		(SELECT count(*) FROM attachments a JOIN messages m ON m.id = a.message_id WHERE m.author_id = ?),
		(SELECT coalesce(sum(a.size), 0) FROM attachments a JOIN messages m ON m.id = a.message_id WHERE m.author_id = ?),
		(SELECT count(*) FROM servers WHERE owner_id = ?),
		(SELECT count(*) FROM channels WHERE type = 'group_dm' AND owner_id = ?),
		(SELECT count(*) FROM server_members WHERE user_id = ?),
		(SELECT count(*) FROM dm_participants p JOIN channels c ON c.id = p.channel_id WHERE p.user_id = ? AND c.type = 'group_dm'),
		(SELECT count(*) FROM sticker_packs WHERE owner_user_id = ?),
		(SELECT count(*) FROM reactions WHERE user_id = ?),
		(SELECT count(*) FROM dm_participants p JOIN channels c ON c.id = p.channel_id WHERE p.user_id = ? AND c.type = 'dm'),
		(SELECT count(*) FROM notes WHERE owner_id = ?)`,
		id, id, id, id, id, id, id, id, id, id, id).Scan(&c.Messages, &c.Attachments, &c.AttachmentBytes, &c.OwnedServers, &c.OwnedGroups, &c.Servers, &c.Groups, &c.Packs, &c.Reactions, &c.DirectMessages, &c.Notes)
	return c, err
}

// DirectConversations lists the one-to-one conversations an account is in.
func (s *Store) DirectConversations(ctx context.Context, uid ids.ID) ([]*Channel, error) {
	list, err := s.idList(ctx, `SELECT c.id FROM channels c JOIN dm_participants p ON p.channel_id = c.id WHERE p.user_id = ? AND c.type = 'dm' ORDER BY c.id`, int64(uid))
	if err != nil {
		return nil, err
	}
	out := make([]*Channel, 0, len(list))
	for _, id := range list {
		ch, err := s.ChannelByID(ctx, id)
		if errors.Is(err, ErrNotFound) {
			continue
		}
		if err != nil {
			return nil, err
		}
		out = append(out, ch)
	}
	return out, nil
}

// DeletedAccountsWithLeftovers lists deleted accounts that something still
// belongs to: accounts deleted before deletion took everything, and any whose
// deletion stopped part way.
func (s *Store) DeletedAccountsWithLeftovers(ctx context.Context) ([]ids.ID, error) {
	return s.idList(ctx, `SELECT u.id FROM users u WHERE (u.flags & ?) != 0 AND (
		EXISTS (SELECT 1 FROM messages WHERE author_id = u.id) OR
		-- a one-to-one conversation, and the subchats under it, outlive an
		-- account a moderator deleted, for the person on the other side
		EXISTS (SELECT 1 FROM dm_participants p JOIN channels c ON c.id = p.channel_id WHERE p.user_id = u.id AND NOT (
			(c.type = 'dm' OR (c.type = 'subchat' AND EXISTS (SELECT 1 FROM channels pc WHERE pc.id = c.parent_id AND pc.type = 'dm')))
			AND `+deletedByStaff+`)) OR
		EXISTS (SELECT 1 FROM server_members WHERE user_id = u.id) OR
		EXISTS (SELECT 1 FROM servers WHERE owner_id = u.id) OR
		EXISTS (SELECT 1 FROM channels WHERE owner_id = u.id) OR
		EXISTS (SELECT 1 FROM reactions WHERE user_id = u.id) OR
		EXISTS (SELECT 1 FROM sticker_packs WHERE owner_user_id = u.id) OR
		EXISTS (SELECT 1 FROM sticker_installs WHERE user_id = u.id) OR
		EXISTS (SELECT 1 FROM recent_stickers WHERE user_id = u.id) OR
		EXISTS (SELECT 1 FROM read_states WHERE user_id = u.id) OR
		EXISTS (SELECT 1 FROM invites WHERE inviter_id = u.id AND vanity = 0) OR
		EXISTS (SELECT 1 FROM guest_links WHERE creator_id = u.id) OR
		EXISTS (SELECT 1 FROM uploads WHERE user_id = u.id) OR
		EXISTS (SELECT 1 FROM poll_votes WHERE user_id = u.id) OR
		EXISTS (SELECT 1 FROM client_errors WHERE user_id = u.id) OR
		EXISTS (SELECT 1 FROM bug_reports WHERE user_id = u.id) OR
		EXISTS (SELECT 1 FROM bans WHERE user_id = u.id) OR
		EXISTS (SELECT 1 FROM channel_overwrites WHERE target_id = u.id AND target_type = 'member') OR
		EXISTS (SELECT 1 FROM mod_restrictions WHERE user_id = u.id) OR
		EXISTS (SELECT 1 FROM sessions WHERE user_id = u.id) OR
		EXISTS (SELECT 1 FROM relationships WHERE user_id = u.id OR target_id = u.id) OR
		EXISTS (SELECT 1 FROM user_settings WHERE user_id = u.id) OR
		EXISTS (SELECT 1 FROM key_grants WHERE user_id = u.id) OR
		EXISTS (SELECT 1 FROM notes WHERE owner_id = u.id) OR
		EXISTS (SELECT 1 FROM note_members WHERE user_id = u.id) OR
		EXISTS (SELECT 1 FROM note_invites WHERE from_id = u.id OR to_id = u.id) OR
		EXISTS (SELECT 1 FROM user_sounds WHERE user_id = u.id)
	) ORDER BY u.id`, FlagDeleted)
}

// deletedByStaff says, of the account u, that a moderator deleted it rather
// than its owner: the moderation panel opens a case for that, and every
// deletion by staff, from the panel or the older admin route, is logged.
const deletedByStaff = `(EXISTS (SELECT 1 FROM mod_cases mc WHERE mc.user_id = u.id AND mc.action = 'delete') OR
	EXISTS (SELECT 1 FROM mod_log ml WHERE ml.target_id = u.id AND ml.action = 'delete'))`

// DeletedByStaff reports whether a moderator, not its owner, deleted the
// account; that decides whether its one-to-one conversations stay for the
// people on the other side of them.
func (s *Store) DeletedByStaff(ctx context.Context, uid ids.ID) (bool, error) {
	var yes bool
	err := s.db.R.QueryRowContext(ctx, `SELECT `+deletedByStaff+` FROM users u WHERE u.id = ?`, int64(uid)).Scan(&yes)
	if errors.Is(err, sql.ErrNoRows) {
		return false, ErrNotFound
	}
	return yes, err
}

// PurgeUserMessages deletes up to batch of the account's messages, from every
// channel, in one transaction, releasing their attachments and fixing each
// channel's newest message. It returns what it deleted, by channel; an empty
// map means there is nothing left. Batches keep one account's history from
// holding the database's only writer for as long as the whole of it takes.
func (s *Store) PurgeUserMessages(ctx context.Context, uid ids.ID, batch int) (map[ids.ID]*Deleted, error) {
	if batch <= 0 {
		batch = 500
	}
	out := map[ids.ID]*Deleted{}
	err := s.Tx(ctx, func(tx *db.Tx) error {
		clear(out)
		rows, err := tx.QueryContext(ctx, `SELECT id, channel_id, `+mentionCols+` FROM messages WHERE author_id = ? ORDER BY id LIMIT ?`, int64(uid), batch)
		if err != nil {
			return err
		}
		var order []ids.ID
		for rows.Next() {
			var id, ch, author, everyone int64
			var mentions, roles string
			if err := rows.Scan(&id, &ch, &author, &mentions, &roles, &everyone); err != nil {
				rows.Close()
				return err
			}
			if out[ids.ID(ch)] == nil {
				out[ids.ID(ch)] = newDeleted()
			}
			out[ids.ID(ch)].note(id, author, mentions, roles, everyone)
			order = append(order, ids.ID(id))
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
		for _, id := range order {
			if err := deleteMessageTx(ctx, tx, id); err != nil {
				return err
			}
		}
		for ch := range out {
			if err := fixLastMessageTx(ctx, tx, ch); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	return out, nil
}

// ReactionRef locates one reaction, for telling its channel it went.
type ReactionRef struct {
	ChannelID ids.ID
	MessageID ids.ID
	Emoji     string
}

// PurgeUserReactions removes every reaction the account left and returns
// where they were, up to limit of them, so open channels can be told.
func (s *Store) PurgeUserReactions(ctx context.Context, uid ids.ID, limit int) ([]ReactionRef, error) {
	var out []ReactionRef
	err := s.Tx(ctx, func(tx *db.Tx) error {
		out = out[:0]
		rows, err := tx.QueryContext(ctx, `SELECT m.channel_id, r.message_id, r.emoji FROM reactions r JOIN messages m ON m.id = r.message_id WHERE r.user_id = ? LIMIT ?`, int64(uid), limit)
		if err != nil {
			return err
		}
		for rows.Next() {
			var ref ReactionRef
			if err := rows.Scan(&ref.ChannelID, &ref.MessageID, &ref.Emoji); err != nil {
				rows.Close()
				return err
			}
			out = append(out, ref)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, `DELETE FROM reactions WHERE user_id = ?`, int64(uid))
		return err
	})
	return out, err
}

// OwnedConversations lists the group DMs and subchats the account owns, which
// go with it rather than passing to somebody else.
func (s *Store) OwnedConversations(ctx context.Context, uid ids.ID) ([]*Channel, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT id FROM channels WHERE owner_id = ? AND type IN ('group_dm', 'subchat') ORDER BY CASE type WHEN 'subchat' THEN 0 ELSE 1 END, id`, int64(uid))
	if err != nil {
		return nil, err
	}
	var list []ids.ID
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return nil, err
		}
		list = append(list, ids.ID(id))
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	out := make([]*Channel, 0, len(list))
	for _, id := range list {
		ch, err := s.ChannelByID(ctx, id)
		if errors.Is(err, ErrNotFound) {
			// a subchat that went with its group a step earlier
			continue
		}
		if err != nil {
			return nil, err
		}
		out = append(out, ch)
	}
	return out, nil
}

// PacksOwnedBy lists the sticker and emoji packs an account owns.
func (s *Store) PacksOwnedBy(ctx context.Context, uid ids.ID) ([]ids.ID, error) {
	return s.idList(ctx, `SELECT id FROM sticker_packs WHERE owner_user_id = ?`, int64(uid))
}

// UploadsOf lists an account's unfinished uploads.
func (s *Store) UploadsOf(ctx context.Context, uid ids.ID) ([]ids.ID, error) {
	return s.idList(ctx, `SELECT id FROM uploads WHERE user_id = ?`, int64(uid))
}

func (s *Store) idList(ctx context.Context, q string, args ...any) ([]ids.ID, error) {
	rows, err := s.db.R.QueryContext(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []ids.ID
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, ids.ID(id))
	}
	return out, rows.Err()
}

// PurgeUserLeftovers removes what the other steps do not reach: poll votes,
// the invites and guest links it made, its unfinished uploads, its read
// positions, recent stickers and installed packs, its per-server avatars
// (whose pictures are let go), its Nexa read position and, for staff, what it
// read of the case threads and bug reports, the crash reports its
// devices sent and the bug reports it wrote (whose files are let go), the
// files of its sound packs, the
// notes it owns (with their versions and files) and its place in the ones it
// was shared (which are marked for a new key), its note invitations, server
// bans and channel permissions naming it, and any mute or ban still on it.
// The moderation cases about it, the log and the usernames held for it stay:
// they are the record of what was done, and a name taken away by a moderator
// stays out of anyone else's hands.
func (s *Store) PurgeUserLeftovers(ctx context.Context, uid ids.ID) error {
	id := int64(uid)
	// its overwrites, in whichever servers they were
	defer s.everythingChanged()
	return s.Tx(ctx, func(tx *db.Tx) error {
		if err := releaseBlobRefs(ctx, tx, `SELECT avatar_id FROM server_members WHERE user_id = ? AND avatar_id IS NOT NULL
			UNION ALL SELECT banner_id FROM server_members WHERE user_id = ? AND banner_id IS NOT NULL`, id, id); err != nil {
			return err
		}
		if err := releaseWidgetRefs(ctx, tx, `SELECT widgets FROM server_members WHERE user_id = ? AND widgets IS NOT NULL`, id); err != nil {
			return err
		}
		if err := deleteUserBugReportsTx(ctx, tx, id); err != nil {
			return err
		}
		// the files of its sound packs, which nobody else ever plays
		if err := deleteUserSoundsTx(ctx, tx, id); err != nil {
			return err
		}
		// the notes it owned go, with their files; the ones it was only
		// shared stay with their owners, without it
		if err := deleteNotesTx(ctx, tx, `owner_id = ?`, id); err != nil {
			return err
		}
		for _, q := range []string{
			// it held their keys: each gets a new one from its owner's app
			`UPDATE notes SET needs_rekey = 1 WHERE id IN (SELECT note_id FROM note_members WHERE user_id = ?)`,
			`DELETE FROM note_members WHERE user_id = ?`,
			`DELETE FROM note_invites WHERE from_id = ?`,
			`DELETE FROM note_invites WHERE to_id = ?`,
			`UPDATE server_members SET avatar_id = NULL, banner_id = NULL, bio = '', pronouns = '', accent_color = NULL, accent_color_2 = NULL, widgets = NULL WHERE user_id = ?`,
			`DELETE FROM thread_members WHERE user_id = ?`,
			`DELETE FROM poll_votes WHERE user_id = ?`,
			// a vanity link is the server's, whoever set it
			`DELETE FROM invites WHERE inviter_id = ? AND vanity = 0`,
			`DELETE FROM guest_links WHERE creator_id = ?`,
			`DELETE FROM uploads WHERE user_id = ?`,
			`DELETE FROM read_states WHERE user_id = ?`,
			`DELETE FROM recent_stickers WHERE user_id = ?`,
			`DELETE FROM sticker_installs WHERE user_id = ?`,
			`DELETE FROM nexa_reads WHERE user_id = ?`,
			`DELETE FROM mod_reads WHERE user_id = ?`,
			`DELETE FROM client_errors WHERE user_id = ?`,
			`DELETE FROM bans WHERE user_id = ?`,
			`DELETE FROM channel_overwrites WHERE target_id = ? AND target_type = 'member'`,
			`DELETE FROM mod_restrictions WHERE user_id = ?`,
		} {
			if _, err := tx.ExecContext(ctx, q, id); err != nil {
				return err
			}
		}
		return nil
	})
}
