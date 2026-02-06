# Testing Philosophy: Easy to Maintain

## Core Principle

**Tests should read like specifications, not implementation details.**

Tests that are easy to maintain have these properties:
1. **Clear intent** - What behavior is being tested?
2. **Minimal brittleness** - Only break when actual behavior changes
3. **Good defaults** - Most things "just work" without explicit setup
4. **Composable** - Build complex scenarios from simple operations
5. **Debuggable** - Failures explain what went wrong

## Architecture Enables This

Your dependency injection architecture makes this possible:

```kotlin
// Production
val integrations = ProductionIntegrations(realClock, realDatabase, realIdGenerator)
val service = ServiceImpl(integrations, eventLog, commandModel, queryModel)

// Testing
val integrations = TestIntegrations(fakeClock, fakeDatabase, fakeIdGenerator)
val service = ServiceImpl(integrations, eventLog, commandModel, queryModel)
```

**Same service code, different infrastructure. Fast, deterministic, testable.**

## Test Style: User-Level Operations

### Bad: Coupled to Implementation
```kotlin
@Test
fun test() {
    val salt = "fake-salt-123"
    val hash = passwordUtil.hash("secret", salt)
    eventLog.appendEvent("system", clock.now(),
        DomainEvent.UserRegistered("sean", "sean@email.com", salt, hash, Role.OWNER))
    commandModel.synchronize()

    val user = queryModel.findUserByName("sean")
    assertEquals("sean@email.com", user.email)
    assertEquals(Role.OWNER, user.role)
}
```

**Problems:**
- Test knows about events, salts, hashes, synchronization
- Breaks when event structure changes
- Doesn't test the actual user journey
- Hard to understand what behavior is being tested

### Good: User-Level Operations
```kotlin
@Test
fun `first user becomes owner`() {
    val sean = testContext.registerUser("sean", "sean@email.com", "secret")

    assertEquals("OWNER", sean.role)
}
```

**Benefits:**
- Reads like a specification
- Tests real registration flow (HTTP → Service → Events → Database)
- Doesn't break when event structure changes
- Clear intent: "first user becomes owner"

## Test Style: Scenario Building

### Bad: Repeated Setup
```kotlin
@Test
fun `owner can create election`() {
    // 20 lines of setup
    eventLog.appendEvent(...)
    eventLog.appendEvent(...)
    commandModel.synchronize()

    // actual test
}

@Test
fun `owner can add candidates`() {
    // Same 20 lines of setup again!
    eventLog.appendEvent(...)
    eventLog.appendEvent(...)
    commandModel.synchronize()

    // actual test
}
```

### Good: Composable Operations
```kotlin
@Test
fun `owner can create election`() {
    val alice = testContext.registerUser("alice")
    val election = alice.createElection("Favorite Language")

    assertEquals("alice", election.ownerName)
    assertEquals("Favorite Language", election.electionName)
}

@Test
fun `owner can add candidates`() {
    val alice = testContext.registerUser("alice")
    val election = alice.createElection("Favorite Language")
    election.setCandidates("Kotlin", "Rust", "Go")

    assertEquals(listOf("Kotlin", "Rust", "Go"), election.candidates)
}
```

**Key insight:** `alice.createElection()` returns a context object that knows about the election, so you can keep building on it.

## Test Style: Multiple Perspectives

Tests should verify correctness from different angles:

```kotlin
@Test
fun `casting ballot creates event and updates database`() {
    val (alice, bob) = testContext.registerUsers("alice", "bob")
    val election = alice.createElection("Best Language")
    election.setCandidates("Kotlin", "Rust", "Go")
    election.setEligibleVoters("alice", "bob")
    election.launch()

    // Act
    bob.castBallot(election, rankings = listOf(
        "Kotlin" to 1,
        "Rust" to 2,
        "Go" to 3
    ))

    // Assert from event perspective
    val events = testContext.events.ofType<DomainEvent.BallotCast>()
    assertEquals(1, events.size)
    assertEquals("bob", events[0].voterName)
    assertEquals("Best Language", events[0].electionName)

    // Assert from database perspective
    val ballot = testContext.database.findBallot(voterName = "bob", electionName = "Best Language")
    assertEquals(3, ballot.rankings.size)
    assertEquals("Kotlin", ballot.rankings.first { it.rank == 1 }.candidateName)

    // Assert from query perspective
    val tally = election.tally()
    assertEquals(1, tally.ballots.size)
}
```

