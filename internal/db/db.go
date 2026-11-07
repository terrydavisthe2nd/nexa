// Package db opens the database chatd runs on — SQLite by default, Postgres
// when one is configured — with the settings each needs, and applies the
// embedded, forward-only migrations.
package db

import (
	"context"
	"database/sql"
	"embed"
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"evilion.xyz/chat/internal/perf"

	_ "github.com/jackc/pgx/v5/stdlib"
	_ "modernc.org/sqlite"
)

//go:embed migrations/*.sql
var migrations embed.FS

//go:embed migrations-postgres/*.sql
var pgMigrations embed.FS

// DB holds a writer pool and a reader pool.
//
// SQLite allows exactly one writer at a time; funnelling all writes through one
// connection turns "database is locked" errors into ordinary queueing. Postgres
// has no such limit, so its writer pool is as wide as its readers, and the split
// stays only because it lets a replica serve the reads later.
type DB struct {
	W *Conn
	R *Conn
	// Dialect says which database is behind the pools, for the handful of
	// queries that cannot be written the same way for both.
	Dialect Dialect
}

// Open opens SQLite at path, creating the parent directory if needed.
func Open(path string) (*DB, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o750); err != nil {
		return nil, fmt.Errorf("create data dir: %w", err)
	}
	base := "file:" + path + "?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=foreign_keys(ON)&_pragma=temp_store(MEMORY)"
	// The writer fsyncs on commit. With synchronous=NORMAL and WAL a committed
	// transaction is durable only after the next checkpoint, so a power cut
	// could take back a message the sender already had a 201 for and everyone
	// else had already been sent — and since the message is end to end
	// encrypted there is no reconciling that afterwards. Readers never write,
	// so they keep the cheaper setting.
	w, err := sql.Open("sqlite", base+"&_pragma=synchronous(FULL)")
	if err != nil {
		return nil, err
	}
	w.SetMaxOpenConns(1)
	w.SetMaxIdleConns(1)
	r, err := sql.Open("sqlite", base+"&_pragma=synchronous(NORMAL)")
	if err != nil {
		w.Close()
		return nil, err
	}
	r.SetMaxOpenConns(8)
	r.SetMaxIdleConns(8)
	if err := w.Ping(); err != nil {
		w.Close()
		r.Close()
		return nil, fmt.Errorf("open %s: %w", path, err)
	}
	return &DB{W: newConn(w, SQLite), R: newConn(r, SQLite), Dialect: SQLite}, nil
}

// OpenPostgres opens a Postgres database from a URL
// (postgres://user:pass@host:5432/chat?sslmode=require).
func OpenPostgres(url string) (*DB, error) {
	w, err := sql.Open("pgx", url)
	if err != nil {
		return nil, err
	}
	w.SetMaxOpenConns(16)
	w.SetMaxIdleConns(8)
	r, err := sql.Open("pgx", url)
	if err != nil {
		w.Close()
		return nil, err
	}
	r.SetMaxOpenConns(16)
	r.SetMaxIdleConns(8)
	if err := w.Ping(); err != nil {
		w.Close()
		r.Close()
		return nil, fmt.Errorf("open postgres: %w", err)
	}
	return &DB{W: newConn(w, Postgres), R: newConn(r, Postgres), Dialect: Postgres}, nil
}

// OpenURL picks the engine from the address: a postgres:// URL, or a file path.
func OpenURL(addr string) (*DB, error) {
	if strings.HasPrefix(addr, "postgres://") || strings.HasPrefix(addr, "postgresql://") {
		return OpenPostgres(addr)
	}
	return Open(addr)
}

// Tx runs fn inside a write transaction, rolling back on error or panic. The
// transaction it hands over rebinds placeholders exactly as the pools do, so a
// query written once works inside a transaction as well as outside one.
//
// The profiler sees how long the transaction queued for the writer and how
// long it then held it, both by the line that called Tx: on SQLite every other
// write waits behind the holder.
func (d *DB) Tx(ctx context.Context, fn func(tx *Tx) error) (err error) {
	site := perf.Site(1)
	asked := time.Now()
	raw, err := d.W.sqlDB.BeginTx(ctx, nil)
	began := time.Now()
	perf.DB(ctx, perf.TxWait, site, "", began.Sub(asked), err != nil)
	if err != nil {
		return err
	}
	defer func() { perf.DB(ctx, perf.TxHold, site, "", time.Since(began), err != nil) }()
	defer func() {
		if p := recover(); p != nil {
			_ = raw.Rollback()
			panic(p)
		}
		if err != nil {
			_ = raw.Rollback()
		}
	}()
	if err = fn(&Tx{tx: raw, dialect: d.Dialect}); err != nil {
		return err
	}
	return raw.Commit()
}

// Version is the schema version the database is at.
func (d *DB) Version(ctx context.Context) (int, error) {
	if d.Dialect == Postgres {
		if _, err := d.W.ExecContext(ctx, `CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)`); err != nil {
			return 0, err
		}
		var v sql.NullInt64
		if err := d.W.QueryRowContext(ctx, `SELECT max(version) FROM schema_version`).Scan(&v); err != nil {
			return 0, err
		}
		return int(v.Int64), nil
	}
	var current int
	err := d.W.QueryRowContext(ctx, "PRAGMA user_version").Scan(&current)
	return current, err
}

