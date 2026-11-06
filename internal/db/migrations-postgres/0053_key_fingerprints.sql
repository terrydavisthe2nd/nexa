-- See the SQLite migration of the same number.
ALTER TABLE key_epochs ADD COLUMN commitment BYTEA;
ALTER TABLE key_epochs ADD COLUMN lost_at BIGINT;
CREATE TABLE key_rejections (
  conv_id BIGINT NOT NULL,
  epoch BIGINT NOT NULL,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at BIGINT NOT NULL,
  PRIMARY KEY (conv_id, epoch, user_id),
  FOREIGN KEY (conv_id, epoch) REFERENCES key_epochs(conv_id, epoch) ON DELETE CASCADE
);
