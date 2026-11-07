package db

import (
	"context"
	"database/sql"
	"errors"
	"time"

	"evilion.xyz/chat/internal/perf"
)

// Every query goes through Conn or Tx, so this is where the profiler times
// them (internal/perf), each by the line in the store that ran it. A query's
// time runs until its rows are closed or its row scanned, since SQLite does
// most of the work while the rows are read.

// Rows is *sql.Rows that reports its query's time when closed.
type Rows struct {
	*sql.Rows
	ctx   context.Context
	site  uintptr
	query string
	start time.Time
	done  bool
}

// Close closes the rows and records how long the query took.
func (r *Rows) Close() error {
	err := r.Rows.Close()
	if !r.done {
		r.done = true
		perf.DB(r.ctx, perf.Query, r.site, r.query, time.Since(r.start), err != nil || r.Rows.Err() != nil)
	}
	return err
}

// Row is *sql.Row that reports its query's time when scanned.
type Row struct {
	*sql.Row
	ctx   context.Context
	site  uintptr
	query string
	start time.Time
}

// Scan reads the row and records how long the query took.
func (r *Row) Scan(dest ...any) error {
	err := r.Row.Scan(dest...)
	perf.DB(r.ctx, perf.Query, r.site, r.query, time.Since(r.start), failed(err))
	return err
}

// failed is an error worth counting: no row at all is an answer, not a failure.
func failed(err error) bool { return err != nil && !errors.Is(err, sql.ErrNoRows) }

func timedRows(ctx context.Context, site uintptr, query string, start time.Time, rows *sql.Rows, err error) (*Rows, error) {
	if err != nil {
		perf.DB(ctx, perf.Query, site, query, time.Since(start), true)
		return nil, err
	}
	return &Rows{Rows: rows, ctx: ctx, site: site, query: query, start: start}, nil
}

func timedExec(ctx context.Context, site uintptr, query string, start time.Time, res sql.Result, err error) (sql.Result, error) {
	perf.DB(ctx, perf.Query, site, query, time.Since(start), err != nil)
	return res, err
}
