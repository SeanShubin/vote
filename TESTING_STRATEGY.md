# Testing Strategy

This document describes the comprehensive testing strategy for the Vote application. The testing architecture consists of three complementary test categories, each serving a distinct purpose.

## Overview

The testing strategy is designed to ensure correctness at multiple levels:

1. **Business Logic Tests** - Verify domain behavior against database contract, no real integrations
2. **Smoke Tests** - Verify database implementations behave identically (InMemory, MySQL, DynamoDB)
3. **HTTP Boundary Tests** - Verify HTTP communication layer (headers, tokens, serialization)

## Test Infrastructure

### Core Components

#### TestContext (`integration/src/test/kotlin/.../dsl/TestContext.kt`)

The main entry point for the test DSL. Provides:

```kotlin
val context = TestContext(
    provider = databaseProvider,  // Optional: InMemory (default), MySQL, or DynamoDB
    backend = scenarioBackend     // Optional: DirectService (default) or HTTP
)
```

**Key features:**
- **Fake Integrations**: All boundary crossings (clock, uniqueIdGenerator, notifications, passwordUtil) use fake implementations
- **Database Abstraction**: Optional DatabaseProvider allows testing against different storage backends
- **Backend Abstraction**: Can use direct service calls or HTTP calls without changing test code
- **Inspectors**: EventInspector and DatabaseInspector for verification
- **Owner Token Management**: First registered user automatically becomes OWNER

**Example usage:**
```kotlin
val alice = context.registerUser("alice", "alice@example.com", "password")
val election = alice.createElection("Best Language")
election.setCandidates("Kotlin", "Rust", "Go")
election.launch(allowEdit = true)

// Verify events
context.events.assertEvent<UserRegistered>()

// Verify database state
val users = context.database.allUsers()
assertEquals(1, users.size)
```

#### DatabaseProvider (`integration/src/test/kotlin/.../database/DatabaseProvider.kt`)

Interface abstracting storage implementations:

```kotlin
interface DatabaseProvider : AutoCloseable {
    val name: String
    val eventLog: EventLog
    val commandModel: CommandModel
    val queryModel: QueryModel
}
```

**Implementations:**
- `InMemoryDatabaseProvider` - Default, no external dependencies
- `MySQLDatabaseProvider` - Integrates with real MySQL database
- `DynamoDBDatabaseProvider` - Integrates with real DynamoDB

#### ScenarioBackend (`integration/src/test/kotlin/.../dsl/ScenarioBackend.kt`)

Abstraction over how API calls are made:

- `DirectServiceBackend` - Calls ServiceImpl directly (for business logic tests)
- `HttpBackend` - Makes HTTP requests (for HTTP boundary tests)

#### Scenario (`integration/src/test/kotlin/.../scenario/Scenario.kt`)

Comprehensive happy-path scenario that exercises **every Service API operation**:

```kotlin
object Scenario {
    fun comprehensive(context: TestContext) {
        // Exercises ALL 40+ Service methods
        // - User registration, authentication, profile management, role changes
        // - Election creation, candidate management, eligibility, launching
        // - Ballot casting, editing, retrieval
        // - Tallying and finalization
        // - Administrative queries (user count, election count, event count, table data)
        // - Election deletion
    }
}
```

### Test DSL Classes

#### UserContext

Provides user-scoped operations:
```kotlin
val alice = context.registerUser("alice", "alice@example.com", "password")

alice.createElection("Best Language")
alice.listElections()
alice.getMyProfile()
alice.changePassword("newpass")
alice.updateUser(newEmail = "newemail@example.com")
alice.setRole("bob", Role.ADMIN)
alice.listUsers()
```

#### ElectionContext

Provides election-scoped operations:
```kotlin
val election = alice.createElection("Best Language")

election.setCandidates("Kotlin", "Rust", "Go")
election.listCandidates()
election.setEligibleVoters("bob", "charlie")
election.listEligibility()
election.launch(allowEdit = true)
election.finalize()
election.tally()
election.delete()
```

