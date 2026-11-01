package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"strings"

	"evilion.xyz/chat/internal/db"
	"evilion.xyz/chat/internal/ids"
)

const channelCols = `id, server_id, type, name, topic, position, parent_id, nsfw, slowmode_seconds, user_limit, owner_id, icon_id, last_message_id, created_at, updated_at, glyph, archived, archived_at, thread_locked, auto_archive, starter_id, message_count, applied_tags, forum, url`

// channelReadCols adds what is read but never written back through insertChannel.
// The comparison is spelled as a CASE so it comes back as 0/1 on both
// databases; Postgres would otherwise hand over a boolean.
const channelReadCols = channelCols + `, CASE WHEN lock_hash <> '' THEN 1 ELSE 0 END AS locked`

// MaxSubchats caps how many side conversations one DM can carry.
const MaxSubchats = 50

// IsDMLike reports whether a channel type is a conversation between people
// rather than part of a server: a DM, a group, or a subchat inside one.
func IsDMLike(t string) bool { return t == "dm" || t == "group_dm" || t == "subchat" }

func scanChannel(r rowScanner) (*Channel, error) {
	var c Channel
	var server, parent, owner, icon, last, archivedAt, starter sql.NullInt64
	var nsfw int64
	var updated int64
	var locked int64
	var archived, threadLocked int64
	var autoArchive, messages int
	var tags, forum, url string
	if err := r.Scan(&c.ID, &server, &c.Type, &c.Name, &c.Topic, &c.Position, &parent, &nsfw, &c.SlowmodeSeconds, &c.UserLimit, &owner, &icon, &last, &c.CreatedAt, &updated, &c.Glyph,
		&archived, &archivedAt, &threadLocked, &autoArchive, &starter, &messages, &tags, &forum, &url, &locked); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	c.ServerID, c.ParentID, c.OwnerID, c.IconID, c.LastMessageID = idPtr(server), idPtr(parent), idPtr(owner), idPtr(icon), idPtr(last)
	c.NSFW = nsfw == 1
	c.Locked = locked == 1
	switch {
	case IsThread(c.Type):
		c.Thread = &ThreadMeta{Archived: archived == 1, ArchivedAt: int64Ptr(archivedAt), Locked: threadLocked == 1, AutoArchive: autoArchive,
			StarterID: idPtr(starter), Messages: messages, AppliedTags: parseIDsJSONOrEmpty(tags)}
	case HoldsThreads(c.Type):
		c.DefaultAutoArchive = autoArchive
		if IsForum(c.Type) {
			c.Forum = parseForum(forum, c.Type)
		}
	case c.Type == ChannelLink:
		c.URL = url
	}
	// the default key conversation; callers that know about private-channel epochs refine it
	if c.ServerID != nil {
		c.KeyConvID = *c.ServerID
	} else {
		c.KeyConvID = c.ID
	}
	return &c, nil
}

