package store

import (
	"context"
	"database/sql"
	"errors"
	"slices"
	"strings"

	"evilion.xyz/chat/internal/db"
	"evilion.xyz/chat/internal/ids"
)

// CreateEpoch inserts the next epoch for a conversation. The caller passes the
// epoch it expects to create; anything but latest+1 (or 0 for a new
// conversation) returns ErrConflict.
func (s *Store) CreateEpoch(ctx context.Context, e *KeyEpoch) error {
	return s.createEpoch(ctx, e, false)
}

// CreatePendingEpoch is CreateEpoch for a rotation someone losing access asked
// for: it only succeeds while that rotation is still pending, so when every
// online device answers the same request only the first creates an epoch and
// the rest get ErrConflict.
func (s *Store) CreatePendingEpoch(ctx context.Context, e *KeyEpoch) error {
	return s.createEpoch(ctx, e, true)
}

func (s *Store) createEpoch(ctx context.Context, e *KeyEpoch, pending bool) error {
	if e.CreatedAt == 0 {
		e.CreatedAt = nowMs()
	}
	return s.Tx(ctx, func(tx *db.Tx) error {
		// Postgres runs these side by side; one at a time, so two cannot both
		// read the same "before" and act on it
		if err := tx.Serialise(ctx, "epoch", int64(e.ConvID)); err != nil {
			return err
		}
		if pending {
			var n int
			if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM key_flags WHERE conv_id = ? AND needs_rotation = 1`, int64(e.ConvID)).Scan(&n); err != nil {
				return err
			}
			if n == 0 {
				return ErrConflict
			}
		}
		var max sql.NullInt64
		if err := tx.QueryRowContext(ctx, `SELECT max(epoch) FROM key_epochs WHERE conv_id = ?`, int64(e.ConvID)).Scan(&max); err != nil {
			return err
		}
		want := 0
		if max.Valid {
			want = int(max.Int64) + 1
		}
		if e.Epoch != want {
			return ErrConflict
		}
		_, err := tx.ExecContext(ctx, `INSERT INTO key_epochs (conv_id, epoch, created_by, created_at, reason, commitment) VALUES (?,?,?,?,?,?)`, int64(e.ConvID), e.Epoch, int64(e.CreatedBy), e.CreatedAt, e.Reason, e.Commitment)
		if err != nil {
			return err
		}
		// what people could not open is moot once there is a newer key
		if _, err := tx.ExecContext(ctx, `DELETE FROM key_rejections WHERE conv_id = ?`, int64(e.ConvID)); err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, `INSERT INTO key_flags (conv_id, needs_rotation, needs_own_key, updated_at) VALUES (?,0,0,?) ON CONFLICT(conv_id) DO UPDATE SET needs_rotation = 0, needs_own_key = 0, updated_at = excluded.updated_at`, int64(e.ConvID), e.CreatedAt)
		return err
	})
}

// MarkEpochLost records that an epoch's key never reached the people who need
// it, and asks for it to be replaced. It does nothing, and answers false, once
// that epoch is no longer the newest: the check runs outside this lock, and
// flagging a key that has just replaced it would cost everyone another rotation.
func (s *Store) MarkEpochLost(ctx context.Context, conv ids.ID, epoch int) (bool, error) {
	lost := false
	err := s.Tx(ctx, func(tx *db.Tx) error {
		if err := tx.Serialise(ctx, "epoch", int64(conv)); err != nil {
			return err
		}
		var err error
		lost, err = markEpochLost(ctx, tx, conv, epoch)
		return err
	})
	return lost, err
}

// markEpochLost is MarkEpochLost inside a transaction holding the epoch lock.
func markEpochLost(ctx context.Context, tx *db.Tx, conv ids.ID, epoch int) (bool, error) {
	var max sql.NullInt64
	if err := tx.QueryRowContext(ctx, `SELECT max(epoch) FROM key_epochs WHERE conv_id = ?`, int64(conv)).Scan(&max); err != nil {
		return false, err
	}
	if !max.Valid || int(max.Int64) != epoch {
		return false, nil
	}
	now := nowMs()
	if _, err := tx.ExecContext(ctx, `UPDATE key_epochs SET lost_at = ? WHERE conv_id = ? AND epoch = ?`, now, int64(conv), epoch); err != nil {
		return false, err
	}
	_, err := tx.ExecContext(ctx, `INSERT INTO key_flags (conv_id, needs_rotation, needs_own_key, updated_at) VALUES (?,1,0,?) ON CONFLICT(conv_id) DO UPDATE SET needs_rotation = 1, updated_at = excluded.updated_at`, int64(conv), now)
	return err == nil, err
}

// MadeLostEpochSince reports whether uid made an epoch of conv whose key was
// lost at or after since.
func (s *Store) MadeLostEpochSince(ctx context.Context, conv, uid ids.ID, since int64) (bool, error) {
	var n int
	err := s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM key_epochs WHERE conv_id = ? AND created_by = ? AND lost_at >= ?`, int64(conv), int64(uid), since).Scan(&n)
	return n > 0, err
}

// LatestEpoch returns the highest epoch of a conversation, and whether any exists.
func (s *Store) LatestEpoch(ctx context.Context, conv ids.ID) (int, bool, error) {
	var max sql.NullInt64
	if err := s.db.R.QueryRowContext(ctx, `SELECT max(epoch) FROM key_epochs WHERE conv_id = ?`, int64(conv)).Scan(&max); err != nil {
		return 0, false, err
	}
	if !max.Valid {
		return 0, false, nil
	}
	return int(max.Int64), true, nil
}

// Epochs lists a conversation's epochs ascending.
func (s *Store) Epochs(ctx context.Context, conv ids.ID) ([]*KeyEpoch, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT conv_id, epoch, created_by, created_at, reason, commitment FROM key_epochs WHERE conv_id = ? ORDER BY epoch`, int64(conv))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*KeyEpoch{}
	for rows.Next() {
		var e KeyEpoch
		if err := rows.Scan(&e.ConvID, &e.Epoch, &e.CreatedBy, &e.CreatedAt, &e.Reason, &e.Commitment); err != nil {
			return nil, err
		}
		out = append(out, &e)
	}
	return out, rows.Err()
}

// PutGrants stores grants, ignoring ones that already exist. Returns how many
// were inserted; grants for unknown epochs fail with ErrNotFound.
func (s *Store) PutGrants(ctx context.Context, grants []KeyGrant) (int, error) {
	inserted := 0
	now := nowMs()
	err := s.Tx(ctx, func(tx *db.Tx) error {
		for _, g := range grants {
			res, err := tx.ExecContext(ctx, `INSERT INTO key_grants (conv_id, epoch, user_id, wrapped_key, wrapped_by, created_at) VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING`, int64(g.ConvID), g.Epoch, int64(g.UserID), g.WrappedKey, int64(g.WrappedBy), now)
			if err != nil {
				if isFKError(err) {
					return ErrNotFound
				}
				return err
			}
			if n, _ := res.RowsAffected(); n > 0 {
				inserted++
			}
		}
		return nil
	})
	return inserted, err
}

// RejectGrant lets only the recipient discard the exact wrapper it could not
// open. Matching the bytes avoids deleting a replacement another device has
// already obtained while this request was in flight.
//
// A wrapper from anyone else says nothing about the key, since a holder sharing
// junk leaves the real key intact. One from the maker of the newest epoch does:
// once need different people could not open theirs, that key is lost and this
// answers true. need 0 discards without counting.
func (s *Store) RejectGrant(ctx context.Context, conv ids.ID, epoch int, recipient ids.ID, wrapped []byte, need int) (bool, error) {
	lost := false
	err := s.Tx(ctx, func(tx *db.Tx) error {
		if err := tx.Serialise(ctx, "epoch", int64(conv)); err != nil {
			return err
		}
		var wrappedBy, createdBy int64
		err := tx.QueryRowContext(ctx, `SELECT g.wrapped_by, e.created_by FROM key_grants g JOIN key_epochs e ON e.conv_id = g.conv_id AND e.epoch = g.epoch
			WHERE g.conv_id = ? AND g.epoch = ? AND g.user_id = ? AND g.wrapped_key = ?`, int64(conv), epoch, int64(recipient), wrapped).Scan(&wrappedBy, &createdBy)
		if errors.Is(err, sql.ErrNoRows) {
			return nil
		}
		if err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `DELETE FROM key_grants WHERE conv_id = ? AND epoch = ? AND user_id = ? AND wrapped_key = ?`, int64(conv), epoch, int64(recipient), wrapped); err != nil {
			return err
		}
		if need == 0 || wrappedBy != createdBy || createdBy == int64(recipient) {
			return nil
		}
		var max sql.NullInt64
		if err := tx.QueryRowContext(ctx, `SELECT max(epoch) FROM key_epochs WHERE conv_id = ?`, int64(conv)).Scan(&max); err != nil {
			return err
		}
		if !max.Valid || int(max.Int64) != epoch {
			return nil
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO key_rejections (conv_id, epoch, user_id, created_at) VALUES (?,?,?,?) ON CONFLICT DO NOTHING`, int64(conv), epoch, int64(recipient), nowMs()); err != nil {
			return err
		}
		var n int
		if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM key_rejections WHERE conv_id = ? AND epoch = ?`, int64(conv), epoch).Scan(&n); err != nil {
			return err
		}
		if n >= need {
			lost, err = markEpochLost(ctx, tx, conv, epoch)
		}
		return err
	})
	return lost, err
}

func isFKError(err error) bool {
	if err == nil {
		return false
	}
	msg := err.Error()
	// SQLite says so in words; Postgres uses SQLSTATE 23503.
	return strings.Contains(msg, "FOREIGN KEY constraint failed") || strings.Contains(msg, "SQLSTATE 23503")
}

// grantRead reads grants with their epoch's fingerprint.
const grantRead = `SELECT g.conv_id, g.epoch, g.user_id, g.wrapped_key, g.wrapped_by, g.created_at, e.commitment FROM key_grants g JOIN key_epochs e ON e.conv_id = g.conv_id AND e.epoch = g.epoch `

func scanGrants(rows *db.Rows) ([]*KeyGrant, error) {
	defer rows.Close()
	out := []*KeyGrant{}
	for rows.Next() {
		var g KeyGrant
		if err := rows.Scan(&g.ConvID, &g.Epoch, &g.UserID, &g.WrappedKey, &g.WrappedBy, &g.CreatedAt, &g.Commitment); err != nil {
			return nil, err
		}
		out = append(out, &g)
	}
	return out, rows.Err()
}

// GrantsForUser returns every grant addressed to uid.
func (s *Store) GrantsForUser(ctx context.Context, uid ids.ID) ([]*KeyGrant, error) {
	rows, err := s.db.R.QueryContext(ctx, grantRead+`WHERE g.user_id = ? ORDER BY g.conv_id, g.epoch`, int64(uid))
	if err != nil {
		return nil, err
	}
	return scanGrants(rows)
}

// Grant returns one grant, or ErrNotFound.
func (s *Store) Grant(ctx context.Context, conv ids.ID, epoch int, uid ids.ID) (*KeyGrant, error) {
	var g KeyGrant
	err := s.db.R.QueryRowContext(ctx, grantRead+`WHERE g.conv_id = ? AND g.epoch = ? AND g.user_id = ?`, int64(conv), epoch, int64(uid)).Scan(&g.ConvID, &g.Epoch, &g.UserID, &g.WrappedKey, &g.WrappedBy, &g.CreatedAt, &g.Commitment)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return &g, nil
}

// HoldersOf lists users holding a grant for conv/epoch.
func (s *Store) HoldersOf(ctx context.Context, conv ids.ID, epoch int) ([]ids.ID, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT user_id FROM key_grants WHERE conv_id = ? AND epoch = ?`, int64(conv), epoch)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []ids.ID{}
	for rows.Next() {
		var u int64
		if err := rows.Scan(&u); err != nil {
			return nil, err
		}
		out = append(out, ids.ID(u))
	}
	return out, rows.Err()
}

