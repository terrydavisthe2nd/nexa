// Command chatmigrate copies a chatd SQLite database into Postgres.
//
// It reads every table in dependency order and writes it in batches inside one
// transaction, so either the whole database lands or none of it does. Nothing
// is interpreted on the way: ids, times, blobs and JSON columns are carried
// across exactly as they are, which is what makes the copy checkable — the row
// counts on both sides have to match, and the tool says so before it finishes.
//
//	chatmigrate --from data/chat.db --to 'postgres://user:pass@host/chat?sslmode=require'
//	chatmigrate --from data/chat.db --to '…' --wipe     # replace whatever is there
//
// Stop chatd first. A copy taken while it is running would miss whatever was
// written after the read began.
package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"flag"
	"fmt"
	"os"
	"sort"
	"strings"
	"time"

	"evilion.xyz/chat/internal/db"
)

// appendOnly names the tables whose triggers refuse updates and deletes (see
// the mod_log migration). Rows go into them like any other; only a wipe has to
// empty them another way.
var appendOnly = map[string]bool{"mod_log": true}

// tableOrder lists every table in the source, ordered so that a table is
// always copied after whatever it points at. It is read from the database
// rather than written down here, so a table added later is carried across
// without anyone remembering to add it.
func tableOrder(ctx context.Context, src *db.DB) ([]string, error) {
	rows, err := src.R.QueryContext(ctx,
		`SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name`)
	if err != nil {
		return nil, err
	}
	var names []string
	for rows.Next() {
		var n string
		if err := rows.Scan(&n); err != nil {
			rows.Close()
			return nil, err
		}
		names = append(names, n)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}

	deps := map[string][]string{}
	known := map[string]bool{}
	for _, n := range names {
		known[n] = true
	}
	for _, n := range names {
		r, err := src.R.QueryContext(ctx, `SELECT "table" FROM pragma_foreign_key_list(?)`, n)
		if err != nil {
			return nil, err
		}
		for r.Next() {
			var target string
			if err := r.Scan(&target); err != nil {
				r.Close()
				return nil, err
			}
			// a table pointing at itself is fine: the rows go in one batch
			if target != n && known[target] {
				deps[n] = append(deps[n], target)
			}
		}
		r.Close()
	}

	var out []string
	done := map[string]bool{}
	var visit func(string, map[string]bool)
	visit = func(n string, stack map[string]bool) {
		if done[n] || stack[n] {
			return
		}
		stack[n] = true
		for _, d := range deps[n] {
			visit(d, stack)
		}
		delete(stack, n)
		done[n] = true
		out = append(out, n)
	}
	for _, n := range names {
		visit(n, map[string]bool{})
	}
	return out, nil
}

func main() {
	from := flag.String("from", "data/chat.db", "the SQLite database to copy from")
	to := flag.String("to", "", "the Postgres URL to copy into")
	wipe := flag.Bool("wipe", false, "empty the Postgres tables first")
	batch := flag.Int("batch", 500, "rows per insert")
	flag.Parse()
	if *to == "" {
		fmt.Fprintln(os.Stderr, "--to is required: a postgres:// URL")
		os.Exit(2)
	}
	if err := run(*from, *to, *wipe, *batch); err != nil {
		fmt.Fprintln(os.Stderr, "migrate:", err)
		os.Exit(1)
	}
}

