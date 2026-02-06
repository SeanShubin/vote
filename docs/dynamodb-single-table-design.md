# DynamoDB Single-Table Design

## Design Principles

1. **Single table** for all entities (users, elections, candidates, voters, ballots, events)
2. **Composite keys** (PK + SK) to model relationships and enable efficient queries
3. **GSI for alternate access patterns** (e.g., lookup user by email)
4. **Natural keys** exposed through QueryModel interface (implementation detail hidden)
5. **Event log** stored separately in dedicated table (append-only, different access pattern)

## Table Structure

### Main Table: `vote_data`

**Primary Key**:
- **PK** (Partition Key, String): Entity type + identifier
- **SK** (Sort Key, String): Sub-entity type + identifier or METADATA

**GSI-1** (Global Secondary Index for email lookup):
- **GSI1PK** (String): Email (for user lookup by email)
- **GSI1SK** (String): USER#{userName}

**Attributes**:
- Various entity-specific attributes stored as JSON or individual attributes

## Entity Patterns

### 1. Users

#### User Item (Main Data)
```
PK: USER#{userName}
SK: METADATA
Attributes:
  - entity_type: "USER"
  - name: "alice"
  - email: "alice@example.com"
  - salt: "..."
  - hash: "..."
  - role: "OWNER"

GSI1PK: alice@example.com
GSI1SK: USER#alice
```

**Access Patterns**:
- Lookup by username: `GetItem(PK=USER#{name}, SK=METADATA)`
- Lookup by email: `Query GSI-1 where GSI1PK={email}`
- List all users: `Query where PK begins_with USER# AND SK=METADATA`
- Check username exists: `GetItem(PK=USER#{name}, SK=METADATA)` returns null or item
- Count users: Scan with COUNT (acceptable for admin operations)

### 2. Elections

#### Election Item (Main Data)
```
PK: ELECTION#{electionName}
SK: METADATA
Attributes:
  - entity_type: "ELECTION"
  - name: "Favorite Language"
  - owner_name: "alice"
  - secret_ballot: true/false
  - no_voting_before: timestamp (nullable)
  - no_voting_after: timestamp (nullable)
  - allow_edit: true/false
  - allow_vote: true/false
```

**Access Patterns**:
- Lookup by name: `GetItem(PK=ELECTION#{name}, SK=METADATA)`
- List all elections: `Query where PK begins_with ELECTION# AND SK=METADATA`
- Count elections: Scan with COUNT (acceptable for admin)

### 3. Candidates

#### Candidate Item
```
PK: ELECTION#{electionName}
SK: CANDIDATE#{candidateName}
Attributes:
  - entity_type: "CANDIDATE"
  - election_name: "Favorite Language"
  - candidate_name: "Kotlin"
```

**Access Patterns**:
- List candidates for election: `Query(PK=ELECTION#{name}, SK begins_with CANDIDATE#)`
- Check candidate exists: `GetItem(PK=ELECTION#{name}, SK=CANDIDATE#{candidateName})`
- Add candidate: `PutItem` with above keys
- Remove candidate: `DeleteItem` with above keys

### 4. Eligible Voters

#### Eligible Voter Item
```
PK: ELECTION#{electionName}
SK: VOTER#{voterName}
Attributes:
  - entity_type: "VOTER"
  - election_name: "Favorite Language"
  - voter_name: "alice"
```

**Access Patterns**:
- List voters for election: `Query(PK=ELECTION#{name}, SK begins_with VOTER#)`
- Check eligibility: `GetItem(PK=ELECTION#{name}, SK=VOTER#{voterName})`
- Add voter: `PutItem` with above keys
- Remove voter: `DeleteItem` with above keys

### 5. Ballots

#### Ballot Item (with Rankings Embedded)
```
PK: ELECTION#{electionName}
SK: BALLOT#{voterName}
Attributes:
  - entity_type: "BALLOT"
  - election_name: "Favorite Language"
  - voter_name: "alice"
  - confirmation: "uuid-..."
  - when_cast: timestamp
  - rankings: [
      {"candidate_name": "Kotlin", "rank": 1},
      {"candidate_name": "Python", "rank": 2},
      {"candidate_name": "Rust", "rank": 3},
      {"candidate_name": "Java", "rank": 4}
    ]
```

**Access Patterns**:
- Get ballot by voter + election: `GetItem(PK=ELECTION#{name}, SK=BALLOT#{voter})`
- List all ballots for election: `Query(PK=ELECTION#{name}, SK begins_with BALLOT#)`
- Cast ballot (overwrite): `PutItem` with above keys (overwrites existing)
- List rankings for election: Query ballots, extract rankings from embedded JSON

**Rationale for Embedded Rankings**: Rankings are always accessed together with the ballot. Denormalizing them into the ballot item eliminates the need for a separate candidates table and simplifies queries. The tally operation needs all ballots for an election anyway, so having rankings embedded is optimal.

### 6. Metadata (for Counts)

#### Metadata Item
```
PK: METADATA
SK: COUNTS
Attributes:
  - user_count: 42
  - election_count: 10
  - last_updated: timestamp
```

**Access Patterns**:
- Get counts: `GetItem(PK=METADATA, SK=COUNTS)`
- Update counts: Increment via UpdateItem (eventual consistency acceptable)

**Alternative**: Could compute counts on-demand via Query with COUNT for precise accuracy. Caching counts is optional optimization.

## Event Log (Separate Table)

### Event Table: `vote_event_log`

**Primary Key**:
- **PK**: Always "EVENTS" (single partition for ordered append)
- **SK**: `{sequence}#{timestamp}` (e.g., "00000042#2025-01-15T10:30:00Z")

