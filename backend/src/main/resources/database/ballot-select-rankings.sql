SELECT r.candidate_name, r.`rank`
FROM ballots b
INNER JOIN rankings r ON b.ballot_id = r.ballot_id
WHERE b.election_name = ? AND b.voter_name = ?
ORDER BY r.`rank`