**Three perspectives:**
1. Events - "did the right event happen?"
2. Database - "is the data stored correctly?"
3. Queries - "does the application see the right thing?"

This catches bugs at different layers.

## Implementation: TestContext

```kotlin
class TestContext {
    // Fake infrastructure
    val clock = FakeClock()
    val idGenerator = SequentialIdGenerator()
    val passwordUtil = FakePasswordUtil()

    // Real components with fake infrastructure
    private val integrations = TestIntegrations(clock, idGenerator, passwordUtil)
    private val eventLog = InMemoryEventLog()
    private val commandModel = InMemoryCommandModel()
    private val queryModel = InMemoryQueryModel(commandModel)
    private val service = ServiceImpl(integrations, eventLog, commandModel, queryModel)

    // Test helpers
    val events = EventInspector(eventLog)
    val database = DatabaseInspector(queryModel)

    fun registerUser(
        name: String = "user${idGenerator.next()}",
        email: String = "${name}@example.com",
        password: String = "password"
    ): UserContext {
        val tokens = service.register(name, email, password)
        service.synchronize()
        return UserContext(this, name, tokens.accessToken)
    }

    fun registerUsers(vararg names: String): List<UserContext> =
        names.map { registerUser(it) }
}

class UserContext(
    private val testContext: TestContext,
    val userName: String,
    private val accessToken: AccessToken
) {
    fun createElection(
        name: String = "Election ${testContext.idGenerator.next()}"
    ): ElectionContext {
        testContext.service.addElection(accessToken, userName, name)
        testContext.service.synchronize()
        return ElectionContext(testContext, name, this)
    }

    fun castBallot(election: ElectionContext, rankings: List<Pair<String, Int>>) {
        val rankingObjects = rankings.map { (candidate, rank) ->
            Ranking(candidate, rank)
        }
        testContext.service.castBallot(accessToken, userName, election.name, rankingObjects)
        testContext.service.synchronize()
    }
}

class ElectionContext(
    private val testContext: TestContext,
    val name: String,
    private val owner: UserContext
) {
    fun setCandidates(vararg names: String) {
        testContext.service.setCandidates(owner.accessToken, name, names.toList())
        testContext.service.synchronize()
    }

    fun setEligibleVoters(vararg names: String) {
        testContext.service.setEligibleVoters(owner.accessToken, name, names.toList())
        testContext.service.synchronize()
    }

    fun launch(allowEdit: Boolean = true) {
        testContext.service.launchElection(owner.accessToken, name, allowEdit)
        testContext.service.synchronize()
    }

    val candidates: List<String>
        get() = testContext.database.listCandidates(name)

    fun tally(): Tally =
        testContext.service.tally(owner.accessToken, name)
}
```

## Implementation: Inspectors

```kotlin
class EventInspector(private val eventLog: EventLog) {
    inline fun <reified T : DomainEvent> ofType(): List<T> =
        eventLog.allEvents()
            .map { it.event }
            .filterIsInstance<T>()

    fun last(): DomainEvent =
        eventLog.allEvents().last().event

    fun count(): Int =
        eventLog.allEvents().size
}

class DatabaseInspector(private val queryModel: QueryModel) {
    fun findUser(name: String): User =
        queryModel.findUserByName(name)

    fun findBallot(voterName: String, electionName: String): BallotSummary =
        queryModel.searchBallot(voterName, electionName)
            ?: error("No ballot found for $voterName in $electionName")

    fun listCandidates(electionName: String): List<String> =
        queryModel.listCandidates(electionName)

    fun userCount(): Int =
        queryModel.userCount()

    fun electionCount(): Int =
        queryModel.electionCount()
}
```

## Implementation: Fakes

```kotlin
class FakeClock : Clock {
    private var currentTime = Instant.parse("2024-01-01T00:00:00Z")

    override fun now(): Instant = currentTime

    fun advance(duration: Duration) {
        currentTime = currentTime.plus(duration)
    }

    fun set(time: Instant) {
        currentTime = time
    }
}

class SequentialIdGenerator : UniqueIdGenerator {
    private var counter = 0

    override fun next(): String {
        return "id-${++counter}"
    }
}

class FakePasswordUtil : PasswordUtil {
    override fun generateSalt(): String = "fake-salt"

    override fun hash(password: String, salt: String): String =
        "hash-for: $password"

    override fun verify(password: String, salt: String, hash: String): Boolean =
        hash == "hash-for: $password"
}
```

