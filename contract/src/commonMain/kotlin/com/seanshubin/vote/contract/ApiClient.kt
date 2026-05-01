package com.seanshubin.vote.contract

import com.seanshubin.vote.domain.ElectionDetail
import com.seanshubin.vote.domain.ElectionSummary
import com.seanshubin.vote.domain.Ranking
import com.seanshubin.vote.domain.Role
import com.seanshubin.vote.domain.TableData
import com.seanshubin.vote.domain.Tally
import com.seanshubin.vote.domain.UserNameRole

/**
 * The API client owns its own auth state. Callers (UI pages) don't pass an
 * access token around — they just call methods. Implementations (HttpApiClient,
 * FakeApiClient) are responsible for tracking the current session and, where
 * applicable, transparently refreshing on 401.
 *
 * State transitions: register / authenticate / refresh start a session;
 * logout ends one. Authenticated calls before a session is started will fail.
 */
interface ApiClient {
    suspend fun register(userName: String, email: String, password: String): AuthResponse

    /** [nameOrEmail] is matched against username first, then email — login accepts either. */
    suspend fun authenticate(nameOrEmail: String, password: String): AuthResponse

    /**
     * Trade the refresh-token cookie (set by a prior register/authenticate)
     * for fresh tokens. Returns null if the browser has no valid cookie —
     * the caller should show the login screen in that case.
     */
    suspend fun refresh(): AuthResponse?

    /** Clear the refresh cookie server-side and the local session. Idempotent. */
    suspend fun logout()

    /** Kick off a password reset — backend looks up user and emails a signed reset link. */
    suspend fun requestPasswordReset(nameOrEmail: String)

    /** Complete a password reset using the token from the email link. */
    suspend fun resetPassword(resetToken: String, newPassword: String)

    suspend fun listElections(): List<ElectionSummary>
    suspend fun createElection(electionName: String, description: String = ""): String
    suspend fun getElection(electionName: String): ElectionDetail
    suspend fun setCandidates(electionName: String, candidates: List<String>)
    suspend fun listCandidates(electionName: String): List<String>
    suspend fun castBallot(electionName: String, rankings: List<Ranking>): String
    suspend fun getTally(electionName: String): Tally

    /** Delete an election. Allowed for the election owner or ADMIN+; rejected otherwise. */
    suspend fun deleteElection(electionName: String)

    /**
     * Admin: list all users with each one's current role and the roles the
     * caller is allowed to assign. The backend computes [UserNameRole.allowedRoles]
     * from the caller's authority — the UI can bind dropdowns directly to it.
     */
    suspend fun listUsers(): List<UserNameRole>

    /**
     * Admin: change a user's role. Promoting another user to OWNER triggers
     * an atomic ownership transfer (the caller is demoted to AUDITOR).
     */
    suspend fun setRole(userName: String, role: Role)

    /** Admin: remove a user. Only allowed against users with strictly lesser roles. */
    suspend fun removeUser(userName: String)

    /** Admin: physical DynamoDB table names (vote_data, vote_event_log). Empty for InMemory. */
    suspend fun listTables(): List<String>

    /** Admin: raw rows of a physical DynamoDB table. */
    suspend fun tableData(tableName: String): TableData

    /** Admin: virtual relational table names projected from DynamoDB items. */
    suspend fun listDebugTables(): List<String>

    /** Admin: relational projection of one virtual table (users, elections, ballots, ...). */
    suspend fun debugTableData(tableName: String): TableData

    fun logErrorToServer(error: Throwable)
}
