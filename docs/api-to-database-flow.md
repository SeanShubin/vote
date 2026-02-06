# API to Database Data Flow

This document traces the complete path from HTTP API call to raw database storage, making it crystal clear what happens at each layer.

## Architecture Layers

```
HTTP Request
    ↓
SimpleHttpHandler (Jetty)
    ↓
Service (Business Logic)
    ↓
EventLog (Event Sourcing)
    ↓
CommandModel (Writes) / QueryModel (Reads)
    ↓
Raw Database Storage (MySQL or DynamoDB)
```

## Complete Example: Cast Ballot

Let's trace a user casting a ballot through all layers, showing actual code and database operations.

### 1. HTTP Request Arrives

```http
POST /election/Favorite%20Language/ballot HTTP/1.1
Host: localhost:8080
Authorization: Bearer {"userName":"alice","role":"OWNER"}
Content-Type: application/json

{
  "voterName": "alice",
  "rankings": [
    {"candidateName": "Kotlin", "rank": 1},
    {"candidateName": "Python", "rank": 2}
  ]
}
```

### 2. SimpleHttpHandler Routes Request

**File**: `backend/src/main/kotlin/com/seanshubin/vote/backend/http/SimpleHttpHandler.kt`

```kotlin
class SimpleHttpHandler(
    private val service: Service,
    private val json: Json
) : AbstractHandler() {
    override fun handle(
        target: String,
        baseRequest: Request,
        request: HttpServletRequest,
        response: HttpServletResponse
    ) {
        val method = request.method
        val path = request.pathInfo

        // Matches: POST /election/{electionName}/ballot
        if (method == "POST" && pathMatches(path, "/election/.*/ballot")) {
            val electionName = extractElectionName(path) // "Favorite Language"
            val authorization = extractAuthorization(request) // {"userName":"alice","role":"OWNER"}
            val requestBody = readRequestBody(request)
            val castBallotRequest = json.decodeFromString<CastBallotRequest>(requestBody)

            // Calls service layer
            val result = service.castBallot(authorization, castBallotRequest)

            writeJsonResponse(response, result)
        }
    }
}
```

**What happens**: Handler extracts election name from URL path, parses authorization header, deserializes JSON body, calls service method.

### 3. Service Coordinates Business Logic

**File**: `backend/src/main/kotlin/com/seanshubin/vote/backend/service/ServiceImpl.kt`

```kotlin
class ServiceImpl(
    private val integrations: Integrations,
    private val eventLog: EventLog,
    private val commandModel: CommandModel,
    private val queryModel: QueryModel
) : Service {
    override fun castBallot(
        authorization: String,
        request: CastBallotRequest
    ): CastBallotResponse {
        // 1. Parse authorization
        val accessToken = json.decodeFromString<AccessToken>(authorization)
        val authority = accessToken.userName

        // 2. Validate user can vote
        val election = queryModel.findElectionByName(request.electionName)
        require(election.allowVote) { "Voting is not allowed" }

        val isEligible = queryModel.isVoterEligible(
            request.electionName,
            request.voterName
        )
        require(isEligible) { "Voter not eligible" }

        // 3. Create domain event
        val event = VoteCast(
            voterName = request.voterName,
            electionName = request.electionName,
            rankings = request.rankings
        )

        // 4. Append to event log
        eventLog.appendEvent(authority, integrations.clock.now(), event)

        // 5. Write to command model
        commandModel.castBallot(
            authority = authority,
            voterName = request.voterName,
            electionName = request.electionName,
            rankings = request.rankings,
            confirmation = generateConfirmation(),
            whenCast = integrations.clock.now()
        )

        // 6. Return response
        return CastBallotResponse(confirmation = ballot.confirmation)
    }
}
```

**What happens**: Service validates business rules, creates domain event, writes to event log, writes to command model (actual database), returns response.

### 4. EventLog Records Event

**File**: `backend/src/main/kotlin/com/seanshubin/vote/backend/repository/DynamoDbEventLog.kt` (or MySqlEventLog.kt)

#### DynamoDB Implementation:

```kotlin
class DynamoDbEventLog(
    private val dynamoDb: DynamoDbClient,
    private val json: Json
) : EventLog {
    override fun appendEvent(authority: String, whenHappened: Instant, event: DomainEvent) {
        val eventType = event::class.simpleName  // "VoteCast"
        val eventData = json.encodeToString(event)  // JSON serialized

        val eventId = getNextEventId()  // Atomic counter: 1, 2, 3...

        dynamoDb.putItem(PutItemRequest {
            tableName = "vote_event_log"
            item = mapOf(
                "event_id" to AttributeValue.N(eventId.toString()),
                "authority" to AttributeValue.S(authority),
                "event_type" to AttributeValue.S(eventType),
                "event_data" to AttributeValue.S(eventData),
                "created_at" to AttributeValue.N(whenHappened.toEpochMilliseconds().toString())
            )
        })
    }

    private fun getNextEventId(): Long {
        // Atomic counter stored in main table
        val response = dynamoDb.updateItem(UpdateItemRequest {
            tableName = "vote_data"
            key = mapOf(
                "PK" to AttributeValue.S("METADATA"),
                "SK" to AttributeValue.S("EVENT_COUNTER")
            )
            updateExpression = "ADD next_event_id :inc"
            expressionAttributeValues = mapOf(":inc" to AttributeValue.N("1"))
            returnValues = ReturnValue.UpdatedNew
        })

        return response.attributes?.get("next_event_id")?.asN()?.toLong() ?: 1L
    }
}
```

**Raw DynamoDB Storage** (`vote_event_log` table):
```json
{
  "event_id": {"N": "42"},
  "authority": {"S": "alice"},
  "event_type": {"S": "VoteCast"},
  "event_data": {"S": "{\"voterName\":\"alice\",\"electionName\":\"Favorite Language\",\"rankings\":[{\"candidateName\":\"Kotlin\",\"rank\":1}]}"},
  "created_at": {"N": "1707253200000"}
}
```

#### MySQL Implementation:

```kotlin
class MySqlEventLog(
    private val connection: Connection,
    private val queryLoader: QueryLoader,
    private val json: Json
) : EventLog {
    override fun appendEvent(authority: String, whenHappened: Instant, event: DomainEvent) {
        val eventType = event::class.simpleName
        val eventData = json.encodeToString(event)

        // SQL: insert into event_log (authority, event_type, event_data, created_at) values (?, ?, ?, ?)
        val sql = queryLoader.load("event-insert")
        val statement = connection.prepareStatement(sql)
        statement.setString(1, authority)
        statement.setString(2, eventType)
        statement.setString(3, eventData)
        statement.setLong(4, whenHappened.toEpochMilliseconds())
        statement.executeUpdate()
    }
}
```

**Raw MySQL Storage** (`event_log` table):
```sql
event_id | authority | event_type | event_data                                                          | created_at
---------|-----------|------------|---------------------------------------------------------------------|-------------
42       | alice     | VoteCast   | {"voterName":"alice","electionName":"Favorite Language",...}        | 1707253200000
```

### 5. CommandModel Writes to Database

**File**: `backend/src/main/kotlin/com/seanshubin/vote/backend/repository/DynamoDbSingleTableCommandModel.kt`

#### DynamoDB Single-Table Implementation:

```kotlin
class DynamoDbSingleTableCommandModel(
    private val dynamoDb: DynamoDbClient,
    private val json: Json
) : CommandModel {
    override fun castBallot(
        authority: String,
        voterName: String,
        electionName: String,
        rankings: List<Ranking>,
        confirmation: String,
        whenCast: Instant
    ) {
        val rankingsJson = json.encodeToString(rankings)

        dynamoDb.putItem(PutItemRequest {
            tableName = "vote_data"
            item = mapOf(
                "PK" to AttributeValue.S("ELECTION#$electionName"),  // Partition key
                "SK" to AttributeValue.S("BALLOT#$voterName"),       // Sort key
                "entity_type" to AttributeValue.S("BALLOT"),
                "electionName" to AttributeValue.S(electionName),
                "voterName" to AttributeValue.S(voterName),
                "rankings" to AttributeValue.S(rankingsJson),
                "confirmation" to AttributeValue.S(confirmation),
                "whenCast" to AttributeValue.N(whenCast.toEpochMilliseconds().toString())
            )
        })
    }
}
```

**Raw DynamoDB Storage** (`vote_data` table):
```json
{
  "PK": {"S": "ELECTION#Favorite Language"},
  "SK": {"S": "BALLOT#alice"},
  "entity_type": {"S": "BALLOT"},
  "electionName": {"S": "Favorite Language"},
  "voterName": {"S": "alice"},
  "rankings": {"S": "[{\"candidateName\":\"Kotlin\",\"rank\":1},{\"candidateName\":\"Python\",\"rank\":2}]"},
  "confirmation": {"S": "abc123-def456"},
  "whenCast": {"N": "1707253200000"}
}
```

**Key Insight**: Notice the composite key structure:
- **PK=ELECTION#Favorite Language**: Groups all election data together (candidates, voters, ballots)
- **SK=BALLOT#alice**: Identifies this specific ballot within the election partition
- This enables efficient queries: "Get all ballots for election" = `Query where PK=ELECTION#name AND SK begins_with BALLOT#`

