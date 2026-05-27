SELECT election_name, candidate_name, voter_name, note_text, last_updated
FROM candidate_notes
WHERE election_name = ?
