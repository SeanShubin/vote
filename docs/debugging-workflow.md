# Debugging Workflow

This document explains the two-layer inspection approach for debugging the vote backend.

## Overview

The backend provides two views of data:

1. **Relational Projection (Admin View)**: Natural keys, conceptual structure
2. **Raw Storage (Debug View)**: Mechanical storage, actual database structure

## Two-Layer Architecture

### Layer 1: Relational Projection (QueryModel API)

The `QueryModel` interface provides a relational view using natural keys:
- Users identified by `userName`
- Elections identified by `electionName`
- Ballots identified by `(electionName, voterName)`

This layer hides storage implementation details. The same API works across all three backends:
- InMemory (Map-based storage)
- MySQL (relational tables)
- DynamoDB (single-table NoSQL)

**Scripts for Relational View:**
- `./scripts/inspect-dynamodb-all` - DynamoDB relational projection
- `./scripts/inspect-mysql-all` - MySQL relational projection

### Layer 2: Raw Storage (Mechanical View)

Raw storage shows how data is ACTUALLY stored:

**DynamoDB:**
- Composite keys: `PK=ELECTION#name`, `SK=CANDIDATE#name`
- Attribute names: `userName`, `electionName` (camelCase)
- GSI for email lookup: `GSI1PK=EMAIL#addr`, `GSI1SK=USER#name`

**MySQL:**
- Natural key primary keys: `election(name)`, `user(name)`
- Foreign key constraints: `election.owner → user.name`
- Column names: `election_name`, `owner_name` (snake_case)

**Scripts for Raw View:**
- `./scripts/inspect-dynamodb-raw <table>` - Raw DynamoDB table scan
- `./scripts/inspect-dynamodb-raw-all` - All DynamoDB items with PK/SK
- `./scripts/inspect-dynamodb-raw-keys` - Just PK/SK structure and access patterns
- `./scripts/inspect-mysql-raw-schema` - MySQL table DDL and indexes
- `./scripts/inspect-mysql-raw-query '<sql>'` - Execute arbitrary SQL

## Inspecting Production Data

**Every `inspect-dynamodb-*` command accepts a `--prod` flag** that targets the
real AWS DynamoDB tables (region `us-east-1`) instead of DynamoDB Local. Output
includes a `Target:` line so you can tell at a glance which environment you're
looking at.

This is the primary way an AI assistant can pull live production state into the
conversation when the user asks "why is X happening in prod?". Pipe the output
into your context and reason from there — same data shape as local, no extra
parsing required.

```bash
# Dump the entire prod DynamoDB single-table picture to the console
scripts/dev inspect-dynamodb-all --prod

# Just the prod event log (most useful for debugging "what got recorded?")
scripts/dev inspect-dynamodb-event-log --prod

# Raw view of a specific prod table
scripts/dev inspect-dynamodb-raw vote_data --prod
```

**Credential expectations**: the command uses the default AWS credential chain
(env vars, `~/.aws/credentials`, SSO). The caller must already be authenticated
for the target account. If you see an authentication error, ask the user to log
in — do not attempt to set credentials yourself.

**No `--prod` for MySQL inspect commands**: MySQL is a local-only backend in
this project; production runs on DynamoDB. If a user asks to inspect prod
MySQL, point them back here.

**Sister commands** that also accept `--prod`: `backup-dynamodb`,
`restore-dynamodb`, `nuke-dynamodb`. The first is the canonical way to take a
full event-log snapshot to a JSONL file (vs. inspect, which streams to stdout).

## Running Locally Against a Snapshot

When inspecting prod isn't enough — e.g., the bug only reproduces when you
exercise the UI/API against the data — you can stand up a full local dev
environment seeded from any JSONL event-log snapshot. The snapshot can come
from prod or be generated synthetically.

`launch-from-snapshot` requires exactly one source flag:

```bash
# Download a fresh prod event log, replay into local DynamoDB, launch.
scripts/dev launch-from-snapshot --prod

# Or replay an existing snapshot file (no download).
scripts/dev launch-from-snapshot --snapshot .local/prod-snapshots/prod-snapshot-20260502-153012.jsonl
```

Snapshots land in `.local/` (gitignored, so prod data never gets committed):
- `--prod` → `.local/prod-snapshots/prod-snapshot-<timestamp>.jsonl`
- generated → `.local/scenario-snapshots/scenarios.jsonl` (see below)

