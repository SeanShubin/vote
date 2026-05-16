INSERT INTO feature_flags (flag_name, enabled) VALUES (?, ?)
ON DUPLICATE KEY UPDATE enabled = VALUES(enabled)
