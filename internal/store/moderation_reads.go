package store

import (
	"context"
	"database/sql"
	"errors"

	"evilion.xyz/chat/internal/db"
	"evilion.xyz/chat/internal/ids"
)

// What each member of staff has read of the case threads and bug reports.
// The panel's badges count the threads waiting on the staff that hold
// something this person has not read: a case whose member wrote last and
// wrote something new to them, and a bug report that is new to them or whose
// reporter wrote again. Opening a thread reads it; Mark as Read reads them
// all. Whoever answers, the thread stops waiting on the staff and leaves
// everyone's count.

// The kinds of thread a read position is kept for, and the row that reads
// everything up to a point at once.
const (
	ModReadCase = "case"
	ModReadBug  = "bug"
	modReadAll  = "all"
)

// ModUnread is the threads waiting on the staff that a member of staff has
// not read, newest activity first.
type ModUnread struct {
	Cases []ids.ID `json:"cases"`
	Bugs  []ids.ID `json:"bugs"`
}

// readAllOf is how far a member of staff has read everything at once (0: never).
func readAllOf(ctx context.Context, q interface {
	QueryRowContext(context.Context, string, ...any) *db.Row
}, uid ids.ID) (int64, error) {
	var upTo int64
	err := q.QueryRowContext(ctx, `SELECT read_id FROM mod_reads WHERE user_id = ? AND kind = ? AND item_id = 0`, int64(uid), modReadAll).Scan(&upTo)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, nil
	}
	return upTo, err
}

// ModUnreadFor lists the case threads and bug reports waiting on the staff
// that uid has not read.
func (s *Store) ModUnreadFor(ctx context.Context, uid ids.ID) (ModUnread, error) {
	out := ModUnread{Cases: []ids.ID{}, Bugs: []ids.ID{}}
	all, err := readAllOf(ctx, s.db.R, uid)
	if err != nil {
		return out, err
	}
	cases, err := s.idList(ctx, `SELECT c.id FROM mod_cases c
		LEFT JOIN mod_reads r ON r.user_id = ? AND r.kind = 'case' AND r.item_id = c.id
		WHERE c.status = 'open' AND c.awaiting = 'staff'
		AND EXISTS (SELECT 1 FROM mod_messages m WHERE m.case_id = c.id AND m.kind = 'member' AND m.id > ? AND m.id > coalesce(r.read_id, 0))
		ORDER BY c.last_activity_at DESC, c.id DESC LIMIT 500`, int64(uid), all)
	if err != nil {
		return out, err
	}
	bugs, err := s.idList(ctx, `SELECT b.id FROM bug_reports b
		LEFT JOIN mod_reads r ON r.user_id = ? AND r.kind = 'bug' AND r.item_id = b.id
		WHERE b.awaiting = 'staff'
		AND ((b.id > ? AND b.id > coalesce(r.read_id, 0))
			OR EXISTS (SELECT 1 FROM bug_messages m WHERE m.report_id = b.id AND m.kind = 'member' AND m.id > ? AND m.id > coalesce(r.read_id, 0)))
		ORDER BY b.last_activity_at DESC, b.id DESC LIMIT 500`, int64(uid), all, all)
	if err != nil {
		return out, err
	}
	if cases != nil {
		out.Cases = cases
	}
	if bugs != nil {
		out.Bugs = bugs
	}
	return out, nil
}

// MarkModRead moves uid's read position in one case thread or bug report
// forward to upTo, or to everything in it when upTo is zero.
func (s *Store) MarkModRead(ctx context.Context, uid ids.ID, kind string, itemID, upTo ids.ID) error {
	if kind != ModReadCase && kind != ModReadBug {
		return errors.New("mod read: unknown kind " + kind)
	}
	return s.Tx(ctx, func(tx *db.Tx) error {
		if upTo.IsZero() {
			var newest sql.NullInt64
			q := `SELECT max(id) FROM mod_messages WHERE case_id = ?`
			if kind == ModReadBug {
				// a report with nothing said in it yet is read up to itself
				q = `SELECT coalesce(max(m.id), r.id) FROM bug_reports r LEFT JOIN bug_messages m ON m.report_id = r.id WHERE r.id = ? GROUP BY r.id`
			}
			if err := tx.QueryRowContext(ctx, q, int64(itemID)).Scan(&newest); err != nil && !errors.Is(err, sql.ErrNoRows) {
				return err
			}
			upTo = ids.ID(newest.Int64)
		}
		if upTo.IsZero() {
			return nil
		}
		_, err := tx.ExecContext(ctx, `INSERT INTO mod_reads (user_id, kind, item_id, read_id) VALUES (?,?,?,?)
			ON CONFLICT (user_id, kind, item_id) DO UPDATE SET read_id = `+tx.Dialect().Greatest("mod_reads.read_id", "excluded.read_id"),
			int64(uid), kind, int64(itemID), int64(upTo))
		return err
	})
}

// MarkModReadAll reads every case thread and bug report for uid up to upTo
// (an id: everything written before it), and lets go of the positions it
// passes.
func (s *Store) MarkModReadAll(ctx context.Context, uid, upTo ids.ID) error {
	return s.Tx(ctx, func(tx *db.Tx) error {
		if _, err := tx.ExecContext(ctx, `INSERT INTO mod_reads (user_id, kind, item_id, read_id) VALUES (?,?,0,?)
			ON CONFLICT (user_id, kind, item_id) DO UPDATE SET read_id = `+tx.Dialect().Greatest("mod_reads.read_id", "excluded.read_id"),
			int64(uid), modReadAll, int64(upTo)); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, `DELETE FROM mod_reads WHERE user_id = ? AND kind != ? AND read_id <= ?`, int64(uid), modReadAll, int64(upTo))
		return err
	})
}
