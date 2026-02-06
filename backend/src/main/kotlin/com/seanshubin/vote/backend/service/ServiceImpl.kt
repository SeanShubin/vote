package com.seanshubin.vote.backend.service

import com.seanshubin.vote.contract.*
import com.seanshubin.vote.domain.*

class ServiceImpl(
    private val integrations: Integrations,
    private val eventLog: EventLog,
    private val commandModel: CommandModel,
    private val queryModel: QueryModel
) : Service {
    private val clock = integrations.clock
    private val uniqueIdGenerator = integrations.uniqueIdGenerator
    private val notifications = integrations.notifications

    override fun synchronize() {
        // Synchronize events from EventLog to CommandModel
        val lastSynced = queryModel.lastSynced() ?: 0
        val newEvents = eventLog.eventsToSync(lastSynced)
        // Apply events to command model (event sourcing)
        // For now, stub
    }

    override fun health(): String {
        return try {
            queryModel.userCount()
            "ok"
        } catch (e: Exception) {
            e.message ?: "error"
        }
    }

    override fun refresh(refreshToken: RefreshToken): Tokens {
        val user = queryModel.findUserByName(refreshToken.userName)
        val accessToken = AccessToken(user.name, user.role)
        return Tokens(accessToken, refreshToken)
    }

    override fun register(userName: String, email: String, password: String): Tokens {
        // TODO: Validation, password hashing
        val role = if (queryModel.userCount() == 0) Role.OWNER else Role.USER
        val salt = uniqueIdGenerator.generate()
        val hash = uniqueIdGenerator.generate() // TODO: proper password hashing

        eventLog.appendEvent(
            "system",
            clock.now(),
            DomainEvent.UserRegistered(userName, email, salt, hash, role)
        )
        synchronize()

        val user = queryModel.findUserByName(userName)
        val accessToken = AccessToken(user.name, user.role)
        val refreshToken = RefreshToken(user.name)
        return Tokens(accessToken, refreshToken)
    }

    override fun authenticate(nameOrEmail: String, password: String): Tokens {
        val user = queryModel.searchUserByName(nameOrEmail)
            ?: queryModel.findUserByEmail(nameOrEmail)
        // TODO: Password verification
        val accessToken = AccessToken(user.name, user.role)
        val refreshToken = RefreshToken(user.name)
        return Tokens(accessToken, refreshToken)
    }

    override fun authenticateWithToken(accessToken: AccessToken): Tokens {
        val user = queryModel.findUserByName(accessToken.userName)
        val newAccessToken = AccessToken(user.name, user.role)
        val refreshToken = RefreshToken(user.name)
        return Tokens(newAccessToken, refreshToken)
    }

    override fun permissionsForRole(role: Role): List<Permission> {
        return queryModel.listPermissions(role)
    }

    override fun setRole(accessToken: AccessToken, userName: String, role: Role) {
        // TODO: Permission checking
        eventLog.appendEvent(
            accessToken.userName,
            clock.now(),
            DomainEvent.UserRoleChanged(userName, role)
        )
        synchronize()
    }

    override fun removeUser(accessToken: AccessToken, userName: String) {
        // TODO: Permission checking
        eventLog.appendEvent(
            accessToken.userName,
            clock.now(),
            DomainEvent.UserRemoved(userName)
        )
        synchronize()
    }

    override fun listUsers(accessToken: AccessToken): List<UserNameRole> {
        // TODO: Permission checking
        return queryModel.listUsers().map { user ->
            val allowedRoles = Role.entries.filter { role -> role <= user.role }
            UserNameRole(user.name, user.role, allowedRoles)
        }
    }

    override fun addElection(accessToken: AccessToken, userName: String, electionName: String) {
        eventLog.appendEvent(
            accessToken.userName,
            clock.now(),
            DomainEvent.ElectionCreated(userName, electionName)
        )
        synchronize()
    }

    override fun launchElection(accessToken: AccessToken, electionName: String, allowEdit: Boolean) {
        val updates = ElectionUpdates(allowVote = true, allowEdit = allowEdit)
        updateElection(accessToken, electionName, updates)
    }

    override fun finalizeElection(accessToken: AccessToken, electionName: String) {
        val updates = ElectionUpdates(allowVote = false, allowEdit = false)
        updateElection(accessToken, electionName, updates)
    }

    override fun updateElection(accessToken: AccessToken, electionName: String, electionUpdates: ElectionUpdates) {
        eventLog.appendEvent(
            accessToken.userName,
            clock.now(),
            DomainEvent.ElectionUpdated(
                electionName,
                electionUpdates.secretBallot,
                electionUpdates.noVotingBefore,
                electionUpdates.noVotingAfter,
                electionUpdates.allowEdit,
                electionUpdates.allowVote
            )
        )
        synchronize()
    }

    override fun updateUser(accessToken: AccessToken, userName: String, userUpdates: UserUpdates) {
        userUpdates.userName?.let {
            eventLog.appendEvent(
                accessToken.userName,
                clock.now(),
                DomainEvent.UserNameChanged(userName, it)
            )
        }
        userUpdates.email?.let {
            eventLog.appendEvent(
                accessToken.userName,
                clock.now(),
                DomainEvent.UserEmailChanged(userName, it)
            )
        }
        synchronize()
    }

    override fun getUser(accessToken: AccessToken, userName: String): UserNameEmail {
        val user = queryModel.findUserByName(userName)
        return UserNameEmail(user.name, user.email)
    }

    override fun getElection(accessToken: AccessToken, electionName: String): ElectionDetail {
        throw NotImplementedError("Stub")
    }

    override fun deleteElection(accessToken: AccessToken, electionName: String) {
        eventLog.appendEvent(
            accessToken.userName,
            clock.now(),
            DomainEvent.ElectionDeleted(electionName)
        )
        synchronize()
    }

    override fun listElections(accessToken: AccessToken): List<ElectionSummary> {
        return queryModel.listElections()
    }

    override fun listTables(accessToken: AccessToken): List<String> {
        return emptyList() // Debug endpoint
    }

    override fun userCount(accessToken: AccessToken): Int {
        return queryModel.userCount()
    }

    override fun electionCount(accessToken: AccessToken): Int {
        return queryModel.electionCount()
    }

    override fun tableCount(accessToken: AccessToken): Int {
        return queryModel.tableCount()
    }

    override fun eventCount(accessToken: AccessToken): Int {
        return eventLog.eventCount()
    }

    override fun tableData(accessToken: AccessToken, tableName: String): TableData {
        return TableData(tableName, emptyList(), emptyList()) // Debug endpoint
    }

    override fun debugTableData(accessToken: AccessToken, tableName: String): TableData {
        return TableData(tableName, emptyList(), emptyList()) // Debug endpoint
    }

    override fun eventData(accessToken: AccessToken): TableData {
        return TableData("events", emptyList(), emptyList()) // Debug endpoint
    }

    override fun setCandidates(accessToken: AccessToken, electionName: String, candidateNames: List<String>) {
        val existing = queryModel.listCandidates(electionName)
        val toAdd = candidateNames.filter { it !in existing }
        val toRemove = existing.filter { it !in candidateNames }

        if (toAdd.isNotEmpty()) {
            eventLog.appendEvent(
                accessToken.userName,
                clock.now(),
                DomainEvent.CandidatesAdded(electionName, toAdd)
            )
        }
        if (toRemove.isNotEmpty()) {
            eventLog.appendEvent(
                accessToken.userName,
                clock.now(),
                DomainEvent.CandidatesRemoved(electionName, toRemove)
            )
        }
        synchronize()
    }

    override fun listCandidates(accessToken: AccessToken, electionName: String): List<String> {
        return queryModel.listCandidates(electionName)
    }

    override fun castBallot(
        accessToken: AccessToken,
        voterName: String,
        electionName: String,
        rankings: List<Ranking>
    ) {
        val confirmation = uniqueIdGenerator.generate()
        val whenCast = clock.now()
        eventLog.appendEvent(
            accessToken.userName,
            whenCast,
            DomainEvent.BallotCast(voterName, electionName, rankings, confirmation, whenCast)
        )
        synchronize()
    }

    override fun listRankings(accessToken: AccessToken, voterName: String, electionName: String): List<Ranking> {
        return queryModel.listRankings(voterName, electionName)
    }

    override fun tally(accessToken: AccessToken, electionName: String): Tally {
        throw NotImplementedError("Stub")
    }

    override fun listEligibility(accessToken: AccessToken, electionName: String): List<VoterEligibility> {
        val eligibleVoters = queryModel.listVotersForElection(electionName)
        val allUsers = queryModel.listUserNames()
        return allUsers.map { userName ->
            VoterEligibility(userName, eligibleVoters.contains(userName))
        }
    }

    override fun setEligibleVoters(accessToken: AccessToken, electionName: String, voterNames: List<String>) {
        val existing = queryModel.listVotersForElection(electionName)
        val toAdd = voterNames.filter { it !in existing }
        val toRemove = existing.filter { it !in voterNames }

        if (toAdd.isNotEmpty()) {
            eventLog.appendEvent(
                accessToken.userName,
                clock.now(),
                DomainEvent.VotersAdded(electionName, toAdd)
            )
        }
        if (toRemove.isNotEmpty()) {
            eventLog.appendEvent(
                accessToken.userName,
                clock.now(),
                DomainEvent.VotersRemoved(electionName, toRemove)
            )
        }
        synchronize()
    }

    override fun isEligible(accessToken: AccessToken, userName: String, electionName: String): Boolean {
        return queryModel.listVotersForElection(electionName).contains(userName)
    }

    override fun getBallot(accessToken: AccessToken, voterName: String, electionName: String): BallotSummary? {
        return queryModel.searchBallot(voterName, electionName)
    }

    override fun changePassword(accessToken: AccessToken, userName: String, password: String) {
        val salt = uniqueIdGenerator.generate()
        val hash = uniqueIdGenerator.generate() // TODO: proper password hashing
        eventLog.appendEvent(
            accessToken.userName,
            clock.now(),
            DomainEvent.UserPasswordChanged(userName, salt, hash)
        )
        synchronize()
    }

    override fun sendLoginLinkByEmail(email: String, baseUri: String) {
        notifications.sendMailEvent(email, "Login link")
    }
}
