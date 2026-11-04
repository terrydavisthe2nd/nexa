package store

import (
	"context"
	"database/sql"
	"errors"

	"evilion.xyz/chat/internal/db"
	"evilion.xyz/chat/internal/ids"
)

// Upload is a transfer in progress: a file arriving in pieces.
type Upload struct {
	ID        ids.ID `json:"id"`
	UserID    ids.ID `json:"user_id"`
	Size      int64  `json:"size"`
	Received  int64  `json:"received"`
	CreatedAt int64  `json:"created_at"`
	UpdatedAt int64  `json:"updated_at"`
	// BlobID is what the transfer became once it was finished.
	BlobID *ids.ID `json:"-"`
	// RemoteID is the bucket's id for an upload going straight there
	// (api/upload_direct.go), and PartSize the size of its parts; empty and
	// zero for one coming through this server.
	RemoteID string `json:"-"`
	PartSize int64  `json:"-"`
}

// CreateUpload opens a transfer.
func (s *Store) CreateUpload(ctx context.Context, userID ids.ID, size int64) (*Upload, error) {
	u := &Upload{ID: ids.New(), UserID: userID, Size: size, CreatedAt: nowMs(), UpdatedAt: nowMs()}
	_, err := s.db.W.ExecContext(ctx, `INSERT INTO uploads (id, user_id, size, received, created_at, updated_at) VALUES (?,?,?,0,?,?)`,
		int64(u.ID), int64(userID), size, u.CreatedAt, u.UpdatedAt)
	if err != nil {
		return nil, err
	}
	return u, nil
}

// UploadByID loads one transfer.
func (s *Store) UploadByID(ctx context.Context, id ids.ID) (*Upload, error) {
	var u Upload
	var blob, part sql.NullInt64
	var remote sql.NullString
	err := s.db.R.QueryRowContext(ctx, `SELECT id, user_id, size, received, created_at, updated_at, blob_id, remote_id, part_size FROM uploads WHERE id = ?`, int64(id)).
		Scan(&u.ID, &u.UserID, &u.Size, &u.Received, &u.CreatedAt, &u.UpdatedAt, &blob, &remote, &part)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		// a half-scanned row is not an upload; returning one alongside the
		// error invites a caller to read it
		return nil, err
	}
	u.BlobID = idPtr(blob)
	u.RemoteID, u.PartSize = remote.String, part.Int64
	return &u, nil
}

// SetUploadRemote records the bucket's multipart upload an upload goes to.
func (s *Store) SetUploadRemote(ctx context.Context, id ids.ID, remoteID string, partSize int64) error {
	_, err := s.db.W.ExecContext(ctx, `UPDATE uploads SET remote_id = ?, part_size = ?, updated_at = ? WHERE id = ?`, remoteID, partSize, nowMs(), int64(id))
	return err
}

// TouchUpload marks an upload as still going, so the stale sweep leaves it.
func (s *Store) TouchUpload(ctx context.Context, id ids.ID) error {
	_, err := s.db.W.ExecContext(ctx, `UPDATE uploads SET updated_at = ? WHERE id = ?`, nowMs(), int64(id))
	return err
}

// AdvanceUpload records more bytes, but only from the offset we expect: two
// requests carrying the same piece must not both count.
func (s *Store) AdvanceUpload(ctx context.Context, id ids.ID, from, to int64) (bool, error) {
	res, err := s.db.W.ExecContext(ctx, `UPDATE uploads SET received = ?, updated_at = ? WHERE id = ? AND received = ?`, to, nowMs(), int64(id), from)
	if err != nil {
		return false, err
	}
	n, err := res.RowsAffected()
	return n > 0, err
}

