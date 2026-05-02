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
-- Simplified to just name + owner. The previous toggles (secret_ballot, allow_edit,
-- allow_vote, no_voting_before/after) and the eligible_voters table were dropped:
-- elections are live as soon as they exist, anyone can vote, and the only
-- moderation is delete-by-owner-or-ADMIN.
CREATE TABLE IF NOT EXISTS elections (
    election_name VARCHAR(255) PRIMARY KEY,
    owner_name VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    FOREIGN KEY (owner_name) REFERENCES users(name) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Command Model Projection: Candidates
CREATE TABLE IF NOT EXISTS candidates (
    election_name VARCHAR(255) NOT NULL,
    candidate_name VARCHAR(255) NOT NULL,
    PRIMARY KEY (election_name, candidate_name),
    FOREIGN KEY (election_name) REFERENCES elections(election_name) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Command Model Projection: Tiers (ordered list per election)
-- Position determines tier order, tier_name is the displayed label.
-- The "set tiers" event replaces the entire list for an election (delete-
-- then-insert), which matches the lock-while-ballots-exist rule.
CREATE TABLE IF NOT EXISTS tiers (
    election_name VARCHAR(255) NOT NULL,
    position INT NOT NULL,
    tier_name VARCHAR(255) NOT NULL,
    PRIMARY KEY (election_name, position),
    UNIQUE KEY unique_tier_name (election_name, tier_name),
    FOREIGN KEY (election_name) REFERENCES elections(election_name) ON DELETE CASCADE
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
