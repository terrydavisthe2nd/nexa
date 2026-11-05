package store

import (
	"context"
	"database/sql"
	"errors"
	"sort"

	"evilion.xyz/chat/internal/db"
	"evilion.xyz/chat/internal/ids"
)

// The games people share from the desktop app, and what staff decide about
// them. Nothing here says who shared what to anyone: the panel gets only how
// many did, and a row goes with its account or once staff review its program.

// Statuses of a known_games row.
const (
	GameListed   = "listed"
	GameRejected = "rejected"
)

// GameSubmission is what someone shared about one program.
type GameSubmission struct {
	Process string
	OS      string
	Name    string
	GameID  string
	UserID  ids.ID
}

// SharedGame is one row of what someone shared, as they can see it themselves.
type SharedGame struct {
	Process string `json:"process"`
	OS      string `json:"os"`
	Name    string `json:"name"`
	GameID  string `json:"game_id,omitempty"`
	At      int64  `json:"shared_at"`
}

// WaitingGame is one program waiting on staff, from everyone who shared it:
// the name and game most of them gave, then the other names as a hint.
type WaitingGame struct {
	Process string   `json:"process"`
	Name    string   `json:"name"`
	GameID  string   `json:"game_id,omitempty"`
	Others  []string `json:"other_names,omitempty"`
	OS      []string `json:"os"`
	Count   int      `json:"count"`
	First   int64    `json:"first_at"`
}

// ListedGame is a program staff decided about.
type ListedGame struct {
	Process   string `json:"process"`
	Name      string `json:"name"`
	GameID    string `json:"game_id,omitempty"`
	Status    string `json:"status"`
	DecidedAt int64  `json:"decided_at"`
}

// ShareGame records what someone shared, replacing what they said about that
// program before. A program staff already decided about is left alone.
func (s *Store) ShareGame(ctx context.Context, g GameSubmission) error {
	var status string
	err := s.db.R.QueryRowContext(ctx, `SELECT status FROM known_games WHERE process = ?`, g.Process).Scan(&status)
	if err == nil {
		return nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	_, err = s.db.W.ExecContext(ctx, `INSERT INTO game_submissions (id, process, os, name, game_id, user_id, created_at) VALUES (?,?,?,?,?,?,?)
		ON CONFLICT (user_id, process) DO UPDATE SET os = excluded.os, name = excluded.name, game_id = excluded.game_id`,
		int64(ids.New()), g.Process, g.OS, g.Name, g.GameID, int64(g.UserID), nowMs())
	return err
}

// WaitingGames is every program waiting on staff, the most shared first.
func (s *Store) WaitingGames(ctx context.Context) ([]WaitingGame, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT process, os, name, game_id, created_at FROM game_submissions ORDER BY created_at LIMIT 5000`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	type tally struct {
		w     WaitingGame
		names map[string]int
		games map[string]int
		os    map[string]bool
	}
	byProcess := map[string]*tally{}
	for rows.Next() {
		var process, osName, name, gameID string
		var at int64
		if err := rows.Scan(&process, &osName, &name, &gameID, &at); err != nil {
			return nil, err
		}
		t := byProcess[process]
		if t == nil {
			t = &tally{w: WaitingGame{Process: process, First: at}, names: map[string]int{}, games: map[string]int{}, os: map[string]bool{}}
			byProcess[process] = t
		}
		t.w.Count++
		t.names[name]++
		t.os[osName] = true
		if gameID != "" {
			t.games[gameID]++
		}
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	out := make([]WaitingGame, 0, len(byProcess))
	for _, t := range byProcess {
		t.w.Name = mostCommon(t.names)
		t.w.GameID = mostCommon(t.games)
		for n := range t.names {
			if n != t.w.Name {
				t.w.Others = append(t.w.Others, n)
			}
		}
		sort.Strings(t.w.Others)
		for o := range t.os {
			t.w.OS = append(t.w.OS, o)
		}
		sort.Strings(t.w.OS)
		out = append(out, t.w)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Count != out[j].Count {
			return out[i].Count > out[j].Count
		}
		return out[i].First < out[j].First
	})
	return out, nil
}

// mostCommon is the key counted most, ties going to the first in order so the
// answer does not change between two looks.
func mostCommon(counts map[string]int) string {
	keys := make([]string, 0, len(counts))
	for k := range counts {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	best, most := "", 0
	for _, k := range keys {
		if counts[k] > most {
			best, most = k, counts[k]
		}
	}
	return best
}

// WaitingGamesCount is how many programs wait on staff.
func (s *Store) WaitingGamesCount(ctx context.Context) (int, error) {
	var n int
	err := s.db.R.QueryRowContext(ctx, `SELECT count(DISTINCT process) FROM game_submissions`).Scan(&n)
	return n, err
}

// DecidedGames is what staff decided, newest first, or only those with a status.
func (s *Store) DecidedGames(ctx context.Context, status string) ([]ListedGame, error) {
	q := `SELECT process, name, game_id, status, decided_at FROM known_games`
	args := []any{}
	if status != "" {
		q += ` WHERE status = ?`
		args = append(args, status)
	}
	rows, err := s.db.R.QueryContext(ctx, q+` ORDER BY decided_at DESC`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []ListedGame{}
	for rows.Next() {
		var g ListedGame
		if err := rows.Scan(&g.Process, &g.Name, &g.GameID, &g.Status, &g.DecidedAt); err != nil {
			return nil, err
		}
		out = append(out, g)
	}
	return out, rows.Err()
}

// DecideGame puts a program on the list or turns it down, and clears what
// people shared about it: it has had its answer.
func (s *Store) DecideGame(ctx context.Context, g ListedGame, by ids.ID, log *ModLogEntry) error {
	return s.Tx(ctx, func(tx *db.Tx) error {
		if _, err := tx.ExecContext(ctx, `INSERT INTO known_games (process, name, game_id, status, decided_by, decided_at) VALUES (?,?,?,?,?,?)
			ON CONFLICT (process) DO UPDATE SET name = excluded.name, game_id = excluded.game_id, status = excluded.status, decided_by = excluded.decided_by, decided_at = excluded.decided_at`,
			g.Process, g.Name, g.GameID, g.Status, int64(by), nowMs()); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `DELETE FROM game_submissions WHERE process = ?`, g.Process); err != nil {
			return err
		}
		return appendModLogTx(ctx, tx, log)
	})
}

// ForgetGame takes a program off the list, or takes back turning it down, so
// that it can be shared again.
func (s *Store) ForgetGame(ctx context.Context, process string, log *ModLogEntry) (bool, error) {
	found := false
	err := s.Tx(ctx, func(tx *db.Tx) error {
		res, err := tx.ExecContext(ctx, `DELETE FROM known_games WHERE process = ?`, process)
		if err != nil {
			return err
		}
		n, err := res.RowsAffected()
		if err != nil {
			return err
		}
		found = n > 0
		if !found {
			return nil
		}
		return appendModLogTx(ctx, tx, log)
	})
	return found, err
}

// MySharedGames is what one person has shared and staff have not yet reviewed,
// for their data export.
func (s *Store) MySharedGames(ctx context.Context, uid ids.ID) ([]SharedGame, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT process, os, name, game_id, created_at FROM game_submissions WHERE user_id = ? ORDER BY created_at`, int64(uid))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []SharedGame{}
	for rows.Next() {
		var g SharedGame
		if err := rows.Scan(&g.Process, &g.OS, &g.Name, &g.GameID, &g.At); err != nil {
			return nil, err
		}
		out = append(out, g)
	}
	return out, rows.Err()
}