```
PK: EVENTS
SK: {sequence}#{timestamp}
Attributes:
  - event_id: 42
  - actor: "alice"
  - when_occurred: timestamp
  - event_type: "BallotCast"
  - event_data: {...} (JSON)
```

**Rationale for Separate Table**: Event log has different access pattern (append-only, sequential reads, rarely queried). Keeping it separate from main data table prevents hot partition issues and simplifies main table design.

**Access Patterns**:
- Append event: `PutItem` with next sequence number
- Read events since last sync: `Query(PK=EVENTS, SK >= {lastSeq})`
- Count events: `Query with COUNT`

## Sync State (Separate or Embedded)

### Option A: Separate Table `vote_sync_state`
```
PK: SYNC#{key}
SK: METADATA
Attributes:
  - key: "last_event_id"
  - value: 42
```

### Option B: Embedded in Main Table
```
PK: METADATA
SK: SYNC
Attributes:
  - last_event_id: 42
```

**Recommendation**: Option B (embedded) since sync state is single metadata item.

## Query Examples

### Authenticate User
```kotlin
// Try username first
val userItem = dynamoDb.getItem {
    tableName = "vote_data"
    key = mapOf(
        "PK" to AttributeValue.S("USER#alice"),
        "SK" to AttributeValue.S("METADATA")
    )
}

// If not found, try email via GSI
if (userItem.item == null) {
    val emailResult = dynamoDb.query {
        tableName = "vote_data"
        indexName = "GSI-1"
        keyConditionExpression = "GSI1PK = :email"
        expressionAttributeValues = mapOf(
            ":email" to AttributeValue.S("alice@example.com")
        )
    }
}
```

### Cast Ballot
```kotlin
dynamoDb.putItem {
    tableName = "vote_data"
    item = mapOf(
        "PK" to AttributeValue.S("ELECTION#Favorite Language"),
        "SK" to AttributeValue.S("BALLOT#alice"),
        "entity_type" to AttributeValue.S("BALLOT"),
        "election_name" to AttributeValue.S("Favorite Language"),
        "voter_name" to AttributeValue.S("alice"),
        "confirmation" to AttributeValue.S(uuid),
        "when_cast" to AttributeValue.N(timestamp),
        "rankings" to AttributeValue.S(rankingsJson)  // JSON string
    )
}
```

### Tally Election
```kotlin
val ballotsResult = dynamoDb.query {
    tableName = "vote_data"
    keyConditionExpression = "PK = :pk AND begins_with(SK, :sk)"
    expressionAttributeValues = mapOf(
        ":pk" to AttributeValue.S("ELECTION#Favorite Language"),
        ":sk" to AttributeValue.S("BALLOT#")
    )
}

// Extract rankings from each ballot item
val allRankings = ballotsResult.items.flatMap { item ->
    val rankingsJson = item["rankings"]?.asS()
    Json.decodeFromString<List<Ranking>>(rankingsJson)
}
```

### List Candidates
```kotlin
val candidatesResult = dynamoDb.query {
    tableName = "vote_data"
    keyConditionExpression = "PK = :pk AND begins_with(SK, :sk)"
    expressionAttributeValues = mapOf(
        ":pk" to AttributeValue.S("ELECTION#Favorite Language"),
        ":sk" to AttributeValue.S("CANDIDATE#")
    )
}

val candidates = candidatesResult.items.map { it["candidate_name"]?.asS()!! }
```

## Advantages of This Design

1. **Single table** - Reduces operational complexity, enables transactions across entities
2. **Natural sharding** - Elections partition data naturally (each election's candidates/voters/ballots in same partition)
3. **Efficient queries** - All hot paths use Query (not Scan), leverage sort key conditions
4. **Minimal GSIs** - Only one GSI needed (email lookup), reduces write costs
5. **Embedded rankings** - Eliminates need for separate rankings table, simplifies tally operation
6. **Maintains relational projection** - QueryModel interface still exposes natural keys, implementation detail hidden

## Trade-offs

1. **Election name changes** - Would require updating many items (ballots, candidates, voters). Mitigation: Disallow election name changes, or use surrogate keys internally.
2. **Hot partitions** - Popular elections could create hot partitions. Mitigation: DynamoDB auto-scales, real-world voting is time-bounded.
3. **GSI costs** - Every user write updates GSI. Mitigation: Only one GSI (email), acceptable cost.
4. **Item size limits** - Ballots with many rankings could approach 400KB limit. Mitigation: Unlikely with ranked-choice voting (typically <20 candidates).

## Implementation Status

Single-table design is now the production DynamoDB implementation:

1. ✅ **Implemented**: DynamoDbSingleTableQueryModel and DynamoDbSingleTableCommandModel
2. ✅ **Tested**: Existing tests run against single-table implementation
3. ✅ **Consolidated**: Three-backend architecture (InMemory, MySQL, DynamoDB single-table)
4. ✅ **Deployed**: Main branch uses single-table design

The system now demonstrates both relational (MySQL) and NoSQL (DynamoDB) approaches, unified behind the QueryModel interface.

## Relational Projection Layer (QueryModel Interface)

The QueryModel interface already provides the relational projection:

```kotlin
interface QueryModel {
    fun findUserByName(name: String): User  // Natural key!
    fun searchBallot(voterName: String, electionName: String): BallotSummary?  // Natural keys!
    fun listBallots(electionName: String): List<RevealedBallot>
    // ... all methods use natural keys
}
```

**Critical Insight**: The implementation (single-table DynamoDB with composite PK/SK) is hidden behind this interface. Tests and admin tools work with natural keys (election names, user names) regardless of how data is stored internally.

This fulfills the architectural vision: "Write tests against the relational model. The implementation is in charge of maintaining the data, not necessarily relational, but it is still responsible for providing relational projections."