// migrateLockKey identifies the advisory lock migrations take on Postgres. Any
// constant will do; it only has to be the same in every chatd process.
const migrateLockKey = 0x63686174 // "chat"

// Migrate applies every migration newer than the schema version, each in its
// own transaction together with the version bump.
//
// On Postgres it runs under an advisory lock. Without one, two processes
// starting together against a fresh database both read version 0 and both
// apply the same file: one commits, the other dies on "relation already
// exists", and with migrations added in pairs the loser could commit a version
// bump for statements the winner had already run, leaving the recorded version
// ahead of the actual schema. SQLite needs no lock — its single writer
// connection already serialises this.
func (d *DB) Migrate(ctx context.Context) error {
	dir, set := "migrations", migrations
	if d.Dialect == Postgres {
		dir, set = "migrations-postgres", pgMigrations
		conn, err := d.W.sqlDB.Conn(ctx)
		if err != nil {
			return err
		}
		defer conn.Close()
		if err := waitForLock(ctx, conn); err != nil {
			return err
		}
		defer func() {
			// the lock is session-scoped, and the session goes back to the
			// pool rather than ending, so it has to be given back by hand
			_, _ = conn.ExecContext(context.WithoutCancel(ctx), `SELECT pg_advisory_unlock($1)`, migrateLockKey)
		}()
	}
	entries, err := fs.ReadDir(set, dir)
	if err != nil {
		return err
	}
	names := make([]string, 0, len(entries))
	known := 0
	for _, e := range entries {
		if !strings.HasSuffix(e.Name(), ".sql") {
			continue
		}
		numStr, _, _ := strings.Cut(e.Name(), "_")
		n, err := strconv.Atoi(numStr)
		if err != nil {
			return fmt.Errorf("migration %s: name must start with a number", e.Name())
		}
		known = max(known, n)
		names = append(names, e.Name())
	}
	sort.Strings(names)
	current, err := d.Version(ctx)
	if err != nil {
		return err
	}
	// A database a newer build has migrated is one this build was never
	// compiled against: a column it does not know may be NOT NULL, a table it
	// reads may have been reshaped. Serving it anyway fails later, per feature
	// and at request time — which is exactly what rolling back to
	// bin/chatd.prev after a release that changed the schema would do. So it
	// stops here, where the reason is plain.
	if current > known {
		return fmt.Errorf("%w: the database is at schema version %d and this build knows up to %d; it was last run by a newer chatd, so start that build (or a later one) instead", ErrNewerSchema, current, known)
	}
	for _, name := range names {
		numStr, _, _ := strings.Cut(name, "_")
		n, _ := strconv.Atoi(numStr)
		if n <= current {
			continue
		}
		body, err := set.ReadFile(dir + "/" + name)
		if err != nil {
			return err
		}
		err = d.Tx(ctx, func(tx *Tx) error {
			if _, err := tx.ExecContext(ctx, string(body)); err != nil {
				return fmt.Errorf("migration %s: %w", name, err)
			}
			if d.Dialect == Postgres {
				_, err := tx.ExecContext(ctx, `INSERT INTO schema_version (version) VALUES ($1)`, n)
				return err
			}
			_, err := tx.ExecContext(ctx, "PRAGMA user_version = "+strconv.Itoa(n))
			return err
		})
		if err != nil {
			return err
		}
		current = n
	}
	return nil
}

// Close checkpoints SQLite's WAL and closes both pools.
func (d *DB) Close() error {
	if d.Dialect == SQLite {
		_, _ = d.W.Exec("PRAGMA wal_checkpoint(TRUNCATE)")
	}
	rerr := d.R.Close()
	werr := d.W.Close()
	if werr != nil {
		return werr
	}
	return rerr
}

// ErrNoMigrations is returned when a Postgres database has no schema yet and
// none is embedded, which only happens in a broken build.
var ErrNoMigrations = errors.New("no migrations for this dialect")

// ErrNewerSchema says the database was migrated by a newer build than this one.
var ErrNewerSchema = errors.New("database is newer than this build")

// lockWaitNotice is how often waitForLock says it is still waiting.
var lockWaitNotice = 10 * time.Second

// waitForLock takes the migration lock on Postgres, saying so while another
// process holds it. pg_advisory_lock would simply block, and a process stuck
// holding the lock — a migration that hung, a chatmigrate left running — then
// looked like a chatd that never finished starting and never said why.
func waitForLock(ctx context.Context, conn *sql.Conn) error {
	started := time.Now()
	lastNotice := started
	for {
		var got bool
		if err := conn.QueryRowContext(ctx, `SELECT pg_try_advisory_lock($1)`, migrateLockKey).Scan(&got); err != nil {
			return fmt.Errorf("take migration lock: %w", err)
		}
		if got {
			return nil
		}
		if time.Since(lastNotice) >= lockWaitNotice || lastNotice == started {
			slog.Warn("waiting for another process to finish migrating the database (it holds the migration lock)", "waited", time.Since(started).Round(time.Second).String())
			lastNotice = time.Now()
		}
		select {
		case <-ctx.Done():
			return fmt.Errorf("take migration lock: %w", ctx.Err())
		case <-time.After(500 * time.Millisecond):
		}
	}
}
