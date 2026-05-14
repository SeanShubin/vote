package com.seanshubin.vote.integration.dsl

import com.seanshubin.vote.contract.AccessToken
import com.seanshubin.vote.domain.*

/**
 * Abstraction over how scenario operations are executed.
 * Allows the same scenario to run against direct service calls or HTTP API.
 *
 * Test setup creates users by appending [DomainEvent.UserRegisteredViaDiscord]
 * events to the underlying [com.seanshubin.vote.contract.EventLog] directly
 * — there is no password-registration path under Discord-only login, and the
 * HTTP backend mocks Discord OAuth at the service edge.
 */
interface ScenarioBackend {
    fun setRole(token: AccessToken, targetUserName: String, newRole: Role)
    fun updateUser(token: AccessToken, userName: String, updates: UserUpdates)
    fun removeUser(token: AccessToken, targetUserName: String)

    fun addElection(token: AccessToken, ownerName: String, electionName: String, description: String = "")
    fun addCandidates(token: AccessToken, electionName: String, candidateNames: List<String>)
    fun removeCandidate(token: AccessToken, electionName: String, candidateName: String)
    fun setTiers(token: AccessToken, electionName: String, tierNames: List<String>)
    fun deleteElection(token: AccessToken, electionName: String)
    fun transferElectionOwnership(token: AccessToken, electionName: String, newOwnerName: String)

    fun castBallot(token: AccessToken, voterName: String, electionName: String, rankings: List<Ranking>): String
    fun getBallot(token: AccessToken, voterName: String, electionName: String): BallotSummary?

    fun tally(token: AccessToken, electionName: String): ElectionTally

    fun listUsers(token: AccessToken): List<UserNameRole>
    fun getUser(token: AccessToken, userName: String): UserNameEmail
    fun userCount(token: AccessToken): Int

    fun listElections(token: AccessToken): List<ElectionSummary>
    fun getElection(token: AccessToken, electionName: String): ElectionDetail
    fun electionCount(token: AccessToken): Int
    fun listCandidates(token: AccessToken, electionName: String): List<String>
    fun listRankings(token: AccessToken, voterName: String, electionName: String): List<Ranking>

    fun listTables(token: AccessToken): List<String>
    fun tableCount(token: AccessToken): Int
    fun eventCount(token: AccessToken): Int
    fun tableData(token: AccessToken, tableName: String): TableData
    fun permissionsForRole(role: Role): List<Permission>

    fun refresh(refreshToken: com.seanshubin.vote.contract.RefreshToken): com.seanshubin.vote.contract.Tokens
    fun authenticateWithToken(accessToken: AccessToken): com.seanshubin.vote.contract.Tokens

    fun synchronize()
}
