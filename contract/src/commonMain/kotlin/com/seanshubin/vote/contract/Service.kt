package com.seanshubin.vote.contract

import com.seanshubin.vote.domain.*

interface Service {
    fun synchronize()
    fun health(): String

    /**
     * Monotonic version of the read model — the id of the last event projected
     * into the query tables. Every write advances it; if it hasn't moved, no
     * data anywhere has changed. Clients poll this to decide whether a refetch
     * is worth doing. 0 before the first event is ever applied.
     */
    fun version(): Long
    fun refresh(refreshToken: RefreshToken): Tokens
    fun authenticateWithToken(accessToken: AccessToken): Tokens
    fun permissionsForRole(role: Role): List<Permission>
    fun setRole(accessToken: AccessToken, userName: String, role: Role)
    fun removeUser(accessToken: AccessToken, userName: String)
    fun listUsers(accessToken: AccessToken): List<UserNameRole>
    fun listUserNames(accessToken: AccessToken): List<String>
    fun addElection(accessToken: AccessToken, userName: String, electionName: String, description: String)
    fun setElectionDescription(accessToken: AccessToken, electionName: String, description: String)
    fun updateUser(accessToken: AccessToken, userName: String, userUpdates: UserUpdates)
    fun getUser(accessToken: AccessToken, userName: String): UserNameEmail
    fun getElection(accessToken: AccessToken, electionName: String): ElectionDetail
    fun deleteElection(accessToken: AccessToken, electionName: String)
    fun transferElectionOwnership(accessToken: AccessToken, electionName: String, newOwnerName: String)
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
    fun addCandidates(accessToken: AccessToken, electionName: String, candidateNames: List<String>)
    fun removeCandidate(accessToken: AccessToken, electionName: String, candidateName: String)
    fun listCandidates(accessToken: AccessToken, electionName: String): List<String>

    /**
     * Rename a single candidate in place. Every ranking in every cast ballot
     * for this election is rewritten so [oldName] becomes [newName]; ballot
     * rank values are preserved. No-op if the candidate is already named
     * [newName]. Rejected when [oldName] doesn't exist, when [newName]
     * collides with another candidate or any tier name, or when the caller
     * isn't the election owner.
     */
    fun renameCandidate(accessToken: AccessToken, electionName: String, oldName: String, newName: String)
    fun renameTier(accessToken: AccessToken, electionName: String, oldName: String, newName: String)

    /**
     * Map of candidate name → number of ballots that mention that candidate
     * (with any rank, including null). Every candidate appears as a key,
     * even those with zero ballots, so the UI can render a row per candidate.
     */
    fun candidateBallotCounts(accessToken: AccessToken, electionName: String): Map<String, Int>
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