## 1. Business Logic Tests

**Purpose**: Verify domain behavior and business rules without touching real infrastructure.

**Location**: `integration/src/test/kotlin/com/seanshubin/vote/integration/VotingWorkflowTest.kt`

**Characteristics:**
- Uses `TestContext` with default (InMemory) provider
- Uses `DirectServiceBackend` (direct service calls)
- All integrations are fakes (fake clock, fake ID generator, fake notifications)
- Verifies both events and database state
- **Complete coverage** - tests happy path AND error conditions
- Tests verify against database contract, not implementation details

**Example test:**

```kotlin
@Test
fun `first registered user becomes owner`() {
    val context = TestContext()

    val alice = context.registerUser("alice", "alice@example.com", "password")

    // Verify event was emitted
    context.events.assertEvent<UserRegistered> {
        assertEquals("alice", it.userName)
        assertEquals(Role.OWNER, it.role)
    }

    // Verify database state
    val user = context.database.getUser("alice")
    assertEquals(Role.OWNER, user.role)

    // Verify token reflects role
    assertEquals(Role.OWNER, alice.accessToken.role)
}

@Test
fun `cannot vote in election that is not launched`() {
    val context = TestContext()
    val alice = context.registerUser("alice")
    val bob = context.registerUser("bob")

    val election = alice.createElection("Test")
    election.setCandidates("A", "B")
    election.setEligibleVoters("bob")

    // Should fail - election not launched
    assertThrows<IllegalStateException> {
        bob.castBallot(election, "A" to 1, "B" to 2)
    }
}
```

**What to test:**
- User registration and role assignment (first user = OWNER, others = USER)
- Authentication flows
- Authorization (permission checks for each operation)
- Election lifecycle (draft → launched → finalized)
- Voting rules (eligibility, edit restrictions, duplicate prevention)
- Condorcet winner calculation
- Error conditions (invalid operations, permission denials)
- Event emission (verify correct events with correct data)
- Database state consistency

**Running business logic tests:**
```bash
./gradlew :integration:test --tests "VotingWorkflowTest"
```

## 2. Smoke Tests

**Purpose**: Verify that all database implementations (InMemory, MySQL, DynamoDB) behave identically under the comprehensive scenario.

**Location**: `integration/src/test/kotlin/com/seanshubin/vote/integration/ScenarioCompatibilityTest.kt`

**Characteristics:**
- Uses `TestContext` with parameterized DatabaseProvider
- Uses `DirectServiceBackend` (direct service calls)
- Exercises **every API operation at least once** via `Scenario.comprehensive()`
- **Happy path only** - no error conditions
- Verifies database behavior matches code expectations across all backends
- Parameterized test runs same scenario against all three backends

**Example test:**

```kotlin
@ParameterizedTest(name = "{0}")
@MethodSource("providerNames")
fun `comprehensive scenario works identically across all backends`(providerName: String) {
    val provider = createProvider(providerName)

    try {
        val context = TestContext(provider = provider)

        // Run comprehensive scenario exercising all 40+ API operations
        Scenario.comprehensive(context)

        // Verify final state
        assertEquals(4, context.userCount())
        assertEquals(2, context.electionCount())  // 2 remain after deletion
        assertTrue(context.eventCount() > 0)

        // Verify database tables exist
        val tables = context.listTables()
        assertTrue(tables.contains("user"))
        assertTrue(tables.contains("election"))

        // Verify specific data
        val users = context.listUsers()
        assertTrue(users.any { it.userName == "alice" && it.role == Role.OWNER })
        assertTrue(users.any { it.userName == "bob" && it.role == Role.ADMIN })

    } finally {
        provider.close()
    }
}

companion object {
    @JvmStatic
    fun providerNames() = Stream.of("InMemory", "MySQL", "DynamoDB")

    private fun createProvider(name: String): DatabaseProvider = when (name) {
        "InMemory" -> InMemoryDatabaseProvider()
        "MySQL" -> MySQLDatabaseProvider()
        "DynamoDB" -> DynamoDBDatabaseProvider()
        else -> throw IllegalArgumentException("Unknown provider: $name")
    }
}
```

