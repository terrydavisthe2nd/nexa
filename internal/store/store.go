// Package store contains every SQL query chatd runs, grouped by domain. Handlers
// never touch database/sql directly.
package store

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"time"

	"evilion.xyz/chat/internal/db"
	"evilion.xyz/chat/internal/ids"
)

var (
	ErrNotFound      = errors.New("not found")
	ErrUsernameTaken = errors.New("username taken")
	ErrConflict      = errors.New("conflict")
	ErrAlreadyExists = errors.New("already exists")
	ErrTooMany       = errors.New("too many")
	// ErrBlocked is a change between two people that a block between them
	// forbids.
	ErrBlocked = errors.New("blocked")
)

// Store wraps the database pools.
type Store struct {
	db *db.DB
	// EntitledHook resolves key entitlement for conversations the DM layer
	// does not know (servers and private channels); set by the server layer.
	EntitledHook func(ctx context.Context, conv ids.ID) ([]ids.ID, error)

	// perm holds each server's permission inputs, so answering who may see
	// or do what costs no queries while nothing has changed (permcache.go)
	perm *permCache
}

type sqlNullInt = sql.NullInt64

// New returns a Store over d.
func New(d *db.DB) *Store { return &Store{db: d, perm: newPermCache()} }

// DB exposes the underlying pools for callers that need transactions spanning domains.
func (s *Store) DB() *db.DB { return s.db }

// Tx runs fn inside a write transaction.
func (s *Store) Tx(ctx context.Context, fn func(tx *db.Tx) error) error { return s.db.Tx(ctx, fn) }

func nowMs() int64 { return time.Now().UnixMilli() }

// nullID converts an optional id into a driver value.
func nullID(id *ids.ID) any {
	if id == nil {
		return nil
	}
	return int64(*id)
}

// idOrZero is an optional id as a plain number, with nothing meaning zero.
// Passing a typed value rather than NULL keeps Postgres from having to guess
// what a parameter is when it sits beside a literal.
func idOrZero(id *ids.ID) int64 {
	if id == nil {
		return 0
	}
	return int64(*id)
}

func idPtr(n sql.NullInt64) *ids.ID {
	if !n.Valid {
		return nil
	}
	v := ids.ID(n.Int64)
	return &v
}

func int64Ptr(n sql.NullInt64) *int64 {
	if !n.Valid {
		return nil
	}
	v := n.Int64
	return &v
}

func intPtr(n sql.NullInt64) *int {
	if !n.Valid {
		return nil
	}
	v := int(n.Int64)
	return &v
}

func nullInt64(p *int64) any {
	if p == nil {
		return nil
	}
	return *p
}

func nullInt(p *int) any {
	if p == nil {
		return nil
	}
	return int64(*p)
}

func boolInt(b bool) int64 {
	if b {
		return 1
	}
	return 0
}

// placeholders returns "?,?,?" for n values.
func placeholders(n int) string {
	if n <= 0 {
		return ""
	}
	return strings.Repeat("?,", n-1) + "?"
}

func idArgs(list []ids.ID) []any {
	out := make([]any, len(list))
	for i, id := range list {
		out[i] = int64(id)
	}
	return out
}

// querier is satisfied by *db.Conn and *db.Tx.
type querier interface {
	ExecContext(ctx context.Context, query string, args ...any) (sql.Result, error)
	QueryContext(ctx context.Context, query string, args ...any) (*db.Rows, error)
	QueryRowContext(ctx context.Context, query string, args ...any) *db.Row
}
