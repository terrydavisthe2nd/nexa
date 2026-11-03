package store

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"errors"
	"fmt"
	"strings"

	"modernc.org/sqlite"

	"evilion.xyz/chat/internal/db"
	"evilion.xyz/chat/internal/ids"
)

// SQLite's lower() and LIKE only fold ASCII, so a search for "łukasz" would
// never find "Łukasz". ulower folds a column the same way Go folds the query.
func init() {
	sqlite.MustRegisterDeterministicScalarFunction("ulower", 1, func(_ *sqlite.FunctionContext, args []driver.Value) (driver.Value, error) {
		switch v := args[0].(type) {
		case string:
			return strings.ToLower(v), nil
		case []byte:
			return strings.ToLower(string(v)), nil
		}
		return args[0], nil
	})
}

const userCols = `id, username, display_name, auth_hash, kdf_salt, kdf_params, public_key, enc_private_key, enc_private_key_recovery, recovery_auth_hash, avatar_id, banner_id, about_me, pronouns, accent_color, accent_color_2, status_text, status_emoji, status_expires_at, presence_pref, dm_privacy, flags, created_at, updated_at, group_link_only, typing_style, tag_server_id, tag_text, tag_badge, tag_color, name_style, plan`

type rowScanner interface{ Scan(dest ...any) error }

func scanUser(r rowScanner) (*User, error) {
	var u User
	var recoveryHash sql.NullString
	var avatar, banner, accent, accent2, statusExp, tagServer, tagColor sql.NullInt64
	var linkOnly int64
	err := r.Scan(&u.ID, &u.Username, &u.DisplayName, &u.AuthHash, &u.KDFSalt, &u.KDFParams, &u.PublicKey, &u.EncPrivateKey, &u.EncPrivateKeyRecovery, &recoveryHash,
		&avatar, &banner, &u.AboutMe, &u.Pronouns, &accent, &accent2, &u.StatusText, &u.StatusEmoji, &statusExp, &u.PresencePref, &u.DMPrivacy, &u.Flags, &u.CreatedAt, &u.UpdatedAt, &linkOnly,
		&u.TypingStyle, &tagServer, &u.TagText, &u.TagBadge, &tagColor, &u.NameStyle, &u.Plan)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	u.RecoveryAuthHash = recoveryHash.String
	u.AvatarID, u.BannerID = idPtr(avatar), idPtr(banner)
	u.AccentColor, u.AccentColor2 = intPtr(accent), intPtr(accent2)
	u.StatusExpiresAt = int64Ptr(statusExp)
	u.GroupLinkOnly = linkOnly == 1
	u.TagServerID, u.TagColor = idPtr(tagServer), intPtr(tagColor)
	return &u, nil
}

// CreateUser inserts u (ID and CreatedAt are filled in when zero).
func (s *Store) CreateUser(ctx context.Context, u *User) error {
	return s.createUser(ctx, u, false, "")
}

// CreateRegisteredUser inserts a person's account and decides bootstrap
// privileges under the same lock as the insert. Concurrent requests must not
// each turn an earlier observation of an empty instance into an administrator.
func (s *Store) CreateRegisteredUser(ctx context.Context, u *User, adminUsername string) error {
	return s.createUser(ctx, u, true, adminUsername)
}