func run(from, to string, wipe bool, batch int) error {
	ctx := context.Background()
	// A copy taken while chatd is still writing misses whatever it writes
	// after the read began — and the row counts at the end may even agree,
	// having counted the same moving target on both sides.
	if busy, err := sqliteInUse(from); err != nil {
		return fmt.Errorf("check whether %s is in use: %w", from, err)
	} else if busy {
		return fmt.Errorf("%s is open in another process — chatd is still running on it. Stop it first (sudo systemctl stop xyz.evilion.chat, or on macOS sudo launchctl bootout system/xyz.evilion.chat) and run this again", from)
	}
	src, err := db.Open(from)
	if err != nil {
		return fmt.Errorf("open %s: %w", from, err)
	}
	defer src.Close()
	dst, err := db.OpenPostgres(to)
	if err != nil {
		return fmt.Errorf("open postgres: %w", err)
	}
	defer dst.Close()

	fmt.Println("applying the schema…")
	if err := dst.Migrate(ctx); err != nil {
		return err
	}

	tables, err := tableOrder(ctx, src)
	if err != nil {
		return err
	}

	started := time.Now()
	total := 0
	err = dst.Tx(ctx, func(tx *db.Tx) error {
		if wipe {
			// in reverse, so nothing is pointed at while it is emptied
			for i := len(tables) - 1; i >= 0; i-- {
				empty := `DELETE FROM ` + tables[i]
				if appendOnly[tables[i]] {
					// its triggers refuse row deletes; replacing the whole
					// database is the one time emptying it is meant
					empty = `TRUNCATE ` + tables[i]
				}
				if _, err := tx.ExecContext(ctx, empty); err != nil {
					return fmt.Errorf("empty %s: %w", tables[i], err)
				}
			}
		}
		for _, t := range tables {
			n, err := copyTable(ctx, src, tx, t, batch)
			if err != nil {
				return fmt.Errorf("%s: %w", t, err)
			}
			total += n
			fmt.Printf("  %-20s %8d rows\n", t, n)
		}
		return nil
	})
	if err != nil {
		return err
	}

	fmt.Printf("copied %d rows in %s; checking…\n", total, time.Since(started).Round(time.Millisecond))
	if err := sameBytes(ctx, src, dst); err != nil {
		return err
	}
	for _, t := range tables {
		var a, b int64
		if err := src.R.QueryRowContext(ctx, `SELECT count(*) FROM `+t).Scan(&a); err != nil {
			return err
		}
		if err := dst.R.QueryRowContext(ctx, `SELECT count(*) FROM `+t).Scan(&b); err != nil {
			return err
		}
		if a != b {
			return fmt.Errorf("%s: %d rows in SQLite, %d in Postgres", t, a, b)
		}
	}
	fmt.Println("every table matches and every key came across unchanged.")
	fmt.Println("Point DATABASE_URL at Postgres and start chatd; the SQLite file is untouched.")
	return nil
}

// keyColumns are the values that have to survive exactly: a key, a salt, a
// session token or a code that came across wrong would lock somebody out, and
// a row count would not notice. Each row is read with what identifies it, so a
// value cannot move between rows unnoticed either. A test holds this list to
// the schema: every BLOB column and every *_hash, *_salt and *_secret column
// has to be here.
var keyColumns = []struct{ table, cols string }{
	{"users", "id, username, kdf_salt, kdf_params, public_key, enc_private_key, enc_private_key_recovery, auth_hash, recovery_auth_hash, flags"},
	{"key_epochs", "conv_id, epoch, created_by, created_at, commitment"},
	{"key_grants", "conv_id, epoch, user_id, wrapped_key, wrapped_by"},
	{"messages", "id, ciphertext, key_conv_id, key_epoch, webhook_id, plain"},
	{"user_settings", "user_id, blob, version"},
	{"blobs", "id, sha256, size, mime, kind"},
	{"sessions", "id, user_id, token_hash"},
	{"passkeys", "id, user_id, credential_id, public_key, alg, sign_count, rp_id, enc_private_key"},
	{"attachments", "id, blob_id, size"},
	// the second factor: a sealed secret or a backup code that came across
	// wrong is an account nobody can sign in to
	{"user_mfa", "user_id, totp_secret, last_step, enabled"},
	{"mfa_backup_codes", "user_id, code_hash, used_at"},
	// a locked subchat's salt and hash are what its password is checked against
	{"channels", "id, lock_salt, lock_params, lock_hash"},
	// the codes people already hold, in links and in the names they sign in with
	{"invites", "code, server_id, channel_id"},
	{"guest_links", "code, channel_id, epoch"},
	{"guests", "id, token_hash"},
	{"username_holds", "username, user_id"},
	// a webhook's URL is its token: one that came across wrong stops every
	// program posting through it
	{"webhooks", "id, token_hash, token_sealed"},
	// a linked Discord account's sealed tokens: ones that came across wrong
	// cannot put it back in the activity server
	{"discord_links", "user_id, discord_id, access_sealed, refresh_sealed"},
	// a followed announcement channel's key pair: the public half posts are
	// sealed to, and the private half sealed for the channel that follows;
	// either one wrong and the posts it gets can no longer be read
	{"channel_follows", "id, source_id, target_id, public_key, secret"},
	// a note's key, sealed to each person in it, to each one asked in and for
	// each link, and its earlier keys: one that came across wrong is a note
	// somebody can no longer open, or a version nobody can
	{"notes", "id, owner_id, version, key_epoch"},
	{"note_members", "note_id, user_id, role, key_sealed"},
	{"note_invites", "id, note_id, to_id, key_sealed"},
	{"note_links", "id, note_id, token_hash, key_wrapped, link_sealed"},
	{"note_keys", "note_id, epoch, wrapped"},
	// an app's token, secret and keys: one that came across wrong is a bot
	// that can no longer sign in, sign its answers or open what it is sent
	{"applications", "id, token_hash, secret_hash, sign_key_sealed, bot_key_sealed"},
	{"bot_copies", "message_id, bot_id, sealed"},
	// what apps and the people who added them hold: an interaction's token,
	// and the codes and tokens the apps signed people in with
	{"interactions", "id, token_hash"},
	{"oauth_codes", "code_hash, application_id, user_id"},
	{"oauth_tokens", "id, access_hash, refresh_hash"},
}

