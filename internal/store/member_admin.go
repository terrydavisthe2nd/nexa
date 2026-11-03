package store

import (
	"context"
	"strings"
	"time"

	"evilion.xyz/chat/internal/ids"
)

// The Members page: every member of a server with when they joined, how old
// their account is, how they came in, their roles, and what might need a
// moderator's look (signals), filtered and sorted on the server so a large
// server is not read whole.

// Member signals.
const (
	SignalTimedOut    = "timed_out"
	SignalQuarantined = "quarantined"
	SignalNewAccount  = "new_account"
	SignalUnusualDMs  = "unusual_dms"
)

// NewAccountAge is how young an account is when it is flagged as new.
const NewAccountAge = 7 * 24 * time.Hour

// unusualDMs is how many conversations someone has to start in a day,
// with anyone, before it is worth a moderator's look.
const unusualDMs = 10

// MemberQuery picks members for the Members page.
type MemberQuery struct {
	// Query matches a username, display name or server nickname from its
	// start, or is a user id.
	Query string
	// Roles keeps members with any of them.
	Roles []ids.ID
	// When they joined the server, and when their account was made (ms; 0 for no bound).
	JoinedAfter, JoinedBefore   int64
	CreatedAfter, CreatedBefore int64
	// JoinMethod keeps those who came in one way: invite, discovery, tag,
	// owner, or unknown (before it was recorded). JoinCode, one invite.
	JoinMethod string
	JoinCode   string
	// Signals keeps members showing any of them.
	Signals []string
	// Sort: joined_desc (the default), joined_asc, created_desc, created_asc.
	Sort   string
	Offset int
	Limit  int
}

// MemberRow is one member on the Members page.
type MemberRow struct {
	*Member
	AccountCreatedAt int64       `json:"account_created_at"`
	Signals          []string    `json:"signals"`
	Join             *MemberJoin `json:"join,omitempty"`
}