func (s *Store) createUser(ctx context.Context, u *User, registration bool, adminUsername string) error {
	if u.ID.IsZero() {
		u.ID = ids.New()
	}
	if u.CreatedAt == 0 {
		u.CreatedAt = nowMs()
	}
	u.UpdatedAt = u.CreatedAt
	if u.PresencePref == "" {
		u.PresencePref = "online"
	}
	if u.DMPrivacy == "" {
		u.DMPrivacy = DefaultDMPrivacy
	}
	var recovery any
	if u.RecoveryAuthHash != "" {
		recovery = u.RecoveryAuthHash
	}
	defer func() {
		if u.Flags&FlagAdmin != 0 {
			s.everythingChanged()
		}
	}()
	return s.Tx(ctx, func(tx *db.Tx) error {
		if registration {
			if err := tx.Serialise(ctx, "registration-bootstrap"); err != nil {
				return err
			}
			u.Flags = 0
			var count int
			if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM users WHERE flags & ? = 0`, NotPeople).Scan(&count); err != nil {
				return err
			}
			if count == 0 {
				u.Flags = FlagAdmin
			} else if adminUsername != "" && u.Username == adminUsername {
				var admins int
				if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM users WHERE flags & ? != 0 AND flags & ? = 0`, FlagAdmin, NotPeople).Scan(&admins); err != nil {
					return err
				}
				if admins == 0 {
					u.Flags = FlagAdmin
				}
			}
		}
		// a name a moderator took off an account is still that account's
		if held, err := usernameHeldTx(ctx, tx, u.Username); err != nil || held {
			if err == nil {
				err = ErrUsernameTaken
			}
			return err
		}
		res, err := tx.ExecContext(ctx, `INSERT INTO users (`+userCols+`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(username) DO NOTHING`,
			int64(u.ID), u.Username, u.DisplayName, u.AuthHash, u.KDFSalt, u.KDFParams, u.PublicKey, u.EncPrivateKey, u.EncPrivateKeyRecovery, recovery,
			nullID(u.AvatarID), nullID(u.BannerID), u.AboutMe, u.Pronouns, nullInt(u.AccentColor), nullInt(u.AccentColor2), u.StatusText, u.StatusEmoji, nullInt64(u.StatusExpiresAt), u.PresencePref, u.DMPrivacy, u.Flags, u.CreatedAt, u.UpdatedAt, boolInt(u.GroupLinkOnly),
			u.TypingStyle, nullID(u.TagServerID), u.TagText, u.TagBadge, nullInt(u.TagColor), u.NameStyle, u.Plan)
		if err != nil {
			return err
		}
		n, err := res.RowsAffected()
		if err != nil {
			return err
		}
		if n == 0 {
			return ErrUsernameTaken
		}
		return nil
	})
}

// usernameHeldTx reports whether a moderator reset took this name off an
// account (see username_holds). It takes the name's lock first, so a reset and
// a registration of the same name on Postgres cannot pass each other.
func usernameHeldTx(ctx context.Context, tx *db.Tx, username string) (bool, error) {
	if err := tx.Serialise(ctx, "username:"+username); err != nil {
		return false, err
	}
	var n int
	if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM username_holds WHERE username = ?`, username).Scan(&n); err != nil {
		return false, err
	}
	return n > 0, nil
}

// UserBySignInName finds the account a sign-in names: the one with that
// username, or else the one a moderator reset that username on. Someone whose
// name was reset while they were away still knows only the old one, and must
// not be locked out of their account for it.
func (s *Store) UserBySignInName(ctx context.Context, username string) (*User, error) {
	u, err := s.UserByUsername(ctx, username)
	if !errors.Is(err, ErrNotFound) {
		return u, err
	}
	var id int64
	if err := s.db.R.QueryRowContext(ctx, `SELECT user_id FROM username_holds WHERE username = ?`, username).Scan(&id); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return s.UserByID(ctx, ids.ID(id))
}

// UserByID loads one user.
func (s *Store) UserByID(ctx context.Context, id ids.ID) (*User, error) {
	return scanUser(s.db.R.QueryRowContext(ctx, `SELECT `+userCols+` FROM users WHERE id = ?`, int64(id)))
}

// UserByUsername loads one user by exact (lowercase) username.
func (s *Store) UserByUsername(ctx context.Context, username string) (*User, error) {
	return scanUser(s.db.R.QueryRowContext(ctx, `SELECT `+userCols+` FROM users WHERE username = ?`, username))
}

// UsersByIDs loads many users at once.
func (s *Store) UsersByIDs(ctx context.Context, list []ids.ID) (map[ids.ID]*User, error) {
	out := map[ids.ID]*User{}
	for start := 0; start < len(list); start += 500 {
		end := start + 500
		if end > len(list) {
			end = len(list)
		}
		chunk := list[start:end]
		rows, err := s.db.R.QueryContext(ctx, `SELECT `+userCols+` FROM users WHERE id IN (`+placeholders(len(chunk))+`)`, idArgs(chunk)...)
		if err != nil {
			return nil, err
		}
		for rows.Next() {
			u, err := scanUser(rows)
			if err != nil {
				rows.Close()
				return nil, err
			}
			out[u.ID] = u
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return nil, err
		}
	}
	return out, nil
}

// SearchUsers finds users whose username or display name starts with q.
func (s *Store) SearchUsers(ctx context.Context, q string, limit int) ([]*User, error) {
	q = strings.ToLower(strings.TrimSpace(q))
	if q == "" {
		return nil, nil
	}
	if limit <= 0 || limit > 50 {
		limit = 10
	}
	like := escapeLike(q) + "%"
	rows, err := s.db.R.QueryContext(ctx, `SELECT `+userCols+` FROM users WHERE (username LIKE ? ESCAPE '\' OR `+s.db.Dialect.Lower("display_name")+` LIKE ? ESCAPE '\') AND flags & ? = 0 ORDER BY username LIMIT ?`, like, like, FlagDeleted|NotPeople, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*User
	for rows.Next() {
		u, err := scanUser(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, u)
	}
	return out, rows.Err()
}

// maxSearchQuery bounds how much text a search pattern is built from, so a
// very long one cannot become a very expensive LIKE.
const maxSearchQuery = 100

func escapeLike(s string) string {
	r := strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`)
	return r.Replace(s)
}

// CountUsers returns the number of accounts people made. Nexa's is not one,
// nor is the one webhooks post as:
// the first person to register must still find Nexa empty, since
// that is what makes them its admin.
// CountAdminsEver counts accounts that hold the admin flag, deleted ones
// included: whether Nexa has ever been set up.
func (s *Store) CountAdminsEver(ctx context.Context) (int, error) {
	var n int
	err := s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM users WHERE flags & ? != 0 AND flags & ? = 0`, FlagAdmin, NotPeople).Scan(&n)
	return n, err
}

