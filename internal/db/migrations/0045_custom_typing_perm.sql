-- Use Custom Typing Indicators (1 << 60): every server's @everyone keeps showing typing styles.
UPDATE roles SET permissions = permissions | 1152921504606846976 WHERE id = server_id;