## Example Tests

```kotlin
class RegistrationTest {
    @Test
    fun `first user becomes owner`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")

        assertEquals("OWNER", testContext.database.findUser("alice").role.name)
    }

    @Test
    fun `second user becomes regular user`() {
        val testContext = TestContext()
        testContext.registerUser("alice")
        val bob = testContext.registerUser("bob")

        assertEquals("USER", testContext.database.findUser("bob").role.name)
    }
}

class ElectionTest {
    @Test
    fun `owner can create election with candidates`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")
        val election = alice.createElection("Best Language")
        election.setCandidates("Kotlin", "Rust", "Go")

        assertEquals(listOf("Kotlin", "Rust", "Go"), election.candidates)
        assertEquals(1, testContext.events.ofType<DomainEvent.CandidatesAdded>().size)
    }

    @Test
    fun `voters can cast ballots after launch`() {
        val testContext = TestContext()
        val (alice, bob, charlie) = testContext.registerUsers("alice", "bob", "charlie")

        val election = alice.createElection("Programming Language")
        election.setCandidates("Kotlin", "Rust", "Go")
        election.setEligibleVoters("bob", "charlie")
        election.launch()

        bob.castBallot(election, rankings = listOf(
            "Kotlin" to 1, "Rust" to 2, "Go" to 3
        ))

        charlie.castBallot(election, rankings = listOf(
            "Rust" to 1, "Kotlin" to 2, "Go" to 3
        ))

        val tally = election.tally()
        assertEquals(2, tally.ballots.size)
        assertEquals(listOf("bob", "charlie"), tally.whoVoted.sorted())
    }
}

class CondorcetAlgorithmTest {
    @Test
    fun `condorcet winner is ranked first`() {
        val testContext = TestContext()
        val (alice, bob, charlie) = testContext.registerUsers("alice", "bob", "charlie")
        val david = testContext.registerUser("david")

        val election = alice.createElection("Best Fruit")
        election.setCandidates("Apple", "Banana", "Cherry")
        election.setEligibleVoters("bob", "charlie", "david")
        election.launch()

        // Bob: Apple > Banana > Cherry
        bob.castBallot(election, listOf("Apple" to 1, "Banana" to 2, "Cherry" to 3))

        // Charlie: Apple > Cherry > Banana
        charlie.castBallot(election, listOf("Apple" to 1, "Cherry" to 2, "Banana" to 3))

        // David: Banana > Apple > Cherry
        david.castBallot(election, listOf("Banana" to 1, "Apple" to 2, "Cherry" to 3))

        val tally = election.tally()

        // Apple beats both others pairwise (2-1 each), so it should win
        val winner = tally.places.first()
        assertEquals(1, winner.place)
        assertEquals("Apple", winner.candidateName)
    }
}
```

## Why This Is Easy to Maintain

1. **Tests read like specifications**
   - `first user becomes owner` not `test_registration_001`
   - Clear intent from test name and body

2. **Changes don't break unrelated tests**
   - Add field to User? Tests still pass (they don't assert all fields)
   - Change event structure? Tests using `alice.createElection()` still work

3. **Good defaults reduce boilerplate**
   - `registerUser()` generates email automatically
   - `createElection()` generates name if not provided
   - Most tests can omit irrelevant details

4. **Composable operations**
   - `registerUsers("alice", "bob", "charlie")` creates three users at once
   - `election.setCandidates(...)` returns context for further operations
   - Build complex scenarios incrementally

5. **Multiple verification strategies**
   - Event perspective: "was the command executed?"
   - Database perspective: "is the data correct?"
   - Query perspective: "does the app see it right?"

6. **Fast execution**
   - No real database, no network, no browser
   - Deterministic (fake clock, fake IDs)
   - Can run thousands of tests in seconds

7. **Clear failures**
   - `findBallot()` throws error with message when ballot not found
   - Assertions are specific: "expected Kotlin at rank 1, got Rust"
   - Context objects provide natural error messages

## Summary

**Goal:** Tests as fast as unit tests, as comprehensive as integration tests, easy to maintain.

**How:**
- Fake infrastructure at I/O boundaries (Clock, IdGenerator, PasswordUtil)
- Real service layer (tests actual composition and wiring)
- User-level operations (readable, composable, maintainable)
- Multiple perspectives (events, database, queries)
- Good defaults (minimal boilerplate)

**Result:** Tests that read like specifications, rarely break from unrelated changes, and run fast.
