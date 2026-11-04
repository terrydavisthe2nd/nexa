-- Files kept in object storage (a Cloudflare R2 bucket) instead of on this
-- machine's disk: end-to-end encrypted attachments, which the mover copies
-- there once something holds them (internal/media/remote.go). name is the
-- content name the bytes go by (media.shaOf) and the last part of the
-- bucket key; size is what the bucket bills. gone_at marks a copy nothing
-- holds any more, which the mover then deletes from the bucket.
CREATE TABLE remote_blobs (
  name      TEXT PRIMARY KEY,
  size      BIGINT NOT NULL,
  stored_at BIGINT NOT NULL,
  gone_at   BIGINT
);
CREATE INDEX remote_blobs_gone ON remote_blobs(gone_at) WHERE gone_at IS NOT NULL;
-- Requests made to the bucket per UTC month ("2026-11"), by the class R2
-- bills them in, for the monthly limit in Settings, Nexa Admin. A signed
-- link handed to an app counts as the one read it makes.
CREATE TABLE storage_usage (
  month   TEXT PRIMARY KEY,
  class_a BIGINT NOT NULL DEFAULT 0,
  class_b BIGINT NOT NULL DEFAULT 0
);
