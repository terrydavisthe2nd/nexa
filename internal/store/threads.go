package store

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"time"

	"evilion.xyz/chat/internal/db"
	"evilion.xyz/chat/internal/ids"
)

// Threads are channels whose parent is a text, announcement or forum channel
// (models.go). A forum post is a public thread of a forum channel, its first
// message what the post says. This file keeps who is in them and finds them.

// Thread limits.
const (
	// MaxThreadMembers is how many people one thread holds.
	MaxThreadMembers = 1000
	// DefaultAutoArchive is how many quiet minutes close a thread when
	// nothing else says: three days.
	DefaultAutoArchive = 3 * 24 * 60
)

// AutoArchiveChoices are the quiet spells a thread may be set to close after,
// in minutes: an hour, a day, three days, a week.
var AutoArchiveChoices = []int{60, 1440, 4320, 10080}

// ThreadMember is someone in a thread.
type ThreadMember struct {
	ThreadID ids.ID `json:"thread_id"`
	UserID   ids.ID `json:"user_id"`
	JoinedAt int64  `json:"joined_at"`
}

// NewThread is what a thread is started with.
type NewThread struct {
	Parent      *Channel
	Owner       ids.ID
	Name        string
	Private     bool
	Starter     *ids.ID
	AutoArchive int
	Slowmode    int
	Tags        []ids.ID
}

// ErrThreadExists is returned when a message already has a thread.
var ErrThreadExists = errors.New("thread exists")

// CreateThread starts a thread in its parent channel, with whoever started it
// in it. A message holds one thread at most: ErrThreadExists when it has one.
func (s *Store) CreateThread(ctx context.Context, nt NewThread) (*Channel, error) {
	typ := ChannelThread
	if nt.Private {
		typ = ChannelPrivateThread
	}
	owner := nt.Owner
	tags := nt.Tags
	if tags == nil {
		tags = []ids.ID{}
	}
	c := &Channel{ServerID: nt.Parent.ServerID, Type: typ, Name: nt.Name, ParentID: &nt.Parent.ID, OwnerID: &owner, SlowmodeSeconds: nt.Slowmode,
		Thread: &ThreadMeta{AutoArchive: nt.AutoArchive, StarterID: nt.Starter, AppliedTags: tags}}
	err := s.Tx(ctx, func(tx *db.Tx) error {
		if nt.Starter != nil {
			// one thread per message: asked and answered inside the write
			if err := tx.Serialise(ctx, "thread-starter", int64(*nt.Starter)); err != nil {
				return err
			}
			var n int
			if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM channels WHERE starter_id = ?`, int64(*nt.Starter)).Scan(&n); err != nil {
				return err
			}
			if n > 0 {
				return ErrThreadExists
			}
		}
		if err := s.insertChannel(ctx, tx, c); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, `INSERT INTO thread_members (thread_id, user_id, joined_at) VALUES (?,?,?) ON CONFLICT DO NOTHING`, int64(c.ID), int64(owner), nowMs())
		return err
	})
	if err != nil {
		return nil, err
	}
	s.serverChanged(*nt.Parent.ServerID)
	return s.ChannelByID(ctx, c.ID)
}

// ThreadOf finds the thread started from a message.
func (s *Store) ThreadOf(ctx context.Context, messageID ids.ID) (*Channel, error) {
	var id int64
	err := s.db.R.QueryRowContext(ctx, `SELECT id FROM channels WHERE starter_id = ?`, int64(messageID)).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return s.ChannelByID(ctx, ids.ID(id))
}

// AddThreadMember puts someone in a thread; added is false when they already
// were. ErrLimit when the thread is full.
func (s *Store) AddThreadMember(ctx context.Context, thread *Channel, uid ids.ID) (bool, error) {
	added := false
	err := s.Tx(ctx, func(tx *db.Tx) error {
		if err := tx.Serialise(ctx, "thread-members", int64(thread.ID)); err != nil {
			return err
		}
		var n int
		if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM thread_members WHERE thread_id = ?`, int64(thread.ID)).Scan(&n); err != nil {
			return err
		}
		if n >= MaxThreadMembers {
			return ErrLimit
		}
		res, err := tx.ExecContext(ctx, `INSERT INTO thread_members (thread_id, user_id, joined_at) VALUES (?,?,?) ON CONFLICT DO NOTHING`, int64(thread.ID), int64(uid), nowMs())
		if err != nil {
			return err
		}
		k, _ := res.RowsAffected()
		added = k > 0
		return nil
	})
	if err == nil && added && thread.Type == ChannelPrivateThread && thread.ServerID != nil {
		// who is in a private thread is who sees it
		s.serverChanged(*thread.ServerID)
	}
	return added, err
}

