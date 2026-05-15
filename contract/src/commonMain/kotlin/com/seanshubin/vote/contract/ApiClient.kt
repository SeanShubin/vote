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
 * State transitions: discordLoginStartUrl + the OAuth callback start a
 * session; logout ends one. Authenticated calls before a session is started
 * will fail.
 */
interface ApiClient {
    /**
     * Trade the refresh-token cookie (set by a prior Discord login) for fresh
     * tokens. Returns null if the browser has no valid cookie — the caller
     * should show the login screen in that case.
     */
    suspend fun refresh(): AuthResponse?

    /** Clear the refresh cookie server-side and the local session. Idempotent. */
    suspend fun logout()

    /**
     * Monotonic version of the server's read model. Unchanged value means
     * nothing has changed anywhere; a higher value means at least one write
     * landed. Cheap to call — backs the client-side polling that decides when
     * a page's real data is worth refetching. Unauthenticated, so it works
     * without an active session and never triggers a token refresh.
     */
    suspend fun version(): Long

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

    /** The current user's name. */
    suspend fun getMyUser(): UserNameEmail

    suspend fun listElections(): List<ElectionSummary>
    suspend fun createElection(electionName: String, description: String = ""): String
    suspend fun getElection(electionName: String): ElectionDetail
    suspend fun setElectionDescription(electionName: String, description: String)
    /**
     * Append one or more candidates to the election. Names already present
     * are silently skipped (the backend filters to "new only" before
     * emitting an event) — paste-a-list flows can submit a superset without
     * worrying about duplicates. Existing ballots are unaffected.
     */
    suspend fun addCandidates(electionName: String, candidateNames: List<String>)

    /**
     * Remove a single candidate from the election. Cascades to every
     * existing ballot: any ranking referencing this candidate is stripped,
     * so the dropped name doesn't survive as a ghost reference. Per-row at
     * the API surface so each removal is its own intent — bulk removal
     * happens one call at a time, with the cascade documented to callers.
     */
    suspend fun removeCandidate(electionName: String, candidateName: String)

    suspend fun listCandidates(electionName: String): List<String>

    /**
     * Rename a single candidate. Every ranking in every cast ballot for the
     * election is rewritten transparently; ranks are preserved. Use this
     * instead of remove-then-add when an existing candidate's display
     * name needs to change without invalidating existing ballots.
     */
    suspend fun renameCandidate(electionName: String, oldName: String, newName: String)

    /**
     * Map of candidate name → number of ballots that reference that
     * candidate. Every current candidate appears as a key (zero for
     * those with no ballots), so the editor can render a row per
     * candidate with its blast-radius count.
     */
    suspend fun candidateBallotCounts(electionName: String): Map<String, Int>

    /**
     * Set the ordered tier names for an election. Empty list disables tier
     * support (ballots become candidate-only). Now safe at any time —
     * the tier-as-annotation model means renames go through [renameTier]
     * (cascading) and removed tiers leave each affected [Ranking.tier]
     * as null (cleared no tier) without rewriting the ranking's rank.
     */
    suspend fun setTiers(electionName: String, tiers: List<String>)
    suspend fun listTiers(electionName: String): List<String>

    /**
     * Rename a single tier in place. Every [Ranking.tier] annotation on
     * every cast ballot for the election is rewritten transparently; the
     * voter's intent (which prestige tier each candidate cleared) is
     * preserved. Use this instead of remove-then-add when an existing
     * tier's display name needs to change without invalidating ballots.
     */
    suspend fun renameTier(electionName: String, oldName: String, newName: String)
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
     * Grant a user co-manager authority on the election — they can edit
     * candidates, tiers, and description, but cannot delete/transfer the
     * election or change the manager list. Allowed for the election owner or
     * ADMIN+. No-op if the user is already a manager.
     */
    suspend fun addElectionManager(electionName: String, userName: String)

    /**
     * Revoke a user's co-manager authority on the election. Allowed for the
     * election owner or ADMIN+. No-op if the user wasn't a manager.
     */
    suspend fun removeElectionManager(electionName: String, userName: String)

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

    /**
     * Kick off Discord OAuth. Returns the URL the browser should navigate to;
     * the backend has already set the state cookie that the callback will
     * verify. The frontend just does `window.location.href = url`.
     *
     * Throws if Discord login isn't configured in this environment (e.g. dev
     * runs without DISCORD_CLIENT_ID set) — the login page should hide the
     * Discord button when this fails.
     */
    suspend fun discordLoginStartUrl(): String

    /**
     * Which login methods this environment offers. Unauthenticated; the login
     * page calls it on load to decide whether to render the dev-login UI.
     */
    suspend fun loginConfig(): LoginConfig

    /**
     * Every registered user's name, for the dev-login picker. Only meaningful
     * when [loginConfig] reports dev login enabled — otherwise the backend
     * rejects the call.
     */
    suspend fun devListUserNames(): List<String>

    /**
     * Dev-only: start a session as an existing user, bypassing Discord. On
     * success the refresh cookie is set, exactly as after a Discord login —
     * the caller reloads so the normal bootstrap picks the session up.
     */
    suspend fun devLoginAsExisting(userName: String)

    /**
     * Dev-only: create a brand-new user and start a session as them,
     * bypassing Discord. Rejects a name that already exists. On success the
     * refresh cookie is set; the caller reloads to pick the session up.
     */
    suspend fun devCreateAndLogin(userName: String)
}
