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
                User("alice", "alice@x.com", "salt-a", "hash-a", Role.OWNER),
                User("bob", "bob@x.com", "salt-b", "hash-b", Role.USER),
            ),
        )
        val sut = DynamoToRelational(qm, stubEventLog())

        val data = sut.project(DynamoToRelational.USERS)

        assertEquals(DynamoToRelational.USERS, data.name)
        assertEquals(listOf("name", "email", "salt", "hash", "role"), data.columnNames)
        assertEquals(2, data.rows.size)
        assertEquals(listOf("alice", "alice@x.com", "salt-a", "hash-a", "OWNER"), data.rows[0])
        assertEquals(listOf("bob", "bob@x.com", "salt-b", "hash-b", "USER"), data.rows[1])
    }

    @Test
    fun `elections projection annotates owner_name as FK to users`() {
        val qm = stubQueryModel(
            elections = listOf(
                ElectionSummary(
                    electionName = "Lang",
                    ownerName = "alice",
                    secretBallot = true,
                    noVotingBefore = null,
                    noVotingAfter = null,
                    allowEdit = true,
                    allowVote = false,
                ),
            ),
        )
        val sut = DynamoToRelational(qm, stubEventLog())

        val data = sut.project(DynamoToRelational.ELECTIONS)

        assertEquals("owner_name (-> users.name)", data.columnNames[1])
        assertEquals("alice", data.rows[0][1])
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
    fun `eligible_voters projection emits one row per (election, voter)`() {
        val qm = stubQueryModel(
            elections = listOf(election("E1")),
            votersForElection = mapOf("E1" to listOf("bob", "carol")),
        )
        val sut = DynamoToRelational(qm, stubEventLog())

        val rows = sut.project(DynamoToRelational.ELIGIBLE_VOTERS).rows

        assertEquals(2, rows.size)
        assertEquals(listOf("E1", "bob"), rows[0])
        assertEquals(listOf("E1", "carol"), rows[1])
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
                event = DomainEvent.UserRegistered("alice", "a@x.com", "s", "h", Role.OWNER),
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
        assertEquals("UserRegistered", data.rows[1][3])
    }

    @Test
    fun `unknown debug table name throws`() {
        val sut = DynamoToRelational(stubQueryModel(), stubEventLog())
        assertFailsWith<IllegalArgumentException> { sut.project("bogus") }
    }

    @Test
    fun `listDebugTableNames returns the eight schema names`() {
        val sut = DynamoToRelational(stubQueryModel(), stubEventLog())
        assertEquals(
            listOf(
                "users", "elections", "candidates", "eligible_voters",
                "ballots", "rankings", "sync_state", "event_log",
            ),
            sut.listDebugTableNames(),
        )
    }

    // --- Test helpers ---

    private fun election(name: String, owner: String = "alice") = ElectionSummary(
        electionName = name,
        ownerName = owner,
        secretBallot = true,
        noVotingBefore = null,
        noVotingAfter = null,
        allowEdit = true,
        allowVote = false,
    )

    private fun stubQueryModel(
        users: List<User> = emptyList(),
        elections: List<ElectionSummary> = emptyList(),
        candidates: Map<String, List<String>> = emptyMap(),
        votersForElection: Map<String, List<String>> = emptyMap(),
        ballots: Map<String, List<Ballot.Revealed>> = emptyMap(),
        lastSynced: Long? = null,
    ): QueryModel = object : QueryModel {
        override fun listUsers() = users
        override fun listElections() = elections
        override fun listCandidates(electionName: String) = candidates[electionName] ?: emptyList()
        override fun listVotersForElection(electionName: String) = votersForElection[electionName] ?: emptyList()
        override fun listBallots(electionName: String) = ballots[electionName] ?: emptyList()
        override fun lastSynced() = lastSynced

        // Unused by DynamoToRelational — tests only call the methods above.
        override fun findUserByName(name: String): User = error("unused")
        override fun findUserByEmail(email: String): User = error("unused")
        override fun searchUserByName(name: String): User? = null
        override fun searchUserByEmail(email: String): User? = null
        override fun userCount() = users.size
        override fun electionCount() = elections.size
        override fun candidateCount(electionName: String) = listCandidates(electionName).size
        override fun voterCount(electionName: String) = listVotersForElection(electionName).size
        override fun tableCount() = 0
        override fun roleHasPermission(role: Role, permission: Permission): Boolean = true
        override fun searchElectionByName(name: String): ElectionSummary? =
            elections.firstOrNull { it.electionName == name }
        override fun listRankings(voterName: String, electionName: String): List<Ranking> = emptyList()
        override fun listRankings(electionName: String): List<VoterElectionCandidateRank> = emptyList()
        override fun searchBallot(voterName: String, electionName: String): BallotSummary? = null
        override fun listVoterNames(): List<String> = emptyList()
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