// RemoveThreadMember takes someone out of a thread; removed is false when
// they were not in it.
func (s *Store) RemoveThreadMember(ctx context.Context, thread *Channel, uid ids.ID) (bool, error) {
	res, err := s.db.W.ExecContext(ctx, `DELETE FROM thread_members WHERE thread_id = ? AND user_id = ?`, int64(thread.ID), int64(uid))
	if err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	if n > 0 && thread.Type == ChannelPrivateThread && thread.ServerID != nil {
		s.serverChanged(*thread.ServerID)
	}
	return n > 0, nil
}

// IsThreadMember reports whether uid is in a thread.
func (s *Store) IsThreadMember(ctx context.Context, threadID, uid ids.ID) (bool, error) {
	var n int
	err := s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM thread_members WHERE thread_id = ? AND user_id = ?`, int64(threadID), int64(uid)).Scan(&n)
	return n > 0, err
}

// ThreadMembers lists a thread's people, earliest first.
func (s *Store) ThreadMembers(ctx context.Context, threadID ids.ID) ([]ThreadMember, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT thread_id, user_id, joined_at FROM thread_members WHERE thread_id = ? ORDER BY joined_at, user_id LIMIT ?`, int64(threadID), MaxThreadMembers)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []ThreadMember{}
	for rows.Next() {
		var m ThreadMember
		if err := rows.Scan(&m.ThreadID, &m.UserID, &m.JoinedAt); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

// ThreadMemberIDs lists who is in a thread.
func (s *Store) ThreadMemberIDs(ctx context.Context, threadID ids.ID) ([]ids.ID, error) {
	return s.idList(ctx, `SELECT user_id FROM thread_members WHERE thread_id = ? ORDER BY joined_at, user_id`, int64(threadID))
}

// JoinedThreads lists the threads of a server uid is in that are still going.
func (s *Store) JoinedThreads(ctx context.Context, serverID, uid ids.ID) ([]ids.ID, error) {
	return s.idList(ctx, `SELECT t.thread_id FROM thread_members t JOIN channels c ON c.id = t.thread_id WHERE c.server_id = ? AND t.user_id = ? AND c.archived = 0`, int64(serverID), int64(uid))
}

// withThreadKeys fills in the key conversation of threads read straight from
// rows: their own when they have epochs of their own (a private thread), else
// their parent channel's, which is its own or the server's.
func (s *Store) withThreadKeys(ctx context.Context, list []*Channel) error {
	if len(list) == 0 {
		return nil
	}
	want := map[ids.ID]bool{}
	for _, c := range list {
		want[c.ID] = true
		if c.ParentID != nil {
			want[*c.ParentID] = true
		}
	}
	keys := make([]ids.ID, 0, len(want))
	for id := range want {
		keys = append(keys, id)
	}
	own := map[ids.ID]bool{}
	for len(keys) > 0 {
		n := min(len(keys), 500)
		rows, err := s.db.R.QueryContext(ctx, `SELECT DISTINCT conv_id FROM key_epochs WHERE conv_id IN (`+placeholders(n)+`)`, idArgs(keys[:n])...)
		if err != nil {
			return err
		}
		for rows.Next() {
			var id int64
			if err := rows.Scan(&id); err != nil {
				rows.Close()
				return err
			}
			own[ids.ID(id)] = true
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
		keys = keys[n:]
	}
	for _, c := range list {
		c.Recipients = []ids.ID{}
		switch {
		case own[c.ID]:
			c.KeyConvID = c.ID
		case c.ParentID != nil && own[*c.ParentID]:
			c.KeyConvID = *c.ParentID
		case c.ServerID != nil:
			c.KeyConvID = *c.ServerID
		}
	}
	return nil
}

func (s *Store) queryChannels(ctx context.Context, q string, args ...any) ([]*Channel, error) {
	rows, err := s.db.R.QueryContext(ctx, q, args...)
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
		out = append(out, c)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return out, s.withThreadKeys(ctx, out)
}

// ActiveThreads lists a server's threads that are still going, every one of
// them: callers keep the ones a person may see.
func (s *Store) ActiveThreads(ctx context.Context, serverID ids.ID) ([]*Channel, error) {
	return s.queryChannels(ctx, `SELECT `+channelReadCols+` FROM channels WHERE server_id = ? AND type IN ('thread','private_thread') AND archived = 0 ORDER BY coalesce(last_message_id, id) DESC LIMIT 2000`, int64(serverID))
}

// PrivateThreads lists a server's private threads, closed ones too.
func (s *Store) PrivateThreads(ctx context.Context, serverID ids.ID) ([]*Channel, error) {
	return s.queryChannels(ctx, `SELECT `+channelReadCols+` FROM channels WHERE server_id = ? AND type = 'private_thread'`, int64(serverID))
}

// ThreadQuery picks threads of one parent channel.
type ThreadQuery struct {
	Parent ids.ID
	// Archived lists the closed ones, newest closed first; otherwise the
	// ones still going, most recently active first.
	Archived bool
	// Before pages on: an archived_at, or a last activity id, to continue below.
	Before int64
	Limit  int
	// Query matches thread names.
	Query string
	// Forum posts: sort by "created" or "activity", keep those with every one
	// of Tags.
	Sort string
	Tags []ids.ID
	// Viewer, on a forum: posts still waiting for their first message are
	// left out, but for the viewer's own.
	Viewer ids.ID
	Forum  bool
}

// Threads lists a channel's threads (a forum's posts), every one of them,
// private ones included: callers keep the ones a person may see.
func (s *Store) Threads(ctx context.Context, q ThreadQuery) ([]*Channel, error) {
	if q.Limit <= 0 || q.Limit > 100 {
		q.Limit = 50
	}
	where := []string{`parent_id = ?`, `type IN ('thread','private_thread')`}
	args := []any{int64(q.Parent)}
	order := `coalesce(last_message_id, id) DESC`
	activity := `coalesce(last_message_id, id)`
	if q.Sort == "created" {
		order, activity = `id DESC`, `id`
	}
	if q.Archived {
		where = append(where, `archived = 1`)
		order = `coalesce(archived_at, 0) DESC, id DESC`
		if q.Before > 0 {
			where = append(where, `coalesce(archived_at, 0) < ?`)
			args = append(args, q.Before)
		}
	} else {
		where = append(where, `archived = 0`)
		if q.Before > 0 {
			where = append(where, activity+` < ?`)
			args = append(args, q.Before)
		}
	}
	if term := strings.ToLower(strings.TrimSpace(q.Query)); term != "" {
		where = append(where, s.db.Dialect.Lower("name")+` LIKE ? ESCAPE '\'`)
		args = append(args, "%"+escapeLike(term)+"%")
	}
	if q.Forum {
		where = append(where, `(last_message_id IS NOT NULL OR owner_id = ?)`)
		args = append(args, int64(q.Viewer))
	}
	for _, tag := range q.Tags {
		// the list is written by idsJSON, ids quoted, so a tag's own quoted id is in it or not
		where = append(where, `applied_tags LIKE ?`)
		args = append(args, `%"`+tag.String()+`"%`)
	}
	args = append(args, q.Limit)
	return s.queryChannels(ctx, `SELECT `+channelReadCols+` FROM channels WHERE `+strings.Join(where, " AND ")+` ORDER BY `+order+` LIMIT ?`, args...)
}

// EmptyForumPosts lists forum posts whose first message never came, made
// before a moment: an app that started one and then failed to post in it.
func (s *Store) EmptyForumPosts(ctx context.Context, before int64, limit int) ([]*Channel, error) {
	return s.queryChannels(ctx, `SELECT `+channelReadCols+` FROM channels WHERE type = 'thread' AND last_message_id IS NULL
		AND parent_id IN (SELECT id FROM channels WHERE type IN ('forum','media')) AND id < ? LIMIT ?`, int64(ids.FromTime(time.UnixMilli(before))), limit)
}

// DueThreads lists threads that have been quiet longer than they are set to
// stay open, oldest activity first.
func (s *Store) DueThreads(ctx context.Context, now int64, limit int) ([]*Channel, error) {
	// a snowflake's top bits are its milliseconds since ids.Epoch
	return s.queryChannels(ctx, `SELECT `+channelReadCols+` FROM channels WHERE type IN ('thread','private_thread') AND archived = 0
		AND ((coalesce(last_message_id, id) / 4194304) + ?) + (CASE WHEN auto_archive > 0 THEN auto_archive ELSE ? END) * 60000 < ?
		ORDER BY coalesce(last_message_id, id) LIMIT ?`, ids.Epoch, DefaultAutoArchive, now, limit)
}

// ThreadSummaries finds the threads started from any of msgs, keyed by the
// message they hang from, and fills in each message's Thread, with the
// newest message of a public thread.
func (s *Store) ThreadSummaries(ctx context.Context, msgs []*Message) error {
	byID := map[ids.ID]*Message{}
	list := make([]ids.ID, 0, len(msgs))
	for _, m := range msgs {
		if m == nil {
			continue
		}
		byID[m.ID] = m
		list = append(list, m.ID)
	}
	public := []ids.ID{}
	byThread := map[ids.ID]*ThreadSummary{}
	for len(list) > 0 {
		n := min(len(list), 500)
		rows, err := s.db.R.QueryContext(ctx, `SELECT id, name, message_count, last_message_id, archived, thread_locked, starter_id, type FROM channels WHERE starter_id IN (`+placeholders(n)+`)`, idArgs(list[:n])...)
		if err != nil {
			return err
		}
		for rows.Next() {
			var t ThreadSummary
			var last sql.NullInt64
			var archived, locked, starter int64
			var typ string
			if err := rows.Scan(&t.ID, &t.Name, &t.Messages, &last, &archived, &locked, &starter, &typ); err != nil {
				rows.Close()
				return err
			}
			t.LastMessageID, t.Archived, t.Locked = idPtr(last), archived == 1, locked == 1
			if m := byID[ids.ID(starter)]; m != nil {
				m.Thread = &t
				if typ == ChannelThread {
					public = append(public, t.ID)
					byThread[t.ID] = &t
				}
			}
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
		list = list[n:]
	}
	if len(public) == 0 {
		return nil
	}
	// what people said, not the lines the server writes about the thread
	var lasts []*Message
	err := chunked(public, func(part []ids.ID) error {
		rows, err := s.db.R.QueryContext(ctx, `SELECT `+messageCols+` FROM messages WHERE id IN (SELECT max(id) FROM messages WHERE channel_id IN (`+placeholders(len(part))+`) AND type IN ('default', 'reply') GROUP BY channel_id)`, idArgs(part)...)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			m, err := scanMessage(rows)
			if err != nil {
				return err
			}
			lasts = append(lasts, m)
		}
		return rows.Err()
	})
	if err != nil {
		return err
	}
	if err := s.hydrate(ctx, lasts, 0); err != nil {
		return err
	}
	for _, m := range lasts {
		if t := byThread[m.ChannelID]; t != nil {
			t.LastMessage = m
		}
	}
	return nil
}

// FirstMessages finds each thread's first message: what a forum post says.
func (s *Store) FirstMessages(ctx context.Context, threads []ids.ID, forUser ids.ID) (map[ids.ID]*Message, error) {
	out := map[ids.ID]*Message{}
	var msgs []*Message
	for _, t := range threads {
		m, err := scanMessage(s.db.R.QueryRowContext(ctx, `SELECT `+messageCols+` FROM messages WHERE channel_id = ? AND type IN ('default','reply') ORDER BY id LIMIT 1`, int64(t)))
		if errors.Is(err, ErrNotFound) {
			continue
		}
		if err != nil {
			return nil, err
		}
		out[t] = m
		msgs = append(msgs, m)
	}
	if err := s.hydrate(ctx, msgs, forUser); err != nil {
		return nil, err
	}
	return out, nil
}
