# Testing Strategy

## Why We Test

We organize tests around four distinct purposes, each serving a different audience and verification goal:

### 1. Business Logic Tests

**Purpose:** Test business logic completely without testing implementation details such as how the code is structured. We should be certain the code works properly no matter what the implementation happens to be. These should be so self-documenting that a product manager could look at them and have confidence the features they asked for actually exist. No interactions with real integration points.

**Who reads these:** Product managers, domain experts, developers

**What they verify:** The features exist and work as specified

**Example:**
```kotlin
@Test
fun `voters can cast ballots after launch`() {
    val testContext = TestContext()
    val (alice, bob, charlie) = testContext.registerUsers("alice", "bob", "charlie")

    val election = alice.createElection("Programming Language")
    election.setCandidates("Kotlin", "Rust", "Go")
    election.setEligibleVoters("bob", "charlie")
    election.launch()

    bob.castBallot(election, "Kotlin" to 1, "Rust" to 2, "Go" to 3)
    charlie.castBallot(election, "Rust" to 1, "Kotlin" to 2, "Go" to 3)

    val tally = election.tally()
    assertEquals(2, tally.ballots.size)
}
```

**Location:** `integration/src/test/kotlin/.../VotingWorkflowTest.kt` (20 tests)

**How they work:** Uses TestContext DSL with fake integrations (fake clock, fake ID generator, fake notifications). Tests the real service layer with fake infrastructure at boundaries. Fast, deterministic, no network or file I/O.

---

### 2. HTTP Interaction Tests

**Purpose:** Test HTTP interactions. These should be complete and so self-documenting that an integration engineer could use them as a specification.

**Who reads these:** Integration engineers, API consumers, developers

**What they verify:** The HTTP API contract - request formats, response formats, status codes, headers, error handling

**Example:**
```kotlin
@Test
fun `cast ballot returns 200 and updates rankings`() {
    val aliceTokens = register("alice")
    val bobTokens = register("bob")
    post("/election", """{"userName":"alice","electionName":"Lang"}""", aliceTokens.accessToken)
    put("/election/Lang/candidates", """{"candidateNames":["Kotlin","Rust"]}""", aliceTokens.accessToken)
    put("/election/Lang/eligibility", """{"voterNames":["bob"]}""", aliceTokens.accessToken)
    post("/election/Lang/launch", """{"allowEdit":true}""", aliceTokens.accessToken)

    val response = post("/election/Lang/ballot",
        """{"voterName":"bob","rankings":[{"candidateName":"Kotlin","rank":1}]}""",
        bobTokens.accessToken)

    assertEquals(200, response.statusCode())
}
```

**Location:** `integration/src/test/kotlin/.../HttpApiTest.kt` (48 tests)

**How they work:** Starts real HTTP server on test port. Uses Java HttpClient to make actual HTTP requests. Verifies serialization, deserialization, authentication, and HTTP-specific concerns. Uses InMemory database for fast execution.

---

### 3. Database Interaction Tests

**Purpose:** Happy path scenario that exercises each feature using real databases to uncover if databases behave the way the code expects them to behave.

**Who reads these:** Infrastructure engineers, database administrators, developers

**What they verify:** The application works correctly with each supported database (InMemory, MySQL, DynamoDB)

**Example:**
```kotlin
@ParameterizedTest(name = "{0}")
@MethodSource("providerNames")
fun `comprehensive scenario works identically across all backends`(providerName: String) {
    val provider = createProvider(providerName)
    val context = TestContext(provider = provider)

    Scenario.comprehensive(context)

    assertEquals(4, context.userCount())
    assertEquals(2, context.electionCount())
}
```

**Location:** `integration/src/test/kotlin/.../ScenarioCompatibilityTest.kt` (3 tests)

**How they work:** Parameterized tests that run the same comprehensive scenario against multiple database implementations. Uses TestContainers to spin up real MySQL and DynamoDB instances. Verifies that database-specific behavior (transactions, consistency, queries) works as expected.

---