#### MySQL Implementation:

```kotlin
class MySqlCommandModel(
    private val connection: Connection,
    private val queryLoader: QueryLoader,
    private val json: Json
) : CommandModel {
    override fun castBallot(
        authority: String,
        voterName: String,
        electionName: String,
        rankings: List<Ranking>,
        confirmation: String,
        whenCast: Instant
    ) {
        val rankingsJson = json.encodeToString(rankings)

        // SQL from ballot-upsert.sql:
        // insert into ballot (election_name, voter_name, rankings, confirmation, when_cast)
        // values (?, ?, ?, ?, ?)
        // on duplicate key update rankings=values(rankings), confirmation=values(confirmation), when_cast=values(when_cast)
        val sql = queryLoader.load("ballot-upsert")
        val statement = connection.prepareStatement(sql)
        statement.setString(1, electionName)
        statement.setString(2, voterName)
        statement.setString(3, rankingsJson)
        statement.setString(4, confirmation)
        statement.setLong(5, whenCast.toEpochMilliseconds())
        statement.executeUpdate()
    }
}
```

**Raw MySQL Storage** (`ballot` table):
```sql
election_name        | voter_name | rankings                                                   | confirmation     | when_cast
---------------------|------------|------------------------------------------------------------|------------------|-------------
Favorite Language    | alice      | [{"candidateName":"Kotlin","rank":1},{"candidateName":"Python","rank":2}] | abc123-def456    | 1707253200000
```

**Key Insight**: Natural key primary key `(election_name, voter_name)` creates the same uniqueness constraint as DynamoDB's `(PK, SK)` but with different access patterns.

### 6. QueryModel Reads from Database

**File**: `backend/src/main/kotlin/com/seanshubin/vote/backend/repository/DynamoDbSingleTableQueryModel.kt`

#### DynamoDB Read Example:

```kotlin
class DynamoDbSingleTableQueryModel(
    private val dynamoDb: DynamoDbClient,
    private val json: Json
) : QueryModel {
    override fun searchBallot(voterName: String, electionName: String): BallotSummary? {
        val response = dynamoDb.getItem(GetItemRequest {
            tableName = "vote_data"
            key = mapOf(
                "PK" to AttributeValue.S("ELECTION#$electionName"),
                "SK" to AttributeValue.S("BALLOT#$voterName")
            )
        })

        val item = response.item ?: return null

        // Transform raw DynamoDB item to domain model
        return BallotSummary(
            voterName = item["voterName"]?.asS() ?: error("Missing voterName"),
            electionName = item["electionName"]?.asS() ?: error("Missing electionName"),
            rankings = json.decodeFromString(item["rankings"]?.asS() ?: "[]"),
            confirmation = item["confirmation"]?.asS() ?: error("Missing confirmation"),
            whenCast = Instant.fromEpochMilliseconds(
                item["whenCast"]?.asN()?.toLong() ?: error("Missing whenCast")
            )
        )
    }
}
```

**What happens**:
1. Constructs composite key from natural keys: `PK=ELECTION#Favorite Language, SK=BALLOT#alice`
2. Performs O(1) GetItem operation
3. Transforms raw DynamoDB attributes to domain model with natural keys
4. Returns `BallotSummary` - no PK/SK visible to caller!

#### MySQL Read Example:

```kotlin
class MySqlQueryModel(
    private val connection: Connection,
    private val queryLoader: QueryLoader,
    private val json: Json
) : QueryModel {
    override fun searchBallot(voterName: String, electionName: String): BallotSummary? {
        // SQL from ballot-select-by-election-and-voter.sql:
        // select election_name, voter_name, rankings, confirmation, when_cast
        // from ballot
        // where election_name = ? and voter_name = ?
        val sql = queryLoader.load("ballot-select-by-election-and-voter")
        val statement = connection.prepareStatement(sql)
        statement.setString(1, electionName)
        statement.setString(2, voterName)
        val resultSet = statement.executeQuery()

        return if (resultSet.next()) {
            BallotSummary(
                voterName = resultSet.getString("voter_name"),
                electionName = resultSet.getString("election_name"),
                rankings = json.decodeFromString(resultSet.getString("rankings")),
                confirmation = resultSet.getString("confirmation"),
                whenCast = Instant.fromEpochMilliseconds(resultSet.getLong("when_cast"))
            )
        } else {
            null
        }
    }
}
```

**What happens**:
1. Loads SQL query from resource file
2. Binds natural key parameters
3. Executes query against relational table
4. Transforms result set to domain model
5. Returns same `BallotSummary` structure as DynamoDB

**Key Insight**: Both implementations return identical `BallotSummary` objects with natural keys. The caller (Service layer) has no idea which database is being used!