// SearchMembers lists members by q, with how many match in all.
func (s *Store) SearchMembers(ctx context.Context, serverID ids.ID, q MemberQuery) ([]*MemberRow, int, error) {
	if q.Limit <= 0 || q.Limit > 200 {
		q.Limit = 50
	}
	if q.Offset < 0 {
		q.Offset = 0
	}
	now := nowMs()
	spree, err := s.unusualDMUsers(ctx, serverID, now)
	if err != nil {
		return nil, 0, err
	}
	where := []string{`m.server_id = ?`}
	args := []any{int64(serverID)}
	if term := strings.ToLower(strings.TrimSpace(q.Query)); term != "" {
		if id, err := ids.Parse(term); err == nil {
			where = append(where, `m.user_id = ?`)
			args = append(args, int64(id))
		} else {
			like := escapeLike(term) + "%"
			where = append(where, `(u.username LIKE ? ESCAPE '\' OR `+s.db.Dialect.Lower("u.display_name")+` LIKE ? ESCAPE '\' OR `+s.db.Dialect.Lower("m.nickname")+` LIKE ? ESCAPE '\')`)
			args = append(args, like, like, like)
		}
	}
	if len(q.Roles) > 0 {
		where = append(where, `EXISTS (SELECT 1 FROM member_roles r WHERE r.server_id = m.server_id AND r.user_id = m.user_id AND r.role_id IN (`+placeholders(len(q.Roles))+`))`)
		args = append(args, idArgs(q.Roles)...)
	}
	if q.JoinedAfter > 0 {
		where = append(where, `m.joined_at >= ?`)
		args = append(args, q.JoinedAfter)
	}
	if q.JoinedBefore > 0 {
		where = append(where, `m.joined_at < ?`)
		args = append(args, q.JoinedBefore)
	}
	if q.CreatedAfter > 0 {
		where = append(where, `u.created_at >= ?`)
		args = append(args, q.CreatedAfter)
	}
	if q.CreatedBefore > 0 {
		where = append(where, `u.created_at < ?`)
		args = append(args, q.CreatedBefore)
	}
	switch q.JoinMethod {
	case "":
	case "unknown":
		where = append(where, `m.join_method = ''`)
	default:
		where = append(where, `m.join_method = ?`)
		args = append(args, q.JoinMethod)
	}
	if q.JoinCode != "" {
		where = append(where, `m.join_code = ?`)
		args = append(args, q.JoinCode)
	}
	if len(q.Signals) > 0 {
		var any []string
		for _, sig := range q.Signals {
			switch sig {
			case SignalTimedOut:
				any = append(any, `coalesce(m.timeout_until, 0) > ?`)
				args = append(args, now)
			case SignalQuarantined:
				any = append(any, `(m.flags & ?) != 0`)
				args = append(args, MemberQuarantined)
			case SignalNewAccount:
				any = append(any, `u.created_at > ?`)
				args = append(args, now-NewAccountAge.Milliseconds())
			case SignalUnusualDMs:
				if len(spree) > 0 {
					list := make([]ids.ID, 0, len(spree))
					for id := range spree {
						list = append(list, id)
					}
					any = append(any, `m.user_id IN (`+placeholders(len(list))+`)`)
					args = append(args, idArgs(list)...)
				} else {
					any = append(any, `1 = 0`)
				}
			}
		}
		if len(any) > 0 {
			where = append(where, `(`+strings.Join(any, " OR ")+`)`)
		}
	}
	from := ` FROM server_members m JOIN users u ON u.id = m.user_id WHERE ` + strings.Join(where, " AND ")
	var total int
	if err := s.db.R.QueryRowContext(ctx, `SELECT count(*)`+from, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	order := `m.joined_at DESC, m.user_id`
	switch q.Sort {
	case "joined_asc":
		order = `m.joined_at, m.user_id`
	case "created_desc":
		order = `u.created_at DESC, m.user_id`
	case "created_asc":
		order = `u.created_at, m.user_id`
	}
	rows, err := s.db.R.QueryContext(ctx, `SELECT `+prefixed("m", memberCols)+`, u.created_at`+from+` ORDER BY `+order+` LIMIT ? OFFSET ?`, append(args, q.Limit, q.Offset)...)
	if err != nil {
		return nil, 0, err
	}
	out := []*MemberRow{}
	byUser := map[ids.ID]*MemberRow{}
	for rows.Next() {
		row := &MemberRow{}
		m, err := scanMember(extraScanner{rows, []any{&row.AccountCreatedAt}})
		if err != nil {
			rows.Close()
			return nil, 0, err
		}
		row.Member = m
		out = append(out, row)
		byUser[m.UserID] = row
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, 0, err
	}
	if len(out) > 0 {
		list := make([]ids.ID, 0, len(out))
		for _, r := range out {
			list = append(list, r.UserID)
		}
		rr, err := s.db.R.QueryContext(ctx, `SELECT user_id, role_id FROM member_roles WHERE server_id = ? AND user_id IN (`+placeholders(len(list))+`)`, append([]any{int64(serverID)}, idArgs(list)...)...)
		if err != nil {
			return nil, 0, err
		}
		for rr.Next() {
			var u, r int64
			if err := rr.Scan(&u, &r); err != nil {
				rr.Close()
				return nil, 0, err
			}
			if row := byUser[ids.ID(u)]; row != nil {
				row.RoleIDs = append(row.RoleIDs, ids.ID(r))
			}
		}
		rr.Close()
		if err := rr.Err(); err != nil {
			return nil, 0, err
		}
	}
	for _, r := range out {
		r.Signals = signalsOf(r.Member, r.AccountCreatedAt, spree[r.UserID], now)
	}
	return out, total, nil
}

// signalsOf says what about a member might want a moderator's look.
func signalsOf(m *Member, accountCreated int64, spree bool, now int64) []string {
	out := []string{}
	if m.TimeoutUntil != nil && *m.TimeoutUntil > now {
		out = append(out, SignalTimedOut)
	}
	if m.Flags&MemberQuarantined != 0 {
		out = append(out, SignalQuarantined)
	}
	if accountCreated > now-NewAccountAge.Milliseconds() {
		out = append(out, SignalNewAccount)
	}
	if spree {
		out = append(out, SignalUnusualDMs)
	}
	return out
}

// unusualDMUsers finds the members of a server who have started a great many
// direct conversations in the past day: nothing about what was said, only
// that they were started.
func (s *Store) unusualDMUsers(ctx context.Context, serverID ids.ID, now int64) (map[ids.ID]bool, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT p.user_id FROM dm_participants p JOIN channels c ON c.id = p.channel_id
		WHERE c.type = 'dm' AND p.added_by = p.user_id AND p.added_at > ? AND p.user_id IN (SELECT user_id FROM server_members WHERE server_id = ?)
		GROUP BY p.user_id HAVING count(*) >= ?`, now-24*time.Hour.Milliseconds(), int64(serverID), unusualDMs)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[ids.ID]bool{}
	for rows.Next() {
		var id ids.ID
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out[id] = true
	}
	return out, rows.Err()
}

// MemberDetail is what Mod View shows of one member beyond their row.
type MemberDetail struct {
	MemberRow
	// Messages counts what they have said in the server, up to MessageCountCap.
	Messages int `json:"message_count"`
	// LastSeen is when their account was last used anywhere on Nexa.
	LastSeen *int64 `json:"last_seen"`
}

// MessageCountCap is where Mod View stops counting someone's messages.
const MessageCountCap = 10000

// MemberDetailOf loads Mod View's facts about one member.
func (s *Store) MemberDetailOf(ctx context.Context, serverID, uid ids.ID) (*MemberDetail, error) {
	m, err := s.Member(ctx, serverID, uid)
	if err != nil {
		return nil, err
	}
	d := &MemberDetail{MemberRow: MemberRow{Member: m}}
	if err := s.db.R.QueryRowContext(ctx, `SELECT created_at FROM users WHERE id = ?`, int64(uid)).Scan(&d.AccountCreatedAt); err != nil {
		return nil, err
	}
	now := nowMs()
	spree, err := s.unusualDMUsers(ctx, serverID, now)
	if err != nil {
		return nil, err
	}
	d.Signals = signalsOf(m, d.AccountCreatedAt, spree[uid], now)
	if err := s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM (SELECT 1 FROM messages m JOIN channels c ON c.id = m.channel_id
		WHERE m.author_id = ? AND c.server_id = ? AND m.type NOT LIKE 'system!_%' ESCAPE '!' LIMIT ?) said`, int64(uid), int64(serverID), MessageCountCap).Scan(&d.Messages); err != nil {
		return nil, err
	}
	var seen *int64
	var last int64
	if err := s.db.R.QueryRowContext(ctx, `SELECT coalesce(max(last_seen_at), 0) FROM sessions WHERE user_id = ?`, int64(uid)).Scan(&last); err != nil {
		return nil, err
	}
	if last > 0 {
		seen = &last
	}
	d.LastSeen = seen
	return d, nil
}

