SELECT b.voter_name, r.candidate_name, r.`rank`, b.confirmation, b.when_cast
FROM ballots b
INNER JOIN rankings r ON b.ballot_id = r.ballot_id
WHERE b.election_name = ?
ORDER BY b.voter_name, r.`rank`
