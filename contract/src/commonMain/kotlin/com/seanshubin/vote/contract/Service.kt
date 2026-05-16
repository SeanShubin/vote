package com.seanshubin.vote.contract

import com.seanshubin.vote.domain.*

interface Service {
    fun synchronize()
    fun health(): String

    /**
     * Pause the event log so no new events can be appended. Owner-only —
     * used before pushing a deploy that requires a data migration, so the
     * migration and the new code can land without races against in-flight
     * writes. While paused, every event-producing service call fails with
     * [ServiceException.Category.PAUSED] (HTTP 503).
     */
    fun pauseEventLog(accessToken: AccessToken)

    /** Resume the event log. Owner-only. Inverse of [pauseEventLog]. */
    fun resumeEventLog(accessToken: AccessToken)

    /**
     * Whether the event log is currently paused. Unauthenticated — the
     * frontend polls this from every browser to render the maintenance
     * banner, so it must work without a session (and never trip the token
     * refresh path), just like [version].
     */
    fun isEventLogPaused(): Boolean

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

    /**
     * Grant [userName] co-manager authority on [electionName]: the same
     * content editing the owner has (candidates, tiers, description), but not
     * delete/transfer/manager-list changes. Allowed for the election owner or
     * ADMIN+. No-op if [userName] is already a manager; rejected if [userName]
     * is the owner or doesn't exist.
     */
    fun addElectionManager(accessToken: AccessToken, electionName: String, userName: String)

    /**
     * Revoke [userName]'s co-manager authority. Allowed for the election owner
     * or ADMIN+. No-op if [userName] wasn't a manager.
     */
    fun removeElectionManager(accessToken: AccessToken, electionName: String, userName: String)
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
    /**
     * Tally for one side of the election. PUBLIC and SECRET are tallied
     * independently; rankings on one side never influence the other's
     * results. SECRET-side ballots have `voterName` redacted to "" for
     * callers who lack [Permission.VIEW_SECRETS] — the rankings stay
     * visible so anyone can browse the secret tally, but only auditors
     * see which voter cast which ballot.
     */
    fun tally(accessToken: AccessToken, electionName: String, side: RankingSide = RankingSide.PUBLIC): ElectionTally
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

    /**
     * Which login methods this environment offers. Unauthenticated — the
     * login page calls it before any session exists to decide whether to
     * render the dev-login UI.
     */
    fun loginConfig(): LoginConfig

    /**
     * Every registered user's name, for the dev-login picker. Unauthenticated
     * but gated: throws ServiceException(UNSUPPORTED) unless dev login is
     * enabled for this environment (see [loginConfig]).
     */
    fun devListUserNames(): List<String>

    /**
     * Dev-only: mint Tokens for an existing user, bypassing Discord. The
     * picker on the login page only offers names that exist, so this is the
     * "find" path — it rejects an unknown name rather than creating one.
     *
     * Throws ServiceException(UNSUPPORTED) unless dev login is enabled, and
     * ServiceException(NOT_FOUND) when [userName] doesn't exist.
     */
    fun devLoginAsExisting(userName: String): Tokens

    /**
     * Dev-only: create a brand-new user with [userName] and mint Tokens for
     * them, bypassing Discord. The "create" path — it rejects a name that
     * already exists so a typo can't silently log the operator in as someone
     * else; use [devLoginAsExisting] for existing users.
     *
     * Throws ServiceException(UNSUPPORTED) unless dev login is enabled, and
     * ServiceException(CONFLICT) when [userName] is already taken.
     */
    fun devCreateAndLogin(userName: String): Tokens
}