Under the hood this is the same flow as `launch-fresh-dynamodb` with a restore
inserted after the purge: terminate → roll logs → purge local → restore
snapshot → build frontend → start backend → start frontend → open browser. The
`--prod` path uses the default AWS credential chain (env vars,
`~/.aws/credentials`, SSO) — the caller must already be authenticated.

### Generated scenario snapshots

`generate-scenario-event-log` produces a single JSONL event log that loads
every condorcet test scenario in `scenario-data/` (skipping
`07-ballot-can-have-ties` because the app does not support tied ranks within a
ballot). All 8 remaining scenarios are owned by a single synthetic `owner`
account; voter names that recur across scenarios share one user (so the same
`Alice` votes in multiple elections). Election names are prefixed with the
scenario number for easy locating in the UI (e.g. `01 - Contrast First Past
The Post`).

```bash
# 1. Convert condorcet test data into per-scenario JSONs (idempotent).
scripts/dev convert-scenarios --source D:/keep/github/sean/condorcet2/jvm/src/test/resources/test-data

# 2. Synthesize a single event log covering every scenario.
scripts/dev generate-scenario-event-log

# 3. Launch locally with that snapshot loaded.
scripts/dev launch-from-snapshot --snapshot .local/scenario-snapshots/scenarios.jsonl
```

The generator runs the real `ServiceImpl` in-process (with `InMemoryEventLog`
+ `RealPasswordUtil`), so the produced events are byte-compatible with what
`backup-dynamodb` would capture from a live backend. All accounts use the
password `password`.

**When to use this vs. inspect commands or prod snapshots:**
- `inspect-dynamodb-* --prod` — read-only; fastest path for "what's in prod
  right now?"
- `launch-from-snapshot --prod` — when you need to *interact with* prod-shaped
  data locally (reproduce a UI bug, replay a sequence, run a write that you'd
  never run against real prod).
- `launch-from-snapshot --snapshot .local/scenario-snapshots/scenarios.jsonl`
  — when you want repeatable, synthetic data covering every voting algorithm
  edge case (Condorcet cycle, tactical voting, result ties, etc.) without
  touching prod or AWS.

## Debugging Workflow

### Step 1: Identify the Problem (Admin View)

Start with the relational projection to understand WHAT is wrong conceptually:

```bash
# See all data through relational projection
./scripts/inspect-dynamodb-all

# Or for MySQL
./scripts/inspect-mysql-all
```

Ask conceptual questions:
- Is the election showing the correct owner?
- Are all expected candidates present?
- Did the ballot get recorded?
- Are the vote counts correct?

### Step 2: Inspect Raw Storage (Debug View)

If the relational projection looks wrong, inspect raw storage to understand HOW it's stored mechanically:

**DynamoDB:**

```bash
# See all items with PK/SK structure
./scripts/inspect-dynamodb-raw-all

# See just the key structure and access patterns
./scripts/inspect-dynamodb-raw-keys

# See raw JSON for a specific table
./scripts/inspect-dynamodb-raw vote_data
```

Questions to answer:
- Are the PK/SK values correct? (`ELECTION#Best Language`, not `Election#Best Language`)
- Are attribute names correct? (`electionName` not `election_name`)
- Is the GSI populated for email lookup?
- Are there orphaned items (SK without matching PK)?

**MySQL:**

```bash
# See table schemas and indexes
./scripts/inspect-mysql-raw-schema

# Execute raw SQL query
./scripts/inspect-mysql-raw-query 'SELECT * FROM election WHERE owner = "alice"'

# Check foreign key constraints
./scripts/inspect-mysql-raw-query 'SHOW CREATE TABLE ballot'
```

Questions to answer:
- Do foreign key constraints exist?
- Are primary keys correct?
- Are indexes present where expected?
- Do column names match expectations?

### Step 3: Compare Storage vs Projection

Often bugs occur in the projection layer (QueryModel implementation):

1. Storage is correct (raw view shows good data)
2. Projection is wrong (relational view shows bad data)
3. Bug is in `DynamoDbSingleTableQueryModel` or `MySqlQueryModel`

Example:
```bash
# Raw storage shows: PK=ELECTION#Best Language, candidateName="Kotlin"
./scripts/inspect-dynamodb-raw-keys

# But relational projection shows: candidates=[]
./scripts/inspect-dynamodb-all
```

