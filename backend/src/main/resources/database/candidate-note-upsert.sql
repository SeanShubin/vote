INSERT INTO candidate_notes (election_name, candidate_name, voter_name, note_text, last_updated)
VALUES (?, ?, ?, ?, ?)
ON DUPLICATE KEY UPDATE note_text = VALUES(note_text), last_updated = VALUES(last_updated)
