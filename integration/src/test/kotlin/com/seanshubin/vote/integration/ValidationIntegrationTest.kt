package com.seanshubin.vote.integration

import com.seanshubin.vote.integration.dsl.TestContext
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ValidationIntegrationTest {

    @Test
    fun `castBallot validates rankings match candidates`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")

        val election = alice.createElection("Test Election")
        election.setCandidates("Candidate A", "Candidate B")

        // Try to vote for a candidate that doesn't exist
        val exception = assertFailsWith<IllegalArgumentException> {
            alice.castBallot(election, "Candidate A" to 1, "Unknown Candidate" to 2)
        }
        assertTrue(exception.message!!.contains("unknown"))
    }

    @Test
    fun `setCandidates rejects duplicate names`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")

        val election = alice.createElection("Test Election")

        // Try to set candidates with duplicates
        val exception = assertFailsWith<IllegalArgumentException> {
            election.setCandidates("Alice", "Bob", "Alice")
        }
        assertTrue(exception.message!!.contains("Duplicate"))

        // Verify no candidates were added
        val candidates = election.candidates
        assertEquals(0, candidates.size)
    }

    @Test
    fun `setCandidates accepts empty list (clears candidates)`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")

        val election = alice.createElection("Test Election")
        election.setCandidates("Kotlin", "Rust")

        // Now clear with an empty list — supported as a "reset to no candidates"
        // operation. The owner can re-add later before launching.
        election.setCandidates()

        val candidates = election.listCandidates()
        assertEquals(0, candidates.size)
    }

    @Test
    fun `setCandidates normalizes candidate names`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")

        val election = alice.createElection("Test Election")

        // Set candidates with extra whitespace
        election.setCandidates("  Alice  ", "  Bob  ", "Charlie")

        // Names should be normalized
        val candidates = election.candidates
        assertEquals(3, candidates.size)
        assertTrue(candidates.contains("Alice"))
        assertTrue(candidates.contains("Bob"))
        assertTrue(candidates.contains("Charlie"))
    }

    @Test
    fun `addElection rejects duplicate election names`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")

        alice.createElection("My Election")

        // Try to create election with same name
        val exception = assertFailsWith<IllegalArgumentException> {
            alice.createElection("My Election")
        }
        assertTrue(exception.message!!.contains("already exists"))
    }

    @Test
    fun `addElection validates election name`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")

        // Try to create election with empty name
        val exception = assertFailsWith<IllegalArgumentException> {
            alice.createElection("")
        }
        assertTrue(exception.message!!.contains("empty"))
    }

    @Test
    fun `addElection normalizes election name`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")

        alice.createElection("  My   Election  ")

        // Name should be normalized
        val elections = testContext.database.listElections()
        assertEquals(1, elections.size)
        assertEquals("My Election", elections[0].electionName)
    }

    @Test
    fun `updateUser rejects duplicate user name`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")
        testContext.registerUser("bob")

        // Try to rename alice to bob (duplicate)
        val exception = assertFailsWith<IllegalArgumentException> {
            alice.updateUser(newName = "bob")
        }
        assertTrue(exception.message!!.contains("already exists"))
    }

    @Test
    fun `updateUser rejects duplicate email`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice", "alice@example.com")
        testContext.registerUser("bob", "bob@example.com")

        // Try to change alice's email to bob's email
        val exception = assertFailsWith<IllegalArgumentException> {
            alice.updateUser(newEmail = "bob@example.com")
        }
        assertTrue(exception.message!!.contains("already exists"))
    }

    @Test
    fun `castBallot accepts valid rankings`() {
        val testContext = TestContext()
        val alice = testContext.registerUser("alice")

        val election = alice.createElection("Test Election")
        election.setCandidates("Candidate A", "Candidate B")

        alice.castBallot(election, "Candidate A" to 1, "Candidate B" to 2)

        val ballots = testContext.database.listBallots("Test Election")
        assertEquals(1, ballots.size)
    }
}
