INSERT INTO event_log_state (id, paused)
VALUES (1, ?)
ON DUPLICATE KEY UPDATE paused = VALUES(paused)
