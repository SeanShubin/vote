package com.seanshubin.vote.contract

import com.seanshubin.vote.domain.*

interface Service {
    fun synchronize()
    fun health(): String
    fun refresh(refreshToken: RefreshToken): Tokens
    fun register(userName: String, email: String, password: String): Tokens
    fun authenticate(nameOrEmail: String, password: String): Tokens
    fun authenticateWithToken(accessToken: AccessToken): Tokens
    fun permissionsForRole(role: Role): List<Permission>
    fun setRole(accessToken: AccessToken, userName: String, role: Role)
    fun removeUser(accessToken: AccessToken, userName: String)
    fun listUsers(accessToken: AccessToken): List<UserNameRole>
    fun addElection(accessToken: AccessToken, userName: String, electionName: String)
    fun launchElection(accessToken: AccessToken, electionName: String, allowEdit: Boolean)
    fun finalizeElection(accessToken: AccessToken, electionName: String)
    fun updateElection(accessToken: AccessToken, electionName: String, electionUpdates: ElectionUpdates)
    fun updateUser(accessToken: AccessToken, userName: String, userUpdates: UserUpdates)
    fun getUser(accessToken: AccessToken, userName: String): UserNameEmail
    fun getElection(accessToken: AccessToken, electionName: String): ElectionDetail
    fun deleteElection(accessToken: AccessToken, electionName: String)
    fun listElections(accessToken: AccessToken): List<ElectionSummary>
    fun listTables(accessToken: AccessToken): List<String>
    fun userCount(accessToken: AccessToken): Int
    fun electionCount(accessToken: AccessToken): Int
    fun tableCount(accessToken: AccessToken): Int
    fun eventCount(accessToken: AccessToken): Int
    fun tableData(accessToken: AccessToken, tableName: String): TableData
    fun debugTableData(accessToken: AccessToken, tableName: String): TableData
    fun eventData(accessToken: AccessToken): TableData
    fun setCandidates(accessToken: AccessToken, electionName: String, candidateNames: List<String>)
    fun listCandidates(accessToken: AccessToken, electionName: String): List<String>
    fun castBallot(accessToken: AccessToken, voterName: String, electionName: String, rankings: List<Ranking>)
    fun listRankings(accessToken: AccessToken, voterName: String, electionName: String): List<Ranking>
    fun tally(accessToken: AccessToken, electionName: String): Tally
    fun listEligibility(accessToken: AccessToken, electionName: String): List<VoterEligibility>
    fun setEligibleVoters(accessToken: AccessToken, electionName: String, voterNames: List<String>)
    fun isEligible(accessToken: AccessToken, userName: String, electionName: String): Boolean
    fun getBallot(accessToken: AccessToken, voterName: String, electionName: String): BallotSummary?
    fun changePassword(accessToken: AccessToken, userName: String, password: String)
    fun sendLoginLinkByEmail(email: String, baseUri: String)
}
