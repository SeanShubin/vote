SELECT confirmation, when_cast
FROM ballots
WHERE election_name = ? AND voter_name = ?
