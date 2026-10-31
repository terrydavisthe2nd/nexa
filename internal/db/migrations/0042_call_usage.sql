-- Cloudflare's own measurement of each month's Realtime egress (SFU and
-- TURN, which share the allowance), beside the running estimate in
-- realtime_usage: it covers the month up to measured_until, and
-- estimate_base is the estimate as it stood then, so the meter adds only what
-- came after.
CREATE TABLE IF NOT EXISTS realtime_measurements (
 month TEXT PRIMARY KEY,
 sfu INTEGER NOT NULL DEFAULT 0,
 turn INTEGER NOT NULL DEFAULT 0,
 measured_until INTEGER NOT NULL DEFAULT 0,
 estimate_base INTEGER NOT NULL DEFAULT 0,
 measured_at INTEGER NOT NULL DEFAULT 0
);

-- Each account's calls by UTC day, for the staff's Calls page: the SFU egress
-- estimated to its devices (received) and caused by its own tracks (sent),
-- its time in calls, and what Cloudflare measured of the same with the TURN
-- relay it used (m_*), once a measurement of the day is in (measured_at).
-- user_id 0 holds what Cloudflare measured that no account could be named
-- for. Rows older than about two months are deleted.
CREATE TABLE IF NOT EXISTS call_usage (
 user_id INTEGER NOT NULL,
 day TEXT NOT NULL,
 received INTEGER NOT NULL DEFAULT 0,
 sent INTEGER NOT NULL DEFAULT 0,
 seconds INTEGER NOT NULL DEFAULT 0,
 m_received INTEGER NOT NULL DEFAULT 0,
 m_sent INTEGER NOT NULL DEFAULT 0,
 m_relay INTEGER NOT NULL DEFAULT 0,
 measured_at INTEGER NOT NULL DEFAULT 0,
 PRIMARY KEY (user_id, day)
);
CREATE INDEX IF NOT EXISTS call_usage_day ON call_usage(day);

-- Which account each SFU session belongs to, so Cloudflare's per-session
-- measurement can be put to a name. Kept three days.
CREATE TABLE IF NOT EXISTS realtime_sessions (
 session_id TEXT PRIMARY KEY,
 user_id INTEGER NOT NULL,
 created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS realtime_sessions_created ON realtime_sessions(created_at);

-- The TURN credentials handed out, by the username that names each for
-- revocation (never the password half), so one can be revoked once its
-- account is no longer in a call, even after a restart. Deleted once expired.
CREATE TABLE IF NOT EXISTS turn_credentials (
 username TEXT PRIMARY KEY,
 user_id INTEGER NOT NULL,
 created_at INTEGER NOT NULL,
 expires_at INTEGER NOT NULL,
 revoked_at INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS turn_credentials_user ON turn_credentials(user_id);
CREATE INDEX IF NOT EXISTS turn_credentials_expires ON turn_credentials(expires_at);
