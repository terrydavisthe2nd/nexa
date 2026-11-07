package db

import (
	"context"
	"database/sql"
	"encoding/binary"
	"hash/fnv"
	"strings"
	"sync"
	"time"

	"evilion.xyz/chat/internal/perf"
)

// Dialect is which database is behind the pools.
//
// chatd started on SQLite, which is still the right answer for one machine:
// no server to run, no network hop, and a single file to back up. Postgres is
// for a database kept by something else — a replica, a managed database
// somebody else keeps alive — still behind one chatd process.
//
// The queries themselves are the same either way. Every difference between the
// two that this codebase actually meets is handled here: the placeholder
// syntax, and the two places a query has to reach inside a JSON column.
type Dialect string

const (
	SQLite   Dialect = "sqlite"
	Postgres Dialect = "postgres"
)

// JSONArrayHas is a condition that is true when the JSON array in `column`
// contains `?`. SQLite walks it with json_each; Postgres turns it into a set of
// text values. The argument is a string either way.
func (d Dialect) JSONArrayHas(column string) string {
	if d == Postgres {
		return "EXISTS (SELECT 1 FROM jsonb_array_elements_text(" + column + "::jsonb) AS e(value) WHERE e.value = ?)"
	}
	return "EXISTS (SELECT 1 FROM json_each(" + column + ") WHERE value = ?)"
}

// JSONArrayHasExpr is JSONArrayHas against another column or expression rather
// than a parameter: the array holds ids as text, so the expression is compared
// as text too.
func (d Dialect) JSONArrayHasExpr(column, expr string) string {
	if d == Postgres {
		return "EXISTS (SELECT 1 FROM jsonb_array_elements_text(" + column + "::jsonb) AS e(value) WHERE e.value = CAST(" + expr + " AS TEXT))"
	}
	return "EXISTS (SELECT 1 FROM json_each(" + column + ") WHERE value = CAST(" + expr + " AS TEXT))"
}

// Greatest is the larger of two values, as a scalar. SQLite spells it max(),
// which Postgres keeps for the aggregate and calls greatest() here.
func (d Dialect) Greatest(a, b string) string {
	if d == Postgres {
		return "greatest(" + a + ", " + b + ")"
	}
	return "max(" + a + ", " + b + ")"
}

// Lower folds a column for a case-insensitive search. SQLite's own lower()
// only folds ASCII, so the store registers a ulower() that folds the way Go
// does; Postgres's lower() already folds everything.
func (d Dialect) Lower(expr string) string {
	if d == Postgres {
		return "lower(" + expr + ")"
	}
	return "ulower(" + expr + ")"
}

// IsUniqueViolation reports whether err is a broken unique constraint.
func IsUniqueViolation(err error) bool {
	if err == nil {
		return false
	}
	msg := err.Error()
	// SQLite says so in words; Postgres uses SQLSTATE 23505.
	return strings.Contains(msg, "UNIQUE constraint failed") || strings.Contains(msg, "SQLSTATE 23505")
}

// rebind turns the `?` placeholders every query here is written with into
// whatever the driver wants. Anything inside a string literal, a quoted
// identifier or a comment is left alone: queries carry comments explaining
// themselves, and an apostrophe in one ("the account's") used to read as the
// start of a string and leave every placeholder after it unnumbered.
func rebind(d Dialect, query string) string {
	if d != Postgres || !strings.Contains(query, "?") {
		return query
	}
	var b strings.Builder
	b.Grow(len(query) + 8)
	n := 0
	// quote is the character that ends the quoted run being copied: ' for a
	// string, " for an identifier, a newline for a -- comment, / for a /* */
	// one; 0 outside any
	var quote byte
	// opened is where the /* of the comment being copied begins, so that the
	// * of its own opening cannot also close it ("/*/")
	opened := 0
	for i := 0; i < len(query); i++ {
		c := query[i]
		switch {
		case quote == '\'' || quote == '"':
			b.WriteByte(c)
			if c == quote {
				// a doubled quote inside is an escaped one, not the end
				if i+1 < len(query) && query[i+1] == quote {
					b.WriteByte(c)
					i++
					continue
				}
				quote = 0
			}
		case quote == '\n':
			b.WriteByte(c)
			if c == '\n' {
				quote = 0
			}
		case quote == '/':
			b.WriteByte(c)
			if c == '/' && query[i-1] == '*' && i-1 > opened+1 {
				quote = 0
			}
		case c == '\'' || c == '"':
			quote = c
			b.WriteByte(c)
		case c == '-' && i+1 < len(query) && query[i+1] == '-':
			quote = '\n'
			b.WriteByte(c)
		case c == '/' && i+1 < len(query) && query[i+1] == '*':
			quote, opened = '/', i
			b.WriteString("/*")
			i++
		case c == '?':
			n++
			b.WriteByte('$')
			b.WriteString(itoa(n))
		default:
			b.WriteByte(c)
		}
	}
	return b.String()
}

func itoa(n int) string {
	if n < 10 {
		return string(rune('0' + n))
	}
	var buf [8]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	return string(buf[i:])
}

// Conn is a pool whose queries are written once and rebound for whichever
// database is behind it. It carries the same three methods the store uses, so
// nothing above here knows which one it is talking to.
type Conn struct {
	sqlDB   *sql.DB
	dialect Dialect
	cache   sync.Map // query as written -> query as the driver wants it
}

func newConn(pool *sql.DB, d Dialect) *Conn { return &Conn{sqlDB: pool, dialect: d} }

