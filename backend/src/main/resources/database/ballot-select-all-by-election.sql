SELECT voter_name, rankings, confirmation, when_cast
FROM ballots
WHERE election_name = ?