// UsersLackingGrant returns the entitled users with no grant for conv/epoch.
func (s *Store) UsersLackingGrant(ctx context.Context, conv ids.ID, epoch int, entitled []ids.ID) ([]ids.ID, error) {
	holders, err := s.HoldersOf(ctx, conv, epoch)
	if err != nil {
		return nil, err
	}
	return Lacking(entitled, holders), nil
}

// Lacking is the entitled users who are not among the holders.
func Lacking(entitled, holders []ids.ID) []ids.ID {
	has := make(map[ids.ID]bool, len(holders))
	for _, h := range holders {
		has[h] = true
	}
	out := []ids.ID{}
	for _, u := range entitled {
		if !has[u] {
			out = append(out, u)
		}
	}
	return out
}

// Entitled lists the users who should hold a conversation's keys: DM and
// group participants, a server's members, or a server channel's viewers. A
// bot is among them only in a server, and only when its application has the
// Message Content intent (keyEligible); every other bot reads just the
// copies people's apps seal to it.
func (s *Store) Entitled(ctx context.Context, conv ids.ID) ([]ids.ID, error) {
	var typ string
	var serverID sql.NullInt64
	err := s.db.R.QueryRowContext(ctx, `SELECT type, server_id FROM channels WHERE id = ?`, int64(conv)).Scan(&typ, &serverID)
	if errors.Is(err, sql.ErrNoRows) {
		// a server's own conversation belongs to its members
		if members, err := s.serverMemberIDs(ctx, conv); err == nil {
			return s.keyFilter(ctx, conv, members)
		} else if !errors.Is(err, ErrNotFound) {
			return nil, err
		}
		if s.EntitledHook != nil {
			return s.EntitledHook(ctx, conv)
		}
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if IsDMLike(typ) {
		return s.keyParticipants(ctx, conv)
	}
	if serverID.Valid {
		viewers, err := s.channelViewers(ctx, ids.ID(serverID.Int64), conv)
		if err != nil {
			return nil, err
		}
		return s.keyFilter(ctx, ids.ID(serverID.Int64), viewers)
	}
	if s.EntitledHook != nil {
		return s.EntitledHook(ctx, conv)
	}
	return nil, ErrNotFound
}

// KeyNeed describes users lacking a grant for a conversation epoch.
type KeyNeed struct {
	ConvID  ids.ID   `json:"conv_id"`
	Epoch   int      `json:"epoch"`
	UserIDs []ids.ID `json:"user_ids"`
	// Rotate asks the holder to replace the key: someone lost access while
	// nobody holding it was online to do so.
	Rotate bool `json:"rotate,omitempty"`
}

// heldKey is one epoch of a conversation's key a user holds.
type heldKey struct {
	KeyEpoch
	// latest is whether it is the newest epoch of the conversation this user
	// holds (the newest of all may be one that never reached anybody); rotate
	// whether the conversation's key is waiting to be replaced.
	latest, rotate bool
}

// heldKeys lists every epoch of every conversation that uid holds a grant
// for, whether each is the conversation's latest, and whether the
// conversation still needs rotating. A grant the user wrapped for themselves
// only counts on an epoch they created: the server cannot tell a key from an
// invite link from junk, so such a holder is not asked to share it.
func (s *Store) heldKeys(ctx context.Context, uid ids.ID) ([]heldKey, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT g.conv_id, g.epoch, coalesce(f.needs_rotation, 0),
		CASE WHEN g.epoch = (SELECT max(m.epoch) FROM key_grants m WHERE m.conv_id = g.conv_id AND m.user_id = g.user_id) THEN 1 ELSE 0 END
		FROM key_grants g
		JOIN key_epochs e ON e.conv_id = g.conv_id AND e.epoch = g.epoch
		LEFT JOIN key_flags f ON f.conv_id = g.conv_id
		WHERE g.user_id = ? AND (g.wrapped_by != g.user_id OR e.created_by = g.user_id)
		ORDER BY g.conv_id, g.epoch`, int64(uid))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []heldKey{}
	for rows.Next() {
		var h heldKey
		var rot, latest int64
		if err := rows.Scan(&h.ConvID, &h.Epoch, &rot, &latest); err != nil {
			return nil, err
		}
		h.rotate, h.latest = rot == 1, latest == 1
		out = append(out, h)
	}
	return out, rows.Err()
}

// Needs computes, for every conversation uid holds keys of and is still part
// of, which entitled users lack each epoch uid holds, and whether the key is
// waiting to be rotated. Every epoch, not only the newest: someone who joined
// after a key was replaced needs the older ones to read what was said before
// they came, and everyone who joins a server reads its past.
//
// Every client asks on connect and after every keys_needed, and an account
// can hold hundreds of conversations' keys, so the conversations, their
// participants and the holders of their keys are each read in one query for
// all of them rather than two or three queries apiece.
func (s *Store) Needs(ctx context.Context, uid ids.ID) ([]KeyNeed, error) {
	held, err := s.heldKeys(ctx, uid)
	if err != nil {
		return nil, err
	}
	seen := map[ids.ID]bool{}
	convs := make([]ids.ID, 0, len(held))
	for _, e := range held {
		if !seen[e.ConvID] {
			seen[e.ConvID] = true
			convs = append(convs, e.ConvID)
		}
	}
	kinds, err := s.convKinds(ctx, convs)
	if err != nil {
		return nil, err
	}
	var talks []ids.ID
	for _, c := range convs {
		if k, ok := kinds[c]; ok && IsDMLike(k.typ) {
			talks = append(talks, c)
		}
	}
	participants, err := s.participantsOf(ctx, talks)
	if err != nil {
		return nil, err
	}
	holders, err := s.epochHolders(ctx, convs)
	if err != nil {
		return nil, err
	}
	entitledOf := map[ids.ID][]ids.ID{}
	gone := map[ids.ID]bool{}
	out := []KeyNeed{}
	for _, e := range held {
		if gone[e.ConvID] {
			continue
		}
		entitled, ok := entitledOf[e.ConvID]
		if !ok {
			k, isChannel := kinds[e.ConvID]
			switch {
			case isChannel && IsDMLike(k.typ):
				entitled = participants[e.ConvID]
			case isChannel && !k.server.IsZero():
				entitled, err = s.channelViewers(ctx, k.server, e.ConvID)
				if err == nil {
					entitled, err = s.keyFilter(ctx, k.server, entitled)
				}
			default:
				// a server's own conversation, or one the hook knows about
				entitled, err = s.Entitled(ctx, e.ConvID)
			}
			if err != nil {
				if errors.Is(err, ErrNotFound) {
					gone[e.ConvID] = true
					continue
				}
				return nil, err
			}
			if !slices.Contains(entitled, uid) {
				// a key kept from a conversation the user has left is not theirs to share
				gone[e.ConvID] = true
				continue
			}
			entitledOf[e.ConvID] = entitled
		}
		lacking := Lacking(entitled, holders[e.ConvID][e.Epoch])
		rotate := e.latest && e.rotate
		if len(lacking) > 0 || rotate {
			out = append(out, KeyNeed{ConvID: e.ConvID, Epoch: e.Epoch, UserIDs: lacking, Rotate: rotate})
		}
	}
	return out, nil
}

// convKind is what a conversation id names when it is a channel.
type convKind struct {
	typ    string
	server ids.ID
}

// chunked runs fn over list in pieces small enough for one IN (…).
func chunked(list []ids.ID, fn func(part []ids.ID) error) error {
	for start := 0; start < len(list); start += 500 {
		if err := fn(list[start:min(start+500, len(list))]); err != nil {
			return err
		}
	}
	return nil
}

// convKinds looks up the channels among convs; a conversation that is not a
// channel (a server's own) is simply absent.
func (s *Store) convKinds(ctx context.Context, convs []ids.ID) (map[ids.ID]convKind, error) {
	out := map[ids.ID]convKind{}
	err := chunked(convs, func(part []ids.ID) error {
		rows, err := s.db.R.QueryContext(ctx, `SELECT id, type, server_id FROM channels WHERE id IN (`+placeholders(len(part))+`)`, idArgs(part)...)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			var id ids.ID
			var k convKind
			var server sql.NullInt64
			if err := rows.Scan(&id, &k.typ, &server); err != nil {
				return err
			}
			if server.Valid {
				k.server = ids.ID(server.Int64)
			}
			out[id] = k
		}
		return rows.Err()
	})
	return out, err
}

// participantsOf lists the participants of each conversation who hold its
// keys (all but bots), in join order.
func (s *Store) participantsOf(ctx context.Context, convs []ids.ID) (map[ids.ID][]ids.ID, error) {
	out := map[ids.ID][]ids.ID{}
	err := chunked(convs, func(part []ids.ID) error {
		// bots read the copies sealed to them, never a conversation's keys
		args := append(idArgs(part), FlagBot)
		rows, err := s.db.R.QueryContext(ctx, `SELECT p.channel_id, p.user_id FROM dm_participants p JOIN users u ON u.id = p.user_id WHERE p.channel_id IN (`+placeholders(len(part))+`) AND u.flags & ? = 0 ORDER BY p.added_at, p.user_id`, args...)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			var ch, u ids.ID
			if err := rows.Scan(&ch, &u); err != nil {
				return err
			}
			out[ch] = append(out[ch], u)
		}
		return rows.Err()
	})
	return out, err
}

// epochHolders lists, for each conversation and each of its epochs, who
// holds a grant for it.
func (s *Store) epochHolders(ctx context.Context, convs []ids.ID) (map[ids.ID]map[int][]ids.ID, error) {
	out := map[ids.ID]map[int][]ids.ID{}
	err := chunked(convs, func(part []ids.ID) error {
		rows, err := s.db.R.QueryContext(ctx, `SELECT conv_id, epoch, user_id FROM key_grants WHERE conv_id IN (`+placeholders(len(part))+`)`, idArgs(part)...)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			var conv, u ids.ID
			var epoch int
			if err := rows.Scan(&conv, &epoch, &u); err != nil {
				return err
			}
			if out[conv] == nil {
				out[conv] = map[int][]ids.ID{}
			}
			out[conv][epoch] = append(out[conv][epoch], u)
		}
		return rows.Err()
	})
	return out, err
}

// SharersOf lists who may hand out a conversation epoch's key: whoever
// another member granted it to, and the epoch's creator. A grant someone
// wrapped for themselves (from an invite link) is not proof of the key, and
// the server refuses to take grants from it.
func (s *Store) SharersOf(ctx context.Context, conv ids.ID, epoch int) ([]ids.ID, error) {
	return s.idList(ctx, `SELECT g.user_id FROM key_grants g JOIN key_epochs e ON e.conv_id = g.conv_id AND e.epoch = g.epoch
		WHERE g.conv_id = ? AND g.epoch = ? AND (g.wrapped_by != g.user_id OR e.created_by = g.user_id)`, int64(conv), epoch)
}

// SetKeyFlags updates the per-conversation flags (nil leaves a flag alone).
//
// Each flag is written on its own, touching only its column. Read and written
// back together, two changes at once lost one another: a rotation asked for
// because someone lost access could vanish under an unrelated change to the
// other flag, leaving the removed member's key working for new messages.
func (s *Store) SetKeyFlags(ctx context.Context, conv ids.ID, needsRotation, needsOwnKey *bool) error {
	now := nowMs()
	return s.Tx(ctx, func(tx *db.Tx) error {
		if needsRotation != nil {
			v := boolInt(*needsRotation)
			if _, err := tx.ExecContext(ctx, `INSERT INTO key_flags (conv_id, needs_rotation, needs_own_key, updated_at) VALUES (?,?,0,?) ON CONFLICT(conv_id) DO UPDATE SET needs_rotation = excluded.needs_rotation, updated_at = excluded.updated_at`,
				int64(conv), v, now); err != nil {
				return err
			}
		}
		if needsOwnKey != nil {
			v := boolInt(*needsOwnKey)
			if _, err := tx.ExecContext(ctx, `INSERT INTO key_flags (conv_id, needs_rotation, needs_own_key, updated_at) VALUES (?,0,?,?) ON CONFLICT(conv_id) DO UPDATE SET needs_own_key = excluded.needs_own_key, updated_at = excluded.updated_at`,
				int64(conv), v, now); err != nil {
				return err
			}
		}
		return nil
	})
}

// KeyFlags reads the flags (zero values when no row exists).
func (s *Store) KeyFlags(ctx context.Context, conv ids.ID) (*KeyFlags, error) {
	var rot, own, updated int64
	err := s.db.R.QueryRowContext(ctx, `SELECT needs_rotation, needs_own_key, updated_at FROM key_flags WHERE conv_id = ?`, int64(conv)).Scan(&rot, &own, &updated)
	if errors.Is(err, sql.ErrNoRows) {
		return &KeyFlags{ConvID: conv}, nil
	}
	if err != nil {
		return nil, err
	}
	return &KeyFlags{ConvID: conv, NeedsRotation: rot == 1, NeedsOwnKey: own == 1, UpdatedAt: updated}, nil
}

// Epoch loads one epoch row.
func (s *Store) Epoch(ctx context.Context, conv ids.ID, epoch int) (*KeyEpoch, error) {
	var e KeyEpoch
	err := s.db.R.QueryRowContext(ctx, `SELECT conv_id, epoch, created_by, created_at, reason, commitment FROM key_epochs WHERE conv_id = ? AND epoch = ?`, int64(conv), epoch).Scan(&e.ConvID, &e.Epoch, &e.CreatedBy, &e.CreatedAt, &e.Reason, &e.Commitment)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return &e, nil
}

// DeleteUserGrants takes away every key an account was given: a bot whose
// application no longer reads every message gives its keys back.
func (s *Store) DeleteUserGrants(ctx context.Context, uid ids.ID) error {
	defer s.everythingChanged()
	_, err := s.db.W.ExecContext(ctx, `DELETE FROM key_grants WHERE user_id = ?`, int64(uid))
	return err
}
