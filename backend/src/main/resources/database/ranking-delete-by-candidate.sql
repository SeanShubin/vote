DELETE r FROM rankings r
INNER JOIN ballots b ON r.ballot_id = b.ballot_id
WHERE b.election_name = ? AND r.candidate_name = ?
