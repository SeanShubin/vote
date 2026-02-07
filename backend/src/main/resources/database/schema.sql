-- Event Sourcing Event Log
CREATE TABLE IF NOT EXISTS event_log (
    event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    authority VARCHAR(255) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    event_data JSON NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_event_id (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Command Model Projection: Users
CREATE TABLE IF NOT EXISTS users (
    name VARCHAR(255) PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    salt VARCHAR(255) NOT NULL,
    hash VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL,
    INDEX idx_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Command Model Projection: Elections
CREATE TABLE IF NOT EXISTS elections (
    election_name VARCHAR(255) PRIMARY KEY,
    owner_name VARCHAR(255) NOT NULL,
    secret_ballot BOOLEAN,
    no_voting_before TIMESTAMP,
    no_voting_after TIMESTAMP,
    allow_edit BOOLEAN,
    allow_vote BOOLEAN,
    FOREIGN KEY (owner_name) REFERENCES users(name) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Command Model Projection: Candidates
CREATE TABLE IF NOT EXISTS candidates (
    election_name VARCHAR(255) NOT NULL,
    candidate_name VARCHAR(255) NOT NULL,
    PRIMARY KEY (election_name, candidate_name),
    FOREIGN KEY (election_name) REFERENCES elections(election_name) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Command Model Projection: Eligible Voters
CREATE TABLE IF NOT EXISTS eligible_voters (
    election_name VARCHAR(255) NOT NULL,
    voter_name VARCHAR(255) NOT NULL,
    PRIMARY KEY (election_name, voter_name),
    FOREIGN KEY (election_name) REFERENCES elections(election_name) ON DELETE CASCADE,
    FOREIGN KEY (voter_name) REFERENCES users(name) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Command Model Projection: Ballots
CREATE TABLE IF NOT EXISTS ballots (
    ballot_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    election_name VARCHAR(255) NOT NULL,
    voter_name VARCHAR(255) NOT NULL,
    confirmation VARCHAR(255) NOT NULL,
    when_cast TIMESTAMP NOT NULL,
    UNIQUE KEY unique_ballot (election_name, voter_name),
    FOREIGN KEY (election_name) REFERENCES elections(election_name) ON DELETE CASCADE,
    FOREIGN KEY (voter_name) REFERENCES users(name) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Command Model Projection: Rankings (normalized)
CREATE TABLE IF NOT EXISTS rankings (
    ballot_id BIGINT NOT NULL,
    candidate_name VARCHAR(255) NOT NULL,
    `rank` INT NOT NULL,
    PRIMARY KEY (ballot_id, candidate_name),
    FOREIGN KEY (ballot_id) REFERENCES ballots(ballot_id) ON DELETE CASCADE,
    INDEX idx_ballot_rank (ballot_id, `rank`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Sync State Tracking
CREATE TABLE IF NOT EXISTS sync_state (
    id INT PRIMARY KEY DEFAULT 1,
    last_synced BIGINT NOT NULL DEFAULT 0,
    CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Initialize sync state
INSERT IGNORE INTO sync_state (id, last_synced) VALUES (1, 0);
