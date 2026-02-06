Your Architectural Insight:

You want to separate:

1. Conceptual Model (Relational, Natural Keys)
   - What admins see for debugging
   - What tests are written against
   - Easy to understand
   - Uses natural keys: election.name, user.name, not surrogate IDs
2. Implementation Model (Optimized for Production)
   - Single-table DynamoDB with composite keys
   - Perfectly optimized for production queries
   - Complex, but hidden from consumers
3. The Contract: Implementation must provide relational projections
   - Even if stored as PK: ELECTION#lang, SK: BALLOT#alice
   - Must project as: election_name=lang, voter_name=alice, rankings=[...]
   - Doesn't need to be fast - it's for debugging/testing

What Your Debug Queries Show:

Looking at debug-ranking.sql:
select election.name  election,
user.name      voter,
candidate.name candidate,
ranking.rank,
ballot.confirmation
from ranking
inner join ballot on ranking.ballot_id = ballot.id
inner join election on ballot.election_id = election.id
-- etc, ordered by election.name, user.name, rank

This is the relational projection - human-readable, uses natural keys, shows relationships clearly.

You Already Have This Architecture!

Your QueryModel interface IS the relational projection layer:

interface QueryModel {
fun listBallots(electionName: String): List<RevealedBallot>  // Natural key!
fun searchBallot(voterName: String, electionName: String): BallotSummary?
fun listRankings(electionName: String): List<VoterElectionCandidateRank>
}

These return relational views using natural keys, not implementation details.

The Power Move:

1. Refactor DynamoDB to single-table:
   - Store as: PK: ELECTION#Favorite Language, SK: BALLOT#alice
   - GSI: GSI1PK: USER#alice, GSI1SK: BALLOT#Favorite Language
   - Optimized for production access patterns
2. DynamoDbQueryModel transforms to relational projections:
   override fun listRankings(electionName: String): List<VoterElectionCandidateRank> {
   // Query single table: PK starts with "ELECTION#$electionName"
   // Transform composite keys back to natural keys
   // Return relational projection
   }
3. Tests work against ANY backend:
   @Test
   fun `admin can see all ballots for election`() {
   val ballots = queryModel.listBallots("Favorite Language")
   assertEquals(2, ballots.size)
   assertEquals("alice", ballots[0].voterName)  // Natural key!
   }
4. Admin UI shows relational projections:
   - Table of ballots showing election name, voter name, rankings
   - Join-like views (voter → elections → ballots)
   - Easy to understand, debug, verify correctness

The Beauty:

- Developers write tests against simple relational projections
- Admins debug from conceptual model first
- Implementation uses single-table DynamoDB for cost/performance
- Abstraction hides complexity from consumers

Your current multi-table DynamoDB is actually the intermediate step - it proves the abstraction works. The next evolution is single-table DynamoDB with the same QueryModel interface.

Should We Refactor to Single-Table?

Given your educational goals: YES! This would demonstrate:
- ✅ Relational simplicity (QueryModel interface, tests, admin UI)
- ✅ Production optimization (single-table DynamoDB)
- ✅ Clean abstraction (implementation complexity hidden)