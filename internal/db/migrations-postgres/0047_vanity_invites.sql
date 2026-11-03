-- Vanity links: a server's own chosen invite code (lowercase letters, digits
-- and hyphens), never expiring or running out, at most one per server.
ALTER TABLE invites ADD COLUMN vanity INTEGER NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX invites_vanity ON invites(server_id) WHERE vanity = 1;
