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
-- Discord-only authentication: every user has a Discord credential. The
-- columns are nullable for the same reason name uniqueness is
-- case-insensitive — defensive coding only. UNIQUE on discord_id prevents
-- two users from claiming the same Discord identity.
CREATE TABLE IF NOT EXISTS users (
    name VARCHAR(255) PRIMARY KEY,
    role VARCHAR(50) NOT NULL,
    discord_id VARCHAR(255) UNIQUE,
    discord_display_name VARCHAR(255),
    INDEX idx_discord_id (discord_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Command Model Projection: Elections
CREATE TABLE IF NOT EXISTS elections (
    election_name VARCHAR(255) PRIMARY KEY,
    owner_name VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    FOREIGN KEY (owner_name) REFERENCES users(name) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Command Model Projection: Election Managers
-- Co-managers an owner has granted content-editing authority on an election
-- (candidates, tiers, description) without granting delete/transfer/manager-
-- list control. election_name FK cascades on election delete, matching
-- candidates/tiers/ballots. user_name is a plain string column with NO FK --
-- the same pattern as rankings.candidate_name -- so user rename and removal
-- cascades are applied explicitly by the command model, not the database.
CREATE TABLE IF NOT EXISTS election_managers (
    election_name VARCHAR(255) NOT NULL,
    user_name VARCHAR(255) NOT NULL,
    PRIMARY KEY (election_name, user_name),
    FOREIGN KEY (election_name) REFERENCES elections(election_name) ON DELETE CASCADE
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
-- tier holds the highest-prestige tier each candidate cleared on this
-- ballot, or NULL when below every tier marker.
CREATE TABLE IF NOT EXISTS rankings (
    ballot_id BIGINT NOT NULL,
    candidate_name VARCHAR(255) NOT NULL,
    `rank` INT NOT NULL,
    tier VARCHAR(255) NULL,
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

-- Event Log Pause Flag — owner-only operator switch the backend reads on
-- every appendEvent. Single-row table mirroring sync_state. Persisted so the
-- pause survives Lambda restarts and is visible to every concurrent instance
-- (critical: the pause must hold across the migration+deploy window).
CREATE TABLE IF NOT EXISTS event_log_state (
    id INT PRIMARY KEY DEFAULT 1,
    paused BOOLEAN NOT NULL DEFAULT FALSE,
    CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO event_log_state (id, paused) VALUES (1, FALSE);
