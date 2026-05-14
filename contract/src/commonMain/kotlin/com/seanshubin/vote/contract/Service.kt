package com.seanshubin.vote.contract

import com.seanshubin.vote.domain.*

interface Service {
    fun synchronize()
    fun health(): String
    fun refresh(refreshToken: RefreshToken): Tokens
    fun authenticateWithToken(accessToken: AccessToken): Tokens
    fun permissionsForRole(role: Role): List<Permission>
    fun setRole(accessToken: AccessToken, userName: String, role: Role)
    fun removeUser(accessToken: AccessToken, userName: String)
    fun listUsers(accessToken: AccessToken): List<UserNameRole>
    fun addElection(accessToken: AccessToken, userName: String, electionName: String, description: String)
    fun setElectionDescription(accessToken: AccessToken, electionName: String, description: String)
    fun updateUser(accessToken: AccessToken, userName: String, userUpdates: UserUpdates)
    fun getUser(accessToken: AccessToken, userName: String): UserNameEmail
    fun getElection(accessToken: AccessToken, electionName: String): ElectionDetail
    fun deleteElection(accessToken: AccessToken, electionName: String)
    fun listElections(accessToken: AccessToken): List<ElectionSummary>
    fun listTables(accessToken: AccessToken): List<String>
    fun listDebugTables(accessToken: AccessToken): List<String>
    fun userCount(accessToken: AccessToken): Int
    fun electionCount(accessToken: AccessToken): Int
    fun tableCount(accessToken: AccessToken): Int
    fun eventCount(accessToken: AccessToken): Int
    fun tableData(accessToken: AccessToken, tableName: String): TableData
    fun debugTableData(accessToken: AccessToken, tableName: String): TableData
    fun eventData(accessToken: AccessToken): TableData
    fun setCandidates(accessToken: AccessToken, electionName: String, candidateNames: List<String>)
    fun listCandidates(accessToken: AccessToken, electionName: String): List<String>
    fun setTiers(accessToken: AccessToken, electionName: String, tierNames: List<String>)
    fun listTiers(accessToken: AccessToken, electionName: String): List<String>
    fun castBallot(accessToken: AccessToken, voterName: String, electionName: String, rankings: List<Ranking>): String
    fun deleteBallot(accessToken: AccessToken, voterName: String, electionName: String)
    fun listRankings(accessToken: AccessToken, voterName: String, electionName: String): List<Ranking>
    fun tally(accessToken: AccessToken, electionName: String): ElectionTally
    fun getBallot(accessToken: AccessToken, voterName: String, electionName: String): BallotSummary?
    fun getUserActivity(accessToken: AccessToken): UserActivity

    /**
     * Returns the URL the browser should visit to start Discord OAuth,
     * paired with the random state string. The state must be persisted
     * (typically as an HttpOnly cookie) and verified on the callback.
     *
     * Throws ServiceException(UNSUPPORTED) when Discord login isn't
     * configured in this environment.
     */
    fun discordLoginStart(): DiscordLoginStart

    /**
     * Complete the Discord OAuth handshake. [code] is the value Discord
     * passed back to the redirect URI. Verifies the Rippaverse guild gate,
     * finds-or-creates the user by Discord ID, and returns Tokens.
     *
     * Throws ServiceException(UNAUTHORIZED) when Discord rejects the code,
     * when the user is not in the Rippaverse guild, or when the auth flow
     * otherwise fails.
     */
    fun discordLoginComplete(code: String): Tokens
}
