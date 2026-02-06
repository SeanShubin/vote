INSERT INTO ballots (election_name, voter_name, rankings, confirmation, when_cast)
VALUES (?, ?, ?, ?, ?)
ON DUPLICATE KEY UPDATE
rankings = VALUES(rankings),
confirmation = VALUES(confirmation),
when_cast = VALUES(when_cast)
