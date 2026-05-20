package com.seanshubin.vote.integration

import com.seanshubin.vote.backend.service.ServiceException
import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.domain.Role
import com.seanshubin.vote.integration.dsl.TestContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ElectionRenameTest {
    @Test
    fun `owner can rename an election and all data carries over`() {
        val testContext = TestContext()
        val (alice, _) = testContext.registerUsers("alice", "bob")
        val charlie = testContext.registerUser("charlie")

        val election = alice.createElection("Favorite Fruit", description = "Pick one")
        election.addCandidates("Apple", "Banana", "Cherry")
        election.setTiers("Great", "Okay")
        election.addManager("bob")
        charlie.castBallot(election, "Apple" to 1, "Banana" to 2, "Cherry" to 3)

        val renamed = election.rename("Best Fruit")

        val details = renamed.getDetails()
        assertEquals("Best Fruit", details.electionName)
        assertEquals("alice", details.ownerName)
        assertEquals("Pick one", details.description)
        assertEquals(3, details.candidateCount)
        assertEquals(1, details.ballotCount)
        assertEquals(listOf("Great", "Okay"), details.tiers)
        assertEquals(listOf("bob"), details.managers)
        assertEquals(listOf("Apple", "Banana", "Cherry"), renamed.listCandidates().sorted())

        // The ballot survived the cascade under the new election name.
        assertEquals(3, charlie.listRankings("Best Fruit").size)

        // The old name no longer resolves to an election.
        val ex = assertFailsWith<ServiceException> {
            testContext.backend.getElection(alice.accessToken, "Favorite Fruit")
        }
        assertEquals(ServiceException.Category.NOT_FOUND, ex.category)

        val events = testContext.events.ofType<DomainEvent.ElectionNameChanged>()
        assertEquals(1, events.size)
        assertEquals("Favorite Fruit", events[0].oldName)
        assertEquals("Best Fruit", events[0].newName)
    }

    @Test
    fun `case-only rename is allowed and updates the display name`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")
        val election = alice.createElection("Best Movie")

        val renamed = election.rename("best movie")

        assertEquals("best movie", renamed.getDetails().electionName)
        assertEquals(1, testContext.events.ofType<DomainEvent.ElectionNameChanged>().size)
    }

    @Test
    fun `renaming to the same name is a no-op and emits no event`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")
        alice.createElection("Best Color")

        testContext.backend.renameElection(alice.accessToken, "Best Color", "Best Color")
        testContext.backend.synchronize()

        assertEquals(0, testContext.events.ofType<DomainEvent.ElectionNameChanged>().size)
    }

    @Test
    fun `renaming onto an existing election name fails with CONFLICT`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")
        alice.createElection("Best Movie")
        alice.createElection("Best Book")

        val ex = assertFailsWith<ServiceException> {
            testContext.backend.renameElection(alice.accessToken, "Best Movie", "Best Book")
        }
        assertEquals(ServiceException.Category.CONFLICT, ex.category)
    }

    @Test
    fun `a manager cannot rename the election`() {
        val testContext = TestContext()
        val (alice, bob) = testContext.registerUsers("alice", "bob")
        val election = alice.createElection("Best Number")
        election.addManager("bob")

        // bob can edit content (candidates/tiers/description) but the name is
        // identity, not content — only the owner or an ADMIN may change it.
        val ex = assertFailsWith<ServiceException> {
            testContext.backend.renameElection(bob.accessToken, election.name, "Worst Number")
        }
        assertEquals(ServiceException.Category.UNAUTHORIZED, ex.category)
        assertEquals("Best Number", election.getDetails().electionName)
    }

    @Test
    fun `an ADMIN can rename an election they do not own`() {
        val testContext = TestContext()
        val (alice, _) = testContext.registerUsers("alice", "bob")
        alice.setRole("bob", Role.ADMIN)
        val bobAdmin = testContext.reissueToken("bob")
        val election = alice.createElection("Best Game")

        testContext.backend.renameElection(bobAdmin.accessToken, election.name, "Best Board Game")
        testContext.backend.synchronize()

        assertEquals("Best Board Game", bobAdmin.getElection("Best Board Game").electionName)
    }

    @Test
    fun `renaming a missing election fails with NOT_FOUND`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")

        val ex = assertFailsWith<ServiceException> {
            testContext.backend.renameElection(alice.accessToken, "Nonexistent", "Whatever")
        }
        assertEquals(ServiceException.Category.NOT_FOUND, ex.category)
    }

    @Test
    fun `rename replays correctly from the event log`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")
        val election = alice.createElection("Best Snack")
        election.addCandidates("Chips", "Nuts")
        val renamed = election.rename("Best Treat")

        // Re-run synchronize against the same projection — the event applier
        // should be idempotent.
        testContext.backend.synchronize()

        val details = renamed.getDetails()
        assertEquals("Best Treat", details.electionName)
        assertEquals(2, details.candidateCount)
    }
}
