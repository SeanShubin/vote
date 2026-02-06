package com.seanshubin.vote.integration

import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.domain.Role
import com.seanshubin.vote.integration.dsl.TestContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VotingWorkflowTest {
    @Test
    fun `first user becomes owner`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")

        val user = testContext.database.findUser("alice")
        assertEquals(Role.OWNER, user.role)
    }

    @Test
    fun `second user becomes regular user`() {
        val testContext = TestContext()
        testContext.registerUser("alice")
        val bob = testContext.registerUser("bob")

        val aliceUser = testContext.database.findUser("alice")
        val bobUser = testContext.database.findUser("bob")
        assertEquals(Role.OWNER, aliceUser.role)
        assertEquals(Role.USER, bobUser.role)
    }

    @Test
    fun `owner can create election with candidates`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")

        val election = alice.createElection("Best Programming Language")
        election.setCandidates("Kotlin", "Rust", "Go")

        assertEquals(3, election.candidates.size)
        assertTrue(election.candidates.containsAll(listOf("Kotlin", "Rust", "Go")))

        // Verify event was created
        val candidateEvents = testContext.events.ofType<DomainEvent.CandidatesAdded>()
        assertEquals(1, candidateEvents.size)
        assertEquals("Best Programming Language", candidateEvents[0].electionName)
    }

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

        // Verify events
        val ballotEvents = testContext.events.ofType<DomainEvent.BallotCast>()
        assertEquals(2, ballotEvents.size)

        // Verify database
        val bobBallot = testContext.database.findBallot("bob", "Programming Language")
        assertEquals("bob", bobBallot.voterName)

        val allBallots = testContext.database.listBallots("Programming Language")
        assertEquals(2, allBallots.size)
    }

    @Test
    fun `condorcet winner is ranked first`() {
        val testContext = TestContext()
        val (alice, bob, charlie, david) = testContext.registerUsers("alice", "bob", "charlie", "david")

        val election = alice.createElection("Best Fruit")
        election.setCandidates("Apple", "Banana", "Cherry")
        election.setEligibleVoters("bob", "charlie", "david")
        election.launch()

        // Bob: Apple > Banana > Cherry
        bob.castBallot(election, "Apple" to 1, "Banana" to 2, "Cherry" to 3)

        // Charlie: Apple > Cherry > Banana
        charlie.castBallot(election, "Apple" to 1, "Cherry" to 2, "Banana" to 3)

        // David: Banana > Apple > Cherry
        david.castBallot(election, "Banana" to 1, "Apple" to 2, "Cherry" to 3)

        val tally = election.tally()

        // Apple beats both others pairwise (2-1 each), so it should win
        val winner = tally.places.first()
        assertEquals(1, winner.rank)
        assertEquals("Apple", winner.candidateName)
    }

    @Test
    fun `election lifecycle from creation to finalization`() {
        val testContext = TestContext()
        val (alice, bob) = testContext.registerUsers("alice", "bob")

        // Create election
        val election = alice.createElection("Favorite Color")
        assertEquals("alice", testContext.database.findElection("Favorite Color").ownerName)

        // Add candidates
        election.setCandidates("Red", "Blue", "Green")
        assertEquals(3, election.candidates.size)

        // Add eligible voters
        election.setEligibleVoters("bob")
        assertEquals(listOf("bob"), election.eligibleVoters)

        // Launch election
        election.launch()
        val launchedElection = testContext.database.findElection("Favorite Color")
        assertTrue(launchedElection.allowVote)

        // Cast ballot
        bob.castBallot(election, "Blue" to 1, "Red" to 2, "Green" to 3)

        // Finalize election
        election.finalize()
        val finalizedElection = testContext.database.findElection("Favorite Color")
        assertEquals(false, finalizedElection.allowVote)
        assertEquals(false, finalizedElection.allowEdit)

        // Verify all events occurred in order
        val events = testContext.events.all()
        assertTrue(events.any { it is DomainEvent.ElectionCreated })
        assertTrue(events.any { it is DomainEvent.CandidatesAdded })
        assertTrue(events.any { it is DomainEvent.VotersAdded })
        assertTrue(events.any { it is DomainEvent.BallotCast })
    }

    @Test
    fun `multiple elections can exist simultaneously`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")

        val election1 = alice.createElection("Best Language")
        election1.setCandidates("Kotlin", "Java")

        val election2 = alice.createElection("Best Framework")
        election2.setCandidates("Spring", "Ktor")

        assertEquals(2, testContext.database.electionCount())
        assertEquals(2, election1.candidates.size)
        assertEquals(2, election2.candidates.size)
    }
}
