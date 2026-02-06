INSERT INTO sync_state (id, last_synced)
VALUES (1, ?)
ON DUPLICATE KEY UPDATE last_synced = VALUES(last_synced)
