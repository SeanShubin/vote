SELECT event_id, authority, event_type, event_data, created_at
FROM event_log
WHERE event_id > ?
ORDER BY event_id ASC
