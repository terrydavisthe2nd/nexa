package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"

	"evilion.xyz/chat/internal/db"
	"evilion.xyz/chat/internal/ids"
)

// UserWidgets is the JSON of someone's profile widgets, or "" for none.
func (s *Store) UserWidgets(ctx context.Context, uid ids.ID) (string, error) {
	var raw string
	err := s.db.R.QueryRowContext(ctx, `SELECT widgets FROM users WHERE id = ?`, int64(uid)).Scan(&raw)
	if errors.Is(err, sql.ErrNoRows) {
		return "", ErrNotFound
	}
	return raw, err
}

// SetUserWidgets replaces someone's widgets, taking a reference on each
// picture the new ones name that the old ones did not, and giving back the
// ones the old ones named that the new ones do not, in one transaction. A
// picture that is gone, or is not a widget's, fails it.
func (s *Store) SetUserWidgets(ctx context.Context, uid ids.ID, raw string) error {
	return s.Tx(ctx, func(tx *db.Tx) error {
		var old string
		if err := tx.QueryRowContext(ctx, `SELECT widgets FROM users WHERE id = ?`, int64(uid)).Scan(&old); err != nil {
			if errors.Is(err, sql.ErrNoRows) {
				return ErrNotFound
			}
			return err
		}
		if err := moveWidgetRefs(ctx, tx, old, raw); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, `UPDATE users SET widgets = ?, updated_at = ? WHERE id = ?`, raw, nowMs(), int64(uid))
		return err
	})
}

// MemberWidgets is the JSON of someone's widgets in one server: nil when
// they use their own everywhere, "" when they show none there.
func (s *Store) MemberWidgets(ctx context.Context, serverID, uid ids.ID) (*string, error) {
	var raw sql.NullString
	err := s.db.R.QueryRowContext(ctx, `SELECT widgets FROM server_members WHERE server_id = ? AND user_id = ?`, int64(serverID), int64(uid)).Scan(&raw)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil || !raw.Valid {
		return nil, err
	}
	return &raw.String, nil
}

// SetMemberWidgets replaces someone's widgets in one server (nil goes back
// to their own), moving the references on their pictures as
// SetUserWidgets does.
func (s *Store) SetMemberWidgets(ctx context.Context, serverID, uid ids.ID, raw *string) error {
	return s.Tx(ctx, func(tx *db.Tx) error {
		var old sql.NullString
		if err := tx.QueryRowContext(ctx, `SELECT widgets FROM server_members WHERE server_id = ? AND user_id = ?`, int64(serverID), int64(uid)).Scan(&old); err != nil {
			if errors.Is(err, sql.ErrNoRows) {
				return ErrNotFound
			}
			return err
		}
		next := ""
		if raw != nil {
			next = *raw
		}
		if err := moveWidgetRefs(ctx, tx, old.String, next); err != nil {
			return err
		}
		var v any
		if raw != nil {
			v = *raw
		}
		_, err := tx.ExecContext(ctx, `UPDATE server_members SET widgets = ? WHERE server_id = ? AND user_id = ?`, v, int64(serverID), int64(uid))
		return err
	})
}

// moveWidgetRefs takes a reference on each picture after names that before
// did not, and gives one back for each before named that after does not. A
// picture that is gone, or is not a widget's, fails it.
func moveWidgetRefs(ctx context.Context, tx *db.Tx, before, after string) error {
	was, now := WidgetImages(before), WidgetImages(after)
	for id := range now {
		if was[id] {
			continue
		}
		res, err := tx.ExecContext(ctx, `UPDATE blobs SET ref_count = ref_count + 1 WHERE id = ? AND kind = 'widget' AND encrypted = 0`, int64(id))
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return ErrNotFound
		}
	}
	for id := range was {
		if now[id] {
			continue
		}
		if _, err := tx.ExecContext(ctx, `UPDATE blobs SET ref_count = `+refFloor(tx.Dialect(), "ref_count - 1")+` WHERE id = ?`, int64(id)); err != nil {
			return err
		}
	}
	return nil
}

// releaseWidgetRefs gives back the references the widgets the query returns
// hold on their pictures: one per picture per row, as moveWidgetRefs took.
func releaseWidgetRefs(ctx context.Context, tx *db.Tx, query string, args ...any) error {
	rows, err := tx.QueryContext(ctx, query, args...)
	if err != nil {
		return err
	}
	counts := map[ids.ID]int{}
	for rows.Next() {
		var raw sql.NullString
		if err := rows.Scan(&raw); err != nil {
			rows.Close()
			return err
		}
		for id := range WidgetImages(raw.String) {
			counts[id]++
		}
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	for id, n := range counts {
		if _, err := tx.ExecContext(ctx, `UPDATE blobs SET ref_count = `+refFloor(tx.Dialect(), "ref_count - ?")+` WHERE id = ?`, n, int64(id)); err != nil {
			return err
		}
	}
	return nil
}

// WidgetImages is every picture widgets' JSON names: an "image" on a widget,
// on one of its games or on one of its artists, albums or songs, as the
// server writes them.
func WidgetImages(raw string) map[ids.ID]bool {
	out := map[ids.ID]bool{}
	if raw == "" {
		return out
	}
	var list []struct {
		Image string `json:"image"`
		Games []struct {
			Image string `json:"image"`
		} `json:"games"`
		Music []struct {
			Image string `json:"image"`
		} `json:"music"`
		Books []struct {
			Image string `json:"image"`
		} `json:"books"`
		Screen []struct {
			Image string `json:"image"`
		} `json:"screen"`
	}
	if json.Unmarshal([]byte(raw), &list) != nil {
		return out
	}
	add := func(v string) {
		if id, err := ids.Parse(v); err == nil && v != "" {
			out[id] = true
		}
	}
	for _, w := range list {
		add(w.Image)
		for _, g := range w.Games {
			add(g.Image)
		}
		for _, m := range w.Music {
			add(m.Image)
		}
		for _, b := range w.Books {
			add(b.Image)
		}
		for _, t := range w.Screen {
			add(t.Image)
		}
	}
	return out
}