// sameBytes checks that the values that matter are identical on both sides.
func sameBytes(ctx context.Context, src, dst *db.DB) error {
	for _, c := range keyColumns {
		a, err := digest(ctx, src, c.table, c.cols)
		if err != nil {
			return err
		}
		b, err := digest(ctx, dst, c.table, c.cols)
		if err != nil {
			return err
		}
		if !bytes.Equal(a, b) {
			return fmt.Errorf("%s came across changed (sqlite %x, postgres %x)", c.table, a[:8], b[:8])
		}
	}
	return nil
}

// digest hashes every row of the columns, independent of their order: the two
// databases sort text by different rules (SQLite byte by byte, Postgres by its
// collation), so the rows are hashed one by one and the hashes sorted instead
// of asking either side for an ORDER BY.
func digest(ctx context.Context, d *db.DB, table, cols string) ([]byte, error) {
	rows, err := d.R.QueryContext(ctx, `SELECT `+cols+` FROM `+table)
	if err != nil {
		return nil, fmt.Errorf("%s: %w", table, err)
	}
	defer rows.Close()
	names, err := rows.Columns()
	if err != nil {
		return nil, err
	}
	var each [][]byte
	for rows.Next() {
		cells := make([]any, len(names))
		ptrs := make([]any, len(names))
		for i := range cells {
			ptrs[i] = &cells[i]
		}
		if err := rows.Scan(ptrs...); err != nil {
			return nil, err
		}
		h := sha256.New()
		for _, v := range cells {
			switch t := v.(type) {
			case []byte:
				h.Write(t)
			case string:
				h.Write([]byte(t))
			case nil:
				h.Write([]byte{0})
			default:
				fmt.Fprintf(h, "%v", t)
			}
			h.Write([]byte{0xff})
		}
		each = append(each, h.Sum(nil))
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	sort.Slice(each, func(i, j int) bool { return bytes.Compare(each[i], each[j]) < 0 })
	all := sha256.New()
	for _, e := range each {
		all.Write(e)
	}
	return all.Sum(nil), nil
}

// copyTable reads one table out of SQLite and writes it into Postgres in
// batches, column for column.
func copyTable(ctx context.Context, src *db.DB, tx *db.Tx, table string, batch int) (int, error) {
	// Channels point at their category, and Postgres checks that as each
	// batch goes in, so categories go first: a channel moved into a category
	// made after it would otherwise arrive in an earlier batch than its parent.
	order := ""
	if table == "channels" {
		order = ` ORDER BY parent_id IS NOT NULL, id`
	}
	rows, err := src.R.QueryContext(ctx, `SELECT * FROM `+table+order)
	if err != nil {
		return 0, err
	}
	defer rows.Close()
	cols, err := rows.Columns()
	if err != nil {
		return 0, err
	}
	quoted := make([]string, len(cols))
	for i, c := range cols {
		quoted[i] = `"` + c + `"`
	}
	insertHead := `INSERT INTO ` + table + ` (` + strings.Join(quoted, ",") + `) VALUES `

	var (
		pending []any
		values  []string
		n       int
	)
	flush := func() error {
		if len(values) == 0 {
			return nil
		}
		_, err := tx.ExecContext(ctx, insertHead+strings.Join(values, ","), pending...)
		pending, values = pending[:0], values[:0]
		return err
	}
	for rows.Next() {
		cells := make([]any, len(cols))
		ptrs := make([]any, len(cols))
		for i := range cells {
			ptrs[i] = &cells[i]
		}
		if err := rows.Scan(ptrs...); err != nil {
			return n, err
		}
		marks := make([]string, len(cols))
		for i := range cols {
			marks[i] = "?"
			// A []byte scanned into an `any` may point at memory the driver
			// reuses on the next row. These are held until the batch is sent,
			// so every one is copied: without this a key or a salt would be
			// written as whatever happened to be in the buffer later, and
			// nothing would say so.
			if b, ok := cells[i].([]byte); ok {
				cp := make([]byte, len(b))
				copy(cp, b)
				pending = append(pending, cp)
			} else {
				pending = append(pending, cells[i])
			}
		}
		values = append(values, "("+strings.Join(marks, ",")+")")
		n++
		if len(values) >= batch {
			if err := flush(); err != nil {
				return n, err
			}
		}
	}
	if err := rows.Err(); err != nil {
		return n, err
	}
	return n, flush()
}