func (s *Store) insertChannel(ctx context.Context, q querier, c *Channel) error {
	if c.ID.IsZero() {
		c.ID = ids.New()
	}
	if c.CreatedAt == 0 {
		c.CreatedAt = nowMs()
	}
	var archived, threadLocked int64
	var autoArchive int
	var starter *ids.ID
	tags, forum := "", ""
	if c.Thread != nil {
		archived, threadLocked, autoArchive, starter = boolInt(c.Thread.Archived), boolInt(c.Thread.Locked), c.Thread.AutoArchive, c.Thread.StarterID
		if len(c.Thread.AppliedTags) > 0 {
			tags = idsJSON(c.Thread.AppliedTags)
		}
	} else {
		autoArchive = c.DefaultAutoArchive
	}
	if c.Forum != nil {
		b, err := json.Marshal(c.Forum)
		if err != nil {
			return err
		}
		forum = string(b)
	}
	_, err := q.ExecContext(ctx, `INSERT INTO channels (`+channelCols+`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
		int64(c.ID), nullID(c.ServerID), c.Type, c.Name, c.Topic, c.Position, nullID(c.ParentID), boolInt(c.NSFW), c.SlowmodeSeconds, c.UserLimit, nullID(c.OwnerID), nullID(c.IconID), nullID(c.LastMessageID), c.CreatedAt, c.CreatedAt, c.Glyph,
		archived, nil, threadLocked, autoArchive, nullID(starter), 0, tags, forum, c.URL)
	return err
}

// parseIDsJSONOrEmpty reads a JSON list of ids, or an empty column, as a list.
func parseIDsJSONOrEmpty(s string) []ids.ID {
	if s == "" {
		return []ids.ID{}
	}
	return parseIDsJSON(s)
}

// parseForum reads a forum or media channel's settings, filling in what an
// empty or older column leaves out. A media channel is always a gallery.
func parseForum(s, typ string) *ForumSettings {
	f := &ForumSettings{}
	if s != "" {
		_ = json.Unmarshal([]byte(s), f)
	}
	if f.Tags == nil {
		f.Tags = []ForumTag{}
	}
	if f.Sort == "" {
		f.Sort = "activity"
	}
	if f.Layout != "list" && f.Layout != "gallery" {
		f.Layout = "list"
	}
	if typ == ChannelMedia {
		f.Layout = "gallery"
	} else {
		f.HideDownloads = false
	}
	return f
}

// CreateDM returns the DM between a and b, creating it when missing, in both
// sidebars at once.
func (s *Store) CreateDM(ctx context.Context, a, b ids.ID) (*Channel, bool, error) {
	return s.createDM(ctx, a, b, false)
}

// StartDM is CreateDM for a opening a conversation with b: a DM it creates
// stays out of b's sidebar until something happens in it (a message, a call;
// UnhideDM), so nobody is shown an empty conversation that someone opened and
// never wrote in.
func (s *Store) StartDM(ctx context.Context, a, b ids.ID) (*Channel, bool, error) {
	return s.createDM(ctx, a, b, true)
}

// DMExists reports whether a and b already have a conversation of their own.
func (s *Store) DMExists(ctx context.Context, a, b ids.ID) (bool, error) {
	var id int64
	err := s.db.R.QueryRowContext(ctx, `SELECT c.id FROM channels c
		JOIN dm_participants p1 ON p1.channel_id = c.id AND p1.user_id = ?
		JOIN dm_participants p2 ON p2.channel_id = c.id AND p2.user_id = ?
		WHERE c.type = 'dm' LIMIT 1`, int64(a), int64(b)).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	return err == nil, err
}

func (s *Store) createDM(ctx context.Context, a, b ids.ID, quiet bool) (*Channel, bool, error) {
	c := &Channel{Type: "dm"}
	var existing int64
	now := nowMs()
	// The lookup runs inside the write transaction, holding the pair. Without
	// that, two people tapping Message on each other at the same moment both
	// read "no DM here" and both insert one: two conversations for one pair,
	// messages landing in whichever the client happened to open, and neither
	// side seeing the other's. SQLite's single writer connection made this
	// safe on its own; Postgres runs sixteen.
	lo, hi := a, b
	if lo > hi {
		lo, hi = hi, lo
	}
	err := s.Tx(ctx, func(tx *db.Tx) error {
		if err := tx.Serialise(ctx, "dm-pair", int64(lo), int64(hi)); err != nil {
			return err
		}
		err := tx.QueryRowContext(ctx, `SELECT c.id FROM channels c
			JOIN dm_participants p1 ON p1.channel_id = c.id AND p1.user_id = ?
			JOIN dm_participants p2 ON p2.channel_id = c.id AND p2.user_id = ?
			WHERE c.type = 'dm' ORDER BY c.id LIMIT 1`, int64(a), int64(b)).Scan(&existing)
		if !errors.Is(err, sql.ErrNoRows) {
			return err
		}
		if err := s.insertChannel(ctx, tx, c); err != nil {
			return err
		}
		for _, uid := range []ids.ID{a, b} {
			hidden := quiet && uid == b
			if _, err := tx.ExecContext(ctx, `INSERT INTO dm_participants (channel_id, user_id, added_by, added_at, hidden) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING`, int64(c.ID), int64(uid), int64(a), now, boolInt(hidden)); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		return nil, false, err
	}
	if existing != 0 {
		c, err := s.ChannelByID(ctx, ids.ID(existing))
		return c, false, err
	}
	c.Recipients = []ids.ID{a, b}
	// A DM is its own key conversation. Left unset this marshalled as "0", and
	// every client that read key_conv_id without a guard against that string
	// encrypted the first message of a new DM for the wrong conversation.
	c.KeyConvID = c.ID
	return c, true, nil
}

// CreateGroupDM creates a group with owner and recipients. Everyone but the
// owner was put in it by the owner directly, which Nexa tells them about
// (GroupAddNotice).
func (s *Store) CreateGroupDM(ctx context.Context, owner ids.ID, recipients []ids.ID, name string) (*Channel, error) {
	c := &Channel{Type: "group_dm", Name: name}
	o := owner
	c.OwnerID = &o
	members := append([]ids.ID{owner}, recipients...)
	now := nowMs()
	err := s.Tx(ctx, func(tx *db.Tx) error {
		if err := s.insertChannel(ctx, tx, c); err != nil {
			return err
		}
		for _, uid := range members {
			if _, err := tx.ExecContext(ctx, `INSERT INTO dm_participants (channel_id, user_id, added_by, added_at, add_notice_by) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING`, int64(c.ID), int64(uid), int64(owner), now, addNoticeBy(uid, owner)); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	return s.ChannelByID(ctx, c.ID)
}

// ChannelByID loads a channel (with recipients for DMs).
func (s *Store) ChannelByID(ctx context.Context, id ids.ID) (*Channel, error) {
	c, err := scanChannel(s.db.R.QueryRowContext(ctx, `SELECT `+channelReadCols+` FROM channels WHERE id = ?`, int64(id)))
	if err != nil {
		return nil, err
	}
	if IsDMLike(c.Type) {
		if c.Recipients, err = s.Participants(ctx, c.ID); err != nil {
			return nil, err
		}
		c.KeyConvID = c.ID
		return c, nil
	}
	c.Recipients = []ids.ID{}
	if c.ServerID != nil {
		c.KeyConvID = *c.ServerID
		if _, own, err := s.LatestEpoch(ctx, c.ID); err != nil {
			return nil, err
		} else if own {
			c.KeyConvID = c.ID
		} else if flags, err := s.KeyFlags(ctx, c.ID); err == nil && flags.NeedsOwnKey {
			c.KeyConvID = c.ID
		} else if IsThread(c.Type) && c.ParentID != nil {
			// a thread without keys of its own is sealed as its channel is
			if _, parentOwn, err := s.LatestEpoch(ctx, *c.ParentID); err != nil {
				return nil, err
			} else if parentOwn {
				c.KeyConvID = *c.ParentID
			}
		}
		if c.Overwrites, err = s.Overwrites(ctx, c.ID); err != nil {
			return nil, err
		}
	}
	return c, nil
}

// ChannelForAccess loads a channel as access checks need it: its row, and for
// a conversation its participants. Unlike ChannelByID it does not look up a
// server channel's overwrites or key conversation — the permission snapshot
// has the one, and the checks never need the other — so KeyConvID and
// Overwrites are not to be read from what it returns.
func (s *Store) ChannelForAccess(ctx context.Context, id ids.ID) (*Channel, error) {
	c, err := scanChannel(s.db.R.QueryRowContext(ctx, `SELECT `+channelReadCols+` FROM channels WHERE id = ?`, int64(id)))
	if err != nil {
		return nil, err
	}
	if IsDMLike(c.Type) {
		if c.Recipients, err = s.Participants(ctx, c.ID); err != nil {
			return nil, err
		}
		c.KeyConvID = c.ID
		return c, nil
	}
	c.Recipients = []ids.ID{}
	return c, nil
}

// DMPartners lists everyone uid shares a conversation with — a DM, a group or
// a subchat — each once.
func (s *Store) DMPartners(ctx context.Context, uid ids.ID) ([]ids.ID, error) {
	return s.idList(ctx, `SELECT DISTINCT p2.user_id FROM dm_participants p1 JOIN dm_participants p2 ON p2.channel_id = p1.channel_id
		WHERE p1.user_id = ? AND p2.user_id != ?`, int64(uid), int64(uid))
}

// GroupDMsForUser lists every group DM the user is in, hidden ones included.
func (s *Store) GroupDMsForUser(ctx context.Context, uid ids.ID) ([]ids.ID, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT c.id FROM channels c JOIN dm_participants p ON p.channel_id = c.id WHERE c.type = 'group_dm' AND p.user_id = ?`, int64(uid))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []ids.ID{}
	for rows.Next() {
		var id ids.ID
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}

// DMChannelsForUser lists the DMs and group DMs the user is in.
func (s *Store) DMChannelsForUser(ctx context.Context, uid ids.ID) ([]*Channel, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT `+channelReadCols+` FROM channels c WHERE c.type IN ('dm','group_dm','subchat') AND EXISTS (SELECT 1 FROM dm_participants p WHERE p.channel_id = c.id AND p.user_id = ? AND p.hidden = 0) ORDER BY coalesce(c.last_message_id, c.id) DESC`, int64(uid))
	if err != nil {
		return nil, err
	}
	var out []*Channel
	byID := map[ids.ID]*Channel{}
	for rows.Next() {
		c, err := scanChannel(rows)
		if err != nil {
			rows.Close()
			return nil, err
		}
		c.Recipients = []ids.ID{}
		out = append(out, c)
		byID[c.ID] = c
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if len(out) == 0 {
		return []*Channel{}, nil
	}
	list := make([]ids.ID, 0, len(out))
	for _, c := range out {
		list = append(list, c.ID)
	}
	prow, err := s.db.R.QueryContext(ctx, `SELECT channel_id, user_id FROM dm_participants WHERE channel_id IN (`+placeholders(len(list))+`) ORDER BY added_at, user_id`, idArgs(list)...)
	if err != nil {
		return nil, err
	}
	defer prow.Close()
	for prow.Next() {
		var ch, u int64
		if err := prow.Scan(&ch, &u); err != nil {
			return nil, err
		}
		if c := byID[ids.ID(ch)]; c != nil {
			c.Recipients = append(c.Recipients, ids.ID(u))
		}
	}
	return out, prow.Err()
}

// CreateSubchat opens a side conversation inside a DM or group, with the same
// people in it. It gets its own key conversation, so its messages are sealed
// separately from the parent's.
//
// A password, when there is one, goes on inside the same transaction: setting
// it afterwards meant a failure left an *unlocked* subchat with the requested
// name in the conversation, visible and readable to everyone in it, behind a
// 500 the caller reasonably read as "nothing happened".
func (s *Store) CreateSubchat(ctx context.Context, parent *Channel, owner ids.ID, name string, lock *ChannelLock) (*Channel, error) {
	ch := &Channel{Type: "subchat", Name: name, ParentID: &parent.ID, OwnerID: &owner}
	var members []ids.ID
	err := s.Tx(ctx, func(tx *db.Tx) error {
		// counting and then inserting against that count is only safe while
		// this conversation is nobody else's to add to
		if err := tx.Serialise(ctx, "subchats", int64(parent.ID)); err != nil {
			return err
		}
		var n int
		if err := tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM channels WHERE parent_id = ? AND type = 'subchat'`, int64(parent.ID)).Scan(&n); err != nil {
			return err
		}
		if n >= MaxSubchats {
			return ErrTooMany
		}
		// the members are read inside the transaction, so someone who left the
		// conversation a moment ago is not copied in from a stale list
		var err error
		if members, err = idsTx(ctx, tx, `SELECT user_id FROM dm_participants WHERE channel_id = ? ORDER BY added_at, user_id`, int64(parent.ID)); err != nil {
			return err
		}
		if len(members) == 0 {
			return ErrNotFound
		}
		if err := s.insertChannel(ctx, tx, ch); err != nil {
			return err
		}
		now := nowMs()
		for _, uid := range members {
			if _, err := tx.ExecContext(ctx, `INSERT INTO dm_participants (channel_id, user_id, added_by, added_at, hidden) VALUES (?,?,?,?,0) ON CONFLICT DO NOTHING`,
				int64(ch.ID), int64(uid), int64(owner), now); err != nil {
				return err
			}
		}
		if lock != nil && lock.Locked() {
			if _, err := tx.ExecContext(ctx, `UPDATE channels SET lock_salt = ?, lock_params = ?, lock_hash = ?, updated_at = ? WHERE id = ?`,
				nullBytes(lock.Salt), lock.Params, lock.Hash, now, int64(ch.ID)); err != nil {
				return err
			}
			ch.Locked = true
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	ch.Recipients = members
	ch.KeyConvID = ch.ID
	return ch, nil
}

// SubchatsFor lists the side conversations of one DM, newest activity first.
func (s *Store) SubchatsFor(ctx context.Context, parentID ids.ID) ([]*Channel, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT `+channelReadCols+` FROM channels WHERE parent_id = ? AND type = 'subchat' ORDER BY coalesce(last_message_id, id) DESC`, int64(parentID))
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
		c.KeyConvID = c.ID
		out = append(out, c)
	}
	// The cursor is closed before the participants are fetched. Asking for a
	// second connection while this one holds an open result set meant eight
	// concurrent calls could take all eight readers with their cursors and
	// then all wait for a ninth — a deadlock that only ends when each client
	// gives up, with every other read on Nexa queued behind it.
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	for _, c := range out {
		var err error
		if c.Recipients, err = s.Participants(ctx, c.ID); err != nil {
			return nil, err
		}
	}
	return out, nil
}

// IsParticipant reports whether uid is in the DM channel.
func (s *Store) IsParticipant(ctx context.Context, chID, uid ids.ID) (bool, error) {
	var n int
	err := s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM dm_participants WHERE channel_id = ? AND user_id = ?`, int64(chID), int64(uid)).Scan(&n)
	return n > 0, err
}

