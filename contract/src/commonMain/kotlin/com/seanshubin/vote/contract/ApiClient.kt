package com.seanshubin.vote.contract

import com.seanshubin.vote.domain.ElectionDetail
import com.seanshubin.vote.domain.ElectionSummary
import com.seanshubin.vote.domain.ElectionTally
import com.seanshubin.vote.domain.Ranking
import com.seanshubin.vote.domain.Role
import com.seanshubin.vote.domain.TableData
import com.seanshubin.vote.domain.UserActivity
import com.seanshubin.vote.domain.UserNameEmail
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
    suspend fun register(userName: String, email: String, password: String, inviteCode: String = ""): AuthResponse

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

    /**
     * Set by the SPA shell to perform "you are now logged out" UX (clear
     * userName/role, route to /login). Implementations invoke it exactly when
     * an authenticated request can't be recovered via refresh — e.g. the user
     * was deleted server-side while their session was still in flight, or the
     * refresh cookie expired. Distinct from [logout] (user-initiated) and
     * from a normal API error (request failed, but the session is still good).
     *
     * Setter, not constructor arg, because the apiClient is built before the
     * Compose tree exists and the callback needs to capture composable state.
     */
    var onSessionLost: (() -> Unit)?

    /** Kick off a password reset — backend looks up user and emails a signed reset link. */
    suspend fun requestPasswordReset(nameOrEmail: String)

    /** Complete a password reset using the token from the email link. */
    suspend fun resetPassword(resetToken: String, newPassword: String)

    /**
     * Change the authenticated user's own password. The backend verifies
     * [oldPassword] before accepting [newPassword] — protects against an
     * attacker who walks up to an unlocked browser session.
     */
    suspend fun changeMyPassword(oldPassword: String, newPassword: String)

    /** The current user's name + email (empty string when no email is on file). */
    suspend fun getMyUser(): UserNameEmail

    /**
     * Set the current user's email. Empty string clears it (user has no
     * email on file → no self-service password reset path). The backend
     * rejects values that collide with another user's email.
     */
    suspend fun updateMyEmail(newEmail: String)

    /**
     * Admin sets another user's password directly — used when the user
     * has forgotten theirs and either didn't provide an email or doesn't
     * want to wait for a reset link. Gated server-side identically to
     * setRole: caller must have MANAGE_USERS and a strictly higher role
     * than the target.
     */
    suspend fun adminSetPassword(userName: String, newPassword: String)

    suspend fun listElections(): List<ElectionSummary>
    suspend fun createElection(electionName: String, description: String = ""): String
    suspend fun getElection(electionName: String): ElectionDetail
    suspend fun setElectionDescription(electionName: String, description: String)
    suspend fun setCandidates(electionName: String, candidates: List<String>)
    suspend fun listCandidates(electionName: String): List<String>

    /**
     * Set the ordered tier names for an election. Empty list disables tier
     * support (ballots become candidate-only). Non-empty enables tier
     * voting; rejected by the backend if the election already has ballots
     * cast — tier names are part of the meaning of those ballots.
     */
    suspend fun setTiers(electionName: String, tiers: List<String>)
    suspend fun listTiers(electionName: String): List<String>
    suspend fun castBallot(electionName: String, rankings: List<Ranking>): String

    /**
     * Remove the current user's ballot for [electionName]. Idempotent — succeeds
     * silently if the voter has no ballot. The backend identifies the voter from
     * the access token, mirroring [castBallot].
     */
    suspend fun deleteMyBallot(electionName: String)

    /**
     * The current user's existing rankings for an election, used to pre-populate
     * the voting view on edit-an-existing-ballot. Empty list when the voter
     * hasn't cast a ballot yet.
     */
    suspend fun getMyRankings(electionName: String): List<Ranking>

    suspend fun getTally(electionName: String): ElectionTally

    /** Delete an election. Allowed for the election owner or ADMIN+; rejected otherwise. */
    suspend fun deleteElection(electionName: String)

    /**
     * Hand the election off to another user. Allowed for the current election
     * owner or ADMIN+ (same gate as [deleteElection]). The new owner gains
     * edit/delete authority on this election; nothing else about either user
     * changes (this is not the global OWNER-role handoff).
     */
    suspend fun transferElectionOwnership(electionName: String, newOwnerName: String)

    /**
     * Admin: list all users with each one's current role and the roles the
     * caller is allowed to assign. The backend computes [UserNameRole.allowedRoles]
     * from the caller's authority — the UI can bind dropdowns directly to it.
     */
    suspend fun listUsers(): List<UserNameRole>

    /**
     * Lightweight name-only listing of every registered user, available to
     * any authenticated caller (USE_APPLICATION). Backs UI affordances like
     * the transfer-ownership picker that need a real list to pick from but
     * shouldn't see roles or the rest of [listUsers]'s admin payload.
     */
    suspend fun listUserNames(): List<String>

    /**
     * Admin: change a user's role. Promoting another user to OWNER triggers
     * an atomic ownership transfer (the caller is demoted to AUDITOR).
     */
    suspend fun setRole(userName: String, role: Role)

    /** Admin: remove a user. Only allowed against users with strictly lesser roles. */
    suspend fun removeUser(userName: String)

    /**
     * The current user's role + how many elections they own + how many ballots
     * they have cast. The Home page indicator binds directly to this; the
     * delete-account confirmation uses the counts to warn the user about the
     * cascade.
     */
    suspend fun getUserActivity(): UserActivity

    /** Admin: physical DynamoDB table names (vote_data, vote_event_log). Empty for InMemory. */
    suspend fun listTables(): List<String>

    /** Admin: raw rows of a physical DynamoDB table. */
    suspend fun tableData(tableName: String): TableData

    /** Admin: virtual relational table names projected from DynamoDB items. */
    suspend fun listDebugTables(): List<String>

    /** Admin: relational projection of one virtual table (users, elections, ballots, ...). */
    suspend fun debugTableData(tableName: String): TableData

    /**
     * Record a frontend exception for server-side observability.
     *
     * If [error] is a [kotlinx.coroutines.CancellationException], this method
     * RETHROWS it instead of recording. Cancellation is a coroutine lifecycle
     * signal (e.g., the composable left the composition while a fetch was in
     * flight), not a real error — recording it would falsely trip the
     * frontend-errors alarm. Rethrow is preferred over silent swallowing so
     * the surrounding `catch (Exception)` block exits early and cancellation
     * propagates correctly up the coroutine.
     */
    fun logErrorToServer(error: Throwable)
}