**What the comprehensive scenario covers:**
- User registration (4 users)
- Role management (promoting to ADMIN)
- Password changes
- Email updates
- User name updates
- Election creation (3 elections)
- Candidate configuration
- Eligibility configuration
- Election launching (with allowEdit=true and allowEdit=false)
- Ballot casting (multiple ballots)
- Ballot editing (when allowed)
- Election finalization
- Tallying
- Election deletion
- Administrative queries (user count, election count, event count, table data)
- Permission queries

**Running smoke tests:**
```bash
# All backends
./gradlew :integration:test --tests "ScenarioCompatibilityTest"

# Specific backend
./gradlew :integration:test --tests "ScenarioCompatibilityTest" --tests "*InMemory*"
./gradlew :integration:test --tests "ScenarioCompatibilityTest" --tests "*MySQL*"
./gradlew :integration:test --tests "ScenarioCompatibilityTest" --tests "*DynamoDB*"
```

**Setup requirements:**
- **InMemory**: No setup required
- **MySQL**: Requires MySQL running locally or via Docker
- **DynamoDB**: Requires DynamoDB Local running or AWS credentials

## 3. HTTP Boundary Tests

**Purpose**: Verify the HTTP communication layer including headers, tokens, serialization, status codes, and error responses.

**Location**: `integration/src/test/kotlin/com/seanshubin/vote/integration/HttpApiTest.kt`

**Characteristics:**
- Starts real HTTP server with InMemory database
- Uses Java `HttpClient` to make real HTTP requests
- Tests serialization/deserialization of requests and responses
- Tests authentication via Bearer tokens
- Tests authorization (correct status codes for permission denials)
- Tests error handling (malformed JSON, missing fields, invalid data)
- **Complete coverage** - tests happy path AND error conditions
- Uses `TestIntegrations` but runs through full HTTP stack

**Example test:**

```kotlin
@Test
fun `register returns access token`() {
    val response = post("/register", """{"userName":"alice","email":"alice@example.com","password":"pass"}""")

    assertEquals(200, response.statusCode())
    val tokens = json.decodeFromString<Tokens>(response.body())
    assertNotNull(tokens.accessToken)
    assertEquals("alice", tokens.accessToken.userName)
}

@Test
fun `endpoint without auth header returns 401`() {
    val response = get("/users")

    assertEquals(401, response.statusCode())
    assertTrue(response.body().contains("Authorization"))
}

@Test
fun `register with duplicate username returns error`() {
    register("alice")
    val response = post("/register", """{"userName":"alice","email":"alice2@example.com","password":"pass"}""")

    assertTrue(response.statusCode() in listOf(400, 409))
    assertTrue(response.body().contains("error"))
}

@Test
fun `register with malformed JSON returns 400`() {
    val response = post("/register", """{"userName":"alice","email":""")

    assertEquals(400, response.statusCode())
}

@Test
fun `cast ballot succeeds with valid token`() {
    val aliceTokens = register("alice")
    val bobTokens = register("bob")
    post("/election", """{"userName":"alice","electionName":"Lang"}""", aliceTokens.accessToken)
    put("/election/Lang/candidates", """{"candidateNames":["A","B"]}""", aliceTokens.accessToken)
    put("/election/Lang/eligibility", """{"voterNames":["bob"]}""", aliceTokens.accessToken)
    post("/election/Lang/launch", """{"allowEdit":true}""", aliceTokens.accessToken)

    val response = post("/election/Lang/ballot",
        """{"voterName":"bob","rankings":[{"candidateName":"A","rank":1}]}""",
        bobTokens.accessToken)

    assertEquals(200, response.statusCode())
}
```

