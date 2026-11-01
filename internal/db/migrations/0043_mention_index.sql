-- Messages that mention anyone, for the Mentions list: a partial index keeps
-- them in each channel's order without walking every message there.
CREATE INDEX IF NOT EXISTS messages_mentioning ON messages(channel_id, id DESC)
  WHERE mention_everyone = 1 OR mentions <> '[]' OR mention_roles <> '[]';