// OldestAccountID is the first account anyone made (the system and webhook accounts aside).
func (s *Store) OldestAccountID(ctx context.Context) (ids.ID, error) {
	var id int64
	err := s.db.R.QueryRowContext(ctx, `SELECT id FROM users WHERE flags & ? = 0 ORDER BY id LIMIT 1`, NotPeople).Scan(&id)
	return ids.ID(id), err
}

func (s *Store) CountUsers(ctx context.Context) (int, error) {
	var n int
	err := s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM users WHERE flags & ? = 0`, NotPeople).Scan(&n)
	return n, err
}

// UpdateProfile applies p to the user.
func (s *Store) UpdateProfile(ctx context.Context, id ids.ID, p ProfilePatch) error {
	var sets []string
	var args []any
	set := func(col string, v any) { sets = append(sets, col+" = ?"); args = append(args, v) }
	if p.Username != nil {
		set("username", *p.Username)
	}
	if p.DisplayName != nil {
		set("display_name", *p.DisplayName)
	}
	if p.AboutMe != nil {
		set("about_me", *p.AboutMe)
	}
	if p.Pronouns != nil {
		set("pronouns", *p.Pronouns)
	}
	if p.AccentColor != nil {
		set("accent_color", nullInt(*p.AccentColor))
	}
	if p.AccentColor2 != nil {
		set("accent_color_2", nullInt(*p.AccentColor2))
	}
	if p.AvatarID != nil {
		set("avatar_id", nullID(*p.AvatarID))
	}
	if p.BannerID != nil {
		set("banner_id", nullID(*p.BannerID))
	}
	if p.StatusText != nil {
		set("status_text", *p.StatusText)
	}
	if p.StatusEmoji != nil {
		set("status_emoji", *p.StatusEmoji)
	}
	if p.StatusExpiresAt != nil {
		set("status_expires_at", nullInt64(*p.StatusExpiresAt))
	}
	if p.PresencePref != nil {
		set("presence_pref", *p.PresencePref)
	}
	if p.DMPrivacy != nil {
		set("dm_privacy", *p.DMPrivacy)
	}
	if p.GroupLinkOnly != nil {
		set("group_link_only", boolInt(*p.GroupLinkOnly))
	}
	if p.TypingStyle != nil {
		set("typing_style", *p.TypingStyle)
	}
	if p.NameStyle != nil {
		set("name_style", *p.NameStyle)
	}
	if len(sets) == 0 {
		return nil
	}
	set("updated_at", nowMs())
	args = append(args, int64(id))
	query := `UPDATE users SET ` + strings.Join(sets, ", ") + ` WHERE id = ?`
	var res sql.Result
	var err error
	if p.Username != nil || p.TypingStyle != nil {
		// A name a moderator took off an account is taken for good, and the
		// check shares a transaction with the write so nothing slips between.
		// A typing style's pictures are held for as long as it names them.
		err = s.Tx(ctx, func(tx *db.Tx) error {
			if p.Username != nil {
				held, err := usernameHeldTx(ctx, tx, *p.Username)
				if err != nil {
					return err
				}
				if held {
					return ErrUsernameTaken
				}
			}
			if p.TypingStyle != nil {
				if err := swapTypingImagesTx(ctx, tx, id, *p.TypingStyle); err != nil {
					return err
				}
			}
			res, err = tx.ExecContext(ctx, query, args...)
			return err
		})
	} else {
		res, err = s.db.W.ExecContext(ctx, query, args...)
	}
	if err != nil {
		if p.Username != nil && db.IsUniqueViolation(err) {
			return ErrUsernameTaken
		}
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// AuthUpdate carries replacement credentials. AuthHash and EncPrivateKey are
// required; KDFSalt/KDFParams replace the stretch parameters when set; the
// recovery pair is replaced when RecoveryAuthHash is non-empty.
type AuthUpdate struct {
	AuthHash              string
	KDFSalt               []byte
	KDFParams             string
	EncPrivateKey         []byte
	RecoveryAuthHash      string
	EncPrivateKeyRecovery []byte
}

// UpdateAuth replaces the password-derived credentials.
func (s *Store) UpdateAuth(ctx context.Context, id ids.ID, a AuthUpdate) error {
	sets := []string{"auth_hash = ?", "enc_private_key = ?", "updated_at = ?"}
	args := []any{a.AuthHash, a.EncPrivateKey, nowMs()}
	if len(a.KDFSalt) > 0 && a.KDFParams != "" {
		sets = append(sets, "kdf_salt = ?", "kdf_params = ?")
		args = append(args, a.KDFSalt, a.KDFParams)
	}
	if a.RecoveryAuthHash != "" {
		sets = append(sets, "recovery_auth_hash = ?", "enc_private_key_recovery = ?")
		args = append(args, a.RecoveryAuthHash, a.EncPrivateKeyRecovery)
	}
	args = append(args, int64(id))
	res, err := s.db.W.ExecContext(ctx, `UPDATE users SET `+strings.Join(sets, ", ")+` WHERE id = ?`, args...)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// UpdateRecovery replaces only the recovery pair.
func (s *Store) UpdateRecovery(ctx context.Context, id ids.ID, recoveryHash string, encPrivRecovery []byte) error {
	res, err := s.db.W.ExecContext(ctx, `UPDATE users SET recovery_auth_hash = ?, enc_private_key_recovery = ?, updated_at = ? WHERE id = ?`, recoveryHash, encPrivRecovery, nowMs(), int64(id))
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// SetUserFlags overwrites the flags bitfield.
func (s *Store) SetUserFlags(ctx context.Context, id ids.ID, flags int64) error {
	// admin, disabled and deleted all decide who is a Nexa admin
	defer s.everythingChanged()
	res, err := s.db.W.ExecContext(ctx, `UPDATE users SET flags = ?, updated_at = ? WHERE id = ?`, flags, nowMs(), int64(id))
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// ChangeUserFlags turns the set bits on and the clear bits off, leaving every
// other bit as it was, and returns the whole field afterwards.
//
// Two admins acting on the same account at the same moment used to read the
// same flags, each compute a new whole value in Go, and each write it: whoever
// landed second silently undid the other's change, and both were shown the
// change they made. Doing it in one statement means neither can lose the
// other's bit, and the value that comes back is what is stored.
func (s *Store) ChangeUserFlags(ctx context.Context, id ids.ID, set, clear int64) (int64, error) {
	defer s.everythingChanged()
	var flags int64
	err := s.Tx(ctx, func(tx *db.Tx) error {
		// The complement is worked out here rather than with a ~ in the SQL:
		// Postgres reads ~ on an untyped parameter as the regular-expression
		// operator and refuses the statement as ambiguous.
		res, err := tx.ExecContext(ctx, `UPDATE users SET flags = (flags | ?) & ?, updated_at = ? WHERE id = ?`, set, ^clear, nowMs(), int64(id))
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return ErrNotFound
		}
		return tx.QueryRowContext(ctx, `SELECT flags FROM users WHERE id = ?`, int64(id)).Scan(&flags)
	})
	if err != nil {
		return 0, err
	}
	return flags, nil
}

// DeleteAccount anonymises the account (messages are kept) and drops sessions.
func (s *Store) DeleteAccount(ctx context.Context, id ids.ID) error {
	defer s.everythingChanged()
	return s.Tx(ctx, func(tx *db.Tx) error {
		anon := fmt.Sprintf("deleted-%d", int64(id))
		// the avatar and banner are cleared below; give back their references
		if err := releaseBlobRefs(ctx, tx, `SELECT avatar_id FROM users WHERE id = ? UNION ALL SELECT banner_id FROM users WHERE id = ?`, int64(id), int64(id)); err != nil {
			return err
		}
		// and the pictures their widgets held
		var widgets string
		if err := tx.QueryRowContext(ctx, `SELECT widgets FROM users WHERE id = ?`, int64(id)).Scan(&widgets); err != nil && err != sql.ErrNoRows {
			return err
		}
		for img := range WidgetImages(widgets) {
			if _, err := tx.ExecContext(ctx, `UPDATE blobs SET ref_count = `+refFloor(tx.Dialect(), "ref_count - 1")+` WHERE id = ?`, int64(img)); err != nil {
				return err
			}
		}
		// and the pictures of their typing style
		if err := swapTypingImagesTx(ctx, tx, id, ""); err != nil && !errors.Is(err, ErrNotFound) {
			return err
		}
		res, err := tx.ExecContext(ctx, `UPDATE users SET username = ?, display_name = 'Deleted User', auth_hash = '!', enc_private_key = ?, enc_private_key_recovery = NULL, recovery_auth_hash = NULL, avatar_id = NULL, banner_id = NULL, about_me = '', pronouns = '', status_text = '', status_emoji = '', typing_style = '', name_style = '', tag_server_id = NULL, tag_text = '', tag_badge = '', tag_color = NULL, widgets = '', signup_invite = NULL, plan = '', flags = flags | ?, updated_at = ? WHERE id = ?`, anon, []byte{}, FlagDeleted, nowMs(), int64(id))
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return ErrNotFound
		}
		for _, q := range []string{`DELETE FROM sessions WHERE user_id = ?`, `DELETE FROM user_ips WHERE user_id = ?`, `DELETE FROM relationships WHERE user_id = ? OR target_id = ?`, `DELETE FROM user_settings WHERE user_id = ?`, `DELETE FROM key_grants WHERE user_id = ?`, `DELETE FROM user_mfa WHERE user_id = ?`, `DELETE FROM mfa_backup_codes WHERE user_id = ?`, `DELETE FROM passkeys WHERE user_id = ?`, `DELETE FROM user_badges WHERE user_id = ?`, `DELETE FROM badge_prefs WHERE user_id = ?`, `DELETE FROM billing WHERE user_id = ?`, `DELETE FROM plan_gifts WHERE to_id = ?`, `UPDATE gift_links SET claimed_by = 0 WHERE claimed_by = ?`, `DELETE FROM call_usage WHERE user_id = ?`, `DELETE FROM realtime_sessions WHERE user_id = ?`} {
			args := []any{int64(id)}
			if strings.Count(q, "?") == 2 {
				args = append(args, int64(id))
			}
			if _, err := tx.ExecContext(ctx, q, args...); err != nil {
				return err
			}
		}
		return nil
	})
}
