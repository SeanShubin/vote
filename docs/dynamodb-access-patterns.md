# DynamoDB Access Pattern Analysis

## User-Facing Operations (Priority for Optimization)

### Critical Path (Very High Priority)

#### 1. **authenticate** (Login)
- **Frequency**: Every user session
- **Input**: nameOrEmail (could be username OR email), password
- **Queries**:
  - `searchUserByName(nameOrEmail)` - Try username first
  - `findUserByEmail(nameOrEmail)` - Fallback to email if not found
- **Access Pattern**: Single item lookup by username OR email
- **DynamoDB Requirement**:
  - Primary lookup by username (PK)
  - GSI for lookup by email

#### 2. **castBallot** (Core Functionality)
- **Frequency**: High during active voting
- **Input**: voterName, electionName, rankings
- **Queries**: None directly, but writes ballot data
- **Write Pattern**: Store ballot with composite key
- **DynamoDB Requirement**:
  - Store: PK=ELECTION#{name}, SK=BALLOT#{voter}
  - Must support overwrite (re-casting ballot)

#### 3. **getBallot** (Check My Vote)
- **Frequency**: High (users verify their vote)
- **Input**: voterName, electionName
- **Query**: `searchBallot(voterName, electionName)`
- **Access Pattern**: Lookup ballot by election + voter
- **DynamoDB Requirement**:
  - Query: PK=ELECTION#{name}, SK=BALLOT#{voter}

#### 4. **tally** (Calculate Results)
- **Frequency**: High during/after voting
- **Input**: electionName
- **Query**: `listBallots(electionName)` - All ballots for election
- **Access Pattern**: Query all ballots for an election
- **DynamoDB Requirement**:
  - Query: PK=ELECTION#{name}, SK begins_with BALLOT#

### High Priority

#### 5. **register** (New User)
- **Frequency**: One-time per user, but critical for UX
- **Input**: userName, email, password
- **Queries**:
  - `searchUserByName(userName)` - Check username available
  - `searchUserByEmail(email)` - Check email available
  - `userCount()` - Determine if first user (becomes OWNER)
- **Access Pattern**: Single item lookup by username OR email
- **DynamoDB Requirement**:
  - Check existence: PK=USER#{name}
  - GSI for email uniqueness check
  - Count could be tracked in metadata item

#### 6. **listElections** (Browse Elections)
- **Frequency**: High (landing page, navigation)
- **Query**: `listElections()` - All elections
- **Access Pattern**: List all elections
- **DynamoDB Requirement**:
  - Query all items where PK begins_with ELECTION#

#### 7. **listCandidates** (View Election Candidates)
- **Frequency**: High (viewing election details)
- **Input**: electionName
- **Query**: `listCandidates(electionName)`
- **Access Pattern**: Query all candidates for an election
- **DynamoDB Requirement**:
  - Query: PK=ELECTION#{name}, SK begins_with CANDIDATE#

### Medium Priority

#### 8. **listRankings** (View Rankings)
- **Frequency**: Medium (analyzing ballots)
- **Input**: electionName (all ballots) OR voterName + electionName (single ballot)
- **Queries**:
  - `listRankings(voterName, electionName)` - Single ballot's rankings
  - `listRankings(electionName)` - All rankings for election
- **Access Pattern**: Either single ballot or all ballots for election
- **DynamoDB Requirement**:
  - Ballots stored with rankings embedded as JSON
  - Query: PK=ELECTION#{name}, SK=BALLOT#{voter} for single
  - Query: PK=ELECTION#{name}, SK begins_with BALLOT# for all