**What to test:**
- Registration endpoint (success, duplicate username, malformed JSON)
- Authentication endpoint (success, wrong password, missing user)
- Authorization (401 when no token, 403 when insufficient permissions)
- All HTTP methods (GET, POST, PUT, DELETE)
- Request serialization (JSON structure, required fields)
- Response serialization (correct JSON structure)
- Header handling (Authorization Bearer tokens, Content-Type)
- Status codes (200, 400, 401, 403, 404, 500)
- Error messages (meaningful error descriptions)
- URL encoding (spaces in election names)

**Test infrastructure:**
```kotlin
@BeforeEach
fun startServer() {
    val integrations = TestIntegrations()
    val configuration = Configuration(port = 9876, databaseConfig = DatabaseConfig.InMemory)
    val appDeps = ApplicationDependencies(integrations, configuration)
    runner = appDeps.runner
    runner.startNonBlocking()

    httpClient = HttpClient.newBuilder().build()
    waitForServerReady()
}

@AfterEach
fun stopServer() {
    runner.stop()
}
```

**Running HTTP boundary tests:**
```bash
./gradlew :integration:test --tests "HttpApiTest"
```

## Test Organization

```
integration/
└── src/
    └── test/
        └── kotlin/
            └── com/seanshubin/vote/integration/
                ├── VotingWorkflowTest.kt           # Business logic tests
                ├── ScenarioCompatibilityTest.kt    # Smoke tests
                ├── HttpApiTest.kt                  # HTTP boundary tests
                ├── dsl/
                │   ├── TestContext.kt              # Main DSL entry point
                │   ├── UserContext.kt              # User-scoped operations
                │   ├── ElectionContext.kt          # Election-scoped operations
                │   ├── ScenarioBackend.kt          # Backend abstraction
                │   ├── DirectServiceBackend.kt     # Direct service calls
                │   ├── HttpBackend.kt              # HTTP calls (if implemented)
                │   ├── EventInspector.kt           # Event verification
                │   └── DatabaseInspector.kt        # Database state verification
                ├── scenario/
                │   └── Scenario.kt                 # Comprehensive scenario
                ├── database/
                │   ├── DatabaseProvider.kt         # Storage abstraction
                │   ├── InMemoryDatabaseProvider.kt
                │   ├── MySQLDatabaseProvider.kt
                │   └── DynamoDBDatabaseProvider.kt
                └── fake/
                    └── TestIntegrations.kt         # Fake implementations
```

## Running All Tests

```bash
# All tests
./gradlew :integration:test

# Specific test category
./gradlew :integration:test --tests "VotingWorkflowTest"
./gradlew :integration:test --tests "ScenarioCompatibilityTest"
./gradlew :integration:test --tests "HttpApiTest"

# With output
./gradlew :integration:test --info

# Continuous
./gradlew :integration:test --continuous
```

## Writing New Tests

### Adding a Business Logic Test

```kotlin
@Test
fun `describe the behavior being tested`() {
    // Arrange: Set up test context and data
    val context = TestContext()
    val alice = context.registerUser("alice")
    val bob = context.registerUser("bob")

    // Act: Perform the operation
    val election = alice.createElection("Test Election")
    election.setCandidates("A", "B")

    // Assert: Verify events
    context.events.assertEvent<ElectionAdded> {
        assertEquals("Test Election", it.electionName)
    }

    // Assert: Verify database state
    val elections = context.database.allElections()
    assertEquals(1, elections.size)
    assertEquals("Test Election", elections[0].name)
}
```

### Adding to Comprehensive Scenario

When adding new Service API operations, update `Scenario.comprehensive()`:

```kotlin
// Add the new operation in the appropriate section
private fun demonstrateNewFeature(context: TestContext, alice: UserContext) {
    // Exercise the new API operation
    val result = alice.newOperation("param")
    // No assertions needed - smoke test verifies it doesn't crash
}
```

### Adding an HTTP Boundary Test

```kotlin
@Test
fun `new endpoint returns expected status and structure`() {
    val tokens = register("alice")

    val response = post("/new-endpoint",
        """{"field":"value"}""",
        tokens.accessToken)

    assertEquals(200, response.statusCode())
    val result = json.decodeFromString<ExpectedType>(response.body())
    assertEquals("expected", result.field)
}
```