### 4. Frontend Tests

**Purpose:** These tests should be complete and so self-documenting that a product manager could look at them and have confidence the features they asked for actually exist.

**Who reads these:** Product managers, UX designers, developers

**What they verify:** The UI features exist, are accessible, and work correctly from a user perspective

**Status:** Planned - not yet implemented

**How they would work:** Browser automation testing the compiled JavaScript frontend against a real HTTP server. Would verify UI flows, accessibility, and user interactions.

---

## Test Organization

```
integration/src/test/kotlin/.../
├── VotingWorkflowTest.kt           # Business Logic Tests (20 tests)
├── HttpApiTest.kt                   # HTTP Interaction Tests (48 tests)
├── ScenarioCompatibilityTest.kt    # Database Interaction Tests (3 tests)
├── dsl/
│   ├── TestContext.kt              # Main DSL entry point
│   ├── UserContext.kt              # User-scoped operations
│   ├── ElectionContext.kt          # Election-scoped operations
│   ├── ScenarioBackend.kt          # Backend abstraction
│   ├── DirectServiceBackend.kt     # Direct service calls
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

**Total: 71 tests, all passing**

---

## Test Infrastructure

### TestContext

The main entry point for the test DSL:

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
election.launch()

// Verify events
context.events.assertEvent<UserRegistered>()

// Verify database state
val users = context.database.allUsers()
assertEquals(1, users.size)
```

### UserContext

Provides user-scoped operations:
```kotlin
val alice = context.registerUser("alice", "alice@example.com", "password")

alice.createElection("Best Language")
alice.listElections()
alice.changePassword("newpass")
alice.updateUser(newEmail = "newemail@example.com")
alice.setRole("bob", Role.ADMIN)
alice.removeUser("bob")
```

### ElectionContext

Provides election-scoped operations:
```kotlin
val election = alice.createElection("Best Language")

election.setCandidates("Kotlin", "Rust", "Go")
election.listCandidates()
election.setEligibleVoters("bob", "charlie")
election.launch()
election.finalize()
election.tally()
election.delete()
```

### DatabaseProvider

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
- `MySQLDatabaseProvider` - Real MySQL database via TestContainers
- `DynamoDBDatabaseProvider` - Real DynamoDB via TestContainers

### ScenarioBackend

Abstraction over how API calls are made:
- `DirectServiceBackend` - Calls ServiceImpl directly (for business logic and database tests)
- `HttpBackend` - Makes HTTP requests (for HTTP boundary tests)

### Comprehensive Scenario

The `Scenario.comprehensive()` method exercises **every Service API operation**:
- User registration, authentication, profile management, role changes
- Election creation, candidate management, eligibility, launching
- Ballot casting, editing, retrieval
- Tallying and finalization
- Administrative queries (user count, election count, event count, table data)
- Election deletion

This scenario is the smoke test specification - when adding new Service methods, add them to the comprehensive scenario.

### Fake Infrastructure

Tests inject fake implementations at I/O boundaries:
- `FakeClock` - deterministic time (no wall clock dependency)
- `FakeIdGenerator` - sequential IDs (no randomness)
- `FakeNotifications` - captures events (no actual email/logging)
- `FakePasswordUtil` - predictable hashing (no crypto overhead)

Real service layer runs with fake infrastructure. Tests verify actual composition and wiring.

---

## Running Tests

```bash
# All tests
./gradlew :integration:test

# Business logic tests
./gradlew :integration:test --tests "VotingWorkflowTest"

# HTTP tests
./gradlew :integration:test --tests "HttpApiTest"

# Database tests (all backends)
./gradlew :integration:test --tests "ScenarioCompatibilityTest"

# Specific backend
./gradlew :integration:test --tests "ScenarioCompatibilityTest" --tests "*InMemory*"
./gradlew :integration:test --tests "ScenarioCompatibilityTest" --tests "*MySQL*"
./gradlew :integration:test --tests "ScenarioCompatibilityTest" --tests "*DynamoDB*"

# With output
./gradlew :integration:test --info

# Continuous
./gradlew :integration:test --continuous
```

