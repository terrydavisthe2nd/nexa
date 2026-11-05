-- Games people shared from the desktop app (game_submissions), one row per
-- person and program until staff review that program, and what staff decided
-- (known_games): a program on the list every desktop app downloads, or one
-- turned down, which is not asked about again. A program is a lower-case file
-- name, never a path.
CREATE TABLE game_submissions (
  id         BIGINT PRIMARY KEY,
  process    TEXT NOT NULL,
  os         TEXT NOT NULL,
  name       TEXT NOT NULL,
  game_id    TEXT NOT NULL DEFAULT '',
  user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at BIGINT NOT NULL
);
CREATE UNIQUE INDEX game_submissions_once ON game_submissions(user_id, process);
CREATE INDEX game_submissions_process ON game_submissions(process);
CREATE TABLE known_games (
  process    TEXT PRIMARY KEY,
  name       TEXT NOT NULL,
  game_id    TEXT NOT NULL DEFAULT '',
  status     TEXT NOT NULL,
  decided_by BIGINT NOT NULL,
  decided_at BIGINT NOT NULL
);
