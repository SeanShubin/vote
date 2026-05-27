package com.seanshubin.vote.integration

import com.seanshubin.vote.backend.service.ServiceException
import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.integration.dsl.TestContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CandidateNoteTest {
    @Test
    fun `voter can attach a note to a candidate`() {
        val testContext = TestContext()
        val (alice, _) = testContext.registerUsers("alice", "bob")
        val election = alice.createElection("Favorite Fruit")
        election.addCandidates("Apple", "Banana")

        alice.setCandidateNote(election.name, "Apple", "Best on a hot day")

        val notes = alice.listCandidateNotes(election.name, "Apple")
        assertEquals(1, notes.size)
        assertEquals("alice", notes[0].voterName)
        assertEquals("Best on a hot day", notes[0].text)

        val events = testContext.events.ofType<DomainEvent.CandidateNoteSet>()
        assertEquals(1, events.size)
        assertEquals("Favorite Fruit", events[0].electionName)
        assertEquals("Apple", events[0].candidateName)
        assertEquals("alice", events[0].voterName)
        assertEquals("Best on a hot day", events[0].text)
    }

    @Test
    fun `two voters can attach distinct notes to the same candidate`() {
        val testContext = TestContext()
        val (alice, bob) = testContext.registerUsers("alice", "bob")
        val election = alice.createElection("Favorite Fruit")
        election.addCandidates("Apple")

        alice.setCandidateNote(election.name, "Apple", "Crisp")
        bob.setCandidateNote(election.name, "Apple", "Mealy")

        // Either voter sees both notes.
        val seenByAlice = alice.listCandidateNotes(election.name, "Apple")
            .associateBy { it.voterName }
        assertEquals(2, seenByAlice.size)
        assertEquals("Crisp", seenByAlice.getValue("alice").text)
        assertEquals("Mealy", seenByAlice.getValue("bob").text)
        assertEquals(seenByAlice, bob.listCandidateNotes(election.name, "Apple")
            .associateBy { it.voterName })
    }

    @Test
    fun `re-saving a note replaces the prior text`() {
        val testContext = TestContext()
        val (alice, _) = testContext.registerUsers("alice")
        val election = alice.createElection("Favorite Color")
        election.addCandidates("Blue")

        alice.setCandidateNote(election.name, "Blue", "First take")
        alice.setCandidateNote(election.name, "Blue", "Second thought")

        val notes = alice.listCandidateNotes(election.name, "Blue")
        assertEquals(1, notes.size)
        assertEquals("Second thought", notes[0].text)
    }

    @Test
    fun `empty text deletes the voters note`() {
        val testContext = TestContext()
        val (alice, _) = testContext.registerUsers("alice", "bob")
        val election = alice.createElection("Favorite Movie")
        election.addCandidates("Dune")
        alice.setCandidateNote(election.name, "Dune", "epic")

        alice.setCandidateNote(election.name, "Dune", "")

        assertTrue(alice.listCandidateNotes(election.name, "Dune").isEmpty())
        val deleted = testContext.events.ofType<DomainEvent.CandidateNoteDeleted>()
        assertEquals(1, deleted.size)
        assertEquals("alice", deleted[0].voterName)
    }

    @Test
    fun `clearing an absent note is a no-op (no event)`() {
        val testContext = TestContext()
        val (alice, _) = testContext.registerUsers("alice")
        val election = alice.createElection("Favorite Pet")
        election.addCandidates("Dog")

        alice.setCandidateNote(election.name, "Dog", "")

        assertTrue(testContext.events.ofType<DomainEvent.CandidateNoteSet>().isEmpty())
        assertTrue(testContext.events.ofType<DomainEvent.CandidateNoteDeleted>().isEmpty())
    }

    @Test
    fun `note text is trimmed and capped`() {
        val testContext = TestContext()
        val (alice, _) = testContext.registerUsers("alice")
        val election = alice.createElection("Favorite Drink")
        election.addCandidates("Tea")

        alice.setCandidateNote(election.name, "Tea", "   hot and strong   ")
        assertEquals("hot and strong", alice.listCandidateNotes(election.name, "Tea")[0].text)

        val ex = assertFailsWith<IllegalArgumentException> {
            alice.setCandidateNote(election.name, "Tea", "x".repeat(4001))
        }
        assertTrue(ex.message!!.contains("4000"))
    }

    @Test
    fun `notes cascade when election is deleted`() {
        val testContext = TestContext()
        val (alice, _) = testContext.registerUsers("alice")
        val election = alice.createElection("Favorite Game")
        election.addCandidates("Chess")
        alice.setCandidateNote(election.name, "Chess", "tactical")

        election.delete()
        alice.createElection("Favorite Game")
        // re-add candidate but expect no surviving notes from the deleted election
        testContext.backend.addCandidates(alice.accessToken, "Favorite Game", listOf("Chess"))
        testContext.backend.synchronize()

        assertTrue(alice.listCandidateNotes("Favorite Game", "Chess").isEmpty())
    }

    @Test
    fun `notes cascade when candidate is removed`() {
        val testContext = TestContext()
        val (alice, _) = testContext.registerUsers("alice")
        val election = alice.createElection("Favorite Show")
        election.addCandidates("Lost", "Heroes")
        alice.setCandidateNote(election.name, "Lost", "weird ending")
        alice.setCandidateNote(election.name, "Heroes", "lost steam")

        election.removeCandidate("Lost")

        // Re-adding the candidate must not resurrect old notes.
        election.addCandidates("Lost")
        assertTrue(alice.listCandidateNotes(election.name, "Lost").isEmpty())
        // The other candidate's note is unaffected.
        assertEquals(1, alice.listCandidateNotes(election.name, "Heroes").size)
    }

    @Test
    fun `notes follow a candidate rename`() {
        val testContext = TestContext()
        val (alice, bob) = testContext.registerUsers("alice", "bob")
        val election = alice.createElection("Favorite Letter")
        election.addCandidates("A")
        alice.setCandidateNote(election.name, "A", "first")
        bob.setCandidateNote(election.name, "A", "alphabetical")

        testContext.backend.renameCandidate(alice.accessToken, election.name, "A", "Alpha")
        testContext.backend.synchronize()

        // The candidate "A" no longer exists, so listing notes on it fails;
        // listing under "Alpha" returns the moved notes.
        val moved = alice.listCandidateNotes(election.name, "Alpha")
            .associateBy { it.voterName }
        assertEquals("first", moved.getValue("alice").text)
        assertEquals("alphabetical", moved.getValue("bob").text)
    }

    @Test
    fun `removing a user drops their notes`() {
        val testContext = TestContext()
        val (alice, bob) = testContext.registerUsers("alice", "bob")
        val election = alice.createElection("Favorite Pet")
        election.addCandidates("Cat")
        alice.setCandidateNote(election.name, "Cat", "purrs")
        bob.setCandidateNote(election.name, "Cat", "scratches")

        alice.removeUser("bob")

        val remaining = alice.listCandidateNotes(election.name, "Cat")
        assertEquals(1, remaining.size)
        assertEquals("alice", remaining[0].voterName)
    }

    @Test
    fun `listing notes for an unknown candidate fails`() {
        val testContext = TestContext()
        val (alice, _) = testContext.registerUsers("alice")
        val election = alice.createElection("Favorite Sport")
        election.addCandidates("Soccer")

        val ex = assertFailsWith<ServiceException> {
            alice.listCandidateNotes(election.name, "Cricket")
        }
        assertEquals(ServiceException.Category.NOT_FOUND, ex.category)
    }
}
