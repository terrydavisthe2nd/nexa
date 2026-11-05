-- Use Stereo Voice (1 << 61): every server's @everyone may send stereo voice until its managers say otherwise.
UPDATE roles SET permissions = permissions | 2305843009213693952 WHERE id = server_id;
