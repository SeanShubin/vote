package com.seanshubin.vote.integration.dsl

import com.seanshubin.vote.contract.AccessToken
import com.seanshubin.vote.domain.*

/**
 * Abstraction over how scenario operations are executed.
 * Allows the same scenario to run against direct service calls or HTTP API.
 */
interface ScenarioBackend {
    // User operations
    fun registerUser(name: String, email: String, password: String): AccessToken
    fun changePassword(token: AccessToken, userName: String, newPassword: String)
    fun setRole(token: AccessToken, targetUserName: String, newRole: Role)
    fun updateUser(token: AccessToken, userName: String, updates: UserUpdates)
    fun removeUser(token: AccessToken, targetUserName: String)

    // Election operations
    fun addElection(token: AccessToken, ownerName: String, electionName: String)
    fun setCandidates(token: AccessToken, electionName: String, candidateNames: List<String>)
    fun setEligibleVoters(token: AccessToken, electionName: String, voterNames: List<String>)
    fun launchElection(token: AccessToken, electionName: String, allowEdit: Boolean)
    fun finalizeElection(token: AccessToken, electionName: String)
    fun deleteElection(token: AccessToken, electionName: String)

    // Ballot operations
    fun castBallot(token: AccessToken, voterName: String, electionName: String, rankings: List<Ranking>)
    fun getBallot(token: AccessToken, voterName: String, electionName: String): BallotSummary?

    // Query operations
    fun tally(token: AccessToken, electionName: String): Tally

    // User queries
    fun listUsers(token: AccessToken): List<UserNameRole>
    fun getUser(token: AccessToken, userName: String): UserNameEmail
    fun userCount(token: AccessToken): Int

    // Election queries
    fun listElections(token: AccessToken): List<ElectionSummary>
    fun getElection(token: AccessToken, electionName: String): ElectionDetail
    fun electionCount(token: AccessToken): Int
    fun listCandidates(token: AccessToken, electionName: String): List<String>
    fun listEligibility(token: AccessToken, electionName: String): List<VoterEligibility>
    fun isEligible(token: AccessToken, userName: String, electionName: String): Boolean
    fun listRankings(token: AccessToken, voterName: String, electionName: String): List<Ranking>

    // Admin/diagnostic operations
    fun listTables(token: AccessToken): List<String>
    fun tableCount(token: AccessToken): Int
    fun eventCount(token: AccessToken): Int
    fun tableData(token: AccessToken, tableName: String): TableData
    fun permissionsForRole(role: Role): List<Permission>

    // Token operations
    fun refresh(refreshToken: com.seanshubin.vote.contract.RefreshToken): com.seanshubin.vote.contract.Tokens
    fun authenticateWithToken(accessToken: AccessToken): com.seanshubin.vote.contract.Tokens
    fun authenticate(nameOrEmail: String, password: String): com.seanshubin.vote.contract.Tokens

    // Election updates
    fun updateElection(token: AccessToken, electionName: String, updates: ElectionUpdates)

    // Email login
    fun sendLoginLinkByEmail(email: String, baseUri: String)

    // Synchronization (for event sourcing)
    fun synchronize()
}
