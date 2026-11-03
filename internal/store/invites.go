package store

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/json"
	"errors"
	"strings"

	"evilion.xyz/chat/internal/db"
	"evilion.xyz/chat/internal/ids"
)

const inviteAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"

func newInviteCode() string { return randomCode(8) }

// randomCode is n characters drawn evenly from inviteAlphabet. A byte taken
// modulo the alphabet's 56 letters would favour the first 32 of them (256 is
// not a multiple of 56), so bytes past the last whole multiple are drawn again.
func randomCode(n int) string {
	const limit = 256 - 256%len(inviteAlphabet)
	out := make([]byte, 0, n)
	buf := make([]byte, n+n/2)
	for len(out) < n {
		_, _ = rand.Read(buf)
		for _, c := range buf {
			if int(c) < limit && len(out) < n {
				out = append(out, inviteAlphabet[int(c)%len(inviteAlphabet)])
			}
		}
	}
	return string(out)
}

const inviteCols = `code, server_id, channel_id, inviter_id, max_uses, uses, expires_at, created_at, vanity`

func scanInvite(r rowScanner) (*Invite, error) {
	var in Invite
	var srv, ch, exp sql.NullInt64
	var vanity int
	if err := r.Scan(&in.Code, &srv, &ch, &in.InviterID, &in.MaxUses, &in.Uses, &exp, &in.CreatedAt, &vanity); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	in.ServerID, in.ChannelID, in.ExpiresAt = idPtr(srv), idPtr(ch), int64Ptr(exp)
	in.Vanity = vanity == 1
	return &in, nil
}

// CreateInvite makes a new invite; maxAge is seconds (0 = never), maxUses 0 = unlimited.
func (s *Store) CreateInvite(ctx context.Context, serverID *ids.ID, channelID *ids.ID, inviter ids.ID, maxAge int64, maxUses int) (*Invite, error) {
	in := &Invite{ServerID: serverID, ChannelID: channelID, InviterID: inviter, MaxUses: maxUses, CreatedAt: nowMs()}
	if maxAge > 0 {
		exp := in.CreatedAt + maxAge*1000
		in.ExpiresAt = &exp
	}
	// A code that is already taken — one in 10^14, but a refusal would be a
	// 500 for nothing the person did — is simply drawn again.
	var err error
	for attempt := 0; attempt < 3; attempt++ {
		in.Code = newInviteCode()
		_, err = s.db.W.ExecContext(ctx, `INSERT INTO invites (`+inviteCols+`) VALUES (?,?,?,?,?,?,?,?,0)`, in.Code, nullID(serverID), nullID(channelID), int64(inviter), in.MaxUses, 0, nullInt64(in.ExpiresAt), in.CreatedAt)
		if !db.IsUniqueViolation(err) {
			break
		}
	}
	if err != nil {
		return nil, err
	}
	return in, nil
}

// InviteByCode loads a live invite; expired or exhausted ones are ErrNotFound.
// A vanity code is found whatever its case.
func (s *Store) InviteByCode(ctx context.Context, code string) (*Invite, error) {
	in, err := s.InviteByCodeAny(ctx, code)
	if err != nil {
		return nil, err
	}
	if (in.ExpiresAt != nil && *in.ExpiresAt < nowMs()) || (in.MaxUses > 0 && in.Uses >= in.MaxUses) {
		return nil, ErrNotFound
	}
	return in, nil
}

// InviteByCodeAny loads an invite whether or not it is still live. Random
// codes match exactly; vanity codes are kept lowercase and match in any case,
// after an exact match had its chance.
func (s *Store) InviteByCodeAny(ctx context.Context, code string) (*Invite, error) {
	in, err := scanInvite(s.db.R.QueryRowContext(ctx, `SELECT `+inviteCols+` FROM invites WHERE code = ?`, code))
	if errors.Is(err, ErrNotFound) {
		if lower := strings.ToLower(code); lower != code {
			return scanInvite(s.db.R.QueryRowContext(ctx, `SELECT `+inviteCols+` FROM invites WHERE code = ? AND vanity = 1`, lower))
		}
	}
	return in, err
}

