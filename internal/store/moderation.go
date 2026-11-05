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

// What staff can do to an account. Each one opens a case.
const (
	ActionAvatarRemove = "avatar_remove"
	ActionFieldReset   = "field_reset"
	ActionMute         = "mute"
	ActionBan          = "ban"
	ActionDelete       = "delete"
	// ActionServerReset takes back what a server shows everyone (its icon,
	// banner, name or description). Its case is its owner's.
	ActionServerReset = "server_reset"
	// ActionExpressionTakedown takes down stickers, emoji or a whole pack, or
	// a pack's name, description or place in Discover
	// (moderation_expressions.go). Its case is the pack's owner's, or the
	// server's owner's for a server's.
	ActionExpressionTakedown = "expression_takedown"
)

// What else staff do that the log records, without a case of its own.
const (
	LogUnmute             = "unmute"
	LogUnban              = "unban"
	LogAnnouncement       = "announcement"
	LogAnnouncementDelete = "announcement_delete"
	// LogAdminGrant and LogAdminRevoke are from before ranks; LogRankChange
	// has replaced them, with the rank before and after in its details.
	LogAdminGrant     = "admin_grant"
	LogAdminRevoke    = "admin_revoke"
	LogRankChange     = "rank_change"
	LogAccountDisable = "account_disable"
	LogAccountEnable  = "account_enable"
	// LogExpressionRelist lets a pack taken out of Discover be listed again.
	LogExpressionRelist = "expression_relist"
	// LogDiscoveryRestore lets a server taken off Discovery be listed again.
	LogDiscoveryRestore = "discovery_restore"
	// Badges (badges.go): giving and taking an account's badges by hand, with
	// the names added and removed in its details, and making, changing and
	// deleting event badges, with the name.
	LogBadgeChange = "badge_change"
	LogBadgeCreate = "badge_create"
	LogBadgeEdit   = "badge_edit"
	LogBadgeDelete = "badge_delete"
	LogGameList    = "game_list"
	LogGameReject  = "game_reject"
	LogGameForget  = "game_forget"
)

// The restrictions a case can put in force.
const (
	RestrictMute = "mute"
	RestrictBan  = "ban"
)

// Who wrote a line of a case's thread, and what a system line records.
const (
	ModMsgMember = "member"
	ModMsgStaff  = "staff"
	ModMsgSystem = "system"

	ModEventLifted   = "lifted"
	ModEventClosed   = "closed"
	ModEventReopened = "reopened"
	ModEventExpired  = "expired"
	ModEventReplaced = "replaced"
)

// Where a case stands, and whose turn it is in its thread.
const (
	CaseOpen   = "open"
	CaseClosed = "closed"

	AwaitStaff  = "staff"
	AwaitMember = "member"
	AwaitNone   = "none"
)

// ErrNotRestricted means the case's mute or ban is no longer in force.
var ErrNotRestricted = errors.New("not restricted")

// ModCase is one action taken against an account, and the thread about it.
type ModCase struct {
	ID                ids.ID          `json:"id"`
	Number            int64           `json:"number"`
	UserID            ids.ID          `json:"user_id"`
	ModeratorID       ids.ID          `json:"moderator_id"`
	Action            string          `json:"action"`
	Reason            string          `json:"reason"`
	Details           json.RawMessage `json:"details"`
	TargetUsername    string          `json:"target_username"`
	TargetDisplayName string          `json:"target_display_name"`
	ExpiresAt         *int64          `json:"expires_at"`
	LiftedAt          *int64          `json:"lifted_at"`
	LiftedBy          *ids.ID         `json:"lifted_by"`
	LiftReason        string          `json:"lift_reason"`
	Status            string          `json:"status"`
	ClosedAt          *int64          `json:"closed_at"`
	ClosedBy          *ids.ID         `json:"closed_by"`
	Awaiting          string          `json:"awaiting"`
	LastActivityAt    int64           `json:"last_activity_at"`
	MemberReadID      ids.ID          `json:"-"`
	NoticeAckAt       *int64          `json:"notice_ack_at"`
	CreatedAt         int64           `json:"created_at"`
}

// Restricts reports whether the case puts a mute or ban in force, and which.
func (c *ModCase) Restricts() string {
	switch c.Action {
	case ActionMute:
		return RestrictMute
	case ActionBan:
		return RestrictBan
	}
	return ""
}

// ModMessage is one line of a case's thread.
type ModMessage struct {
	ID        ids.ID `json:"id"`
	CaseID    ids.ID `json:"case_id"`
	AuthorID  ids.ID `json:"author_id"`
	Kind      string `json:"kind"`
	Event     string `json:"event"`
	Body      string `json:"body"`
	CreatedAt int64  `json:"created_at"`
	// Files are the pictures and recordings sent with the line.
	Files []CaseFile `json:"files,omitempty"`
}

// Restriction is a mute or ban in force on an account.
type Restriction struct {
	UserID    ids.ID `json:"-"`
	Kind      string `json:"kind"`
	CaseID    ids.ID `json:"case_id"`
	ExpiresAt *int64 `json:"expires_at"`
	CreatedAt int64  `json:"created_at"`
}

// Active reports whether the restriction is still in force at now.
func (r *Restriction) Active(now int64) bool {
	return r != nil && (r.ExpiresAt == nil || *r.ExpiresAt > now)
}

// Announcement is a message staff sent to everyone through Nexa.
type Announcement struct {
	ID        ids.ID `json:"id"`
	AuthorID  ids.ID `json:"author_id"`
	Title     string `json:"title"`
	Body      string `json:"body"`
	CreatedAt int64  `json:"created_at"`
}

// ModLogEntry is one line of the append-only record of what staff did.
type ModLogEntry struct {
	ID                ids.ID          `json:"id"`
	Action            string          `json:"action"`
	ModeratorID       ids.ID          `json:"moderator_id"`
	ModeratorUsername string          `json:"moderator_username"`
	TargetID          *ids.ID         `json:"target_id"`
	TargetUsername    string          `json:"target_username"`
	CaseID            *ids.ID         `json:"case_id"`
	CaseNumber        *int64          `json:"case_number"`
	Reason            string          `json:"reason"`
	Details           json.RawMessage `json:"details"`
	CreatedAt         int64           `json:"created_at"`
}

// --- cases ------------------------------------------------------------------

const modCaseCols = `id, number, user_id, moderator_id, action, reason, details, target_username, target_display_name, expires_at, lifted_at, lifted_by, lift_reason, status, closed_at, closed_by, awaiting, last_activity_at, member_read_id, notice_ack_at, created_at`

func scanModCase(r rowScanner) (*ModCase, error) {
	var c ModCase
	var details string
	var expires, lifted, liftedBy, closed, closedBy, ack sql.NullInt64
	err := r.Scan(&c.ID, &c.Number, &c.UserID, &c.ModeratorID, &c.Action, &c.Reason, &details, &c.TargetUsername, &c.TargetDisplayName,
		&expires, &lifted, &liftedBy, &c.LiftReason, &c.Status, &closed, &closedBy, &c.Awaiting, &c.LastActivityAt, &c.MemberReadID, &ack, &c.CreatedAt)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	c.Details = json.RawMessage(details)
	if !json.Valid(c.Details) {
		c.Details = json.RawMessage(`{}`)
	}
	c.ExpiresAt, c.LiftedAt, c.ClosedAt, c.NoticeAckAt = int64Ptr(expires), int64Ptr(lifted), int64Ptr(closed), int64Ptr(ack)
	c.LiftedBy, c.ClosedBy = idPtr(liftedBy), idPtr(closedBy)
	return &c, nil
}

