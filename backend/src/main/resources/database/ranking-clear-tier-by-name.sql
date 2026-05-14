UPDATE rankings r
INNER JOIN ballots b ON r.ballot_id = b.ballot_id
SET r.tier = NULL
WHERE b.election_name = ? AND r.tier = ?
