package com.seanshubin.vote.integration

import com.seanshubin.vote.domain.Role
import com.seanshubin.vote.integration.database.DatabaseProvider
import com.seanshubin.vote.integration.database.DynamoDBDatabaseProvider
import com.seanshubin.vote.integration.database.MySQLDatabaseProvider
import com.seanshubin.vote.integration.dsl.TestContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cross-backend parity for the user-deletion cascade, run against real
 * MySQL and DynamoDB (TestContainers).
 *
 * The three stores reach the same end state by very different means: MySQL
 * leans on `ON DELETE CASCADE` from `elections.owner_name` and
 * `ballots.voter_name`, while DynamoDB and InMemory spell every cascade out
 * by hand (DynamoDB via the owner-name / voter-name / manager-user sparse
 * GSIs). That divergence in mechanism is exactly why this needs a parity
 * test and not just the InMemory coverage in [UserRemovalTest] — a missing
 * hand-written sweep looks identical to a working FK until you run it.
 */
class UserRemovalCascadeAcrossBackendsTest {

    @Test
    fun `MySQL — deleting a user cascades to their ballots, notes, grants and owned elections`() {
        MySQLDatabaseProvider().use { assertCascade(it) }
    }

    @Test
    fun `DynamoDB — deleting a user cascades to their ballots, notes, grants and owned elections`() {
        DynamoDBDatabaseProvider().use { assertCascade(it) }
    }

    private fun assertCascade(provider: DatabaseProvider) {
        val testContext = TestContext(provider = provider)
        val owner = testContext.registerUser("owner")
        val bob = testContext.registerUser("bob", Role.USER)
        val carol = testContext.registerUser("carol", Role.USER)

        // bob owns an election that carol participates in …
        val bobsElection = bob.createElection("Bobs Election")
        bobsElection.addCandidates("Red", "Blue")
        carol.castBallot(bobsElection, "Red" to 1, "Blue" to 2)
        carol.setCandidateNote("Bobs Election", "Red", "Classic")
        bobsElection.addManager("carol")

        // … and participates in one owned by someone else.
        val ownersElection = owner.createElection("Owners Election")
        ownersElection.addCandidates("Up", "Down")
        bob.castBallot(ownersElection, "Up" to 1, "Down" to 2)
        bob.setCandidateNote("Owners Election", "Up", "Only sensible choice")
        ownersElection.addManager("bob")

        owner.removeUser("bob")

        val db = testContext.database
        assertNull(db.searchUser("bob"), "the user row itself is gone")

        // Everything bob owned goes, including the rows carol put inside it.
        assertNull(db.searchElection("Bobs Election"), "an election owned by a deleted user is deleted")
        assertTrue(db.listCandidates("Bobs Election").isEmpty(), "its candidates go with it")
        assertTrue(db.listBallots("Bobs Election").isEmpty(), "so do every voter's ballots on it")
        assertNull(db.searchBallot("carol", "Bobs Election"), "including carol's")

        // Everything bob left in other people's elections goes too, but the
        // elections themselves are untouched.
        assertEquals(
            listOf("Owners Election"),
            db.listElections().map { it.electionName },
            "elections owned by other users survive",
        )
        assertEquals(listOf("Down", "Up"), db.listCandidates("Owners Election").sorted())
        assertNull(db.searchBallot("bob", "Owners Election"), "bob's ballot elsewhere is dropped")
        assertTrue(db.listBallots("Owners Election").isEmpty())
        assertTrue(
            owner.listCandidateNotes("Owners Election", "Up").isEmpty(),
            "bob's note elsewhere is dropped",
        )
        assertTrue(
            ownersElection.getDetails().managers.isEmpty(),
            "bob's co-manager grant elsewhere is revoked",
        )

        // carol is a bystander in all of this.
        assertEquals("carol", db.findUser("carol").name)
        assertEquals(
            listOf("carol", "owner"),
            db.listUsers().map { it.name }.sorted(),
        )
    }
}
