package com.seanshubin.vote.integration

import com.seanshubin.vote.backend.service.ServiceException
import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.domain.Role
import com.seanshubin.vote.integration.dsl.TestContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Moderator-initiated account deletion: who may delete whom, and what the
 * deletion drags down with it. Self-deletion has its own rules (you must not
 * leave orphan elections) and is covered where those rules live; everything
 * here is one user deleting another.
 */
class UserRemovalTest {
    @Test
    fun `admin can delete a user with a lesser role`() {
        val testContext = TestContext()
        testContext.registerUser("owner")
        val admin = testContext.registerUser("admin", Role.ADMIN)
        testContext.registerUser("bob", Role.VOTER)

        admin.removeUser("bob")

        assertNull(testContext.database.searchUser("bob"))
        assertEquals(listOf("bob"), testContext.events.ofType<DomainEvent.UserRemoved>().map { it.userName })
        assertEquals(listOf("admin"), testContext.events.authoritiesOf<DomainEvent.UserRemoved>())
    }

    @Test
    fun `deleting a user deletes the ballots they cast`() {
        val testContext = TestContext()
        val owner = testContext.registerUser("owner")
        val bob = testContext.registerUser("bob", Role.VOTER)
        val election = owner.createElection("Best Planet")
        election.addCandidates("Mars", "Venus")
        bob.castBallot(election, "Mars" to 1, "Venus" to 2)
        assertEquals(1, testContext.database.listBallots("Best Planet").size)

        owner.removeUser("bob")

        assertTrue(testContext.database.listBallots("Best Planet").isEmpty())
        assertNull(testContext.database.searchBallot("bob", "Best Planet"))
        // The election itself survives — bob only voted in it, he didn't own it.
        assertEquals("Best Planet", testContext.database.findElection("Best Planet").electionName)
    }

    @Test
    fun `deleting a user deletes the candidate notes they wrote`() {
        val testContext = TestContext()
        val owner = testContext.registerUser("owner")
        val bob = testContext.registerUser("bob", Role.VOTER)
        val election = owner.createElection("Best Planet")
        election.addCandidates("Mars")
        bob.setCandidateNote("Best Planet", "Mars", "Too dusty")
        assertEquals(1, owner.listCandidateNotes("Best Planet", "Mars").size)

        owner.removeUser("bob")

        assertTrue(owner.listCandidateNotes("Best Planet", "Mars").isEmpty())
    }

    @Test
    fun `deleting a user deletes the elections they own and everyone's ballots on them`() {
        val testContext = TestContext()
        val owner = testContext.registerUser("owner")
        val bob = testContext.registerUser("bob", Role.USER)
        val carol = testContext.registerUser("carol", Role.VOTER)
        val bobsElection = bob.createElection("Bob's Election")
        bobsElection.addCandidates("Red", "Blue")
        carol.castBallot(bobsElection, "Red" to 1, "Blue" to 2)
        carol.setCandidateNote("Bob's Election", "Red", "Classic")
        bobsElection.addManager("carol")

        owner.removeUser("bob")

        // The whole election goes, not just bob's own rows: carol's ballot and
        // note lived inside bob's election, so they go with it.
        assertNull(testContext.database.searchElection("Bob's Election"))
        assertEquals(0, testContext.database.electionCount())
        assertTrue(testContext.database.listBallots("Bob's Election").isEmpty())
        assertTrue(testContext.database.listCandidates("Bob's Election").isEmpty())
        // carol herself is untouched.
        assertEquals("carol", testContext.database.findUser("carol").name)
    }

    @Test
    fun `deleting a user leaves other owners' elections alone`() {
        val testContext = TestContext()
        val owner = testContext.registerUser("owner")
        val bob = testContext.registerUser("bob", Role.USER)
        val carol = testContext.registerUser("carol", Role.USER)
        bob.createElection("Bob's Election")
        val carolsElection = carol.createElection("Carol's Election")
        carolsElection.addCandidates("Up", "Down")
        carolsElection.addManager("bob")

        owner.removeUser("bob")

        assertEquals(
            listOf("Carol's Election"),
            testContext.database.listElections().map { it.electionName },
        )
        assertEquals(listOf("Down", "Up"), testContext.database.listCandidates("Carol's Election").sorted())
        // Only bob's co-manager grant on it is revoked.
        assertTrue(carolsElection.getDetails().managers.isEmpty())
    }

    @Test
    fun `admin cannot delete a peer admin or a superior`() {
        val testContext = TestContext()
        testContext.registerUser("owner")
        val admin = testContext.registerUser("admin", Role.ADMIN)
        testContext.registerUser("other-admin", Role.ADMIN)
        testContext.registerUser("auditor", Role.AUDITOR)

        assertFailsWith<ServiceException> { admin.removeUser("other-admin") }
        assertFailsWith<ServiceException> { admin.removeUser("auditor") }
        assertFailsWith<ServiceException> { admin.removeUser("owner") }

        assertEquals(4, testContext.database.userCount())
        assertTrue(testContext.events.ofType<DomainEvent.UserRemoved>().isEmpty())
    }

    @Test
    fun `a user without MANAGE_USERS cannot delete anyone else`() {
        val testContext = TestContext()
        testContext.registerUser("owner")
        val bob = testContext.registerUser("bob", Role.USER)
        testContext.registerUser("carol", Role.VOTER)

        assertFailsWith<ServiceException> { bob.removeUser("carol") }

        assertEquals(3, testContext.database.userCount())
    }

    @Test
    fun `listUsers reports who the caller may delete`() {
        val testContext = TestContext()
        testContext.registerUser("owner")
        val admin = testContext.registerUser("admin", Role.ADMIN)
        testContext.registerUser("other-admin", Role.ADMIN)
        testContext.registerUser("bob", Role.VOTER)

        val removable = admin.listUsers().filter { it.canRemove }.map { it.userName }

        // Everyone with a strictly lesser role — not the caller, not a peer,
        // not the owner.
        assertEquals(listOf("bob"), removable)
    }

    @Test
    fun `listUsers reports the footprint of each deletable user`() {
        val testContext = TestContext()
        val owner = testContext.registerUser("owner")
        val bob = testContext.registerUser("bob", Role.USER)
        val bobsElection = bob.createElection("Bob's Election")
        bobsElection.addCandidates("Red", "Blue")
        bob.createElection("Bob's Other Election")
        val ownersElection = owner.createElection("Owner's Election")
        ownersElection.addCandidates("Up", "Down")
        bob.castBallot(bobsElection, "Red" to 1, "Blue" to 2)
        bob.castBallot(ownersElection, "Up" to 1, "Down" to 2)

        val bobRow = owner.listUsers().single { it.userName == "bob" }

        assertTrue(bobRow.canRemove)
        assertEquals(2, bobRow.electionsOwnedCount)
        assertEquals(2, bobRow.ballotsCastCount)
    }

    @Test
    fun `listUsers reports no footprint for rows the caller cannot delete`() {
        val testContext = TestContext()
        val owner = testContext.registerUser("owner")
        val election = owner.createElection("Owner's Election")
        election.addCandidates("Up", "Down")
        owner.castBallot(election, "Up" to 1, "Down" to 2)

        // The caller's own row is never deletable, so the counts stay at their
        // "not applicable" zero rather than describing a delete that can't happen.
        val ownRow = owner.listUsers().single { it.userName == "owner" }

        assertEquals(false, ownRow.canRemove)
        assertEquals(0, ownRow.electionsOwnedCount)
        assertEquals(0, ownRow.ballotsCastCount)
    }
}
