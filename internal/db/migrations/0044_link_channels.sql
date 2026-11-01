-- Link channels: an entry in a server's channel list that opens an address.
ALTER TABLE channels ADD COLUMN url TEXT NOT NULL DEFAULT '';