func (s *Store) queryModCases(ctx context.Context, q string, args ...any) ([]*ModCase, error) {
	rows, err := s.db.R.QueryContext(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*ModCase{}
	for rows.Next() {
		c, err := scanModCase(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

func modCaseTx(ctx context.Context, tx *db.Tx, id ids.ID) (*ModCase, error) {
	return scanModCase(tx.QueryRowContext(ctx, `SELECT `+modCaseCols+` FROM mod_cases WHERE id = ?`, int64(id)))
}

// createCaseTx numbers and inserts a case. Numbers are handed out under a lock
// so two moderators acting at once on Postgres cannot draw the same one.
func createCaseTx(ctx context.Context, tx *db.Tx, c *ModCase) error {
	if err := tx.Serialise(ctx, "mod-case-number"); err != nil {
		return err
	}
	if err := tx.QueryRowContext(ctx, `SELECT coalesce(max(number), 0) + 1 FROM mod_cases`).Scan(&c.Number); err != nil {
		return err
	}
	if c.ID.IsZero() {
		c.ID = ids.New()
	}
	if c.CreatedAt == 0 {
		c.CreatedAt = nowMs()
	}
	c.LastActivityAt = c.CreatedAt
	if c.Status == "" {
		c.Status = CaseOpen
	}
	if c.Awaiting == "" {
		c.Awaiting = AwaitNone
	}
	if len(c.Details) == 0 || !json.Valid(c.Details) {
		c.Details = json.RawMessage(`{}`)
	}
	_, err := tx.ExecContext(ctx, `INSERT INTO mod_cases (id, number, user_id, moderator_id, action, reason, details, target_username, target_display_name, expires_at, status, closed_at, closed_by, awaiting, last_activity_at, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
		int64(c.ID), c.Number, int64(c.UserID), int64(c.ModeratorID), c.Action, c.Reason, string(c.Details), c.TargetUsername, c.TargetDisplayName,
		nullInt64(c.ExpiresAt), c.Status, nullInt64(c.ClosedAt), nullID(c.ClosedBy), c.Awaiting, c.LastActivityAt, c.CreatedAt)
	return err
}

// ModCaseByID loads one case.
func (s *Store) ModCaseByID(ctx context.Context, id ids.ID) (*ModCase, error) {
	return scanModCase(s.db.R.QueryRowContext(ctx, `SELECT `+modCaseCols+` FROM mod_cases WHERE id = ?`, int64(id)))
}

// MaxCasesPerUser bounds how many of one account's cases a single read returns.
const MaxCasesPerUser = 500

// ModCasesForUser lists an account's cases, newest first.
func (s *Store) ModCasesForUser(ctx context.Context, uid ids.ID) ([]*ModCase, error) {
	return s.queryModCases(ctx, `SELECT `+modCaseCols+` FROM mod_cases WHERE user_id = ? ORDER BY id DESC LIMIT ?`, int64(uid), MaxCasesPerUser)
}

// PendingModNotices lists the cases whose notice the member has not dismissed,
// oldest first, so they are shown in the order they happened.
func (s *Store) PendingModNotices(ctx context.Context, uid ids.ID) ([]*ModCase, error) {
	return s.queryModCases(ctx, `SELECT `+modCaseCols+` FROM mod_cases WHERE user_id = ? AND notice_ack_at IS NULL ORDER BY id LIMIT 20`, int64(uid))
}

// AckModNotice records that the member dismissed a case's notice.
func (s *Store) AckModNotice(ctx context.Context, caseID, uid ids.ID) error {
	res, err := s.db.W.ExecContext(ctx, `UPDATE mod_cases SET notice_ack_at = ? WHERE id = ? AND user_id = ? AND notice_ack_at IS NULL`, nowMs(), int64(caseID), int64(uid))
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		// already dismissed is as good as dismissed; a case of someone else's is not
		var owner int64
		if err := s.db.R.QueryRowContext(ctx, `SELECT user_id FROM mod_cases WHERE id = ?`, int64(caseID)).Scan(&owner); err != nil || ids.ID(owner) != uid {
			return ErrNotFound
		}
	}
	return nil
}

// ModCaseFilter picks which cases the staff list shows.
type ModCaseFilter struct {
	// View is needs_reply (the member wrote last), open, in_effect (its mute or
	// ban still holds), closed, or all.
	View string
	// Before is a cursor: cases active before this moment, or at it with a
	// smaller id, so a page boundary never skips or repeats one.
	BeforeAt int64
	BeforeID ids.ID
	Limit    int
	Now      int64
}

// ModCaseList pages cases for the staff, most recently active first.
func (s *Store) ModCaseList(ctx context.Context, f ModCaseFilter) ([]*ModCase, error) {
	if f.Limit <= 0 || f.Limit > 100 {
		f.Limit = 50
	}
	where, args := modCaseView(f.View, f.Now)
	if f.BeforeAt > 0 {
		where = append(where, `(last_activity_at < ? OR (last_activity_at = ? AND id < ?))`)
		args = append(args, f.BeforeAt, f.BeforeAt, int64(f.BeforeID))
	}
	q := `SELECT ` + modCaseCols + ` FROM mod_cases`
	if len(where) > 0 {
		q += ` WHERE ` + strings.Join(where, " AND ")
	}
	args = append(args, f.Limit)
	return s.queryModCases(ctx, q+` ORDER BY last_activity_at DESC, id DESC LIMIT ?`, args...)
}

func modCaseView(view string, now int64) ([]string, []any) {
	switch view {
	case "needs_reply":
		return []string{`status = 'open'`, `awaiting = 'staff'`}, nil
	case "open":
		return []string{`status = 'open'`}, nil
	case "in_effect":
		return []string{`EXISTS (SELECT 1 FROM mod_restrictions r WHERE r.case_id = mod_cases.id AND (r.expires_at IS NULL OR r.expires_at > ?))`}, []any{now}
	case "closed":
		return []string{`status = 'closed'`}, nil
	}
	return nil, nil
}

// ModCaseCounts is how many cases each staff view holds.
type ModCaseCounts struct {
	NeedsReply int `json:"needs_reply"`
	Open       int `json:"open"`
	InEffect   int `json:"in_effect"`
	Closed     int `json:"closed"`
	All        int `json:"all"`
}

// CountModCases counts the cases in each staff view, each the way
// ModCaseList picks them, so a view holds what its count says.
func (s *Store) CountModCases(ctx context.Context, now int64) (ModCaseCounts, error) {
	var c ModCaseCounts
	err := s.db.R.QueryRowContext(ctx, `SELECT
		(SELECT count(*) FROM mod_cases WHERE status = 'open' AND awaiting = 'staff'),
		(SELECT count(*) FROM mod_cases WHERE status = 'open'),
		(SELECT count(*) FROM mod_cases WHERE EXISTS (SELECT 1 FROM mod_restrictions r WHERE r.case_id = mod_cases.id AND (r.expires_at IS NULL OR r.expires_at > ?))),
		(SELECT count(*) FROM mod_cases WHERE status = 'closed'),
		(SELECT count(*) FROM mod_cases)`, now).Scan(&c.NeedsReply, &c.Open, &c.InEffect, &c.Closed, &c.All)
	return c, err
}

// MaxModMessages bounds one page of a thread.
const MaxModMessages = 200

const modMsgCols = `id, case_id, author_id, kind, event, body, created_at`

func scanModMessage(r rowScanner) (*ModMessage, error) {
	var m ModMessage
	if err := r.Scan(&m.ID, &m.CaseID, &m.AuthorID, &m.Kind, &m.Event, &m.Body, &m.CreatedAt); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &m, nil
}

// ModMessages returns a case's thread in order, the oldest page first, or the
// page after `after`.
func (s *Store) ModMessages(ctx context.Context, caseID, after ids.ID, limit int) ([]*ModMessage, error) {
	if limit <= 0 || limit > MaxModMessages {
		limit = MaxModMessages
	}
	rows, err := s.db.R.QueryContext(ctx, `SELECT `+modMsgCols+` FROM mod_messages WHERE case_id = ? AND id > ? ORDER BY id LIMIT ?`, int64(caseID), int64(after), limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*ModMessage{}
	for rows.Next() {
		m, err := scanModMessage(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	rows.Close()
	return out, s.withCaseFiles(ctx, out)
}

func insertModMessageTx(ctx context.Context, tx *db.Tx, m *ModMessage) error {
	if m.ID.IsZero() {
		m.ID = ids.New()
	}
	if m.CreatedAt == 0 {
		m.CreatedAt = nowMs()
	}
	_, err := tx.ExecContext(ctx, `INSERT INTO mod_messages (`+modMsgCols+`) VALUES (?,?,?,?,?,?,?)`,
		int64(m.ID), int64(m.CaseID), int64(m.AuthorID), m.Kind, m.Event, m.Body, m.CreatedAt)
	return err
}

// AddModMessage writes one line to a case's thread, with the files it names,
// and moves the case along: whoever wrote is no longer the one being waited
// on. A closed case is closed to everyone: staff closed it, and only staff
// opening it again lets anyone write in it (ErrCaseClosed). The second value
// is the system lines the write added, which is none today.
func (s *Store) AddModMessage(ctx context.Context, caseID, author ids.ID, kind, body string, files []CaseFile) (*ModMessage, []*ModMessage, *ModCase, error) {
	var msg *ModMessage
	var extra []*ModMessage
	var c *ModCase
	err := s.Tx(ctx, func(tx *db.Tx) error {
		msg, extra, c = nil, nil, nil
		if err := tx.Serialise(ctx, "mod-case", int64(caseID)); err != nil {
			return err
		}
		cur, err := modCaseTx(ctx, tx, caseID)
		if err != nil {
			return err
		}
		if cur.Status == CaseClosed {
			return ErrCaseClosed
		}
		now := nowMs()
		status, awaiting := cur.Status, cur.Awaiting
		switch kind {
		case ModMsgMember:
			awaiting = AwaitStaff
		case ModMsgStaff:
			awaiting = AwaitMember
		}
		msg = &ModMessage{CaseID: caseID, AuthorID: author, Kind: kind, Body: body, CreatedAt: now}
		if err := insertModMessageTx(ctx, tx, msg); err != nil {
			return err
		}
		if err := attachCaseFilesTx(ctx, tx, msg, files); err != nil {
			return err
		}
		closedAt, closedBy := any(nullInt64(cur.ClosedAt)), any(nullID(cur.ClosedBy))
		if status == CaseOpen {
			closedAt, closedBy = nil, nil
		}
		readID := int64(cur.MemberReadID)
		if kind == ModMsgMember {
			// what they just wrote, and everything before it, they have seen
			readID = int64(msg.ID)
		}
		if _, err := tx.ExecContext(ctx, `UPDATE mod_cases SET status = ?, awaiting = ?, last_activity_at = ?, closed_at = ?, closed_by = ?, member_read_id = ? WHERE id = ?`,
			status, awaiting, now, closedAt, closedBy, readID, int64(caseID)); err != nil {
			return err
		}
		c, err = modCaseTx(ctx, tx, caseID)
		return err
	})
	if err != nil {
		return nil, nil, nil, err
	}
	return msg, extra, c, nil
}

// SetModCaseClosed closes or reopens a case for the staff, with a system line
// saying who did it. Asking for the state it is already in changes nothing.
func (s *Store) SetModCaseClosed(ctx context.Context, caseID, by ids.ID, closed bool) (*ModCase, *ModMessage, error) {
	var c *ModCase
	var line *ModMessage
	err := s.Tx(ctx, func(tx *db.Tx) error {
		c, line = nil, nil
		if err := tx.Serialise(ctx, "mod-case", int64(caseID)); err != nil {
			return err
		}
		cur, err := modCaseTx(ctx, tx, caseID)
		if err != nil {
			return err
		}
		if (cur.Status == CaseClosed) == closed {
			c = cur
			return nil
		}
		now := nowMs()
		event := ModEventReopened
		if closed {
			event = ModEventClosed
			_, err = tx.ExecContext(ctx, `UPDATE mod_cases SET status = 'closed', closed_at = ?, closed_by = ?, awaiting = 'none', last_activity_at = ? WHERE id = ?`, now, int64(by), now, int64(caseID))
		} else {
			_, err = tx.ExecContext(ctx, `UPDATE mod_cases SET status = 'open', closed_at = NULL, closed_by = NULL, last_activity_at = ? WHERE id = ?`, now, int64(caseID))
		}
		if err != nil {
			return err
		}
		line = &ModMessage{CaseID: caseID, AuthorID: by, Kind: ModMsgSystem, Event: event, CreatedAt: now}
		if err := insertModMessageTx(ctx, tx, line); err != nil {
			return err
		}
		c, err = modCaseTx(ctx, tx, caseID)
		return err
	})
	return c, line, err
}

// MarkModCaseRead moves the member's read position in their own case forward.
func (s *Store) MarkModCaseRead(ctx context.Context, caseID, uid, upTo ids.ID) error {
	res, err := s.db.W.ExecContext(ctx, `UPDATE mod_cases SET member_read_id = `+s.db.Dialect.Greatest("member_read_id", "?")+` WHERE id = ? AND user_id = ?`, int64(upTo), int64(caseID), int64(uid))
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// ModUnreadByCase counts, per case of the member's, the staff replies they
// have not read yet. Cases with none are left out.
func (s *Store) ModUnreadByCase(ctx context.Context, uid ids.ID) (map[ids.ID]int, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT m.case_id, count(*) FROM mod_messages m JOIN mod_cases c ON c.id = m.case_id
		WHERE c.user_id = ? AND m.kind = 'staff' AND m.id > c.member_read_id GROUP BY m.case_id`, int64(uid))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[ids.ID]int{}
	for rows.Next() {
		var id int64
		var n int
		if err := rows.Scan(&id, &n); err != nil {
			return nil, err
		}
		out[ids.ID(id)] = n
	}
	return out, rows.Err()
}

// LastModMessages returns the newest line of each listed case's thread, for
// the previews in the case lists.
func (s *Store) LastModMessages(ctx context.Context, caseIDs []ids.ID) (map[ids.ID]*ModMessage, error) {
	out := map[ids.ID]*ModMessage{}
	for start := 0; start < len(caseIDs); start += 200 {
		end := min(start+200, len(caseIDs))
		chunk := caseIDs[start:end]
		rows, err := s.db.R.QueryContext(ctx, `SELECT `+modMsgCols+` FROM mod_messages WHERE id IN (SELECT max(id) FROM mod_messages WHERE case_id IN (`+placeholders(len(chunk))+`) GROUP BY case_id)`, idArgs(chunk)...)
		if err != nil {
			return nil, err
		}
		for rows.Next() {
			m, err := scanModMessage(rows)
			if err != nil {
				rows.Close()
				return nil, err
			}
			out[m.CaseID] = m
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return nil, err
		}
	}
	// a preview of a line that is only a picture says so
	list := make([]*ModMessage, 0, len(out))
	for _, m := range out {
		list = append(list, m)
	}
	return out, s.withCaseFiles(ctx, list)
}

// --- actions ----------------------------------------------------------------

// ModImages lists which pictures an avatar_remove takes off.
type ModImages struct {
	Avatar bool
	Banner bool
}

// ModRemoveImages takes an account's avatar and/or banner away and opens the
// case for it, in one transaction. Taking the avatar takes the per-server
// avatars too, and the banner the per-server banners, which are the same kind
// of picture in another place; the servers whose member lists changed come
// back, so they can be told. The pictures' references are let go, so the
// files go with the next sweep unless something else still uses them.
func (s *Store) ModRemoveImages(ctx context.Context, c *ModCase, which ModImages, log *ModLogEntry) ([]ids.ID, error) {
	var servers []ids.ID
	err := s.Tx(ctx, func(tx *db.Tx) error {
		// Postgres runs these side by side; one at a time, so two cannot both
		// read the same "before" and act on it
		if err := tx.Serialise(ctx, "image:users.avatar_id", int64(c.UserID)); err != nil {
			return err
		}
		// Postgres runs these side by side; one at a time, so two cannot both
		// read the same "before" and act on it
		if err := tx.Serialise(ctx, "image:users.banner_id", int64(c.UserID)); err != nil {
			return err
		}
		servers = nil
		changed := map[ids.ID]bool{}
		// The per-server pictures are the same picture in another place: the
		// avatar takes the servers' avatars, the banner the servers' banners.
		for col, on := range map[string]bool{"avatar_id": which.Avatar, "banner_id": which.Banner} {
			if !on {
				continue
			}
			list, err := idsTx(ctx, tx, `SELECT server_id FROM server_members WHERE user_id = ? AND `+col+` IS NOT NULL`, int64(c.UserID))
			if err != nil {
				return err
			}
			for _, id := range list {
				if !changed[id] {
					changed[id] = true
					servers = append(servers, id)
				}
			}
			if err := releaseBlobRefs(ctx, tx, `SELECT `+col+` FROM server_members WHERE user_id = ? AND `+col+` IS NOT NULL`, int64(c.UserID)); err != nil {
				return err
			}
			if _, err := tx.ExecContext(ctx, `UPDATE server_members SET `+col+` = NULL WHERE user_id = ? AND `+col+` IS NOT NULL`, int64(c.UserID)); err != nil {
				return err
			}
		}
		for col, on := range map[string]bool{"avatar_id": which.Avatar, "banner_id": which.Banner} {
			if !on {
				continue
			}
			var old sql.NullInt64
			if err := tx.QueryRowContext(ctx, `SELECT `+col+` FROM users WHERE id = ?`, int64(c.UserID)).Scan(&old); err != nil {
				if errors.Is(err, sql.ErrNoRows) {
					return ErrNotFound
				}
				return err
			}
			if !old.Valid {
				continue
			}
			if _, err := tx.ExecContext(ctx, `UPDATE users SET `+col+` = NULL, updated_at = ? WHERE id = ?`, nowMs(), int64(c.UserID)); err != nil {
				return err
			}
			if _, err := tx.ExecContext(ctx, `UPDATE blobs SET ref_count = `+refFloor(tx.Dialect(), "ref_count - 1")+` WHERE id = ?`, old.Int64); err != nil {
				return err
			}
		}
		return openCaseTx(ctx, tx, c, log)
	})
	if err != nil {
		return nil, err
	}
	return servers, nil
}

// SetModCaseDetails replaces what a case records about itself, for a deletion
// finishing: how much went, and that it all did.
func (s *Store) SetModCaseDetails(ctx context.Context, caseID ids.ID, details json.RawMessage) error {
	res, err := s.db.W.ExecContext(ctx, `UPDATE mod_cases SET details = ?, last_activity_at = ? WHERE id = ?`, string(details), nowMs(), int64(caseID))
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// ModFieldReset is what a field_reset writes: the new value of each field it
// resets, and the username it took, which stays with the account for signing in.
type ModFieldReset struct {
	Username    *string
	DisplayName *string
	AboutMe     *string
	Pronouns    *string
	// Status clears the custom status and writes StatusText in its place.
	Status     bool
	StatusText string
	// Nicknames clears the nickname the account goes by in every server.
	Nicknames bool
	// ServerProfiles clears the bio, pronouns and widgets of the account's
	// profile in every server, and lets the widgets' pictures go.
	ServerProfiles bool
	// TypingStyle puts the account's typing line back to the plain one.
	TypingStyle bool
	// NameStyle draws the display name plainly again.
	NameStyle bool
	// Widgets takes every widget off the profile and lets their pictures go.
	Widgets bool
	// ServerTag takes off the server tag the account wears.
	ServerTag bool
	// HeldUsername is the name the reset took off the account.
	HeldUsername string
}

// ModResetFields overwrites profile fields and opens the case for it, in one
// transaction. A new username that collides comes back as ErrUsernameTaken, so
// the caller can draw another.
func (s *Store) ModResetFields(ctx context.Context, c *ModCase, f ModFieldReset, log *ModLogEntry) error {
	var sets []string
	var args []any
	set := func(col string, v any) { sets = append(sets, col+" = ?"); args = append(args, v) }
	if f.Username != nil {
		set("username", *f.Username)
	}
	if f.DisplayName != nil {
		set("display_name", *f.DisplayName)
	}
	if f.AboutMe != nil {
		set("about_me", *f.AboutMe)
	}
	if f.Pronouns != nil {
		set("pronouns", *f.Pronouns)
	}
	if f.Status {
		set("status_text", f.StatusText)
		set("status_emoji", "")
		set("status_expires_at", nil)
	}
	if f.TypingStyle {
		set("typing_style", "")
	}
	if f.NameStyle {
		set("name_style", "")
	}
	if f.Widgets {
		set("widgets", "")
	}
	if f.ServerTag {
		set("tag_server_id", nil)
		set("tag_text", "")
		set("tag_badge", "")
		set("tag_color", nil)
	}
	if len(sets) == 0 && !f.Nicknames && !f.ServerProfiles {
		return errors.New("field reset: nothing to reset")
	}
	set("updated_at", nowMs())
	args = append(args, int64(c.UserID))
	err := s.Tx(ctx, func(tx *db.Tx) error {
		if f.Username != nil {
			held, err := usernameHeldTx(ctx, tx, *f.Username)
			if err != nil {
				return err
			}
			if held {
				return ErrUsernameTaken
			}
		}
		// the pictures a typing style names, given back with it
		if f.TypingStyle {
			if err := swapTypingImagesTx(ctx, tx, c.UserID, ""); err != nil {
				return err
			}
		}
		// the pictures the widgets name, given back once the widgets are gone
		var widgets string
		if f.Widgets {
			if err := tx.QueryRowContext(ctx, `SELECT widgets FROM users WHERE id = ?`, int64(c.UserID)).Scan(&widgets); err != nil {
				if errors.Is(err, sql.ErrNoRows) {
					return ErrNotFound
				}
				return err
			}
		}
		res, err := tx.ExecContext(ctx, `UPDATE users SET `+strings.Join(sets, ", ")+` WHERE id = ?`, args...)
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return ErrNotFound
		}
		if f.ServerProfiles {
			if err := releaseWidgetRefs(ctx, tx, `SELECT widgets FROM server_members WHERE user_id = ? AND widgets IS NOT NULL`, int64(c.UserID)); err != nil {
				return err
			}
			if _, err := tx.ExecContext(ctx, `UPDATE server_members SET bio = '', pronouns = '', widgets = NULL WHERE user_id = ? AND (bio <> '' OR pronouns <> '' OR widgets IS NOT NULL)`, int64(c.UserID)); err != nil {
				return err
			}
		}
		for id := range WidgetImages(widgets) {
			if _, err := tx.ExecContext(ctx, `UPDATE blobs SET ref_count = `+refFloor(tx.Dialect(), "ref_count - 1")+` WHERE id = ?`, int64(id)); err != nil {
				return err
			}
		}
		if f.Nicknames {
			if _, err := tx.ExecContext(ctx, `UPDATE server_members SET nickname = '' WHERE user_id = ? AND nickname <> ''`, int64(c.UserID)); err != nil {
				return err
			}
		}
		if err := openCaseTx(ctx, tx, c, log); err != nil {
			return err
		}
		if f.HeldUsername != "" && (f.Username == nil || *f.Username != f.HeldUsername) {
			if err := tx.Serialise(ctx, "username:"+f.HeldUsername); err != nil {
				return err
			}
			if _, err := tx.ExecContext(ctx, `INSERT INTO username_holds (username, user_id, case_id, created_at) VALUES (?,?,?,?) ON CONFLICT (username) DO NOTHING`,
				f.HeldUsername, int64(c.UserID), int64(c.ID), c.CreatedAt); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil && db.IsUniqueViolation(err) {
		return ErrUsernameTaken
	}
	return err
}

// ModRestrict puts a mute or ban in force and opens the case for it, in one
// transaction. One of each kind holds at a time: a new one replaces the one
// before, whose case gets a line saying which case took over. The replaced
// case's id comes back, zero when there was none.
func (s *Store) ModRestrict(ctx context.Context, c *ModCase, log *ModLogEntry) (ids.ID, *ModMessage, error) {
	kind := c.Restricts()
	if kind == "" {
		return 0, nil, errors.New("restrict: action " + c.Action + " restricts nothing")
	}
	var prev ids.ID
	var line *ModMessage
	err := s.Tx(ctx, func(tx *db.Tx) error {
		prev, line = 0, nil
		if err := tx.Serialise(ctx, "mod-restrict", int64(c.UserID)); err != nil {
			return err
		}
		if err := openCaseTx(ctx, tx, c, log); err != nil {
			return err
		}
		var old sql.NullInt64
		var oldExp sql.NullInt64
		err := tx.QueryRowContext(ctx, `SELECT case_id, expires_at FROM mod_restrictions WHERE user_id = ? AND kind = ?`, int64(c.UserID), kind).Scan(&old, &oldExp)
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			return err
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO mod_restrictions (user_id, kind, case_id, expires_at, created_at) VALUES (?,?,?,?,?)
			ON CONFLICT (user_id, kind) DO UPDATE SET case_id = excluded.case_id, expires_at = excluded.expires_at, created_at = excluded.created_at`,
			int64(c.UserID), kind, int64(c.ID), nullInt64(c.ExpiresAt), c.CreatedAt); err != nil {
			return err
		}
		// Only a restriction still in force is "replaced": one that already ran
		// out had its own ending, even if the sweeper has not got to it yet.
		if old.Valid && (!oldExp.Valid || oldExp.Int64 > c.CreatedAt) {
			prev = ids.ID(old.Int64)
			line = &ModMessage{CaseID: prev, AuthorID: c.ModeratorID, Kind: ModMsgSystem, Event: ModEventReplaced, Body: itoa64(c.Number), CreatedAt: c.CreatedAt}
			if err := insertModMessageTx(ctx, tx, line); err != nil {
				return err
			}
			if _, err := tx.ExecContext(ctx, `UPDATE mod_cases SET last_activity_at = ? WHERE id = ?`, c.CreatedAt, int64(prev)); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		return 0, nil, err
	}
	return prev, line, nil
}

// OpenModCase records a case that changes nothing itself — a deletion, whose
// work is done afterwards by the caller — together with its log line.
func (s *Store) OpenModCase(ctx context.Context, c *ModCase, log *ModLogEntry) error {
	return s.Tx(ctx, func(tx *db.Tx) error { return openCaseTx(ctx, tx, c, log) })
}

// openCaseTx inserts the case and its log line, which names it.
func openCaseTx(ctx context.Context, tx *db.Tx, c *ModCase, log *ModLogEntry) error {
	if err := createCaseTx(ctx, tx, c); err != nil {
		return err
	}
	if log == nil {
		return nil
	}
	id, num := c.ID, c.Number
	log.CaseID, log.CaseNumber = &id, &num
	if log.CreatedAt == 0 {
		log.CreatedAt = c.CreatedAt
	}
	return appendModLogTx(ctx, tx, log)
}

// ModLift ends a case's mute or ban early. It fails with ErrNotRestricted when
// the case's restriction is no longer the one in force: it ran out, was lifted
// already, or a later case replaced it.
func (s *Store) ModLift(ctx context.Context, caseID, by ids.ID, reason string, log *ModLogEntry) (*ModCase, *ModMessage, error) {
	var c *ModCase
	var line *ModMessage
	err := s.Tx(ctx, func(tx *db.Tx) error {
		c, line = nil, nil
		cur, err := modCaseTx(ctx, tx, caseID)
		if err != nil {
			return err
		}
		kind := cur.Restricts()
		if kind == "" {
			return ErrNotRestricted
		}
		if err := tx.Serialise(ctx, "mod-restrict", int64(cur.UserID)); err != nil {
			return err
		}
		now := nowMs()
		res, err := tx.ExecContext(ctx, `DELETE FROM mod_restrictions WHERE user_id = ? AND kind = ? AND case_id = ? AND (expires_at IS NULL OR expires_at > ?)`,
			int64(cur.UserID), kind, int64(caseID), now)
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return ErrNotRestricted
		}
		if _, err := tx.ExecContext(ctx, `UPDATE mod_cases SET lifted_at = ?, lifted_by = ?, lift_reason = ?, last_activity_at = ? WHERE id = ?`,
			now, int64(by), reason, now, int64(caseID)); err != nil {
			return err
		}
		line = &ModMessage{CaseID: caseID, AuthorID: by, Kind: ModMsgSystem, Event: ModEventLifted, Body: reason, CreatedAt: now}
		if err := insertModMessageTx(ctx, tx, line); err != nil {
			return err
		}
		if log != nil {
			id, num := cur.ID, cur.Number
			log.CaseID, log.CaseNumber = &id, &num
			log.CreatedAt = now
			if err := appendModLogTx(ctx, tx, log); err != nil {
				return err
			}
		}
		c, err = modCaseTx(ctx, tx, caseID)
		return err
	})
	return c, line, err
}

// ActiveRestrictions returns the mute and ban in force on an account at now;
// either is nil when there is none.
func (s *Store) ActiveRestrictions(ctx context.Context, uid ids.ID, now int64) (mute, ban *Restriction, err error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT kind, case_id, expires_at, created_at FROM mod_restrictions WHERE user_id = ? AND (expires_at IS NULL OR expires_at > ?)`, int64(uid), now)
	if err != nil {
		return nil, nil, err
	}
	defer rows.Close()
	for rows.Next() {
		r := &Restriction{UserID: uid}
		var exp sql.NullInt64
		if err := rows.Scan(&r.Kind, &r.CaseID, &exp, &r.CreatedAt); err != nil {
			return nil, nil, err
		}
		r.ExpiresAt = int64Ptr(exp)
		switch r.Kind {
		case RestrictMute:
			mute = r
		case RestrictBan:
			ban = r
		}
	}
	return mute, ban, rows.Err()
}

// EndedRestriction is a mute or ban that ran out.
type EndedRestriction struct {
	UserID ids.ID
	Kind   string
	CaseID ids.ID
	Line   *ModMessage
}

// EndExpiredRestrictions removes the mutes and bans whose time has come and
// writes a line in each one's case. It returns what it ended, so the members
// and the staff can be told.
func (s *Store) EndExpiredRestrictions(ctx context.Context, now int64) ([]EndedRestriction, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT user_id, kind, case_id FROM mod_restrictions WHERE expires_at IS NOT NULL AND expires_at <= ? LIMIT 500`, now)
	if err != nil {
		return nil, err
	}
	var due []EndedRestriction
	for rows.Next() {
		var e EndedRestriction
		if err := rows.Scan(&e.UserID, &e.Kind, &e.CaseID); err != nil {
			rows.Close()
			return nil, err
		}
		due = append(due, e)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	var out []EndedRestriction
	for _, e := range due {
		err := s.Tx(ctx, func(tx *db.Tx) error {
			res, err := tx.ExecContext(ctx, `DELETE FROM mod_restrictions WHERE user_id = ? AND kind = ? AND case_id = ? AND expires_at IS NOT NULL AND expires_at <= ?`,
				int64(e.UserID), e.Kind, int64(e.CaseID), now)
			if err != nil {
				return err
			}
			if n, _ := res.RowsAffected(); n == 0 {
				// lifted or replaced in the meantime; that path told everyone
				return nil
			}
			e.Line = &ModMessage{CaseID: e.CaseID, Kind: ModMsgSystem, Event: ModEventExpired, CreatedAt: now}
			if err := insertModMessageTx(ctx, tx, e.Line); err != nil {
				return err
			}
			_, err = tx.ExecContext(ctx, `UPDATE mod_cases SET last_activity_at = ? WHERE id = ?`, now, int64(e.CaseID))
			return err
		})
		if err != nil {
			return out, err
		}
		if e.Line != nil {
			out = append(out, e)
		}
	}
	return out, nil
}

// --- the log ----------------------------------------------------------------

func appendModLogTx(ctx context.Context, tx *db.Tx, e *ModLogEntry) error {
	if e.ID.IsZero() {
		e.ID = ids.New()
	}
	if e.CreatedAt == 0 {
		e.CreatedAt = nowMs()
	}
	if len(e.Details) == 0 || !json.Valid(e.Details) {
		e.Details = json.RawMessage(`{}`)
	}
	_, err := tx.ExecContext(ctx, `INSERT INTO mod_log (id, action, moderator_id, moderator_username, target_id, target_username, case_id, case_number, reason, details, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)`,
		int64(e.ID), e.Action, int64(e.ModeratorID), e.ModeratorUsername, nullID(e.TargetID), e.TargetUsername, nullID(e.CaseID), nullInt64(e.CaseNumber), e.Reason, string(e.Details), e.CreatedAt)
	return err
}

// AppendModLog adds a line to the log on its own, for staff actions that
// change nothing else in this package's tables.
func (s *Store) AppendModLog(ctx context.Context, e *ModLogEntry) error {
	return s.Tx(ctx, func(tx *db.Tx) error { return appendModLogTx(ctx, tx, e) })
}

// ModLogFilter narrows the log.
type ModLogFilter struct {
	Actions  []string
	TargetID ids.ID
	// Query matches the target's or the moderator's username as recorded.
	Query  string
	Before ids.ID
	Limit  int
}

// ModLog pages the log, newest first.
func (s *Store) ModLog(ctx context.Context, f ModLogFilter) ([]*ModLogEntry, error) {
	if f.Limit <= 0 || f.Limit > 200 {
		f.Limit = 50
	}
	var where []string
	var args []any
	if len(f.Actions) > 0 {
		where = append(where, `action IN (`+placeholders(len(f.Actions))+`)`)
		for _, a := range f.Actions {
			args = append(args, a)
		}
	}
	if !f.TargetID.IsZero() {
		where = append(where, `target_id = ?`)
		args = append(args, int64(f.TargetID))
	}
	if q := strings.ToLower(strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(f.Query), "@"))); q != "" {
		// cut on a character, not a byte: half a character is text Postgres refuses
		if r := []rune(q); len(r) > maxSearchQuery {
			q = string(r[:maxSearchQuery])
		}
		like := "%" + escapeLike(q) + "%"
		where = append(where, `(`+s.db.Dialect.Lower("target_username")+` LIKE ? ESCAPE '\' OR `+s.db.Dialect.Lower("moderator_username")+` LIKE ? ESCAPE '\' OR `+s.db.Dialect.Lower("reason")+` LIKE ? ESCAPE '\')`)
		args = append(args, like, like, like)
	}
	if !f.Before.IsZero() {
		where = append(where, `id < ?`)
		args = append(args, int64(f.Before))
	}
	q := `SELECT id, action, moderator_id, moderator_username, target_id, target_username, case_id, case_number, reason, details, created_at FROM mod_log`
	if len(where) > 0 {
		q += ` WHERE ` + strings.Join(where, " AND ")
	}
	args = append(args, f.Limit)
	rows, err := s.db.R.QueryContext(ctx, q+` ORDER BY id DESC LIMIT ?`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*ModLogEntry{}
	for rows.Next() {
		var e ModLogEntry
		var target, caseID, caseNum sql.NullInt64
		var details string
		if err := rows.Scan(&e.ID, &e.Action, &e.ModeratorID, &e.ModeratorUsername, &target, &e.TargetUsername, &caseID, &caseNum, &e.Reason, &details, &e.CreatedAt); err != nil {
			return nil, err
		}
		e.TargetID, e.CaseID, e.CaseNumber = idPtr(target), idPtr(caseID), int64Ptr(caseNum)
		e.Details = json.RawMessage(details)
		if !json.Valid(e.Details) {
			e.Details = json.RawMessage(`{}`)
		}
		out = append(out, &e)
	}
	return out, rows.Err()
}

// --- announcements ------------------------------------------------------------

// CreateAnnouncement stores an announcement together with its log line.
func (s *Store) CreateAnnouncement(ctx context.Context, a *Announcement, log *ModLogEntry) error {
	if a.ID.IsZero() {
		a.ID = ids.New()
	}
	if a.CreatedAt == 0 {
		a.CreatedAt = nowMs()
	}
	return s.Tx(ctx, func(tx *db.Tx) error {
		if _, err := tx.ExecContext(ctx, `INSERT INTO mod_announcements (id, author_id, title, body, created_at) VALUES (?,?,?,?,?)`,
			int64(a.ID), int64(a.AuthorID), a.Title, a.Body, a.CreatedAt); err != nil {
			return err
		}
		if log == nil {
			return nil
		}
		log.CreatedAt = a.CreatedAt
		return appendModLogTx(ctx, tx, log)
	})
}

// Announcements pages announcements, newest first: those sent before
// `beforeAt` (0: now), leaving out any sent before `since` — an account only
// has the ones sent while it existed.
func (s *Store) Announcements(ctx context.Context, since, beforeAt int64, limit int) ([]*Announcement, error) {
	if limit <= 0 || limit > 101 {
		limit = 50
	}
	q := `SELECT id, author_id, title, body, created_at FROM mod_announcements WHERE created_at >= ?`
	args := []any{since}
	if beforeAt > 0 {
		q += ` AND created_at < ?`
		args = append(args, beforeAt)
	}
	args = append(args, limit)
	rows, err := s.db.R.QueryContext(ctx, q+` ORDER BY created_at DESC, id DESC LIMIT ?`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*Announcement{}
	for rows.Next() {
		var a Announcement
		if err := rows.Scan(&a.ID, &a.AuthorID, &a.Title, &a.Body, &a.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, &a)
	}
	return out, rows.Err()
}

// DeleteAnnouncement retracts an announcement, logging it. The retracted one
// comes back so the log and the reply can name it.
func (s *Store) DeleteAnnouncement(ctx context.Context, id ids.ID, log *ModLogEntry) (*Announcement, error) {
	var a Announcement
	err := s.Tx(ctx, func(tx *db.Tx) error {
		if err := tx.QueryRowContext(ctx, `SELECT id, author_id, title, body, created_at FROM mod_announcements WHERE id = ?`, int64(id)).
			Scan(&a.ID, &a.AuthorID, &a.Title, &a.Body, &a.CreatedAt); err != nil {
			if errors.Is(err, sql.ErrNoRows) {
				return ErrNotFound
			}
			return err
		}
		if _, err := tx.ExecContext(ctx, `DELETE FROM mod_announcements WHERE id = ?`, int64(id)); err != nil {
			return err
		}
		if log == nil {
			return nil
		}
		if log.Details == nil {
			log.Details, _ = json.Marshal(map[string]any{"announcement_id": a.ID, "title": a.Title})
		}
		return appendModLogTx(ctx, tx, log)
	})
	if err != nil {
		return nil, err
	}
	return &a, nil
}

// --- the member's side ----------------------------------------------------------

// NexaSeen is when the member last looked at what Nexa sent them (0: never).
func (s *Store) NexaSeen(ctx context.Context, uid ids.ID) (int64, error) {
	var at int64
	err := s.db.R.QueryRowContext(ctx, `SELECT seen_at FROM nexa_reads WHERE user_id = ?`, int64(uid)).Scan(&at)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, nil
	}
	return at, err
}

// MarkNexaRead reads the member's whole Nexa conversation up to at: its
// notices and announcements, and every line the team had written by then in
// their case threads and bug reports. The conversation counts all of those as
// one, and a thread that only opening it could read kept the count up however
// often the conversation itself was read.
func (s *Store) MarkNexaRead(ctx context.Context, uid ids.ID, at int64) error {
	upTo := func(table, key string) string {
		return s.db.Dialect.Greatest("member_read_id", `(SELECT coalesce(max(m.id), 0) FROM `+table+` m WHERE m.`+key+` AND m.created_at <= ?)`)
	}
	return s.Tx(ctx, func(tx *db.Tx) error {
		if _, err := tx.ExecContext(ctx, `INSERT INTO nexa_reads (user_id, seen_at) VALUES (?, ?)
			ON CONFLICT (user_id) DO UPDATE SET seen_at = `+s.db.Dialect.Greatest("nexa_reads.seen_at", "excluded.seen_at"), int64(uid), at); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `UPDATE mod_cases SET member_read_id = `+upTo("mod_messages", "case_id = mod_cases.id")+` WHERE user_id = ?`, at, int64(uid)); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, `UPDATE bug_reports SET member_read_id = `+upTo("bug_messages", "report_id = bug_reports.id")+` WHERE user_id = ?`, at, int64(uid))
		return err
	})
}

// NexaUnread counts what is waiting for the member from Nexa: notices and
// announcements that arrived after they last looked, staff replies in their
// cases they have not read, staff replies and status changes in their bug
// reports they have not read, and outcomes of safety reports they sent that
// arrived after they last looked. createdAt is when their account was made;
// announcements from before it were never theirs.
func (s *Store) NexaUnread(ctx context.Context, uid ids.ID, createdAt int64) (int, error) {
	seen, err := s.NexaSeen(ctx, uid)
	if err != nil {
		return 0, err
	}
	var n int
	err = s.db.R.QueryRowContext(ctx, `SELECT
		(SELECT count(*) FROM mod_cases WHERE user_id = ? AND created_at > ?) +
		(SELECT count(*) FROM mod_cases WHERE user_id = ? AND lifted_at IS NOT NULL AND lifted_at > ?) +
		(SELECT count(*) FROM mod_announcements WHERE created_at > ? AND created_at >= ?) +
		(SELECT count(*) FROM mod_messages m JOIN mod_cases c ON c.id = m.case_id WHERE c.user_id = ? AND m.kind = 'staff' AND m.id > c.member_read_id) +
		(SELECT count(*) FROM bug_messages m JOIN bug_reports r ON r.id = m.report_id WHERE r.user_id = ? AND `+bugNews+` AND m.id > r.member_read_id) +
		(SELECT count(*) FROM safety_reports WHERE reporter_id = ? AND outcome_at > ?)`,
		int64(uid), seen, int64(uid), seen, seen, createdAt, int64(uid), int64(uid), int64(uid), seen).Scan(&n)
	return n, err
}

// NexaLatest is when Nexa last had something for the member (0: never): a
// notice, a lift, an announcement, a staff reply in one of their cases, a bug
// report they sent, or a staff reply or status change in one. It decides
// whether their Nexa conversation shows at all, where it sorts among their
// conversations, and whether one they closed comes back.
func (s *Store) NexaLatest(ctx context.Context, uid ids.ID, createdAt int64) (int64, error) {
	var a, b, c, d, e, f, g, h sql.NullInt64
	err := s.db.R.QueryRowContext(ctx, `SELECT
		(SELECT max(created_at) FROM mod_cases WHERE user_id = ?),
		(SELECT max(lifted_at) FROM mod_cases WHERE user_id = ?),
		(SELECT max(created_at) FROM mod_announcements WHERE created_at >= ?),
		(SELECT max(m.created_at) FROM mod_messages m JOIN mod_cases c ON c.id = m.case_id WHERE c.user_id = ? AND m.kind = 'staff'),
		(SELECT max(created_at) FROM bug_reports WHERE user_id = ?),
		(SELECT max(m.created_at) FROM bug_messages m JOIN bug_reports r ON r.id = m.report_id WHERE r.user_id = ? AND `+bugNews+`),
		(SELECT max(created_at) FROM safety_reports WHERE reporter_id = ?),
		(SELECT max(outcome_at) FROM safety_reports WHERE reporter_id = ?)`,
		int64(uid), int64(uid), createdAt, int64(uid), int64(uid), int64(uid), int64(uid), int64(uid)).Scan(&a, &b, &c, &d, &e, &f, &g, &h)
	return max(a.Int64, b.Int64, c.Int64, d.Int64, e.Int64, f.Int64, g.Int64, h.Int64), err
}

// --- accounts -------------------------------------------------------------------

// SystemUser returns the Nexa account, or ErrNotFound before it exists.
func (s *Store) SystemUser(ctx context.Context) (*User, error) {
	return scanUser(s.db.R.QueryRowContext(ctx, `SELECT `+userCols+` FROM users WHERE flags & ? != 0 AND flags & ? = 0 ORDER BY id LIMIT 1`, FlagSystem, FlagDeleted))
}

// StaffIDs lists the staff who can sign in and hold one of the rank bits in
// ranks: StaffFlags for everyone who can use the moderation panel.
func (s *Store) StaffIDs(ctx context.Context, ranks int64) ([]ids.ID, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT id FROM users WHERE flags & ? != 0 AND flags & ? = 0`, ranks, FlagDisabled|FlagDeleted|NotPeople)
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

// InstanceOwnerID is the owner of Nexa: its oldest admin account, which is
// the one that set it up (the first account made is an admin by default).
// Nobody can moderate it or take its access away, so it stays the oldest.
func (s *Store) InstanceOwnerID(ctx context.Context) (ids.ID, error) {
	var id int64
	err := s.db.R.QueryRowContext(ctx, `SELECT id FROM users WHERE flags & ? != 0 AND flags & ? = 0 ORDER BY id LIMIT 1`, FlagAdmin, FlagDeleted|NotPeople).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, nil
	}
	return ids.ID(id), err
}

// AccountIDs lists every account that can sign in, for telling everyone
// something at once.
func (s *Store) AccountIDs(ctx context.Context) ([]ids.ID, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT id FROM users WHERE flags & ? = 0`, FlagDisabled|FlagDeleted|NotPeople)
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

// LastSeen is when any of the account's sessions was last used, or nil.
func (s *Store) LastSeen(ctx context.Context, uid ids.ID) (*int64, error) {
	var at sql.NullInt64
	if err := s.db.R.QueryRowContext(ctx, `SELECT max(last_seen_at) FROM sessions WHERE user_id = ?`, int64(uid)).Scan(&at); err != nil {
		return nil, err
	}
	return int64Ptr(at), nil
}

// ModUser is an account as the moderation grid shows it.
type ModUser struct {
	PublicUser
	AdminFlags   int64        `json:"admin_flags"`
	CaseCount    int          `json:"case_count"`
	OpenCases    int          `json:"open_cases"`
	AwaitingCase int          `json:"awaiting_cases"`
	LastSeenAt   *int64       `json:"last_seen_at"`
	Mute         *Restriction `json:"mute"`
	Ban          *Restriction `json:"ban"`
	// PresencePref is what they chose to look like (online, idle, dnd or
	// invisible), which decides the presence the grid shows beside them.
	PresencePref string `json:"-"`
}

// ModUserFilter narrows and orders the grid.
type ModUserFilter struct {
	Query string
	// Show is all, online, muted, banned, staff, cases (has an open case) or
	// disabled. "online" is the accounts in Online that have not chosen to
	// look offline.
	Show string
	// Online is who has a connection open, for Show "online".
	Online []ids.ID
	// Sort is newest, oldest or name. Cursor is the last id (newest, oldest)
	// or username (name) of the page before.
	Sort   string
	Cursor string
	Limit  int
	Now    int64
}

// aboutExcerpt is as much of "about me" as a card in the grid ever shows.
const aboutExcerpt = 400

// ModUsers pages the accounts for the moderation grid.
func (s *Store) ModUsers(ctx context.Context, f ModUserFilter) ([]*ModUser, error) {
	if f.Limit <= 0 || f.Limit > 120 {
		f.Limit = 60
	}
	// apps' bots are in the Bots tab, not with the people
	where := []string{`u.flags & ? = 0`}
	args := []any{f.Now, f.Now, FlagDeleted | NotPeople | FlagBot}
	switch f.Show {
	case "muted":
		where = append(where, `mr.case_id IS NOT NULL`)
	case "banned":
		where = append(where, `br.case_id IS NOT NULL`)
	case "staff":
		where = append(where, `u.flags & ? != 0`)
		args = append(args, StaffFlags)
	case "disabled":
		where = append(where, `u.flags & ? != 0`)
		args = append(args, FlagDisabled)
	case "cases":
		where = append(where, `EXISTS (SELECT 1 FROM mod_cases c WHERE c.user_id = u.id AND c.status = 'open')`)
	case "online":
		// someone invisible looks offline here too, as they do everywhere else
		if len(f.Online) == 0 {
			return []*ModUser{}, nil
		}
		marks := strings.TrimSuffix(strings.Repeat("?,", len(f.Online)), ",")
		where = append(where, `u.id IN (`+marks+`)`, `u.presence_pref != 'invisible'`)
		for _, id := range f.Online {
			args = append(args, int64(id))
		}
	}
	if q := strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(f.Query), "@")); q != "" {
		if len(q) > maxSearchQuery {
			q = q[:maxSearchQuery]
		}
		like := "%" + escapeLike(strings.ToLower(q)) + "%"
		cond := `u.username LIKE ? ESCAPE '\' OR ` + s.db.Dialect.Lower("u.display_name") + ` LIKE ? ESCAPE '\'`
		args = append(args, like, like)
		if id, err := ids.Parse(q); err == nil {
			cond += ` OR u.id = ?`
			args = append(args, int64(id))
		}
		where = append(where, `(`+cond+`)`)
	}
	order := `u.id DESC`
	switch f.Sort {
	case "oldest":
		order = `u.id ASC`
		if id, err := ids.Parse(f.Cursor); err == nil && f.Cursor != "" {
			where = append(where, `u.id > ?`)
			args = append(args, int64(id))
		}
	case "name":
		order = `u.username ASC`
		if f.Cursor != "" {
			where = append(where, `u.username > ?`)
			args = append(args, f.Cursor)
		}
	default:
		if id, err := ids.Parse(f.Cursor); err == nil && f.Cursor != "" {
			where = append(where, `u.id < ?`)
			args = append(args, int64(id))
		}
	}
	args = append(args, f.Limit)
	rows, err := s.db.R.QueryContext(ctx, `SELECT u.id, u.username, u.display_name, u.avatar_id, u.banner_id, substr(u.about_me, 1, `+itoa64(aboutExcerpt)+`), u.pronouns,
		u.accent_color, u.accent_color_2, u.status_text, u.status_emoji, u.status_expires_at, u.flags, u.created_at, u.presence_pref,
		(SELECT count(*) FROM mod_cases c WHERE c.user_id = u.id),
		(SELECT count(*) FROM mod_cases c WHERE c.user_id = u.id AND c.status = 'open'),
		(SELECT count(*) FROM mod_cases c WHERE c.user_id = u.id AND c.status = 'open' AND c.awaiting = 'staff'),
		(SELECT max(last_seen_at) FROM sessions se WHERE se.user_id = u.id),
		mr.case_id, mr.expires_at, mr.created_at, br.case_id, br.expires_at, br.created_at
		FROM users u
		LEFT JOIN mod_restrictions mr ON mr.user_id = u.id AND mr.kind = 'mute' AND (mr.expires_at IS NULL OR mr.expires_at > ?)
		LEFT JOIN mod_restrictions br ON br.user_id = u.id AND br.kind = 'ban' AND (br.expires_at IS NULL OR br.expires_at > ?)
		WHERE `+strings.Join(where, " AND ")+` ORDER BY `+order+` LIMIT ?`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*ModUser{}
	for rows.Next() {
		var m ModUser
		var avatar, banner, accent, accent2, statusExp, seen sql.NullInt64
		var muteCase, muteExp, muteAt, banCase, banExp, banAt sql.NullInt64
		if err := rows.Scan(&m.ID, &m.Username, &m.DisplayName, &avatar, &banner, &m.AboutMe, &m.Pronouns,
			&accent, &accent2, &m.StatusText, &m.StatusEmoji, &statusExp, &m.AdminFlags, &m.CreatedAt, &m.PresencePref,
			&m.CaseCount, &m.OpenCases, &m.AwaitingCase, &seen,
			&muteCase, &muteExp, &muteAt, &banCase, &banExp, &banAt); err != nil {
			return nil, err
		}
		m.AvatarID, m.BannerID = idPtr(avatar), idPtr(banner)
		m.AccentColor, m.AccentColor2 = intPtr(accent), intPtr(accent2)
		m.StatusExpiresAt, m.LastSeenAt = int64Ptr(statusExp), int64Ptr(seen)
		m.Flags = m.AdminFlags & publicFlags
		if muteCase.Valid {
			m.Mute = &Restriction{UserID: m.ID, Kind: RestrictMute, CaseID: ids.ID(muteCase.Int64), ExpiresAt: int64Ptr(muteExp), CreatedAt: muteAt.Int64}
		}
		if banCase.Valid {
			m.Ban = &Restriction{UserID: m.ID, Kind: RestrictBan, CaseID: ids.ID(banCase.Int64), ExpiresAt: int64Ptr(banExp), CreatedAt: banAt.Int64}
		}
		out = append(out, &m)
	}
	return out, rows.Err()
}

// itoa64 formats a number without importing strconv for one call site each.
func itoa64(n int64) string {
	if n == 0 {
		return "0"
	}
	neg := n < 0
	if neg {
		n = -n
	}
	var b [20]byte
	i := len(b)
	for n > 0 {
		i--
		b[i] = byte('0' + n%10)
		n /= 10
	}
	if neg {
		i--
		b[i] = '-'
	}
	return string(b[i:])
}
