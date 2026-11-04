-- Uploads relayed to the bucket in parts (api/upload_relay.go): remote_id
-- is the bucket's id for the multipart upload, part_size the size of every
-- part but the last. Both are null for an upload that lands on this disk.
ALTER TABLE uploads ADD COLUMN remote_id TEXT;
ALTER TABLE uploads ADD COLUMN part_size INTEGER;
