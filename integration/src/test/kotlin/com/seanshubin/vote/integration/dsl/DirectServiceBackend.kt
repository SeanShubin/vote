package com.seanshubin.vote.integration.dsl

import com.seanshubin.vote.contract.AccessToken
import com.seanshubin.vote.contract.EventLog
import com.seanshubin.vote.contract.Service
import com.seanshubin.vote.domain.*
import kotlinx.datetime.Instant

/**
 * Backend that executes operations by directly calling the Service implementation.
 * Used for event log, SQL, and DynamoDB documentation generation.
 */
class DirectServiceBackend(
    private val service: Service,
    private val eventLog: EventLog,
) : ScenarioBackend {
    override fun seedEvent(authority: String, whenHappened: Instant, event: DomainEvent) {
        eventLog.appendEvent(authority, whenHappened, event)
    }

    override fun setRole(token: AccessToken, targetUserName: String, newRole: Role) {
        service.setRole(token, targetUserName, newRole)
    }

    override fun updateUser(token: AccessToken, userName: String, updates: UserUpdates) {
        service.updateUser(token, userName, updates)
    }

    override fun removeUser(token: AccessToken, targetUserName: String) {
        service.removeUser(token, targetUserName)
    }

    override fun addElection(token: AccessToken, ownerName: String, electionName: String, description: String) {
        service.addElection(token, ownerName, electionName, description)
    }

    override fun addCandidates(token: AccessToken, electionName: String, candidateNames: List<String>) {
        service.addCandidates(token, electionName, candidateNames)
    }

    override fun removeCandidate(token: AccessToken, electionName: String, candidateName: String) {
        service.removeCandidate(token, electionName, candidateName)
    }

    override fun setTiers(token: AccessToken, electionName: String, tierNames: List<String>) {
        service.setTiers(token, electionName, tierNames)
    }

    override fun deleteElection(token: AccessToken, electionName: String) {
        service.deleteElection(token, electionName)
    }

    override fun transferElectionOwnership(token: AccessToken, electionName: String, newOwnerName: String) {
        service.transferElectionOwnership(token, electionName, newOwnerName)
    }

    override fun addElectionManager(token: AccessToken, electionName: String, userName: String) {
        service.addElectionManager(token, electionName, userName)
    }

    override fun removeElectionManager(token: AccessToken, electionName: String, userName: String) {
        service.removeElectionManager(token, electionName, userName)
    }

    override fun castBallot(token: AccessToken, voterName: String, electionName: String, rankings: List<Ranking>): String {
        return service.castBallot(token, voterName, electionName, rankings)
    }

    override fun getBallot(token: AccessToken, voterName: String, electionName: String): BallotSummary? {
        return service.getBallot(token, voterName, electionName)
    }

    override fun tally(token: AccessToken, electionName: String): ElectionTally {
        return service.tally(token, electionName)
    }

    override fun listUsers(token: AccessToken): List<UserNameRole> {
        return service.listUsers(token)
    }

    override fun getUser(token: AccessToken, userName: String): UserNameEmail {
        return service.getUser(token, userName)
    }

    override fun userCount(token: AccessToken): Int {
        return service.userCount(token)
    }

    override fun listElections(token: AccessToken): List<ElectionSummary> {
        return service.listElections(token)
    }

    override fun getElection(token: AccessToken, electionName: String): ElectionDetail {
        return service.getElection(token, electionName)
    }

    override fun electionCount(token: AccessToken): Int {
        return service.electionCount(token)
    }

    override fun listCandidates(token: AccessToken, electionName: String): List<String> {
        return service.listCandidates(token, electionName)
    }

    override fun listRankings(token: AccessToken, voterName: String, electionName: String): List<Ranking> {
        return service.listRankings(token, voterName, electionName)
    }

    override fun listTables(token: AccessToken): List<String> {
        return service.listTables(token)
    }

    override fun tableCount(token: AccessToken): Int {
        return service.tableCount(token)
    }

    override fun eventCount(token: AccessToken): Int {
        return service.eventCount(token)
    }

    override fun tableData(token: AccessToken, tableName: String): TableData {
        return service.tableData(token, tableName)
    }

    override fun permissionsForRole(role: Role): List<Permission> {
        return service.permissionsForRole(role)
    }

    override fun refresh(refreshToken: com.seanshubin.vote.contract.RefreshToken): com.seanshubin.vote.contract.Tokens {
        return service.refresh(refreshToken)
    }

    override fun authenticateWithToken(accessToken: AccessToken): com.seanshubin.vote.contract.Tokens {
        return service.authenticateWithToken(accessToken)
    }

    override fun pauseEventLog(token: AccessToken) {
        service.pauseEventLog(token)
    }

    override fun resumeEventLog(token: AccessToken) {
        service.resumeEventLog(token)
    }

    override fun isEventLogPaused(): Boolean = service.isEventLogPaused()

    override fun synchronize() {
        service.synchronize()
    }
}
