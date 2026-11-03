-- Profile badges. Most are earned and worked out when a profile is opened
-- (internal/store/badges.go); these tables hold the rest: the badges staff
-- make for events (badge_defs), the badges staff give by hand, Contributor
-- and those event badges (user_badges, badge "contributor" or "event:<id>"),
-- and the order and hidden set each person chose for their own (badge_prefs,
-- JSON lists of badge ids).
CREATE TABLE badge_defs (
  id         BIGINT PRIMARY KEY,
  name       TEXT NOT NULL,
  icon       TEXT NOT NULL,
  color      BIGINT NOT NULL,
  created_by BIGINT NOT NULL,
  created_at BIGINT NOT NULL
);
CREATE TABLE user_badges (
  user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  badge      TEXT NOT NULL,
  granted_by BIGINT NOT NULL,
  granted_at BIGINT NOT NULL,
  PRIMARY KEY (user_id, badge)
);
CREATE INDEX user_badges_badge ON user_badges(badge);
CREATE TABLE badge_prefs (
  user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  shown   TEXT NOT NULL DEFAULT '[]',
  hidden  TEXT NOT NULL DEFAULT '[]'
);
-- Gift Giver counts the gifts someone paid for.
CREATE INDEX plan_gifts_from ON plan_gifts(from_id);