## Relational Projection Layer

The **QueryModel interface** defines the relational projection:

```kotlin
interface QueryModel {
    fun searchBallot(voterName: String, electionName: String): BallotSummary?
    fun listBallots(electionName: String): List<RevealedBallot>
    fun findUserByName(name: String): User?
    fun findElectionByName(name: String): Election?
}
```

**All methods use natural keys:**
- `voterName` not `BALLOT#voterName`
- `electionName` not `ELECTION#electionName`
- No PK/SK, no composite keys, no implementation details

**This is the abstraction layer** that lets admins debug from the conceptual model even though production uses optimized composite keys.

## Debugging Workflow

### Step 1: Admin View (Relational Projection)

Run inspection script (uses QueryModel):

```bash
./scripts/inspect-dynamodb-ballots 'Favorite Language'
```

Output (relational projection):
```
Found 2 ballot(s) for election: Favorite Language

Voter:        alice
Rankings:     [{"candidateName":"Kotlin","rank":1},{"candidateName":"Python","rank":2}]
Confirmation: abc123-def456
When Cast:    1707253200000 (epoch ms)

Voter:        bob
Rankings:     [{"candidateName":"Python","rank":1}]
Confirmation: xyz789-ghi012
When Cast:    1707253300000 (epoch ms)
```

**What you see**: Natural keys, human-readable election and voter names.

### Step 2: Raw Database View (Mechanical)

Run raw inspection script (bypasses QueryModel):

```bash
./scripts/inspect-dynamodb-raw-keys
```

Output (actual storage):
```
PK                                  SK                                  Entity Type
=================================== =================================== ============
ELECTION#Favorite Language          BALLOT#alice                        BALLOT
ELECTION#Favorite Language          BALLOT#bob                          BALLOT
ELECTION#Favorite Language          CANDIDATE#Kotlin                    CANDIDATE
ELECTION#Favorite Language          CANDIDATE#Python                    CANDIDATE
ELECTION#Favorite Language          METADATA                            ELECTION
```

**What you see**: Composite keys (PK/SK), actual storage structure, access patterns.

### Step 3: Compare and Debug

**Relational projection wrong?** → Bug in QueryModel implementation
**Raw storage wrong?** → Bug in CommandModel implementation
**Both correct?** → Bug in business logic (Service layer)

## Summary: Complete Data Flow

```
1. HTTP POST /election/Favorite%20Language/ballot
   ↓
2. SimpleHttpHandler.handle()
   - Extracts: electionName="Favorite Language", voterName="alice"
   - Deserializes: rankings=[{Kotlin,1}, {Python,2}]
   ↓
3. ServiceImpl.castBallot(authorization, request)
   - Validates business rules (eligible voter, voting allowed)
   - Creates domain event: VoteCast(...)
   ↓
4. EventLog.appendEvent(authority, now, event)
   DynamoDB: INSERT into vote_event_log (event_id=42, event_data=..., ...)
   MySQL:    INSERT into event_log (event_id, event_data, ...)
   ↓
5. CommandModel.castBallot(authority, voterName, electionName, rankings, ...)
   DynamoDB: PUT item with PK=ELECTION#Favorite Language, SK=BALLOT#alice
   MySQL:    INSERT/UPDATE ballot (election_name, voter_name, rankings, ...)
   ↓
6. Return CastBallotResponse(confirmation="abc123-def456")
   ↓
7. HTTP 200 OK with JSON response
```

**Raw Database State After Operation:**

DynamoDB `vote_data` table:
```
PK: "ELECTION#Favorite Language"
SK: "BALLOT#alice"
electionName: "Favorite Language"
voterName: "alice"
rankings: "[{\"candidateName\":\"Kotlin\",\"rank\":1}]"
```

MySQL `ballot` table:
```
election_name: "Favorite Language"
voter_name: "alice"
rankings: "[{\"candidateName\":\"Kotlin\",\"rank\":1}]"
```

**Both represent the same conceptual data**, just stored differently!

## Key Takeaways for Newcomers

1. **HTTP layer** (SimpleHttpHandler) routes requests and deserializes JSON
2. **Service layer** (ServiceImpl) enforces business rules and coordinates operations
3. **Event layer** (EventLog) records what happened for audit/replay
4. **Command layer** (CommandModel) writes actual database records
5. **Query layer** (QueryModel) reads and projects as relational views
6. **Database layer** stores raw data (MySQL relational, DynamoDB composite keys)

**The magic**: QueryModel provides relational projection regardless of storage implementation. Tests, admin tools, and debugging all work with natural keys. Only the repository implementations know about PK/SK or SQL joins.