// PruneCandidates lists the members a prune would remove: not the owner,
// holding no role but those in keep, and whose account nobody has used for
// days (or ever, for an account with no sign-in on record since then).
func (s *Store) PruneCandidates(ctx context.Context, serverID, owner ids.ID, days int, include []ids.ID) ([]ids.ID, error) {
	cutoff := nowMs() - int64(days)*24*time.Hour.Milliseconds()
	q := `SELECT m.user_id FROM server_members m JOIN users u ON u.id = m.user_id
		WHERE m.server_id = ? AND m.user_id <> ? AND u.flags & ? = 0
		AND coalesce((SELECT max(last_seen_at) FROM sessions se WHERE se.user_id = m.user_id), 0) < ?
		AND m.joined_at < ?`
	args := []any{int64(serverID), int64(owner), NotPeople, cutoff, cutoff}
	if len(include) > 0 {
		q += ` AND NOT EXISTS (SELECT 1 FROM member_roles r WHERE r.server_id = m.server_id AND r.user_id = m.user_id AND r.role_id NOT IN (` + placeholders(len(include)) + `))`
		args = append(args, idArgs(include)...)
	} else {
		q += ` AND NOT EXISTS (SELECT 1 FROM member_roles r WHERE r.server_id = m.server_id AND r.user_id = m.user_id)`
	}
	q += ` ORDER BY m.user_id LIMIT 10000`
	return s.idList(ctx, q, args...)
}

// JoinCodes lists the invite codes people joined a server with, most used first.
func (s *Store) JoinCodes(ctx context.Context, serverID ids.ID) ([]string, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT join_code FROM server_members WHERE server_id = ? AND join_code <> '' GROUP BY join_code ORDER BY count(*) DESC LIMIT 100`, int64(serverID))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []string{}
	for rows.Next() {
		var c string
		if err := rows.Scan(&c); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// DeleteServerInvites revokes every invite of a server but its vanity link,
// saying which went.
func (s *Store) DeleteServerInvites(ctx context.Context, serverID ids.ID) ([]string, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT code FROM invites WHERE server_id = ? AND vanity = 0`, int64(serverID))
	if err != nil {
		return nil, err
	}
	var codes []string
	for rows.Next() {
		var c string
		if err := rows.Scan(&c); err != nil {
			rows.Close()
			return nil, err
		}
		codes = append(codes, c)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if _, err := s.db.W.ExecContext(ctx, `DELETE FROM invites WHERE server_id = ? AND vanity = 0`, int64(serverID)); err != nil {
		return nil, err
	}
	if codes == nil {
		codes = []string{}
	}
	return codes, nil
}
