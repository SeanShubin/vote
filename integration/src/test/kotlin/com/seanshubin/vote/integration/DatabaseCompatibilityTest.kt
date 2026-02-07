package com.seanshubin.vote.integration

import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.domain.ElectionUpdates
import com.seanshubin.vote.domain.Permission
import com.seanshubin.vote.domain.Ranking
import com.seanshubin.vote.domain.Role
import com.seanshubin.vote.integration.database.DatabaseProvider
import com.seanshubin.vote.integration.database.DynamoDBDatabaseProvider
import com.seanshubin.vote.integration.database.InMemoryDatabaseProvider
import com.seanshubin.vote.integration.database.MySQLDatabaseProvider
import kotlinx.datetime.Instant
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Comprehensive test that verifies all three storage implementations behave identically.
 * Tests database operations without re-testing business logic.
 */
class DatabaseCompatibilityTest {
    companion object {
        @JvmStatic
        fun providerNames(): Stream<String> = Stream.of(
            "InMemory",
            "MySQL",
            "DynamoDB"
        )
    }

    private var currentProvider: DatabaseProvider? = null

    @AfterEach
    fun cleanup() {
        currentProvider?.close()
        currentProvider = null
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providerNames")
    fun `all database operations work identically across storage implementations`(providerName: String) {
        // Lazily create provider to avoid Docker initialization during test discovery
        val provider = when (providerName) {
            "InMemory" -> InMemoryDatabaseProvider()
            "MySQL" -> MySQLDatabaseProvider()
            "DynamoDB" -> DynamoDBDatabaseProvider()
            else -> throw IllegalArgumentException("Unknown provider: $providerName")
        }
        currentProvider = provider
        val eventLog = provider.eventLog
        val commandModel = provider.commandModel
        val queryModel = provider.queryModel

        val now = Instant.parse("2024-01-01T00:00:00Z")

        // ========== Event Log Operations ==========

        // Append events
        eventLog.appendEvent("system", now, DomainEvent.UserRegistered("alice", "alice@example.com", "salt1", "hash1", Role.OWNER))
        eventLog.appendEvent("system", now, DomainEvent.UserRegistered("bob", "bob@example.com", "salt2", "hash2", Role.USER))

        // Query events
        val eventsAfter0 = eventLog.eventsToSync(0)
        assertEquals(2, eventsAfter0.size, "Should have 2 events after sync point 0")
        assertEquals(1, eventsAfter0[0].eventId)
        assertEquals(2, eventsAfter0[1].eventId)

        val eventsAfter1 = eventLog.eventsToSync(1)
        assertEquals(1, eventsAfter1.size, "Should have 1 event after sync point 1")

        assertEquals(2, eventLog.eventCount())

        // ========== User Operations ==========

        // Sync events to command model
        commandModel.initializeLastSynced(0)
        eventsAfter0.forEach { envelope ->
            if (envelope.event is DomainEvent.UserRegistered) {
                val event = envelope.event as DomainEvent.UserRegistered
                commandModel.createUser(envelope.authority, event.name, event.email, event.salt, event.hash, event.role)
            }
            commandModel.setLastSynced(envelope.eventId)
        }

        // Query users
        assertEquals(2, queryModel.userCount())
        val alice = queryModel.findUserByName("alice")
        assertEquals("alice", alice.name)
        assertEquals("alice@example.com", alice.email)
        assertEquals(Role.OWNER, alice.role)

        val bob = queryModel.searchUserByName("bob")
        assertNotNull(bob)
        assertEquals("bob", bob!!.name)
        assertEquals(Role.USER, bob.role)

        // Find by email
        val aliceByEmail = queryModel.findUserByEmail("alice@example.com")
        assertEquals("alice", aliceByEmail.name)

        // Update user role
        commandModel.setRole("admin", "bob", Role.ADMIN)
        val bobUpdated = queryModel.findUserByName("bob")
        assertEquals(Role.ADMIN, bobUpdated.role)

        // Update password
        commandModel.setPassword("bob", "bob", "newSalt", "newHash")
        val bobWithNewPassword = queryModel.findUserByName("bob")
        assertEquals("newSalt", bobWithNewPassword.salt)
        assertEquals("newHash", bobWithNewPassword.hash)

        // Update email
        commandModel.setEmail("bob", "bob", "bob-new@example.com")
        val bobWithNewEmail = queryModel.findUserByName("bob")
        assertEquals("bob-new@example.com", bobWithNewEmail.email)

        // Search user by email
        val bobByNewEmail = queryModel.searchUserByEmail("bob-new@example.com")
        assertNotNull(bobByNewEmail)
        assertEquals("bob", bobByNewEmail!!.name)

        // Update user name
        commandModel.setUserName("bob", "bob", "robert")
        val renamedUser = queryModel.findUserByName("robert")
        assertEquals("robert", renamedUser.name)
        assertEquals("bob-new@example.com", renamedUser.email)
        assertNull(queryModel.searchUserByName("bob"))

        // List user names
        val userNames = queryModel.listUserNames()
        assertTrue(userNames.contains("alice"))
        assertTrue(userNames.contains("robert"))
        assertFalse(userNames.contains("bob"))

        // List all users
        val allUsers = queryModel.listUsers()
        assertEquals(2, allUsers.size)

        // ========== Election Operations ==========

        // Create election
        eventLog.appendEvent("alice", now, DomainEvent.ElectionCreated("alice", "Best Language"))
        commandModel.addElection("alice", "alice", "Best Language")

        assertEquals(1, queryModel.electionCount())
        val election = queryModel.searchElectionByName("Best Language")
        assertNotNull(election)
        assertEquals("alice", election!!.ownerName)
        assertEquals("Best Language", election.electionName)

        // List elections
        val elections = queryModel.listElections()
        assertEquals(1, elections.size)

        // Update election
        commandModel.updateElection("alice", "Best Language", ElectionUpdates(allowVote = true, allowEdit = false))
        val updatedElection = queryModel.searchElectionByName("Best Language")
        assertNotNull(updatedElection)
        assertEquals(true, updatedElection!!.allowVote)
        assertEquals(false, updatedElection.allowEdit)

        // ========== Candidate Operations ==========

        // Add candidates
        commandModel.addCandidates("alice", "Best Language", listOf("Kotlin", "Rust", "Go"))
        val candidates = queryModel.listCandidates("Best Language")
        assertEquals(3, candidates.size)
        assertTrue(candidates.containsAll(listOf("Kotlin", "Rust", "Go")))

        assertEquals(3, queryModel.candidateCount("Best Language"))

        // Remove candidate
        commandModel.removeCandidates("alice", "Best Language", listOf("Go"))
        val candidatesAfterRemoval = queryModel.listCandidates("Best Language")
        assertEquals(2, candidatesAfterRemoval.size)
        assertTrue(candidatesAfterRemoval.containsAll(listOf("Kotlin", "Rust")))

        // ========== Voter Operations ==========

        // Add eligible voters
        commandModel.addVoters("alice", "Best Language", listOf("robert", "alice"))
        val voters = queryModel.listVotersForElection("Best Language")
        assertEquals(2, voters.size)
        assertTrue(voters.containsAll(listOf("robert", "alice")))

        assertEquals(2, queryModel.voterCount("Best Language"))

        // Remove voter
        commandModel.removeVoters("alice", "Best Language", listOf("alice"))
        val votersAfterRemoval = queryModel.listVotersForElection("Best Language")
        assertEquals(1, votersAfterRemoval.size)
        assertEquals("robert", votersAfterRemoval[0])

        // ========== Ballot Operations ==========

        // Cast ballot
        val rankings = listOf(Ranking("Kotlin", 1), Ranking("Rust", 2))
        commandModel.castBallot("robert", "robert", "Best Language", rankings, "confirmation-123", now)

        val ballot = queryModel.searchBallot("robert", "Best Language")
        assertNotNull(ballot)
        assertEquals("robert", ballot!!.voterName)
        assertEquals("Best Language", ballot.electionName)
        assertEquals("confirmation-123", ballot.confirmation)

        val ballotRankings = queryModel.listRankings("robert", "Best Language")
        assertEquals(2, ballotRankings.size)
        assertEquals("Kotlin", ballotRankings[0].candidateName)
        assertEquals(1, ballotRankings[0].rank)

        // List ballots for election
        val ballots = queryModel.listBallots("Best Language")
        assertEquals(1, ballots.size)
        assertEquals("robert", ballots[0].voterName)

        // Update ballot rankings
        val newRankings = listOf(Ranking("Rust", 1), Ranking("Kotlin", 2))
        commandModel.setRankings("robert", "confirmation-123", "Best Language", newRankings)
        val updatedRankings = queryModel.listRankings("robert", "Best Language")
        assertEquals(2, updatedRankings.size)
        assertEquals("Rust", updatedRankings[0].candidateName)
        assertEquals(1, updatedRankings[0].rank)

        // Update ballot timestamp
        val newTimestamp = Instant.parse("2024-01-02T00:00:00Z")
        commandModel.updateWhenCast("robert", "confirmation-123", newTimestamp)
        val updatedBallot = queryModel.searchBallot("robert", "Best Language")
        assertNotNull(updatedBallot)
        assertEquals(newTimestamp, updatedBallot!!.whenCast)

        // ========== Sync State Operations ==========

        val lastSynced = queryModel.lastSynced()
        assertNotNull(lastSynced)
        assertEquals(2L, lastSynced)

        // ========== Permission Operations ==========

        // Check role has permission
        val ownerHasManageUsers = queryModel.roleHasPermission(Role.OWNER, Permission.MANAGE_USERS)
        assertEquals(true, ownerHasManageUsers)

        // List permissions for role
        val adminPermissions = queryModel.listPermissions(Role.ADMIN)
        assertTrue(adminPermissions.isNotEmpty())

        // ========== Table Operations ==========

        // Table count (if supported by implementation)
        val tableCountValue = queryModel.tableCount()
        assertTrue(tableCountValue >= 0)

        // ========== Cleanup Operations ==========

        // Delete election (cascades)
        commandModel.deleteElection("alice", "Best Language")
        assertEquals(0, queryModel.electionCount())
        assertEquals(0, queryModel.listCandidates("Best Language").size)
        assertEquals(0, queryModel.listVotersForElection("Best Language").size)

        // Remove user
        commandModel.removeUser("admin", "robert")
        assertEquals(1, queryModel.userCount())
        assertNull(queryModel.searchUserByName("robert"))

        println("✓ All operations passed for ${provider.name}")
    }
}
