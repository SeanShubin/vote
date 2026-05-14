SELECT c.candidate_name, COUNT(DISTINCT r.ballot_id) AS ballot_count
FROM candidates c
LEFT JOIN ballots b ON b.election_name = c.election_name
LEFT JOIN rankings r ON r.ballot_id = b.ballot_id AND r.candidate_name = c.candidate_name
WHERE c.election_name = ?
GROUP BY c.candidate_name