This indicates the query in `DynamoDbSingleTableQueryModel.getCandidatesForElection()` is broken.

### Step 4: Check Event Log

The event log contains the source of truth (event sourcing):

```bash
# DynamoDB
./scripts/inspect-dynamodb-event-log

# MySQL
./scripts/inspect-mysql-raw-query 'SELECT * FROM event_log ORDER BY event_id'
```

Questions:
- Was the event recorded?
- Does the event_data match expectations?
- Was the event processed (sync_state updated)?
- Are there duplicate events?

## Common Issues

### Issue: "Data missing in relational view but exists in raw storage"

**Root cause**: Bug in QueryModel implementation

**Debug steps**:
1. Confirm data exists: `./scripts/inspect-dynamodb-raw-all`
2. Check PK/SK pattern: `./scripts/inspect-dynamodb-raw-keys`
3. Review query logic in `DynamoDbSingleTableQueryModel`

### Issue: "Foreign key constraint violation (MySQL)"

**Root cause**: Inserting child before parent, or using wrong natural key

**Debug steps**:
1. Check table schema: `./scripts/inspect-mysql-raw-schema`
2. Check existing data: `./scripts/inspect-mysql-raw-query 'SELECT * FROM user'`
3. Review insertion order in `MySqlCommandModel`

### Issue: "Email lookup not working (DynamoDB)"

**Root cause**: GSI not populated correctly

**Debug steps**:
1. Check GSI values: `./scripts/inspect-dynamodb-raw vote_data | jq '.Items[] | select(.GSI1PK)'`
2. Verify format: Should be `GSI1PK=EMAIL#alice@example.com`, `GSI1SK=USER#alice`
3. Review user creation in `DynamoDbSingleTableCommandModel.createUser()`

### Issue: "Event recorded but data not synced"

**Root cause**: Sync state not updated, or projection logic not processing events

**Debug steps**:
1. Check event exists: `./scripts/inspect-dynamodb-event-log`
2. Check sync state: `./scripts/inspect-dynamodb-sync-state`
3. Check if `lastSynced` is advancing
4. Review sync logic in `ServiceImpl`

## Storage Comparison

### DynamoDB Single-Table Design

**Philosophy**: One table, composite keys, overloaded attributes

**Structure**:
```
vote_data:
  PK (String) + SK (String)
  Attributes: entity_type, userName/electionName/etc (varies by type)
  GSI: GSI1PK + GSI1SK (for email lookup)

vote_event_log:
  event_id (Number)
  Attributes: authority, event_type, event_data, created_at
```

**Access Patterns**:
- Get user: `Query(PK=USER#alice, SK=METADATA)`
- Get user by email: `Query(email-index, GSI1PK=EMAIL#alice@example.com)`
- Get candidates: `Query(PK=ELECTION#Best, SK begins_with CANDIDATE#)`

### MySQL Multi-Table Design

**Philosophy**: Normalized relational tables, natural keys, foreign keys

**Structure**:
```
user(name PK, email, salt, hash, role)
election(name PK, owner FK, secret_ballot, allow_vote, allow_edit, ...)
candidate(election FK, name, PRIMARY KEY (election, name))
ballot(election FK, voter FK, rankings, confirmation, when_cast, PRIMARY KEY (election, voter))
event_log(event_id PK, authority, event_type, event_data, created_at)
sync_state(id PK, last_synced)
```

**Access Patterns**:
- Get user: `SELECT * FROM user WHERE name = 'alice'`
- Get user by email: `SELECT * FROM user WHERE email = 'alice@example.com'`
- Get candidates: `SELECT * FROM candidate WHERE election = 'Best Language'`

## Summary

**Debugging workflow**:
1. Start with relational projection (admin view) - understand WHAT is wrong
2. Inspect raw storage (debug view) - understand HOW it's stored
3. Compare storage vs projection - find WHERE the bug is
4. Check event log - verify source of truth

**Two views serve different purposes**:
- Relational projection: API contract, domain model, what users see
- Raw storage: Implementation details, what databases store

**Scripts provide access to both layers**:
- `inspect-*-all`: Relational projection (admin view)
- `inspect-*-raw-*`: Raw storage (debug view)