**Setup requirements:**
- **InMemory**: No setup required
- **MySQL**: TestContainers starts MySQL automatically (requires Docker)
- **DynamoDB**: TestContainers starts DynamoDB Local automatically (requires Docker)

---

## Writing New Tests

### Adding a Business Logic Test

```kotlin
@Test
fun `describe the behavior being tested`() {
    // Arrange
    val context = TestContext()
    val alice = context.registerUser("alice")
    val bob = context.registerUser("bob")

    // Act
    val election = alice.createElection("Test Election")
    election.setCandidates("A", "B")

    // Assert events
    context.events.assertEvent<ElectionCreated> {
        assertEquals("Test Election", it.electionName)
    }

    // Assert database state
    val elections = context.database.listElections()
    assertEquals(1, elections.size)
}
```

### Adding to Comprehensive Scenario

When adding new Service API operations, update `Scenario.comprehensive()`:

```kotlin
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

---

## Test Principles

1. **Purpose drives design** - Each test type serves a clear audience and verification goal
2. **Tests are self-documenting** - Test names and bodies read like specifications
3. **Tests don't test implementation** - Verify behavior through public API, not internal structure
4. **Business logic tests use fakes** - No real clock, no real IDs, no real notifications
5. **Database tests verify compatibility** - Same behavior across InMemory, MySQL, DynamoDB
6. **HTTP tests verify communication** - Headers, tokens, serialization, status codes
7. **TestContext provides consistent API** - Same DSL works with different backends
8. **Scenario exercises everything** - Comprehensive scenario is the smoke test specification
9. **Tests are isolated** - Each test creates its own context
10. **Tests are fast** - Business logic tests run in milliseconds (no I/O)

---

## Coverage Summary

| Test Type | Purpose | Integration Level | Coverage | Tests |
|-----------|---------|-------------------|----------|-------|
| **Business Logic** | Verify features exist and work | None (all fakes) | Complete | 20 |
| **HTTP Boundary** | Verify API contract | HTTP + InMemory | Complete | 48 |
| **Database** | Verify backend compatibility | Real databases | Happy path | 3 |
| **Frontend** | Verify UI works | Browser + HTTP | Planned | 0 |
| **Total** | | | | **71** |

This strategy ensures:
- ✅ Features work correctly (product managers can verify via business logic tests)
- ✅ API contract is correct (integration engineers can verify via HTTP tests)
- ✅ Works with real databases (infrastructure engineers can verify via database tests)
- ✅ Fast feedback (business logic tests run in milliseconds)
- ✅ Confidence in deployment (database tests verify real integrations)

---

## Troubleshooting

### MySQL tests fail with connection error
- Ensure Docker is running
- TestContainers will automatically start MySQL
- Check Docker logs: `docker logs <container-id>`

### DynamoDB tests fail
- Ensure Docker is running
- TestContainers will automatically start DynamoDB Local
- Check Docker logs: `docker logs <container-id>`

### HTTP tests fail with "address already in use"
- Another test didn't clean up properly
- Check that `@AfterEach` is stopping the server
- Restart IDE/terminal to release port

### Tests pass individually but fail when run together
- Check for shared mutable state
- Ensure each test creates its own `TestContext`
- Verify `TestIntegrations` resets properly
- Look for static state in fake implementations

### TestContainers fails to start
- Ensure Docker daemon is running
- Check Docker resource limits (memory, CPU)
- Try: `docker system prune` to clean up old containers
- Check TestContainers logs in `build/test-results/test/`

---

## Future Enhancements

### Potential additions:
1. **Frontend tests** - Browser automation for UI workflows
2. **Performance tests** - Measure throughput and latency under load
3. **Concurrency tests** - Verify thread safety and race condition handling
4. **Security tests** - Penetration testing, input validation fuzzing
5. **Chaos tests** - Network failures, database failures, partial failures
6. **Migration tests** - Verify data migrations don't break existing data

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
