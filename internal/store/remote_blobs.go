package store

import (
	"context"
	"database/sql"
	"errors"
)

// Files kept in the bucket (internal/media/remote.go). A name is a blob's
// content name, media.shaOf: the first 64 characters of blobs.sha256,
// which substr(sha256, 1, 64) gives in both databases.

// AddRemoteBlob records that the bucket holds name, size bytes. A copy
// marked gone is held again.
func (s *Store) AddRemoteBlob(ctx context.Context, name string, size int64) error {
	_, err := s.db.W.ExecContext(ctx, `INSERT INTO remote_blobs (name, size, stored_at) VALUES (?, ?, ?)
		ON CONFLICT(name) DO UPDATE SET size = excluded.size, stored_at = excluded.stored_at, gone_at = NULL`, name, size, nowMs())
	return err
}

// RemoteBlobSize returns the size of the bucket's copy of name, and whether
// there is one.
func (s *Store) RemoteBlobSize(ctx context.Context, name string) (int64, bool, error) {
	var size int64
	err := s.db.R.QueryRowContext(ctx, `SELECT size FROM remote_blobs WHERE name = ?`, name).Scan(&size)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, false, nil
	}
	return size, err == nil, err
}

// MarkRemoteGone says nothing holds the bucket's copy of name any more, for
// the mover to delete.
func (s *Store) MarkRemoteGone(ctx context.Context, name string) error {
	_, err := s.db.W.ExecContext(ctx, `UPDATE remote_blobs SET gone_at = ? WHERE name = ? AND gone_at IS NULL`, nowMs(), name)
	return err
}

// KeepRemoteBlob takes back a gone mark: something holds name again.
func (s *Store) KeepRemoteBlob(ctx context.Context, name string) error {
	_, err := s.db.W.ExecContext(ctx, `UPDATE remote_blobs SET gone_at = NULL WHERE name = ?`, name)
	return err
}

// GoneRemoteBlobs lists copies marked gone, oldest mark first.
func (s *Store) GoneRemoteBlobs(ctx context.Context, limit int) ([]string, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT name FROM remote_blobs WHERE gone_at IS NOT NULL ORDER BY gone_at LIMIT ?`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var n string
		if err := rows.Scan(&n); err != nil {
			return nil, err
		}
		out = append(out, n)
	}
	return out, rows.Err()
}

// DeleteRemoteBlob forgets the bucket's copy of name, once it is deleted.
func (s *Store) DeleteRemoteBlob(ctx context.Context, name string) error {
	_, err := s.db.W.ExecContext(ctx, `DELETE FROM remote_blobs WHERE name = ?`, name)
	return err
}

// RemoteBlobBytes is everything the bucket holds and bills for, copies
// marked gone included until they are deleted.
func (s *Store) RemoteBlobBytes(ctx context.Context) (int64, error) {
	var n int64
	err := s.db.R.QueryRowContext(ctx, `SELECT COALESCE(SUM(size), 0) FROM remote_blobs`).Scan(&n)
	return n, err
}

// LocalAttachmentBytes is what end-to-end encrypted attachments still on
// this disk weigh: what the bucket will hold once they have moved. Rows
// sharing the same bytes count once each, which errs high.
func (s *Store) LocalAttachmentBytes(ctx context.Context) (int64, error) {
	var n int64
	err := s.db.R.QueryRowContext(ctx, `SELECT COALESCE(SUM(b.size), 0) FROM blobs b
		WHERE b.kind = 'attachment' AND b.encrypted = 1
		  AND NOT EXISTS (SELECT 1 FROM remote_blobs r WHERE r.name = substr(b.sha256, 1, 64))`).Scan(&n)
	return n, err
}

// BlobToMove is content waiting to go to the bucket.
type BlobToMove struct {
	Name string
	Size int64
}

// BlobsToMove lists end-to-end encrypted attachments that something holds,
// made before `before` (unix ms), whose bytes the bucket does not have yet,
// oldest first.
func (s *Store) BlobsToMove(ctx context.Context, before int64, limit int) ([]BlobToMove, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT substr(b.sha256, 1, 64), MAX(b.size), MIN(b.created_at) AS first_at FROM blobs b
		WHERE b.kind = 'attachment' AND b.encrypted = 1 AND b.ref_count > 0 AND b.created_at < ?
		  AND NOT EXISTS (SELECT 1 FROM remote_blobs r WHERE r.name = substr(b.sha256, 1, 64))
		GROUP BY substr(b.sha256, 1, 64)
		ORDER BY first_at
		LIMIT ?`, before, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []BlobToMove
	for rows.Next() {
		var b BlobToMove
		var first int64
		if err := rows.Scan(&b.Name, &b.Size, &first); err != nil {
			return nil, err
		}
		out = append(out, b)
	}
	return out, rows.Err()
}

// AddStorageOps adds requests made to the bucket to a month's count.
func (s *Store) AddStorageOps(ctx context.Context, month string, a, b int64) error {
	_, err := s.db.W.ExecContext(ctx, `INSERT INTO storage_usage (month, class_a, class_b) VALUES (?, ?, ?)
		ON CONFLICT(month) DO UPDATE SET class_a = storage_usage.class_a + excluded.class_a, class_b = storage_usage.class_b + excluded.class_b`, month, a, b)
	return err
}

// StorageOps returns a month's counted requests to the bucket.
func (s *Store) StorageOps(ctx context.Context, month string) (a, b int64, err error) {
	err = s.db.R.QueryRowContext(ctx, `SELECT class_a, class_b FROM storage_usage WHERE month = ?`, month).Scan(&a, &b)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, 0, nil
	}
	return a, b, err
}

// OpenUploadBytes is the declared size of every resumable upload still on
// its way: files the bucket will hold once they arrive and are sent.
func (s *Store) OpenUploadBytes(ctx context.Context) (int64, error) {
	var n int64
	err := s.db.R.QueryRowContext(ctx, `SELECT COALESCE(SUM(size), 0) FROM uploads WHERE blob_id IS NULL`).Scan(&n)
	return n, err
}
