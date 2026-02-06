SELECT election_name, owner_name, secret_ballot, no_voting_before,
       no_voting_after, allow_edit, allow_vote
FROM elections
WHERE election_name = ?
