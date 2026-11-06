package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"

	"evilion.xyz/chat/internal/db"
	"evilion.xyz/chat/internal/ids"
)

// refFloor keeps a reference count from going below zero. SQLite spells the
// larger of two values max(); Postgres keeps max() for the aggregate.
func refFloor(d db.Dialect, expr string) string { return d.Greatest("0", expr) }

const blobCols = `id, sha256, size, mime, kind, encrypted, ref_count, owner_id, width, height, animated, created_at`

// blobReadCols are the columns a blob is read with: what it is written with,
// and whether the staff took its picture down (moderation_expressions.go).
const blobReadCols = blobCols + `, blocked`

func scanBlob(r rowScanner) (*Blob, error) {
	var b Blob
	var enc, anim, blocked int64
	var owner, w, h sql.NullInt64
	if err := r.Scan(&b.ID, &b.SHA256, &b.Size, &b.Mime, &b.Kind, &enc, &b.RefCount, &owner, &w, &h, &anim, &b.CreatedAt, &blocked); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	b.Encrypted, b.Animated, b.Blocked = enc == 1, anim == 1, blocked == 1
	b.OwnerID, b.Width, b.Height = idPtr(owner), intPtr(w), intPtr(h)
	return &b, nil
}

// InsertBlob stores metadata for a new blob (ID/CreatedAt filled when zero).
func (s *Store) InsertBlob(ctx context.Context, b *Blob) error {
	if b.ID.IsZero() {
		b.ID = ids.New()
	}
	if b.CreatedAt == 0 {
		b.CreatedAt = nowMs()
	}
	_, err := s.db.W.ExecContext(ctx, `INSERT INTO blobs (`+blobCols+`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)`,
		int64(b.ID), b.SHA256, b.Size, b.Mime, b.Kind, boolInt(b.Encrypted), b.RefCount, nullID(b.OwnerID), nullInt(b.Width), nullInt(b.Height), boolInt(b.Animated), b.CreatedAt)
	return err
}

// BlobBySHA finds a blob with the same content and kind.
func (s *Store) BlobBySHA(ctx context.Context, sha, kind string) (*Blob, error) {
	return scanBlob(s.db.R.QueryRowContext(ctx, `SELECT `+blobReadCols+` FROM blobs WHERE sha256 = ? AND kind = ?`, sha, kind))
}

// BlobByID loads a blob.
func (s *Store) BlobByID(ctx context.Context, id ids.ID) (*Blob, error) {
	return scanBlob(s.db.R.QueryRowContext(ctx, `SELECT `+blobReadCols+` FROM blobs WHERE id = ?`, int64(id)))
}

// AddBlobRef changes the reference count by delta (never below zero).
func (s *Store) AddBlobRef(ctx context.Context, id ids.ID, delta int) error {
	res, err := s.db.W.ExecContext(ctx, `UPDATE blobs SET ref_count = `+refFloor(s.db.Dialect, "ref_count + ?")+` WHERE id = ?`, delta, int64(id))
	if err != nil {
		return err
	}
	// a blob that is gone takes no reference; saying so lets the caller store it again
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// UnreferencedBlobs lists blobs with no references created before olderThan.
func (s *Store) UnreferencedBlobs(ctx context.Context, olderThan int64) ([]*Blob, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT `+blobReadCols+` FROM blobs WHERE ref_count <= 0 AND created_at < ? ORDER BY id LIMIT 500`, olderThan)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*Blob
	for rows.Next() {
		b, err := scanBlob(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, b)
	}
	return out, rows.Err()
}

// DeleteBlob removes blob metadata, but only while nothing references it: a
// blob listed as unreferenced can be picked up again (public images are shared
// by content) before the sweep gets to it. It reports whether a row went.
func (s *Store) DeleteBlob(ctx context.Context, id ids.ID) (bool, error) {
	gone := false
	err := s.Tx(ctx, func(tx *db.Tx) error {
		res, err := tx.ExecContext(ctx, `DELETE FROM blobs WHERE id = ? AND ref_count <= 0`, int64(id))
		if err != nil {
			return err
		}
		n, err := res.RowsAffected()
		if err != nil || n == 0 {
			return err
		}
		gone = true
		// an upload for a note that no save ever named (see AddNoteUpload);
		// spelled out rather than left to the foreign key, which a database
		// opened without them enforced would skip
		_, err = tx.ExecContext(ctx, `DELETE FROM note_uploads WHERE blob_id = ?`, int64(id))
		return err
	})
	return gone && err == nil, err
}

// imageColumns are the only places SwapImage writes, so its SQL is never
// built from anything else.
var imageColumns = map[string]bool{
	"users.avatar_id": true, "users.banner_id": true,
	"servers.icon_id": true, "servers.banner_id": true,
	"channels.icon_id":     true,
	"webhooks.avatar_id":   true,
	"applications.icon_id": true, "applications.cover_id": true,
}

// SwapImage points an image column of one row at newID (nil clears it) and
// releases the blob it pointed at before. The caller already holds a reference
// on newID. The old value is read in the same write, so two overlapping
// requests (a double tap on Remove) cannot both release it.
func (s *Store) SwapImage(ctx context.Context, table, col string, rowID ids.ID, newID *ids.ID) error {
	if !imageColumns[table+"."+col] {
		return fmt.Errorf("swap image: %s.%s is not an image column", table, col)
	}
	return s.Tx(ctx, func(tx *db.Tx) error {
		// Postgres runs these side by side; one at a time, so two cannot both
		// read the same "before" and act on it
		if err := tx.Serialise(ctx, "image:"+table+"."+col, int64(rowID)); err != nil {
			return err
		}
		var old sql.NullInt64
		if err := tx.QueryRowContext(ctx, `SELECT `+col+` FROM `+table+` WHERE id = ?`, int64(rowID)).Scan(&old); err != nil {
			if errors.Is(err, sql.ErrNoRows) {
				return ErrNotFound
			}
			return err
		}
		if _, err := tx.ExecContext(ctx, `UPDATE `+table+` SET `+col+` = ?, updated_at = ? WHERE id = ?`, nullID(newID), nowMs(), int64(rowID)); err != nil {
			return err
		}
		if !old.Valid {
			return nil
		}
		// even when old is newID: the new reference was taken for this row, which already had one
		_, err := tx.ExecContext(ctx, `UPDATE blobs SET ref_count = `+refFloor(s.db.Dialect, "ref_count - 1")+` WHERE id = ?`, old.Int64)
		return err
	})
}

// CountBlobsByContent counts rows that share the same bytes: exact sha matches
// (public kinds) plus encrypted rows whose sha carries a uniqueness suffix.
func (s *Store) CountBlobsByContent(ctx context.Context, sha string) (int, error) {
	var n int
	err := s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM blobs WHERE sha256 = ? OR sha256 LIKE ? ESCAPE '\'`, sha, escapeLike(sha)+"-%").Scan(&n)
	return n, err
}

// BlobsAnimated says, for each of the listed blobs that exists, whether it
// moves: what the bot API's picture hashes mark with "a_".
func (s *Store) BlobsAnimated(ctx context.Context, list []ids.ID) (map[ids.ID]bool, error) {
	out := map[ids.ID]bool{}
	err := chunked(list, func(part []ids.ID) error {
		rows, err := s.db.R.QueryContext(ctx, `SELECT id, animated FROM blobs WHERE id IN (`+placeholders(len(part))+`)`, idArgs(part)...)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			var id ids.ID
			var animated int64
			if err := rows.Scan(&id, &animated); err != nil {
				return err
			}
			out[id] = animated == 1
		}
		return rows.Err()
	})
	return out, err
}