## Coverage Goals

### Business Logic Tests
- **Goal**: 100% coverage of business rules and error conditions
- **Current**: Core workflows covered, expand error condition coverage as needed
- **Focus**: Every business rule should have a test verifying correct behavior AND incorrect behavior

### Smoke Tests
- **Goal**: Every Service API method exercised at least once
- **Current**: `Scenario.comprehensive()` covers all 40+ methods
- **Maintenance**: When adding new Service methods, add to comprehensive scenario

### HTTP Boundary Tests
- **Goal**: Every HTTP endpoint covered with success and failure cases
- **Current**: Core endpoints covered
- **Focus**: Authentication, authorization, serialization, error codes

## Test Principles

1. **Business logic tests use fake integrations** - No real clock, no real IDs, no real notifications
2. **Smoke tests verify backend compatibility** - Same behavior across InMemory, MySQL, DynamoDB
3. **HTTP tests verify communication** - Headers, tokens, serialization, status codes
4. **TestContext provides consistent API** - Same DSL works with different backends
5. **Scenario exercises everything** - Comprehensive scenario is the smoke test specification
6. **Tests are readable** - DSL makes tests read like specifications
7. **Tests are isolated** - Each test creates its own context
8. **Tests are fast** - Business logic tests run in milliseconds (no I/O)

## Future Enhancements

### Potential additions:

1. **Performance tests** - Measure throughput and latency under load
2. **Concurrency tests** - Verify thread safety and race condition handling
3. **Frontend tests** - Browser automation for UI workflows
4. **API contract tests** - Verify frontend and backend agree on contract
5. **Security tests** - Penetration testing, input validation fuzzing
6. **Chaos tests** - Network failures, database failures, partial failures
7. **Migration tests** - Verify data migrations don't break existing data

### Test data builders:

Consider adding builder pattern for complex test data:

```kotlin
fun TestContext.userBuilder(name: String = randomName()) = UserBuilder(this, name)

class UserBuilder(private val context: TestContext, private val name: String) {
    private var email: String = "$name@example.com"
    private var password: String = "password"

    fun withEmail(email: String) = apply { this.email = email }
    fun withPassword(password: String) = apply { this.password = password }
    fun register(): UserContext = context.registerUser(name, email, password)
}

// Usage
val alice = context.userBuilder("alice")
    .withEmail("alice.smith@example.com")
    .withPassword("securepass123")
    .register()
```

## Troubleshooting

### MySQL tests fail with connection error
- Ensure MySQL is running: `docker run -p 3306:3306 -e MYSQL_ROOT_PASSWORD=password mysql`
- Check connection string in `MySQLDatabaseProvider`
- Verify database credentials

### DynamoDB tests fail
- Ensure DynamoDB Local is running or AWS credentials are configured
- Check endpoint configuration in `DynamoDBDatabaseProvider`

### HTTP tests fail with "address already in use"
- Another test didn't clean up properly
- Check that `@AfterEach` is stopping the server
- Use unique ports for each test class if needed

### Tests pass individually but fail when run together
- Check for shared mutable state
- Ensure each test creates its own `TestContext`
- Verify `TestIntegrations` resets properly

## Summary

The three-tier testing strategy provides comprehensive coverage:

| Test Type | Purpose | Integration Level | Coverage Type |
|-----------|---------|------------------|---------------|
| **Business Logic** | Verify domain behavior | None (all fakes) | Complete (happy + errors) |
| **Smoke** | Verify backend compatibility | Real databases | Happy path only |
| **HTTP Boundary** | Verify communication | HTTP stack + InMemory DB | Complete (happy + errors) |

This strategy ensures:
- ✅ Business rules are correct (business logic tests)
- ✅ All backends work identically (smoke tests)
- ✅ HTTP layer works correctly (boundary tests)
- ✅ Fast feedback (business logic tests run in milliseconds)
- ✅ Confidence in deployment (smoke tests verify real integrations)
- ✅ API contract verification (boundary tests)
