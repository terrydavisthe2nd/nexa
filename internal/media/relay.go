package media

import (
	"context"
	"errors"
	"fmt"
	"io"
	"time"

	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/objstore"
	"evilion.xyz/chat/internal/store"
)

// Uploads relayed to the bucket (api/upload_relay.go). This server opens a
// multipart upload under the name the file will have; each part an app
// sends here is streamed on to the bucket as it arrives, never written to
// this disk; and at the end every part is checked, joined and the whole
// checked again before the file exists for anyone. Apps hold no link to
// the bucket: every request to it is this server's own. Like everything in
// the bucket the bytes are ciphertext.

// ErrPartsMissing is a relayed upload finished before every part arrived;
// Missing lists the part numbers still to send.
type ErrPartsMissing struct{ Missing []int }

func (e *ErrPartsMissing) Error() string {
	return fmt.Sprintf("%d parts have not arrived", len(e.Missing))
}

// PartCount is how many parts a relayed upload of size bytes in parts of
// part bytes has.
func PartCount(size, part int64) int {
	if part <= 0 || size <= 0 {
		return 0
	}
	return int((size + part - 1) / part)
}

// PartLength is how many bytes part n (from 1) of such an upload holds.
func PartLength(size, part int64, n int) int64 {
	start := int64(n-1) * part
	return min(part, size-start)
}

// StartRelay opens the bucket's multipart upload for transfer id and
// returns its id.
func (m *Store) StartRelay(ctx context.Context, id ids.ID) (string, error) {
	if m.Remote == nil {
		return "", errors.New("media: no bucket")
	}
	return m.Remote.CreateMultipart(ctx, remoteKey(uploadName(id)), "application/octet-stream")
}

// RelayPart streams part n of transfer id, exactly size bytes from r, on to
// the bucket as they arrive. A body that ends early or arrives damaged
// stores nothing.
func (m *Store) RelayPart(ctx context.Context, id ids.ID, remoteID string, n int, r io.Reader, size int64) error {
	_, err := m.Remote.PutPartStream(ctx, remoteKey(uploadName(id)), remoteID, n, r, size)
	return err
}

// FinishRelay turns a relayed upload whose parts have all arrived into a
// blob. Every part must be there at exactly the size its number gives it,
// and the joined file exactly up.Size, or nothing is kept. Finishing
// again after a lost answer, or after the bucket joined the parts but the
// rows were not written, ends the same way. The caller holds the upload's
// lock (LockUpload).
func (m *Store) FinishRelay(ctx context.Context, up *store.Upload, owner ids.ID) (*store.Blob, error) {
	if m.Remote == nil || up.RemoteID == "" || up.PartSize <= 0 {
		return nil, store.ErrNotFound
	}
	name := uploadName(up.ID)
	key := remoteKey(name)
	parts, err := m.Remote.ListParts(ctx, key, up.RemoteID)
	switch {
	case errors.Is(err, objstore.ErrNotFound):
		// joined already (or thrown away): only a whole file of the right size counts
		got, herr := m.Remote.Head(ctx, key)
		if herr != nil || got != up.Size {
			return nil, store.ErrNotFound
		}
	case err != nil:
		return nil, err
	default:
		want := PartCount(up.Size, up.PartSize)
		have := map[int]objstore.Part{}
		for _, p := range parts {
			if p.Number < 1 || p.Number > want || p.Size != PartLength(up.Size, up.PartSize, p.Number) {
				// this server never sent such a part; nothing here is trusted
				m.AbortRelay(ctx, up.ID, up.RemoteID)
				return nil, ErrBadOffset
			}
			have[p.Number] = p
		}
		if len(have) < want {
			missing := []int{}
			for n := 1; n <= want; n++ {
				if _, ok := have[n]; !ok {
					missing = append(missing, n)
				}
			}
			return nil, &ErrPartsMissing{Missing: missing}
		}
		ordered := make([]objstore.Part, 0, want)
		for n := 1; n <= want; n++ {
			ordered = append(ordered, have[n])
		}
		if err := m.Remote.CompleteMultipart(ctx, key, up.RemoteID, ordered); err != nil {
			return nil, err
		}
		if got, err := m.Remote.Head(ctx, key); err != nil || got != up.Size {
			_ = m.Remote.Delete(ctx, key)
			if err != nil {
				return nil, err
			}
			return nil, ErrBadOffset
		}
	}
	unlock := m.lockContent(name)
	defer unlock()
	if err := m.st.AddRemoteBlob(ctx, name, up.Size); err != nil {
		return nil, err
	}
	ow := owner
	b := &store.Blob{SHA256: name, Size: up.Size, Mime: "application/octet-stream", Kind: "attachment", Encrypted: true, RefCount: 0, OwnerID: &ow}
	if err := m.st.FinishUploadWithBlob(ctx, up.ID, b); err != nil {
		return nil, err
	}
	return b, nil
}

// AbortRelay throws away a relayed upload that will not be finished: its
// parts, and the joined file if the bucket got that far but no blob was
// made of it. Aborting and deleting are free.
func (m *Store) AbortRelay(ctx context.Context, id ids.ID, remoteID string) {
	if m.Remote == nil {
		return
	}
	name := uploadName(id)
	key := remoteKey(name)
	actx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 30*time.Second)
	defer cancel()
	if remoteID != "" {
		_ = m.Remote.AbortMultipart(actx, key, remoteID)
	}
	unlock := m.lockContent(name)
	defer unlock()
	if n, err := m.st.CountBlobsByContent(actx, name); err != nil || n > 0 {
		return // a file was made of it: it goes the way any file does
	}
	if err := m.Remote.Delete(actx, key); err == nil {
		_ = m.st.DeleteRemoteBlob(actx, name)
	}
}