// FinishUploadWithBlob records the blob an upload became and points the upload
// row at it, as one change. The upload row stays until the stale sweep, so a
// repeated finish can answer with the same blob.
//
// Two statements meant the bytes could be committed and the blob row written
// while the upload row still said unfinished. The retry then ran the whole
// finish again, found the spool file renamed away, and answered 404 — every
// time, forever, for a transfer that had in fact arrived. The person had to
// send the file again from the start.
func (s *Store) FinishUploadWithBlob(ctx context.Context, id ids.ID, b *Blob) error {
	if b.ID.IsZero() {
		b.ID = ids.New()
	}
	if b.CreatedAt == 0 {
		b.CreatedAt = nowMs()
	}
	return s.Tx(ctx, func(tx *db.Tx) error {
		if _, err := tx.ExecContext(ctx, `INSERT INTO blobs (`+blobCols+`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)`,
			int64(b.ID), b.SHA256, b.Size, b.Mime, b.Kind, boolInt(b.Encrypted), b.RefCount, nullID(b.OwnerID), nullInt(b.Width), nullInt(b.Height), boolInt(b.Animated), b.CreatedAt); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, `UPDATE uploads SET blob_id = ?, updated_at = ? WHERE id = ?`, int64(b.ID), nowMs(), int64(id))
		return err
	})
}

// DeleteUpload forgets a transfer.
func (s *Store) DeleteUpload(ctx context.Context, id ids.ID) error {
	_, err := s.db.W.ExecContext(ctx, `DELETE FROM uploads WHERE id = ?`, int64(id))
	return err
}

// StaleUpload is a transfer nothing has touched for a while, and the
// bucket's multipart upload behind it when it was going straight there and
// never finished.
type StaleUpload struct {
	ID       ids.ID
	RemoteID string
}

// StaleUploads lists transfers nothing has touched since before cutoff.
func (s *Store) StaleUploads(ctx context.Context, cutoff int64) ([]StaleUpload, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT id, CASE WHEN blob_id IS NULL THEN remote_id END FROM uploads WHERE updated_at < ?`, cutoff)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []StaleUpload{}
	for rows.Next() {
		var u StaleUpload
		var remote sql.NullString
		if err := rows.Scan(&u.ID, &remote); err != nil {
			return nil, err
		}
		u.RemoteID = remote.String
		out = append(out, u)
	}
	return out, rows.Err()
}

// UploadRoom is what uploads already have a claim on: the bytes one account
// has on the server but not in a message yet — files uploaded and not sent,
// and what its unfinished transfers said they would bring — how many of those
// transfers it has open, and what every unfinished transfer, anyone's, still
// has to bring.
type UploadRoom struct {
	UserPending int64
	// UserDeclared is what the account's unfinished transfers will bring,
	// which UserPending counts too, beside its files not sent yet.
	UserDeclared int64
	UserOpen     int
	// AllRemaining is what transfers coming through this server still
	// bring to its disk; those going straight to the bucket bring nothing.
	AllRemaining int64
}

// UploadRoomFor reads the claims uploads have on the disk, for uid.
func (s *Store) UploadRoomFor(ctx context.Context, uid ids.ID) (UploadRoom, error) {
	var r UploadRoom
	var loose, declared int64
	if err := s.db.R.QueryRowContext(ctx, `SELECT coalesce(sum(size), 0) FROM blobs WHERE owner_id = ? AND kind = 'attachment' AND ref_count = 0`, int64(uid)).Scan(&loose); err != nil {
		return r, err
	}
	if err := s.db.R.QueryRowContext(ctx, `SELECT coalesce(sum(size), 0), count(*) FROM uploads WHERE user_id = ? AND blob_id IS NULL`, int64(uid)).Scan(&declared, &r.UserOpen); err != nil {
		return r, err
	}
	if err := s.db.R.QueryRowContext(ctx, `SELECT coalesce(sum(size - received), 0) FROM uploads WHERE blob_id IS NULL AND remote_id IS NULL`).Scan(&r.AllRemaining); err != nil {
		return r, err
	}
	r.UserPending, r.UserDeclared = loose+declared, declared
	return r, nil
}
