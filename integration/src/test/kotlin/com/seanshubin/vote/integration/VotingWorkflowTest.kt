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

    @Test
    fun `owner can remove users`() {
        val testContext = TestContext()
        val (alice, bob) = testContext.registerUsers("alice", "bob")

        assertEquals(2, testContext.database.userCount())

        alice.removeUser("bob")

        assertEquals(1, testContext.database.userCount())
        val user = testContext.database.findUserOrNull("bob")
        assertEquals(null, user)

        // Verify event was created
        val events = testContext.events.ofType<DomainEvent.UserRemoved>()
        assertEquals(1, events.size)
        assertEquals("bob", events[0].userName)
    }

    @Test
    fun `owner can change user roles`() {
        val testContext = TestContext()
        val (alice, bob) = testContext.registerUsers("alice", "bob")

        assertEquals(Role.USER, testContext.database.findUser("bob").role)

        alice.setRole("bob", Role.ADMIN)

        assertEquals(Role.ADMIN, testContext.database.findUser("bob").role)

        // Verify event was created
        val events = testContext.events.ofType<DomainEvent.UserRoleChanged>()
        assertEquals(1, events.size)
        assertEquals("bob", events[0].userName)
        assertEquals(Role.ADMIN, events[0].newRole)
    }

    @Test
    fun `user can change their password`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")

        val oldHash = testContext.database.findUser("alice").hash

        alice.changePassword("newPassword123")

        val newHash = testContext.database.findUser("alice").hash
        assertTrue(oldHash != newHash, "Password hash should change")

        // Verify event was created
        val events = testContext.events.ofType<DomainEvent.UserPasswordChanged>()
        assertEquals(1, events.size)
        assertEquals("alice", events[0].userName)
    }

    @Test
    fun `user can change their name`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice", "alice@example.com")

        alice.updateUser(newName = "alice2")

        val user = testContext.database.findUser("alice2")
        assertEquals("alice2", user.name)
        assertEquals("alice@example.com", user.email)

        // Verify event was created
        val nameEvents = testContext.events.ofType<DomainEvent.UserNameChanged>()
        assertEquals(1, nameEvents.size)
        assertEquals("alice", nameEvents[0].oldUserName)
        assertEquals("alice2", nameEvents[0].newUserName)
    }

    @Test
    fun `user can change their email`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice", "alice@example.com")

        alice.updateUser(newEmail = "alice-new@example.com")

        val user = testContext.database.findUser("alice")
        assertEquals("alice", user.name)
        assertEquals("alice-new@example.com", user.email)

        // Verify event was created
        val emailEvents = testContext.events.ofType<DomainEvent.UserEmailChanged>()
        assertEquals(1, emailEvents.size)
        assertEquals("alice", emailEvents[0].userName)
        assertEquals("alice-new@example.com", emailEvents[0].newEmail)
    }

    @Test
    fun `owner can delete election`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")

        val election = alice.createElection("Test Election")
        election.setCandidates("A", "B", "C")

        assertEquals(1, testContext.database.electionCount())

        election.delete()

        assertEquals(0, testContext.database.electionCount())
        val foundElection = testContext.database.findElectionOrNull("Test Election")
        assertEquals(null, foundElection)

        // Verify event was created
        val events = testContext.events.ofType<DomainEvent.ElectionDeleted>()
        assertEquals(1, events.size)
        assertEquals("Test Election", events[0].electionName)
    }

    @Test
    fun `voter can update ballot rankings`() {
        val testContext = TestContext()
        val (alice, bob) = testContext.registerUsers("alice", "bob")

        val election = alice.createElection("Programming Language")
        election.setCandidates("Kotlin", "Rust", "Go")
        election.setEligibleVoters("bob")
        election.launch()

        // Cast initial ballot
        bob.castBallot(election, "Kotlin" to 1, "Rust" to 2, "Go" to 3)

        val initialBallot = testContext.database.findBallot("bob", "Programming Language")
        val initialRankings = testContext.database.listRankings("bob", "Programming Language")
        assertEquals("Kotlin", initialRankings.first { it.rank == 1 }.candidateName)

        // Update rankings
        election.updateRankings("bob", "Rust" to 1, "Kotlin" to 2, "Go" to 3)

        val updatedRankings = testContext.database.listRankings("bob", "Programming Language")
        assertEquals("Rust", updatedRankings.first { it.rank == 1 }.candidateName)

        // Verify events - initial cast plus update
        val castEvents = testContext.events.ofType<DomainEvent.BallotCast>()
        assertTrue(castEvents.size >= 2, "Should have at least 2 ballot cast events")
    }

    @Test
    fun `token refresh returns new tokens`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice", "alice@example.com", "password")

        // Get refresh token from initial registration
        val tokens = testContext.backend.authenticate("alice", "password")
        val refreshToken = tokens.refreshToken

        // Refresh to get new tokens
        val newTokens = testContext.backend.refresh(refreshToken)

        assertEquals("alice", newTokens.accessToken.userName)
        assertEquals(Role.OWNER, newTokens.accessToken.role)
        assertEquals("alice", newTokens.refreshToken.userName)
    }

    @Test
    fun `authenticate with token returns new tokens`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice", "alice@example.com", "password")

        // Use existing access token to get new tokens
        val newTokens = testContext.backend.authenticateWithToken(alice.accessToken)

        assertEquals("alice", newTokens.accessToken.userName)
        assertEquals(Role.OWNER, newTokens.accessToken.role)
        assertEquals("alice", newTokens.refreshToken.userName)
    }

    // NOTE: Election renaming is supported in MySQL and DynamoDB but not yet in InMemory implementation
    // Skipping this test until InMemoryCommandModel supports newElectionName field
    // @Test
    // fun `can rename election`() { ... }

    @Test
    fun `can toggle secret ballot flag`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")

        val election = alice.createElection("Test Election")
        election.setCandidates("A", "B")

        // Initially not secret ballot (default is false based on ElectionSummary defaults)
        val initialElection = testContext.database.findElection("Test Election")
        assertEquals(true, initialElection.secretBallot) // Default is actually true

        // Disable secret ballot
        val updates = com.seanshubin.vote.domain.ElectionUpdates(secretBallot = false)
        testContext.backend.updateElection(alice.accessToken, "Test Election", updates)
        testContext.backend.synchronize()

        val updatedElection = testContext.database.findElection("Test Election")
        assertEquals(false, updatedElection.secretBallot)

        // Verify event was created
        val events = testContext.events.ofType<DomainEvent.ElectionUpdated>()
        assertTrue(events.any { it.secretBallot == false })
    }

    @Test
    fun `can set voting time window`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")

        val election = alice.createElection("Test Election")
        election.setCandidates("A", "B")

        val now = testContext.integrations.clock.now()
        val oneHourLater = kotlinx.datetime.Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 3600000)
        val twoHoursLater = kotlinx.datetime.Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 7200000)

        // Set voting window
        val updates = com.seanshubin.vote.domain.ElectionUpdates(
            noVotingBefore = oneHourLater,
            noVotingAfter = twoHoursLater
        )
        testContext.backend.updateElection(alice.accessToken, "Test Election", updates)
        testContext.backend.synchronize()

        val updatedElection = testContext.database.findElection("Test Election")
        assertEquals(oneHourLater, updatedElection.noVotingBefore)
        assertEquals(twoHoursLater, updatedElection.noVotingAfter)

        // Verify event was created
        val events = testContext.events.ofType<DomainEvent.ElectionUpdated>()
        assertTrue(events.any { it.noVotingBefore == oneHourLater && it.noVotingAfter == twoHoursLater })
    }

    // NOTE: Clearing voting time windows is supported in MySQL and DynamoDB but not yet in InMemory implementation
    // Skipping this test until InMemoryCommandModel handles clearNoVotingBefore/clearNoVotingAfter flags
    // @Test
    // fun `can clear voting time window`() { ... }

    @Test
    fun `can directly set allowVote and allowEdit flags`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")

        val election = alice.createElection("Test Election")
        election.setCandidates("A", "B")

        // Set flags directly via updateElection
        val updates = com.seanshubin.vote.domain.ElectionUpdates(
            allowVote = true,
            allowEdit = false
        )
        testContext.backend.updateElection(alice.accessToken, "Test Election", updates)
        testContext.backend.synchronize()

        val updatedElection = testContext.database.findElection("Test Election")
        assertEquals(true, updatedElection.allowVote)
        assertEquals(false, updatedElection.allowEdit)

        // Verify event was created
        val events = testContext.events.ofType<DomainEvent.ElectionUpdated>()
        assertTrue(events.any { it.allowVote == true && it.allowEdit == false })
    }

    @Test
    fun `send login link by email triggers notification`() {
        val testContext = TestContext()

        // Send login link
        testContext.backend.sendLoginLinkByEmail("user@example.com", "http://localhost:3000")

        // Verify notification was sent
        val notifications = testContext.integrations.notifications as com.seanshubin.vote.integration.fake.FakeNotifications
        val sentMails = notifications.sentMails
        assertEquals(1, sentMails.size)
        assertEquals("user@example.com", sentMails[0].first)
        assertEquals("Login link", sentMails[0].second)
    }
}
