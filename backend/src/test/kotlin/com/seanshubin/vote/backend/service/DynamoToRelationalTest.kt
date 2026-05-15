package com.seanshubin.vote.backend.service

import com.seanshubin.vote.contract.EventLog
import com.seanshubin.vote.contract.QueryModel
import com.seanshubin.vote.domain.Ballot
import com.seanshubin.vote.domain.BallotSummary
import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.domain.ElectionSummary
import com.seanshubin.vote.domain.EventEnvelope
import com.seanshubin.vote.domain.Permission
import com.seanshubin.vote.domain.Ranking
import com.seanshubin.vote.domain.Role
import com.seanshubin.vote.domain.User
import com.seanshubin.vote.domain.VoterElectionCandidateRank
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DynamoToRelationalTest {

    @Test
    fun `users projection includes all User fields with role rendered as name`() {
        val qm = stubQueryModel(
            users = listOf(
                User("alice", Role.OWNER, discordId = "a-id", discordDisplayName = "Alice"),
                User("bob", Role.USER, discordId = "12345", discordDisplayName = "Bobby"),
            ),
        )
        val sut = DynamoToRelational(qm, stubEventLog())

        val data = sut.project(DynamoToRelational.USERS)

        assertEquals(DynamoToRelational.USERS, data.name)
        assertEquals(
            listOf("name", "role", "discord_id", "discord_display_name"),
            data.columnNames,
        )
        assertEquals(2, data.rows.size)
        assertEquals(
            listOf("alice", "OWNER", "a-id", "Alice"),
            data.rows[0],
        )
        assertEquals(
            listOf("bob", "USER", "12345", "Bobby"),
            data.rows[1],
        )
    }

    @Test
    fun `elections projection annotates owner_name as FK to users`() {
        val qm = stubQueryModel(
            elections = listOf(
                ElectionSummary(
                    electionName = "Lang",
                    ownerName = "alice",
                    description = "favorite language poll",
                ),
            ),
        )
        val sut = DynamoToRelational(qm, stubEventLog())

        val data = sut.project(DynamoToRelational.ELECTIONS)

        assertEquals("owner_name (-> users.name)", data.columnNames[1])
        assertEquals("description", data.columnNames[2])
        assertEquals("alice", data.rows[0][1])
        assertEquals("favorite language poll", data.rows[0][2])
    }

    @Test
    fun `candidates projection emits one row per (election, candidate)`() {
        val qm = stubQueryModel(
            elections = listOf(election("E1"), election("E2")),
            candidates = mapOf(
                "E1" to listOf("a", "b"),
                "E2" to listOf("c"),
            ),
        )
        val sut = DynamoToRelational(qm, stubEventLog())

        val rows = sut.project(DynamoToRelational.CANDIDATES).rows

        assertEquals(3, rows.size)
        assertEquals(listOf("E1", "a"), rows[0])
        assertEquals(listOf("E1", "b"), rows[1])
        assertEquals(listOf("E2", "c"), rows[2])
    }

    @Test
    fun `rankings projection explodes each ballot rankings JSON into one row per (election, voter, candidate)`() {
        val ballot = Ballot.Revealed(
            voterName = "bob",
            electionName = "E1",
            confirmation = "conf-1",
            whenCast = Instant.fromEpochMilliseconds(1_000),
            rankings = listOf(Ranking("a", 1), Ranking("b", 2), Ranking("c", null)),
        )
        val qm = stubQueryModel(
            elections = listOf(election("E1")),
            ballots = mapOf("E1" to listOf(ballot)),
        )
        val sut = DynamoToRelational(qm, stubEventLog())

        val data = sut.project(DynamoToRelational.RANKINGS)

        assertEquals(
            listOf(
                "election_name (-> elections.election_name)",
                "voter_name (-> users.name)",
                "candidate_name (-> candidates.candidate_name)",
                "rank",
            ),
            data.columnNames,
        )
        assertEquals(3, data.rows.size)
        assertEquals(listOf("E1", "bob", "a", "1"), data.rows[0])
        assertEquals(listOf("E1", "bob", "b", "2"), data.rows[1])
        assertEquals(listOf("E1", "bob", "c", null), data.rows[2])
    }

    @Test
    fun `sync_state projection emits zero rows when never synced`() {
        val sut = DynamoToRelational(stubQueryModel(lastSynced = null), stubEventLog())
        assertEquals(0, sut.project(DynamoToRelational.SYNC_STATE).rows.size)
    }

    @Test
    fun `sync_state projection emits one row with last_synced when set`() {
        val sut = DynamoToRelational(stubQueryModel(lastSynced = 42L), stubEventLog())
        val rows = sut.project(DynamoToRelational.SYNC_STATE).rows
        assertEquals(1, rows.size)
        assertEquals(listOf("1", "42"), rows[0])
    }

    @Test
    fun `event_log projection sorts most-recent-first and uses simple event_type names`() {
        // Feed events in shuffled order to verify the projection sorts by event_id.
        val events = listOf(
            EventEnvelope(
                eventId = 2L,
                whenHappened = Instant.fromEpochMilliseconds(200),
                authority = "alice",
                event = DomainEvent.ElectionCreated("alice", "E1"),
            ),
            EventEnvelope(
                eventId = 1L,
                whenHappened = Instant.fromEpochMilliseconds(100),
                authority = "alice",
                event = DomainEvent.UserRegisteredViaDiscord(
                    name = "alice",
                    discordId = "discord-1",
                    discordDisplayName = "Alice",
                    role = Role.OWNER,
                ),
            ),
        )
        val sut = DynamoToRelational(stubQueryModel(), stubEventLog(events))

        val data = sut.project(DynamoToRelational.EVENT_LOG)

        // Columns: event_id | created_at | authority | event_type | event_data
        assertEquals(
            listOf("event_id", "created_at", "authority (-> users.name)", "event_type", "event_data"),
            data.columnNames,
        )
        assertEquals(2, data.rows.size)
        // Descending by event_id — newest first.
        assertEquals("2", data.rows[0][0])
        assertEquals("ElectionCreated", data.rows[0][3])
        assertEquals("1", data.rows[1][0])
        assertEquals("UserRegisteredViaDiscord", data.rows[1][3])
    }

    @Test
    fun `unknown debug table name throws`() {
        val sut = DynamoToRelational(stubQueryModel(), stubEventLog())
        assertFailsWith<IllegalArgumentException> { sut.project("bogus") }
    }

    @Test
    fun `listDebugTableNames returns the seven schema names`() {
        val sut = DynamoToRelational(stubQueryModel(), stubEventLog())
        assertEquals(
            listOf(
                "users", "elections", "candidates",
                "ballots", "rankings", "sync_state", "event_log",
            ),
            sut.listDebugTableNames(),
        )
    }

    // --- Test helpers ---

    private fun election(name: String, owner: String = "alice") = ElectionSummary(
        electionName = name,
        ownerName = owner,
    )

    private fun stubQueryModel(
        users: List<User> = emptyList(),
        elections: List<ElectionSummary> = emptyList(),
        candidates: Map<String, List<String>> = emptyMap(),
        ballots: Map<String, List<Ballot.Revealed>> = emptyMap(),
        lastSynced: Long? = null,
    ): QueryModel = object : QueryModel {
        override fun listUsers() = users
        override fun listElections() = elections
        override fun listCandidates(electionName: String) = candidates[electionName] ?: emptyList()
        override fun listBallots(electionName: String) = ballots[electionName] ?: emptyList()
        override fun lastSynced() = lastSynced

        // Unused by DynamoToRelational — tests only call the methods above.
        override fun findUserByName(name: String): User = error("unused")
        override fun searchUserByName(name: String): User? = null
        override fun searchUserByDiscordId(discordId: String): User? = null
        override fun userCount() = users.size
        override fun electionsOwnedCount(userName: String): Int =
            elections.count { it.ownerName == userName }
        override fun ballotsCastCount(userName: String): Int =
            ballots.values.flatten().count { it.voterName == userName }
        override fun electionCount() = elections.size
        override fun candidateCount(electionName: String) = listCandidates(electionName).size
        override fun ballotCount(electionName: String) = listBallots(electionName).size
        override fun tableCount() = 0
        override fun roleHasPermission(role: Role, permission: Permission): Boolean = true
        override fun searchElectionByName(name: String): ElectionSummary? =
            elections.firstOrNull { it.electionName == name }
        override fun listElectionManagers(electionName: String): List<String> = emptyList()
        override fun listTiers(electionName: String): List<String> = emptyList()
        override fun candidateBallotCounts(electionName: String): Map<String, Int> = emptyMap()
        override fun listRankings(voterName: String, electionName: String): List<Ranking> = emptyList()
        override fun listRankings(electionName: String): List<VoterElectionCandidateRank> = emptyList()
        override fun searchBallot(voterName: String, electionName: String): BallotSummary? = null
        override fun listUserNames(): List<String> = users.map { it.name }
        override fun listPermissions(role: Role): List<Permission> = emptyList()
    }

    private fun stubEventLog(events: List<EventEnvelope> = emptyList()): EventLog = object : EventLog {
        override fun appendEvent(authority: String, whenHappened: Instant, event: DomainEvent) {
            error("unused in projection tests")
        }
        override fun eventsToSync(lastEventSynced: Long): List<EventEnvelope> =
            events.filter { it.eventId > lastEventSynced }
        override fun eventCount(): Int = events.size
    }
}
