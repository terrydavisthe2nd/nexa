-- What staff told the person who sent a report when they closed it (empty:
-- the outcome alone), and when. A told outcome is an item in the reporter's
-- Nexa conversation, unread until they look.
ALTER TABLE safety_reports ADD COLUMN outcome TEXT NOT NULL DEFAULT '';
ALTER TABLE safety_reports ADD COLUMN outcome_at BIGINT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS safety_reports_outcomes ON safety_reports(reporter_id, outcome_at);