// Participants lists the members of a DM channel in join order.
func (s *Store) Participants(ctx context.Context, chID ids.ID) ([]ids.ID, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT user_id FROM dm_participants WHERE channel_id = ? ORDER BY added_at, user_id`, int64(chID))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []ids.ID{}
	for rows.Next() {
		var u int64
		if err := rows.Scan(&u); err != nil {
			return nil, err
		}
		out = append(out, ids.ID(u))
	}
	return out, rows.Err()
}

// AddParticipant adds uid to a group DM and to every subchat inside it, since
// a subchat holds the same people as its group. It returns the subchats uid
// joined, or ErrAlreadyExists when uid is already in the group. Someone added
// by another member (addedBy is not uid) is told so by Nexa; someone who
// joined by themselves, from an invite link, is not.
func (s *Store) AddParticipant(ctx context.Context, chID, uid, addedBy ids.ID) ([]ids.ID, error) {
	var subs []ids.ID
	err := s.Tx(ctx, func(tx *db.Tx) error {
		// the same lock CreateSubchat takes, so a subchat made at the same
		// moment sees the group's members from before or after, not between
		if err := tx.Serialise(ctx, "subchats", int64(chID)); err != nil {
			return err
		}
		now := nowMs()
		res, err := tx.ExecContext(ctx, `INSERT INTO dm_participants (channel_id, user_id, added_by, added_at, add_notice_by) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING`, int64(chID), int64(uid), int64(addedBy), now, addNoticeBy(uid, addedBy))
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return ErrAlreadyExists
		}
		if subs, err = idsTx(ctx, tx, `SELECT c.id FROM channels c WHERE c.parent_id = ? AND c.type = 'subchat'
			AND NOT EXISTS (SELECT 1 FROM dm_participants p WHERE p.channel_id = c.id AND p.user_id = ?)`, int64(chID), int64(uid)); err != nil {
			return err
		}
		for _, id := range subs {
			if _, err := tx.ExecContext(ctx, `INSERT INTO dm_participants (channel_id, user_id, added_by, added_at, hidden) VALUES (?,?,?,?,0) ON CONFLICT DO NOTHING`, int64(id), int64(uid), int64(addedBy), now); err != nil {
				return err
			}
		}
		return nil
	})
	return subs, err
}

// addNoticeBy is what a new member's add_notice_by holds: whoever put them in
// the group, unless they came in by themselves.
func addNoticeBy(uid, addedBy ids.ID) any {
	if addedBy == uid || addedBy.IsZero() {
		return nil
	}
	return int64(addedBy)
}

// GroupAddNotice is Nexa's note to someone a friend put in a group directly:
// who added them and when. Only that person is ever told, and it stays until
// they dismiss it or leave the group.
type GroupAddNotice struct {
	ChannelID ids.ID `json:"channel_id"`
	AddedBy   ids.ID `json:"added_by"`
	AddedAt   int64  `json:"added_at"`
}

// groupAddNoticeQuery takes the member and FlagDeleted: a note naming an
// account that has since been deleted has nobody left to name.
const groupAddNoticeQuery = `SELECT p.channel_id, p.add_notice_by, p.added_at FROM dm_participants p JOIN channels c ON c.id = p.channel_id
	WHERE p.user_id = ? AND p.add_notice_by IS NOT NULL AND c.type = 'group_dm'
	AND NOT EXISTS (SELECT 1 FROM users a WHERE a.id = p.add_notice_by AND a.flags & ? != 0)`

func scanGroupAddNotices(rows *sql.Rows) ([]GroupAddNotice, error) {
	defer rows.Close()
	out := []GroupAddNotice{}
	for rows.Next() {
		var ch, by, at int64
		if err := rows.Scan(&ch, &by, &at); err != nil {
			return nil, err
		}
		out = append(out, GroupAddNotice{ChannelID: ids.ID(ch), AddedBy: ids.ID(by), AddedAt: at})
	}
	return out, rows.Err()
}

// GroupAddNotices lists the notes uid has not dismissed, one per group.
func (s *Store) GroupAddNotices(ctx context.Context, uid ids.ID) ([]GroupAddNotice, error) {
	rows, err := s.db.R.QueryContext(ctx, groupAddNoticeQuery+` ORDER BY p.added_at`, int64(uid), FlagDeleted)
	if err != nil {
		return nil, err
	}
	return scanGroupAddNotices(rows)
}

// GroupAddNoticeIn is uid's note in one group, or ErrNotFound when there is
// none: they joined from a link, made the group, or dismissed it.
func (s *Store) GroupAddNoticeIn(ctx context.Context, chID, uid ids.ID) (*GroupAddNotice, error) {
	rows, err := s.db.R.QueryContext(ctx, groupAddNoticeQuery+` AND p.channel_id = ?`, int64(uid), FlagDeleted, int64(chID))
	if err != nil {
		return nil, err
	}
	list, err := scanGroupAddNotices(rows)
	if err != nil {
		return nil, err
	}
	if len(list) == 0 {
		return nil, ErrNotFound
	}
	return &list[0], nil
}

// DismissGroupAddNotice clears uid's note in a group, reporting whether there
// was one to clear.
func (s *Store) DismissGroupAddNotice(ctx context.Context, chID, uid ids.ID) (bool, error) {
	res, err := s.db.W.ExecContext(ctx, `UPDATE dm_participants SET add_notice_by = NULL WHERE channel_id = ? AND user_id = ? AND add_notice_by IS NOT NULL`, int64(chID), int64(uid))
	if err != nil {
		return false, err
	}
	n, err := res.RowsAffected()
	return n > 0, err
}

// RemoveParticipant removes uid from a group DM and from every subchat inside
// it, returning the subchats uid was taken out of.
func (s *Store) RemoveParticipant(ctx context.Context, chID, uid ids.ID) (subs, remaining []ids.ID, err error) {
	err = s.Tx(ctx, func(tx *db.Tx) error {
		if err := tx.Serialise(ctx, "subchats", int64(chID)); err != nil {
			return err
		}
		res, err := tx.ExecContext(ctx, `DELETE FROM dm_participants WHERE channel_id = ? AND user_id = ?`, int64(chID), int64(uid))
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return ErrNotFound
		}
		if subs, err = idsTx(ctx, tx, `SELECT p.channel_id FROM dm_participants p JOIN channels c ON c.id = p.channel_id
			WHERE c.parent_id = ? AND c.type = 'subchat' AND p.user_id = ?`, int64(chID), int64(uid)); err != nil {
			return err
		}
		for _, id := range subs {
			if _, err := tx.ExecContext(ctx, `DELETE FROM dm_participants WHERE channel_id = ? AND user_id = ?`, int64(id), int64(uid)); err != nil {
				return err
			}
		}
		// Who is left is read here rather than worked out from whatever the
		// caller loaded earlier. The last two members of a group leaving at the
		// same moment each held a snapshot naming the other, so neither saw an
		// empty group and the row survived with an owner who was not in it,
		// along with every message, subchat and key epoch it held.
		remaining, err = idsTx(ctx, tx, `SELECT user_id FROM dm_participants WHERE channel_id = ? ORDER BY added_at, user_id`, int64(chID))
		return err
	})
	if err != nil {
		return nil, nil, err
	}
	return subs, remaining, nil
}

// idsTx runs a query returning one id column inside a transaction.
func idsTx(ctx context.Context, tx *db.Tx, query string, args ...any) ([]ids.ID, error) {
	rows, err := tx.QueryContext(ctx, query, args...)
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

// RevokeChannelInvites deletes every invite link to a group, so someone who
// was removed cannot walk back in with a code they saw while they were in it.
func (s *Store) RevokeChannelInvites(ctx context.Context, chID ids.ID) error {
	_, err := s.db.W.ExecContext(ctx, `DELETE FROM invites WHERE channel_id = ?`, int64(chID))
	return err
}

// UpdateChannel applies p.
func (s *Store) UpdateChannel(ctx context.Context, id ids.ID, p ChannelPatch) error {
	var sets []string
	var args []any
	set := func(col string, v any) { sets = append(sets, col+" = ?"); args = append(args, v) }
	if p.Name != nil {
		set("name", *p.Name)
	}
	if p.Topic != nil {
		set("topic", *p.Topic)
	}
	if p.IconID != nil {
		set("icon_id", nullID(*p.IconID))
	}
	if p.Glyph != nil {
		set("glyph", *p.Glyph)
	}
	if p.OwnerID != nil {
		set("owner_id", int64(*p.OwnerID))
	}
	if p.NSFW != nil {
		set("nsfw", boolInt(*p.NSFW))
	}
	if p.SlowmodeSeconds != nil {
		set("slowmode_seconds", *p.SlowmodeSeconds)
	}
	if p.UserLimit != nil {
		set("user_limit", *p.UserLimit)
	}
	if p.ParentID != nil {
		set("parent_id", nullID(*p.ParentID))
	}
	if p.Position != nil {
		set("position", *p.Position)
	}
	if p.Type != nil {
		set("type", *p.Type)
	}
	if p.Archived != nil {
		set("archived", boolInt(*p.Archived))
		if *p.Archived {
			set("archived_at", nowMs())
		} else {
			sets = append(sets, "archived_at = NULL")
		}
	}
	if p.Locked != nil {
		set("thread_locked", boolInt(*p.Locked))
	}
	if p.AutoArchive != nil {
		set("auto_archive", *p.AutoArchive)
	}
	if p.AppliedTags != nil {
		set("applied_tags", idsJSON(*p.AppliedTags))
	}
	if p.Forum != nil {
		b, err := json.Marshal(p.Forum)
		if err != nil {
			return err
		}
		set("forum", string(b))
	}
	if p.URL != nil {
		set("url", *p.URL)
	}
	if len(sets) == 0 {
		return nil
	}
	set("updated_at", nowMs())
	args = append(args, int64(id))
	res, err := s.db.W.ExecContext(ctx, `UPDATE channels SET `+strings.Join(sets, ", ")+` WHERE id = ?`, args...)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	// a channel's type and a thread's state are part of who may do what in it
	if p.Type != nil || p.Archived != nil || p.Locked != nil {
		s.changedIn(channelServer(ctx, s.db.R, id))
	}
	return nil
}

// DeleteChannel removes a channel and everything in it. Deleting a category
// leaves its children in place without a parent; deleting a conversation
// deletes its subchats with it, and a channel its threads (forum posts
// included), since nothing could reach them afterwards.
func (s *Store) DeleteChannel(ctx context.Context, id ids.ID) error {
	var server ids.ID
	var serverErr error
	// the channel and its overwrites leave its server's permissions with it
	defer func() { s.changedIn(server, serverErr) }()
	return s.Tx(ctx, func(tx *db.Tx) error {
		server, serverErr = channelServer(ctx, tx, id)
		subs, err := idsTx(ctx, tx, `SELECT id FROM channels WHERE parent_id = ? AND type IN ('subchat','thread','private_thread')`, int64(id))
		if err != nil {
			return err
		}
		for _, sub := range subs {
			if err := deleteChannelTx(ctx, tx, sub); err != nil {
				return err
			}
		}
		if _, err := tx.ExecContext(ctx, `UPDATE channels SET parent_id = NULL WHERE parent_id = ?`, int64(id)); err != nil {
			return err
		}
		// a server's join notices stop, rather than go on pointing at nothing
		if _, err := tx.ExecContext(ctx, `UPDATE servers SET system_channel_id = NULL WHERE system_channel_id = ?`, int64(id)); err != nil {
			return err
		}
		return deleteChannelTx(ctx, tx, id)
	})
}

func deleteChannelTx(ctx context.Context, tx *db.Tx, id ids.ID) error {
	// release attachment blobs, one reference per attachment row, and a group's icon
	if _, err := tx.ExecContext(ctx, `UPDATE blobs SET ref_count = `+refFloor(tx.Dialect(), "ref_count - (SELECT count(*) FROM attachments a WHERE a.channel_id = ? AND a.blob_id = blobs.id)")+` WHERE id IN (SELECT blob_id FROM attachments WHERE channel_id = ?)`, int64(id), int64(id)); err != nil {
		return err
	}
	// its icon, and its webhooks' pictures: the webhooks go with it by cascade
	if err := releaseBlobRefs(ctx, tx, `SELECT icon_id FROM channels WHERE id = ? UNION ALL SELECT avatar_id FROM webhooks WHERE channel_id = ?`, int64(id), int64(id)); err != nil {
		return err
	}
	for _, q := range []string{`DELETE FROM key_grants WHERE conv_id = ?`, `DELETE FROM key_epochs WHERE conv_id = ?`, `DELETE FROM key_flags WHERE conv_id = ?`} {
		if _, err := tx.ExecContext(ctx, q, int64(id)); err != nil {
			return err
		}
	}
	res, err := tx.ExecContext(ctx, `DELETE FROM channels WHERE id = ?`, int64(id))
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// HideDM closes (or reopens) a DM for one participant. A hidden DM stays out
// of their sidebar until it has something new in it.
func (s *Store) HideDM(ctx context.Context, chID, uid ids.ID, hidden bool) error {
	res, err := s.db.W.ExecContext(ctx, `UPDATE dm_participants SET hidden = ? WHERE channel_id = ? AND user_id = ?`, boolInt(hidden), int64(chID), int64(uid))
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// ReopenDM puts a closed DM back in one participant's sidebar, reporting
// whether it had been closed.
func (s *Store) ReopenDM(ctx context.Context, chID, uid ids.ID) (bool, error) {
	res, err := s.db.W.ExecContext(ctx, `UPDATE dm_participants SET hidden = 0 WHERE channel_id = ? AND user_id = ? AND hidden = 1`, int64(chID), int64(uid))
	if err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	return n > 0, nil
}

// DMShownTo lists the participants who have a conversation in their sidebar:
// everyone in it but those who closed it and those who have not been shown it
// yet (StartDM).
func (s *Store) DMShownTo(ctx context.Context, chID ids.ID) ([]ids.ID, error) {
	return s.idList(ctx, `SELECT user_id FROM dm_participants WHERE channel_id = ? AND hidden = 0 ORDER BY added_at, user_id`, int64(chID))
}

// UnhideDM reopens a conversation for everyone who had closed it, returning
// those users so the caller can put it back in their sidebar.
func (s *Store) UnhideDM(ctx context.Context, chID ids.ID) ([]ids.ID, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT user_id FROM dm_participants WHERE channel_id = ? AND hidden = 1`, int64(chID))
	if err != nil {
		return nil, err
	}
	out := []ids.ID{}
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return nil, err
		}
		out = append(out, ids.ID(id))
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if len(out) == 0 {
		return out, nil
	}
	_, err = s.db.W.ExecContext(ctx, `UPDATE dm_participants SET hidden = 0 WHERE channel_id = ? AND hidden = 1`, int64(chID))
	return out, err
}

// DeleteChannel removes a channel and everything hanging off it.
func (s *Store) DeleteDMChannel(ctx context.Context, chID ids.ID) error {
	return s.DeleteChannel(ctx, chID)
}