// ErrVanityTaken is a vanity code another invite already has.
var ErrVanityTaken = errors.New("vanity code taken")

// VanityInvite is a server's vanity link, or ErrNotFound.
func (s *Store) VanityInvite(ctx context.Context, serverID ids.ID) (*Invite, error) {
	return scanInvite(s.db.R.QueryRowContext(ctx, `SELECT `+inviteCols+` FROM invites WHERE server_id = ? AND vanity = 1`, int64(serverID)))
}

// SetVanityInvite gives a server the vanity code (already checked and
// lowercase) leading to channelID, replacing the one it had, whose code it
// returns (or ""). Keeping the same code changes nothing and returns it as old. ErrVanityTaken when
// any invite has the code already.
func (s *Store) SetVanityInvite(ctx context.Context, serverID, channelID, by ids.ID, code string) (in *Invite, old string, err error) {
	err = s.Tx(ctx, func(tx *db.Tx) error {
		cur, err := scanInvite(tx.QueryRowContext(ctx, `SELECT `+inviteCols+` FROM invites WHERE server_id = ? AND vanity = 1`, int64(serverID)))
		switch {
		case err == nil && cur.Code == code:
			in, old = cur, cur.Code
			return nil
		case err == nil:
			old = cur.Code
			if _, err := tx.ExecContext(ctx, `DELETE FROM invites WHERE code = ?`, cur.Code); err != nil {
				return err
			}
		case !errors.Is(err, ErrNotFound):
			return err
		}
		var taken int
		if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM invites WHERE code = ?`, code).Scan(&taken); err != nil {
			return err
		}
		if taken > 0 {
			return ErrVanityTaken
		}
		in = &Invite{Code: code, ServerID: &serverID, ChannelID: &channelID, InviterID: by, CreatedAt: nowMs(), Vanity: true}
		_, err = tx.ExecContext(ctx, `INSERT INTO invites (`+inviteCols+`) VALUES (?,?,?,?,0,0,NULL,?,1)`, code, int64(serverID), int64(channelID), int64(by), in.CreatedAt)
		if db.IsUniqueViolation(err) {
			return ErrVanityTaken
		}
		return err
	})
	if err != nil {
		return nil, "", err
	}
	return in, old, nil
}

// ClearVanityInvite takes a server's vanity link away, saying which code it
// was ("" when it had none).
func (s *Store) ClearVanityInvite(ctx context.Context, serverID ids.ID) (string, error) {
	var code string
	err := s.Tx(ctx, func(tx *db.Tx) error {
		if err := tx.QueryRowContext(ctx, `SELECT code FROM invites WHERE server_id = ? AND vanity = 1`, int64(serverID)).Scan(&code); err != nil {
			if errors.Is(err, sql.ErrNoRows) {
				return nil
			}
			return err
		}
		_, err := tx.ExecContext(ctx, `DELETE FROM invites WHERE code = ?`, code)
		return err
	})
	return code, err
}

// SetSignupInvite records the invite an account signed up with, whose use
// its first join through that invite rides on.
func (s *Store) SetSignupInvite(ctx context.Context, uid ids.ID, code string) error {
	_, err := s.db.W.ExecContext(ctx, `UPDATE users SET signup_invite = ? WHERE id = ?`, code, int64(uid))
	return err
}

// SignupInvite is the invite an account signed up with and has not yet
// joined through, or "".
func (s *Store) SignupInvite(ctx context.Context, uid ids.ID) string {
	var code sql.NullString
	if err := s.db.R.QueryRowContext(ctx, `SELECT signup_invite FROM users WHERE id = ?`, int64(uid)).Scan(&code); err != nil {
		return ""
	}
	return code.String
}

// spendInvite takes one use of an invite for uid's join, unless uid signed up
// with it and this is its first join through it: that use was spent at sign-up.
func spendInvite(ctx context.Context, tx *db.Tx, uid ids.ID, code string) (signup bool, err error) {
	res, err := tx.ExecContext(ctx, `UPDATE users SET signup_invite = NULL WHERE id = ? AND signup_invite = ?`, int64(uid), code)
	if err != nil {
		return false, err
	}
	if n, _ := res.RowsAffected(); n == 1 {
		return true, nil
	}
	res, err = tx.ExecContext(ctx, `UPDATE invites SET uses = uses + 1 WHERE code = ? AND (max_uses = 0 OR uses < max_uses) AND (expires_at IS NULL OR expires_at > ?)`, code, nowMs())
	if err != nil {
		return false, err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return false, ErrNotFound
	}
	return false, nil
}

// UseInviteFor records uid's use of an invite for a join, the way spendInvite
// does. signup says the use was the one its sign-up spent, which
// GiveBackInviteUse needs to undo it.
func (s *Store) UseInviteFor(ctx context.Context, uid ids.ID, code string) (signup bool, err error) {
	err = s.Tx(ctx, func(tx *db.Tx) error {
		signup, err = spendInvite(ctx, tx, uid, code)
		return err
	})
	return signup, err
}

// GiveBackInviteUse undoes UseInviteFor for a join that did not happen.
func (s *Store) GiveBackInviteUse(ctx context.Context, uid ids.ID, code string, signup bool) error {
	if signup {
		return s.SetSignupInvite(ctx, uid, code)
	}
	return s.UnuseInvite(ctx, code)
}

// UseInvite records a use, refusing when the invite is exhausted.
func (s *Store) UseInvite(ctx context.Context, code string) error {
	res, err := s.db.W.ExecContext(ctx, `UPDATE invites SET uses = uses + 1 WHERE code = ? AND (max_uses = 0 OR uses < max_uses) AND (expires_at IS NULL OR expires_at > ?)`, code, nowMs())
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// UnuseInvite gives back a use recorded for a join that did not happen.
func (s *Store) UnuseInvite(ctx context.Context, code string) error {
	_, err := s.db.W.ExecContext(ctx, `UPDATE invites SET uses = uses - 1 WHERE code = ? AND uses > 0`, code)
	return err
}

// InvitesForServer lists live invites, leaving out the vanity link.
func (s *Store) InvitesForServer(ctx context.Context, serverID ids.ID) ([]*Invite, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT `+inviteCols+` FROM invites WHERE server_id = ? AND vanity = 0 AND (expires_at IS NULL OR expires_at > ?) AND (max_uses = 0 OR uses < max_uses) ORDER BY created_at DESC`, int64(serverID), nowMs())
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*Invite{}
	for rows.Next() {
		in, err := scanInvite(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, in)
	}
	return out, rows.Err()
}

// DeleteInvite revokes an invite.
func (s *Store) DeleteInvite(ctx context.Context, code string) error {
	res, err := s.db.W.ExecContext(ctx, `DELETE FROM invites WHERE code = ?`, code)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// Ban records a ban (and the caller removes the member).
func (s *Store) Ban(ctx context.Context, b *Ban) error {
	if b.CreatedAt == 0 {
		b.CreatedAt = nowMs()
	}
	_, err := s.db.W.ExecContext(ctx, `INSERT INTO bans (server_id, user_id, reason, banned_by, created_at) VALUES (?,?,?,?,?) ON CONFLICT(server_id, user_id) DO UPDATE SET reason = excluded.reason, banned_by = excluded.banned_by, created_at = excluded.created_at`,
		int64(b.ServerID), int64(b.UserID), b.Reason, int64(b.BannedBy), b.CreatedAt)
	return err
}

// Unban lifts a ban.
func (s *Store) Unban(ctx context.Context, serverID, uid ids.ID) error {
	res, err := s.db.W.ExecContext(ctx, `DELETE FROM bans WHERE server_id = ? AND user_id = ?`, int64(serverID), int64(uid))
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// IsBanned reports whether uid is banned from a server.
func (s *Store) IsBanned(ctx context.Context, serverID, uid ids.ID) (bool, error) {
	var n int
	err := s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM bans WHERE server_id = ? AND user_id = ?`, int64(serverID), int64(uid)).Scan(&n)
	return n > 0, err
}

// Bans lists a server's bans.
func (s *Store) Bans(ctx context.Context, serverID ids.ID) ([]*Ban, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT server_id, user_id, reason, banned_by, created_at FROM bans WHERE server_id = ? ORDER BY created_at DESC`, int64(serverID))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*Ban{}
	for rows.Next() {
		var b Ban
		if err := rows.Scan(&b.ServerID, &b.UserID, &b.Reason, &b.BannedBy, &b.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, &b)
	}
	return out, rows.Err()
}

// Audit appends an audit log entry.
func (s *Store) Audit(ctx context.Context, e *AuditEntry) error {
	if e.ID.IsZero() {
		e.ID = ids.New()
	}
	if e.CreatedAt == 0 {
		e.CreatedAt = nowMs()
	}
	if len(e.Changes) == 0 {
		e.Changes = json.RawMessage("{}")
	}
	_, err := s.db.W.ExecContext(ctx, `INSERT INTO audit_log (id, server_id, actor_id, action, target_type, target_id, changes, reason, created_at) VALUES (?,?,?,?,?,?,?,?,?)`,
		int64(e.ID), int64(e.ServerID), int64(e.ActorID), e.Action, e.TargetType, nullID(e.TargetID), string(e.Changes), e.Reason, e.CreatedAt)
	return err
}

// AuditEntries pages a server's audit log, newest first.
func (s *Store) AuditEntries(ctx context.Context, serverID, before ids.ID, limit int) ([]*AuditEntry, error) {
	return s.AuditEntriesFor(ctx, serverID, AuditFilter{}, before, limit)
}

// AuditFilter narrows the audit log: to some actions, to what one person
// did, or to what was done to one person.
type AuditFilter struct {
	Actions []string
	Actor   *ids.ID
	Target  *ids.ID
}

// AuditEntriesFor pages a server's audit log through a filter, newest first.
func (s *Store) AuditEntriesFor(ctx context.Context, serverID ids.ID, f AuditFilter, before ids.ID, limit int) ([]*AuditEntry, error) {
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	if before.IsZero() {
		before = ids.ID(1<<62 - 1)
	}
	q := `SELECT id, server_id, actor_id, action, target_type, target_id, changes, reason, created_at FROM audit_log WHERE server_id = ? AND id < ?`
	args := []any{int64(serverID), int64(before)}
	if len(f.Actions) > 0 {
		q += ` AND action IN (` + placeholders(len(f.Actions)) + `)`
		for _, a := range f.Actions {
			args = append(args, a)
		}
	}
	if f.Actor != nil {
		q += ` AND actor_id = ?`
		args = append(args, int64(*f.Actor))
	}
	if f.Target != nil {
		q += ` AND target_type = 'user' AND target_id = ?`
		args = append(args, int64(*f.Target))
	}
	q += ` ORDER BY id DESC LIMIT ?`
	args = append(args, limit)
	rows, err := s.db.R.QueryContext(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*AuditEntry{}
	for rows.Next() {
		var e AuditEntry
		var target sql.NullInt64
		var changes string
		if err := rows.Scan(&e.ID, &e.ServerID, &e.ActorID, &e.Action, &e.TargetType, &target, &changes, &e.Reason, &e.CreatedAt); err != nil {
			return nil, err
		}
		e.TargetID = idPtr(target)
		e.Changes = json.RawMessage(changes)
		out = append(out, &e)
	}
	return out, rows.Err()
}

// InvitesForChannel lists the live invites of one group DM.
func (s *Store) InvitesForChannel(ctx context.Context, channelID ids.ID) ([]*Invite, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT `+inviteCols+` FROM invites WHERE channel_id = ? AND server_id IS NULL AND (expires_at IS NULL OR expires_at > ?) AND (max_uses = 0 OR uses < max_uses) ORDER BY created_at DESC`, int64(channelID), nowMs())
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*Invite{}
	for rows.Next() {
		in, err := scanInvite(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, in)
	}
	return out, rows.Err()
}