#### 9. **listEligibility** (Check Voter Eligibility)
- **Frequency**: Medium (admin managing elections)
- **Input**: electionName
- **Queries**:
  - `listVotersForElection(electionName)` - Eligible voters
  - `listUserNames()` - All users (to show who's not eligible)
- **Access Pattern**: Query all eligible voters for election, all users
- **DynamoDB Requirement**:
  - Query: PK=ELECTION#{name}, SK begins_with VOTER#
  - Query all users: PK begins_with USER#

#### 10. **setCandidates** (Setup Election)
- **Frequency**: Low (once per election setup)
- **Input**: electionName, candidateNames
- **Query**: `listCandidates(electionName)` - To compute diff
- **Access Pattern**: Query candidates, then add/remove
- **DynamoDB Requirement**:
  - Query: PK=ELECTION#{name}, SK begins_with CANDIDATE#
  - Write: PK=ELECTION#{name}, SK=CANDIDATE#{name}

#### 11. **setEligibleVoters** (Manage Voters)
- **Frequency**: Low (election setup)
- **Input**: electionName, voterNames
- **Query**: `listVotersForElection(electionName)` - To compute diff
- **Access Pattern**: Query voters, then add/remove
- **DynamoDB Requirement**:
  - Query: PK=ELECTION#{name}, SK begins_with VOTER#
  - Write: PK=ELECTION#{name}, SK=VOTER#{name}

#### 12. **requireIsElectionOwner** (Authorization Check)
- **Frequency**: Called by many operations
- **Input**: electionName
- **Query**: `searchElectionByName(electionName)` - Get owner
- **Access Pattern**: Single election lookup
- **DynamoDB Requirement**:
  - Query: PK=ELECTION#{name}, SK=METADATA

### Low Priority (User Operations, but Infrequent)

- **refresh**: `findUserByName(userName)` - Token refresh
- **authenticateWithToken**: `findUserByName(userName)` - Token validation
- **getUser**: `findUserByName(userName)` - View profile
- **updateUser**: Email/name changes (rare)
- **changePassword**: Password changes (rare)
- **addElection**: Create election (once per election)
- **launchElection**: Start voting (once per election)
- **finalizeElection**: End voting (once per election)
- **deleteElection**: Remove election (rare)

## Admin/Debug Operations (Excluded from Optimization)

These operations are for administrative/debugging purposes and don't need to be optimized:

- `listUsers()` - Admin user management
- `userCount()`, `electionCount()`, `tableCount()` - Metrics
- `eventCount()` - Debug
- `tableData()`, `debugTableData()`, `eventData()` - Debug endpoints
- `listTables()` - Debug endpoint

## Summary: Top 5 Access Patterns to Optimize

1. **Lookup user by username OR email** (authenticate, register)
   - Primary: PK=USER#{name}
   - GSI: email → USER#{name}

2. **Query all ballots for election** (tally, critical for results)
   - Query: PK=ELECTION#{name}, SK begins_with BALLOT#

3. **Lookup ballot by election + voter** (getBallot, users checking vote)
   - Query: PK=ELECTION#{name}, SK=BALLOT#{voter}

4. **Query all candidates for election** (listCandidates, viewing election)
   - Query: PK=ELECTION#{name}, SK begins_with CANDIDATE#

5. **List all elections** (listElections, browsing)
   - Query: PK begins_with ELECTION#, SK=METADATA

## Access Pattern Frequency Estimate

Assuming 1000 users, 10 elections, average 50 votes per election:

| Operation | Estimated Requests/Day | Priority |
|-----------|----------------------|----------|
| authenticate | 3000 (3/user/day) | CRITICAL |
| getBallot | 500 (users checking) | CRITICAL |
| listElections | 2000 (browsing) | HIGH |
| listCandidates | 1500 (viewing details) | HIGH |
| castBallot | 500 (once per user per election) | CRITICAL |
| tally | 100 (calculated frequently) | HIGH |
| listEligibility | 50 (admin) | MEDIUM |
| register | 10 (new users) | HIGH (but low volume) |
| setCandidates | 10 (election setup) | MEDIUM |
| setEligibleVoters | 10 (election setup) | MEDIUM |

**Conclusion**: Design must optimize for:
1. User lookups (by username and email)
2. Election-scoped queries (ballots, candidates, voters)
3. Single-item lookups (specific ballot, specific election)
