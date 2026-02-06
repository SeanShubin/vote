# DynamoDB Single-Table Design - Executive Summary

## Question Answered

**"Do you have enough information to create a prioritized list of access patterns in order to properly design the dynamo db schema?"**

**Answer: Yes.** The codebase provides complete information about user-facing operations and their query patterns.

## Key Findings

### Top 5 Critical Access Patterns (Must Optimize)

1. **User Authentication** (3000 req/day estimated)
   - Lookup by username OR email
   - Must be fast: users logging in

2. **Tally Election** (100 req/day, but critical for results)
   - Query all ballots for an election
   - Must be efficient: affects result calculation

3. **Get Ballot** (500 req/day)
   - Lookup single ballot by election + voter
   - Users checking their vote

4. **List Candidates** (1500 req/day)
   - Query all candidates for an election
   - Users viewing election details

5. **List Elections** (2000 req/day)
   - Browse all available elections
   - Landing page functionality

### Design Decision: Single-Table with Natural Sharding

**Main Table Structure**:
```
PK (Partition Key): Entity type + identifier
SK (Sort Key): Sub-entity type + identifier or METADATA
```

**Key Insight**: Elections naturally partition the data. Each election's candidates, voters, and ballots live in the same partition, enabling efficient queries.

**Examples**:
- User: `PK=USER#alice, SK=METADATA`
- Election: `PK=ELECTION#Favorite Language, SK=METADATA`
- Candidate: `PK=ELECTION#Favorite Language, SK=CANDIDATE#Kotlin`
- Ballot: `PK=ELECTION#Favorite Language, SK=BALLOT#alice`
- Eligible Voter: `PK=ELECTION#Favorite Language, SK=VOTER#alice`

**GSI-1** (only one GSI needed):
- `GSI1PK=email, GSI1SK=USER#name` for user lookup by email

## How This Fulfills the Architectural Vision

### The Vision (from user):

> "The idea is that the tests will still work even if the underlying database is not relational, what I am testing is a relational projection of data. The rationale is that admins debugging the application need to understand it from the relational model first anyways, even if that does not match the implementation. The implementation is in charge of maintaining the data, not necessarily relational, but it is still responsible for providing relational projections, but they are not required to be optimized for speed."

### How Single-Table Design Delivers This:

1. **QueryModel Interface = Relational Projection Layer**
   ```kotlin
   interface QueryModel {
       fun findUserByName(name: String): User  // Natural key!
       fun searchBallot(voterName: String, electionName: String): BallotSummary?
       fun listCandidates(electionName: String): List<String>
   }
   ```
   - All methods use **natural keys** (names, not DynamoDB composite keys)
   - Tests and admin tools work with human-readable identifiers
   - Implementation details (PK/SK patterns) completely hidden

2. **Implementation Optimized for Production**
   - Single table with efficient query patterns
   - Minimal GSIs (only one needed)
   - Natural data sharding by election
   - All hot paths use Query (not Scan)

3. **Tests Remain Unchanged**
   - Existing tests work with QueryModel interface
   - Tests verify behavior using natural keys
   - Swapping multi-table → single-table implementation requires zero test changes
   - **This proves the abstraction works!**

4. **Admin Tools Work the Same Way**
   - Inspection scripts call QueryModel methods
   - Admin sees "election name", "voter name", "candidate name"
   - No knowledge of internal PK/SK structure required

## Three-Backend Architecture

The system supports three database backends, each demonstrating different data modeling approaches:

### 1. InMemory (Testing)
- Map-based storage in memory
- No dependencies, fast setup
- Used for unit tests and quick development

### 2. MySQL (Relational Model)
- Traditional relational tables with foreign keys
- Natural key primary keys (`user(name)`, `election(name)`)
- Normalized design with explicit relationships
- Educational comparison to NoSQL approach

### 3. DynamoDB Single-Table (Production NoSQL)
- `vote_data` - Main table (users, elections, candidates, voters, ballots)
- `vote_event_log` - Separate (append-only, different access pattern)
- Sync state embedded in `vote_data` as metadata (PK=METADATA, SK=SYNC)

**Why Single-Table DynamoDB?**
- Idiomatic DynamoDB (follows AWS best practices)
- Efficient query patterns (fewer round-trips, better use of partition keys)
- Enables transactions across entities (election + candidates in one partition)
- Production-ready design

**Why Keep Event Log Separate?**
- Different access pattern (append-only, sequential reads)
- Prevents hot partition issues
- Event sourcing is architectural concern, not just data storage

**All Three Share Same QueryModel Interface**
- Tests and admin tools use relational projections
- Swapping backends requires zero code changes outside repository layer
- This proves the abstraction layer works!

## Admin/Debug Relational Projections

The `debug-*.sql` pattern from the old Condorcet project can be replicated:

### Old Relational Projection (SQL):
```sql
-- debug-ballot.sql
select ballot.id,
       user.name     user,
       election.name election,
       ballot.confirmation,
       ballot.when_cast
from ballot
inner join user on ballot.user_id = user.id
inner join election on ballot.election_id = election.id
order by ballot.id
```

### New Relational Projection (QueryModel):
```kotlin
interface QueryModel {
    // Equivalent to debug-ballot.sql
    fun listBallots(electionName: String): List<RevealedBallot>

    // RevealedBallot uses natural keys:
    data class RevealedBallot(
        val voterName: String,      // Not voter_id!
        val electionName: String,   // Not election_id!
        val confirmation: String,
        val whenCast: Instant,
        val rankings: List<Ranking>
    )
}
```

**Key Insight**: The QueryModel method returns the same human-readable view as the SQL debug query, but implementation can be anything (relational, single-table DynamoDB, document store, etc.).

## Next Steps

1. **Review and validate** this design with stakeholders
2. **Implement** DynamoDbQueryModel using single-table design
3. **Run existing tests** unchanged against new implementation
4. **Verify** tests pass (proving abstraction works)
5. **Compare performance** multi-table vs single-table (educational)
6. **Document** lessons learned for educational goals

## Files Created

1. **dynamodb-access-patterns.md** - Detailed analysis of all user operations, query patterns, and frequency estimates
2. **dynamodb-single-table-design.md** - Complete schema design with entity patterns, query examples, and trade-offs
3. **dynamodb-design-summary.md** (this file) - Executive summary connecting access patterns to design decisions

## Validation

**Question**: "Only user access patterns need to be prioritized, admin/debug patterns naturally don't make the priority list."

**Confirmed**: Analysis explicitly separates:
- **User-facing operations** (authenticate, castBallot, tally, etc.) - optimized in single-table design
- **Admin/debug operations** (listUsers, userCount, tableData, etc.) - excluded from optimization, can use Scan if needed

The single-table design optimizes the top 5 critical paths while maintaining the relational projection layer for admin/debug use.
