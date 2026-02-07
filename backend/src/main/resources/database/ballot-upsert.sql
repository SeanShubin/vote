INSERT INTO ballots (election_name, voter_name, confirmation, when_cast)
VALUES (?, ?, ?, ?)
ON DUPLICATE KEY UPDATE
confirmation = VALUES(confirmation),
when_cast = VALUES(when_cast)