func (c *Conn) bind(query string) string {
	if c.dialect != Postgres {
		return query
	}
	if v, ok := c.cache.Load(query); ok {
		return v.(string)
	}
	out := rebind(c.dialect, query)
	c.cache.Store(query, out)
	return out
}

// SQL is the pool itself, for the few callers that need it (pings, closing).
func (c *Conn) SQL() *sql.DB { return c.sqlDB }

func (c *Conn) ExecContext(ctx context.Context, query string, args ...any) (sql.Result, error) {
	return c.exec(ctx, perf.Site(1), query, args)
}

func (c *Conn) QueryContext(ctx context.Context, query string, args ...any) (*Rows, error) {
	return c.query(ctx, perf.Site(1), query, args)
}

func (c *Conn) QueryRowContext(ctx context.Context, query string, args ...any) *Row {
	return c.queryRow(ctx, perf.Site(1), query, args)
}

func (c *Conn) Exec(query string, args ...any) (sql.Result, error) {
	return c.exec(context.Background(), perf.Site(1), query, args)
}

func (c *Conn) Query(query string, args ...any) (*Rows, error) {
	return c.query(context.Background(), perf.Site(1), query, args)
}

func (c *Conn) QueryRow(query string, args ...any) *Row {
	return c.queryRow(context.Background(), perf.Site(1), query, args)
}

func (c *Conn) exec(ctx context.Context, site uintptr, query string, args []any) (sql.Result, error) {
	start := time.Now()
	res, err := c.sqlDB.ExecContext(ctx, c.bind(query), args...)
	return timedExec(ctx, site, query, start, res, err)
}

func (c *Conn) query(ctx context.Context, site uintptr, query string, args []any) (*Rows, error) {
	start := time.Now()
	rows, err := c.sqlDB.QueryContext(ctx, c.bind(query), args...)
	return timedRows(ctx, site, query, start, rows, err)
}

func (c *Conn) queryRow(ctx context.Context, site uintptr, query string, args []any) *Row {
	start := time.Now()
	return &Row{Row: c.sqlDB.QueryRowContext(ctx, c.bind(query), args...), ctx: ctx, site: site, query: query, start: start}
}

func (c *Conn) Close() error { return c.sqlDB.Close() }

// Tx is a transaction whose queries are rebound the same way.
type Tx struct {
	tx      *sql.Tx
	dialect Dialect
}

// Dialect says which database this transaction is on, for the few queries that
// have to be spelled differently.
func (t *Tx) Dialect() Dialect { return t.dialect }

func (t *Tx) bind(query string) string { return rebind(t.dialect, query) }

func (t *Tx) ExecContext(ctx context.Context, query string, args ...any) (sql.Result, error) {
	return t.exec(ctx, perf.Site(1), query, args)
}

func (t *Tx) QueryContext(ctx context.Context, query string, args ...any) (*Rows, error) {
	return t.query(ctx, perf.Site(1), query, args)
}

func (t *Tx) QueryRowContext(ctx context.Context, query string, args ...any) *Row {
	return t.queryRow(ctx, perf.Site(1), query, args)
}

func (t *Tx) exec(ctx context.Context, site uintptr, query string, args []any) (sql.Result, error) {
	start := time.Now()
	res, err := t.tx.ExecContext(ctx, t.bind(query), args...)
	return timedExec(ctx, site, query, start, res, err)
}

func (t *Tx) query(ctx context.Context, site uintptr, query string, args []any) (*Rows, error) {
	start := time.Now()
	rows, err := t.tx.QueryContext(ctx, t.bind(query), args...)
	return timedRows(ctx, site, query, start, rows, err)
}

func (t *Tx) queryRow(ctx context.Context, site uintptr, query string, args []any) *Row {
	start := time.Now()
	return &Row{Row: t.tx.QueryRowContext(ctx, t.bind(query), args...), ctx: ctx, site: site, query: query, start: start}
}

// Serialise makes this transaction the only one working on what parts name,
// until it commits or rolls back.
//
// It exists for the check-then-insert sequences — is there already a DM
// between these two, how many subchats does this conversation have, how many
// distinct emoji are on this message. Those are safe on SQLite only because
// every write goes through one connection; Postgres runs sixteen at READ
// COMMITTED, so two transactions read the same "no" and both write. An
// advisory lock is the cheapest way to give them back the property the code
// was written against, and it needs no new index or column.
//
// The parts are hashed together, so an accidental collision costs two
// unrelated transactions a moment of waiting and nothing else.
func (t *Tx) Serialise(ctx context.Context, kind string, parts ...int64) error {
	if t.dialect != Postgres {
		// the single writer connection has already done this
		return nil
	}
	h := fnv.New64a()
	_, _ = h.Write([]byte(kind))
	var b [8]byte
	for _, p := range parts {
		binary.BigEndian.PutUint64(b[:], uint64(p))
		_, _ = h.Write(b[:])
	}
	_, err := t.tx.ExecContext(ctx, `SELECT pg_advisory_xact_lock($1)`, int64(h.Sum64()))
	return err
}

func (t *Tx) Exec(query string, args ...any) (sql.Result, error) {
	return t.exec(context.Background(), perf.Site(1), query, args)
}

func (t *Tx) Query(query string, args ...any) (*Rows, error) {
	return t.query(context.Background(), perf.Site(1), query, args)
}

func (t *Tx) QueryRow(query string, args ...any) *Row {
	return t.queryRow(context.Background(), perf.Site(1), query, args)
}

// SQL is the transaction itself, for a caller that needs something else from it.
func (t *Tx) SQL() *sql.Tx { return t.tx }
