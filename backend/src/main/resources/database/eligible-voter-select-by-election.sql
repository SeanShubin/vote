SELECT voter_name
FROM eligible_voters
WHERE election_name = ?
ORDER BY voter_name
