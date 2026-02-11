# Testing Philosophy

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

**How they work:** Uses TestContext DSL with fake integrations (fake clock, fake database, fake ID generator). Tests the real service layer with fake infrastructure at boundaries. Fast, deterministic, no network or file I/O.

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

    val rankings = get("/election/Lang/rankings/bob", bobTokens.accessToken)
    assertTrue(rankings.body().contains("Kotlin"))
}
```

**How they work:** Starts real HTTP server on test port. Uses Java HttpClient to make actual HTTP requests. Verifies serialization, deserialization, authentication, and HTTP-specific concerns. Uses InMemory database for fast execution.

---

### 3. Database Interaction Tests
**Purpose:** Happy path scenario that exercises each feature using real databases to uncover if databases behave the way the code expects them to behave.

**Who reads these:** Infrastructure engineers, database administrators, developers

**What they verify:** The application works correctly with each supported database (MySQL, DynamoDB, etc.)

**Example:**
```kotlin
@ParameterizedTest
@MethodSource("backendProvider")
fun `comprehensive voting scenario`(backend: ScenarioBackend) {
    val scenario = Scenario.comprehensive()

    scenario.execute(backend)
    scenario.verify(backend)
}

companion object {
    @JvmStatic
    fun backendProvider() = listOf(
        InMemoryBackend(),
        MySQLBackend(testContainer),
        DynamoDBBackend(testContainer)
    )
}
```

**How they work:** Parameterized tests that run the same comprehensive scenario against multiple database implementations. Uses TestContainers to spin up real MySQL and DynamoDB instances. Verifies that database-specific behavior (transactions, consistency, queries) works as expected.

---

### 4. Frontend Tests
**Purpose:** These tests should be complete and so self-documenting that a product manager could look at them and have confidence the features they asked for actually exist.

**Who reads these:** Product managers, UX designers, developers

**What they verify:** The UI features exist, are accessible, and work correctly from a user perspective

**Example (planned):**
```kotlin
@Test
fun `user can create election and add candidates`() {
    browser.navigateTo("/")
    browser.login("alice", "password")

    browser.click("Create Election")
    browser.fillField("Election Name", "Best Language")
    browser.click("Save")

    browser.click("Add Candidates")
    browser.fillField("Candidate 1", "Kotlin")
    browser.fillField("Candidate 2", "Rust")
    browser.click("Save Candidates")

    browser.assertVisible("Kotlin")
    browser.assertVisible("Rust")
}
```

**How they work (planned):** Browser automation testing the compiled JavaScript frontend against a real HTTP server. Verifies UI flows, accessibility, and user interactions.

---

## Test Organization

```
integration/src/test/kotlin/
├── VotingWorkflowTest.kt           # Business Logic Tests (20 tests)
├── HttpApiTest.kt                   # HTTP Interaction Tests (48 tests)
├── ScenarioCompatibilityTest.kt    # Database Interaction Tests (3 tests)
└── (frontend tests - planned)
```

**Total: 71 tests, all passing**

---

## Core Principles

### Tests Read Like Specifications
Test names and bodies should be understandable by non-programmers:
- ✅ `first user becomes owner`
- ❌ `test_registration_001`

### Tests Don't Test Implementation Details
Tests should verify behavior, not structure:
- ✅ Tests verify features work through public API
- ❌ Tests don't assert on event structure, internal class organization, or private methods

### Tests Are Self-Documenting
The test code itself is the specification:
- ✅ Clear intent from test name and operations
- ❌ Don't add comment blocks explaining what tests do

### Changes Don't Break Unrelated Tests
Tests should be resilient to internal refactoring:
- ✅ Add field to User? Tests still pass (they don't assert all fields)
- ✅ Change event structure? Tests using DSL operations still work
- ❌ Rename internal class? Should not break any tests

---

## Implementation Details

### TestContext DSL

Business logic tests use a domain-specific language for readable test code:

```kotlin
class TestContext {
    fun registerUser(name: String): UserContext
    fun registerUsers(vararg names: String): List<UserContext>
}

class UserContext {
    fun createElection(name: String): ElectionContext
    fun castBallot(election: ElectionContext, rankings: List<Pair<String, Int>>)
}

class ElectionContext {
    fun setCandidates(vararg names: String)
    fun setEligibleVoters(vararg names: String)
    fun launch()
    fun tally(): Tally
}
```

### Fake Infrastructure

Tests inject fake implementations at I/O boundaries:
- `FakeClock` - deterministic time (no wall clock dependency)
- `FakeIdGenerator` - sequential IDs (no randomness)
- `FakeNotifications` - captures events (no actual email/logging)

Real service layer runs with fake infrastructure. Tests verify actual composition and wiring.

### Multiple Verification Perspectives

Tests can verify correctness from different angles:
- **Events:** "Did the right command execute?"
- **Database:** "Is the data stored correctly?"
- **Queries:** "Does the application see the right thing?"

This catches bugs at different layers:
```kotlin
// Verify event occurred
val events = testContext.events.ofType<DomainEvent.BallotCast>()
assertEquals(1, events.size)

// Verify database state
val ballot = testContext.database.findBallot("bob", "Best Language")
assertEquals(3, ballot.rankings.size)

// Verify query result
val tally = election.tally()
assertEquals(1, tally.ballots.size)
```

### Good Defaults

DSL operations provide sensible defaults to reduce boilerplate:
- `registerUser()` generates email automatically: `"${name}@example.com"`
- `createElection()` generates name if not provided: `"Election ${id}"`
- Most tests can omit irrelevant details

### Fast Execution

Business logic tests run without:
- Real database connections
- Network calls
- File system operations
- Browser automation

Thousands of tests run in seconds. Deterministic results from fake clock and ID generator.

---

## Summary

**Goal:** Each test type serves a clear purpose and audience.

**Business Logic:** Features work correctly (product managers can verify)
**HTTP:** API contract is correct (integration engineers can verify)
**Database:** Works with real databases (infrastructure engineers can verify)
**Frontend:** UI works correctly (product managers can verify)

**Result:** Comprehensive verification at multiple levels, each test category optimized for its specific purpose.
