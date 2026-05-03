package com.seanshubin.vote.contract

import com.seanshubin.vote.domain.*

interface Service {
    fun synchronize()
    fun health(): String
    fun refresh(refreshToken: RefreshToken): Tokens
    fun register(userName: String, email: String, password: String, inviteCode: String = ""): Tokens
    fun authenticate(nameOrEmail: String, password: String): Tokens
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
    fun tally(accessToken: AccessToken, electionName: String): Tally
    fun getBallot(accessToken: AccessToken, voterName: String, electionName: String): BallotSummary?
    fun sendLoginLinkByEmail(email: String, baseUri: String)
    fun getUserActivity(accessToken: AccessToken): UserActivity

    /**
     * Send a password reset email. Looks up the user by username or email and
     * emails them a reset link with a short-lived signed token.
     *
     * Throws ServiceException(NOT_FOUND) when no matching user exists — the
     * user explicitly chose honest errors over enumeration-resistance.
     */
    fun requestPasswordReset(nameOrEmail: String)

    /**
     * Consume a reset token (from the email link) to set a new password.
     * Throws ServiceException(UNAUTHORIZED) for a missing/expired/tampered
     * token. Throws ServiceException(NOT_FOUND) if the user has been
     * removed since the token was issued.
     */
    fun resetPassword(resetToken: String, newPassword: String)

    /**
     * Authenticated user changes their own password. The old password is
     * verified before the new one is accepted, so an attacker who finds
     * an unattended browser session can't trivially lock the user out by
     * setting a new password without knowing the old one.
     *
     * Throws ServiceException(UNAUTHORIZED) when [oldPassword] doesn't
     * match the stored hash.
     */
    fun changeMyPassword(accessToken: AccessToken, oldPassword: String, newPassword: String)

    /**
     * Admin sets another user's password without proving control of the
     * old one — used when the user has forgotten theirs and there's no
     * email recovery path (or they didn't provide an email).
     *
     * Gated identically to [setRole]: caller needs MANAGE_USERS, must not
     * be acting on themselves (use [changeMyPassword]), and must have a
     * strictly higher role than the target. Audit-trail authority on the
     * resulting [DomainEvent.UserPasswordChanged] event is the caller's
     * username, not "system" — so the event log records exactly which
     * admin reset which user's password.
     */
    fun adminSetPassword(accessToken: AccessToken, userName: String, newPassword: String)

    /**
     * Delete every user whose email lands in the .test TLD, plus every
     * election those users own. Cleanup path for the public test-user
     * convention; gated by MANAGE_USERS.
     */
    fun wipeTestUsers(accessToken: AccessToken): WipeTestUsersResult
}
