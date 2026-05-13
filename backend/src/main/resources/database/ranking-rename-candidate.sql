UPDATE rankings r
INNER JOIN ballots b ON r.ballot_id = b.ballot_id
SET r.candidate_name = ?
WHERE b.election_name = ? AND r.candidate_name = ?
