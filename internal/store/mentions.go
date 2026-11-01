package store

import (
	"context"
	"errors"

	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/perms"
)

// Kinds of mention the Mentions list can be narrowed to.
const (
	MentionKindMe       = 1 // the reader by name
	MentionKindRole     = 2 // a role the reader holds now
	MentionKindEveryone = 4 // @everyone or @here
	MentionKindAll      = MentionKindMe | MentionKindRole | MentionKindEveryone
)

// MentionQuery is one page of the Mentions list.
type MentionQuery struct {
	Channels []ids.ID
	Kinds    int
	// Oldest pages forward from After; otherwise backward from Before.
	Oldest bool
	Before ids.ID
	After  ids.ID
	Limit  int
}

// MaxMentionPage is the most messages one page of mentions holds.
const MaxMentionPage = 50

// MentionChannels lists the server channels uid can read the history of:
// every channel and thread they can view with Read Message History, archived
// threads included, optionally only in one server. Categories hold nothing.
func (s *Store) MentionChannels(ctx context.Context, uid ids.ID, onlyServer ids.ID) ([]ids.ID, error) {
	servers, err := s.serversOf(ctx, uid)
	if err != nil {
		return nil, err
	}
	out := []ids.ID{}
	now := nowMs()
	for _, serverID := range servers {
		if !onlyServer.IsZero() && serverID != onlyServer {
			continue
		}
		snap, err := s.snapshot(ctx, serverID)
		if errors.Is(err, ErrNotFound) {
			continue
		}
		if err != nil {
			return nil, err
		}
		if _, ok := snap.members[uid]; !ok {
			continue
		}
		for _, ch := range snap.channelOrder {
			if snap.channels[ch] == "category" {
				continue
			}
			if snap.channelPerms(ch, uid, now).Has(perms.ViewChannel | perms.ReadMessageHistory) {
				out = append(out, ch)
			}
		}
	}
	return out, nil
}

// Mentions returns a page of the messages in q.Channels that mention uid in
// one of q.Kinds, newest first (or oldest first with q.Oldest). It leaves out
// uid's own messages, anyone uid blocked, the server's own notes, and
// @everyone sent before uid joined, as the unread badges do.
func (s *Store) Mentions(ctx context.Context, uid ids.ID, q MentionQuery) ([]*Message, error) {
	if len(q.Channels) == 0 || q.Kinds&MentionKindAll == 0 {
		return []*Message{}, nil
	}
	limit := q.Limit
	if limit <= 0 || limit > MaxMentionPage {
		limit = 25
	}
	d := s.db.Dialect
	floor := "coalesce(((coalesce(sm.joined_at, p.added_at) - ?) << 22) - 1, 0)"
	var kinds []string
	var kindArgs []any
	if q.Kinds&MentionKindMe != 0 {
		kinds = append(kinds, d.JSONArrayHas("m.mentions"))
		kindArgs = append(kindArgs, uid.String())
	}
	if q.Kinds&MentionKindRole != 0 {
		kinds = append(kinds, `(m.mention_roles <> '[]' AND EXISTS (SELECT 1 FROM member_roles mr WHERE mr.server_id = c.server_id AND mr.user_id = ? AND `+d.JSONArrayHasExpr("m.mention_roles", "mr.role_id")+`))`)
		kindArgs = append(kindArgs, int64(uid))
	}
	if q.Kinds&MentionKindEveryone != 0 {
		kinds = append(kinds, `(m.mention_everyone = 1 AND m.id > `+floor+`)`)
		kindArgs = append(kindArgs, ids.Epoch)
	}
	kindCond := kinds[0]
	for _, k := range kinds[1:] {
		kindCond += " OR " + k
	}
	// The first condition repeats the partial index's own, so both databases
	// read messages_mentioning rather than every message of each channel.
	sql := `SELECT ` + messageColsM + ` FROM messages m
		JOIN channels c ON c.id = m.channel_id
		LEFT JOIN server_members sm ON sm.server_id = c.server_id AND sm.user_id = ?
		LEFT JOIN dm_participants p ON p.channel_id = c.id AND p.user_id = ?
		WHERE m.channel_id IN (` + placeholders(len(q.Channels)) + `)
		AND (m.mention_everyone = 1 OR m.mentions <> '[]' OR m.mention_roles <> '[]')
		AND m.type NOT LIKE 'system%' AND m.author_id != ? AND ` + notBlockedAuthor + `
		AND (` + kindCond + `)`
	args := []any{int64(uid), int64(uid)}
	args = append(args, idArgs(q.Channels)...)
	args = append(args, int64(uid), int64(uid))
	args = append(args, kindArgs...)
	if q.Oldest {
		if !q.After.IsZero() {
			sql += ` AND m.id > ?`
			args = append(args, int64(q.After))
		}
		sql += ` ORDER BY m.id ASC LIMIT ?`
	} else {
		if !q.Before.IsZero() {
			sql += ` AND m.id < ?`
			args = append(args, int64(q.Before))
		}
		sql += ` ORDER BY m.id DESC LIMIT ?`
	}
	args = append(args, limit)
	list, err := s.queryMessages(ctx, sql, args...)
	if err != nil {
		return nil, err
	}
	if list == nil {
		list = []*Message{}
	}
	if err := s.hydrate(ctx, list, uid); err != nil {
		return nil, err
	}
	return list, nil
}
