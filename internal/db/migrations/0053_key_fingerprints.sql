-- What keeps one member from breaking a conversation's key for everyone.
-- commitment is a one-way fingerprint of the key an epoch was made with, which
-- each app checks the key it was handed against. lost_at says the key never
-- reached people, or they could not open what they got, and it was replaced.
-- key_rejections is who could not open what the epoch's maker sent them; rows go
-- as soon as a newer epoch makes them moot.
ALTER TABLE key_epochs ADD COLUMN commitment BLOB;
ALTER TABLE key_epochs ADD COLUMN lost_at INTEGER;
CREATE TABLE key_rejections (
  conv_id INTEGER NOT NULL,
  epoch INTEGER NOT NULL,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (conv_id, epoch, user_id),
  FOREIGN KEY (conv_id, epoch) REFERENCES key_epochs(conv_id, epoch) ON DELETE CASCADE
);
