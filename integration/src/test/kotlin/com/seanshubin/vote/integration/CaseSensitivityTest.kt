package com.seanshubin.vote.integration

import com.seanshubin.vote.backend.service.ServiceException
import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.integration.dsl.TestContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Names in this app are case-insensitive — only passwords would be
 * case-sensitive, and the app has no user passwords (Discord OAuth only).
 * These tests pin that contract at the service boundary: case-only
 * duplicates are rejected, case-only references resolve to the canonical
 * stored entity, and the audit log records the canonical case regardless
 * of what case the caller used.
 */
class CaseSensitivityTest {

    @Test
    fun `addElection rejects case-only duplicate of existing election`() {
        val testContext = TestContext()
        val (alice) = testContext.registerUsers("alice")
        alice.createElection("Best Movie")

        val ex = assertFailsWith<IllegalArgumentException> {
            alice.createElection("BEST MOVIE")
        }
        assertTrue(ex.message!!.contains("already exists"))
    }

    @Test
    fun `addCandidates skips case-only duplicate of existing candidate`() {
        val testContext = TestContext()
        val (alice) = testContext.registerUsers("alice")
        val election = alice.createElection("Best Color")
        election.addCandidates("Red", "Blue")
        election.addCandidates("RED", "Green")

        // "RED" was a case-only duplicate of "Red" — the original case
        // wins, only "Green" is actually added.
        assertEquals(listOf("Blue", "Green", "Red"), election.candidates.sorted())
    }

    @Test
    fun `addCandidates rejects case-only duplicates within the same call`() {
        val testContext = TestContext()
        val (alice) = testContext.registerUsers("alice")
        val election = alice.createElection("Best Sport")

        val ex = assertFailsWith<IllegalArgumentException> {
            election.addCandidates("Tennis", "tennis")
        }
        assertTrue(ex.message!!.contains("Duplicate"))
    }

    @Test
    fun `removeCandidate resolves case-only reference to the canonical candidate`() {
        val testContext = TestContext()
        val (alice) = testContext.registerUsers("alice")
        val election = alice.createElection("Best Drink")
        election.addCandidates("Coffee", "Tea")

        // Caller uses "COFFEE" — should remove the stored "Coffee".
        testContext.backend.removeCandidate(alice.accessToken, election.name, "COFFEE")
        testContext.backend.synchronize()

        assertEquals(listOf("Tea"), election.candidates)
        // Event records the canonical case, not the caller's case.
        val removed = testContext.events.ofType<DomainEvent.CandidatesRemoved>().single()
        assertEquals(listOf("Coffee"), removed.candidateNames)
    }

    @Test
    fun `renameCandidate rejects collision with different existing candidate`() {
        val testContext = TestContext()
        val (alice) = testContext.registerUsers("alice")
        val election = alice.createElection("Best Pet")
        election.addCandidates("Dog", "Cat")

        // Renaming "Dog" → "CAT" would collide with the existing "Cat" —
        // reject.
        val ex = assertFailsWith<ServiceException> {
            testContext.backend.renameCandidate(alice.accessToken, election.name, "Dog", "CAT")
        }
        assertEquals(ServiceException.Category.CONFLICT, ex.category)
    }

    @Test
    fun `renameCandidate allows case-only rename of the candidate itself`() {
        val testContext = TestContext()
        val (alice) = testContext.registerUsers("alice")
        val election = alice.createElection("Best Book")
        election.addCandidates("Dune")

        // "Dune" → "DUNE" is a case-only rename of the same candidate —
        // allowed (mirrors how username rename treats case-only changes).
        testContext.backend.renameCandidate(alice.accessToken, election.name, "Dune", "DUNE")
        testContext.backend.synchronize()

        assertEquals(listOf("DUNE"), election.candidates)
    }

    @Test
    fun `setTiers rejects case-only duplicates within the input`() {
        val testContext = TestContext()
        val (alice) = testContext.registerUsers("alice")
        val election = alice.createElection("Best Game")

        val ex = assertFailsWith<IllegalArgumentException> {
            election.setTiers("Gold", "gold")
        }
        assertTrue(ex.message!!.contains("Duplicate"))
    }

    @Test
    fun `renameTier rejects collision with a different tier`() {
        val testContext = TestContext()
        val (alice) = testContext.registerUsers("alice")
        val election = alice.createElection("Best Cuisine")
        election.setTiers("Gold", "Silver", "Bronze")

        val ex = assertFailsWith<ServiceException> {
            testContext.backend.renameTier(alice.accessToken, election.name, "Bronze", "GOLD")
        }
        assertEquals(ServiceException.Category.CONFLICT, ex.category)
    }

    @Test
    fun `service operations accept case-variant election name and resolve canonically`() {
        val testContext = TestContext()
        val (alice) = testContext.registerUsers("alice")
        alice.createElection("Best Album")

        // Subsequent op references the election with a different case;
        // service resolves to the canonical stored name and the event
        // records that canonical name.
        testContext.backend.addCandidates(alice.accessToken, "BEST ALBUM", listOf("Thriller"))
        testContext.backend.synchronize()

        val added = testContext.events.ofType<DomainEvent.CandidatesAdded>().single()
        assertEquals("Best Album", added.electionName)
        assertEquals(listOf("Thriller"), added.candidateNames)
    }

    @Test
    fun `castBallot canonicalizes candidate names in the stored ranking`() {
        val testContext = TestContext()
        val (alice, bob) = testContext.registerUsers("alice", "bob")
        val election = alice.createElection("Best Snack")
        election.addCandidates("Popcorn", "Chips")

        // Bob casts a ballot using "POPCORN" — the stored ranking should
        // carry the canonical "Popcorn".
        bob.castBallot(election, "POPCORN" to 1)

        val cast = testContext.events.ofType<DomainEvent.BallotCast>().single()
        assertEquals(listOf("Popcorn"), cast.rankings.map { it.candidateName })
        assertEquals("Best Snack", cast.electionName)
    }
}
