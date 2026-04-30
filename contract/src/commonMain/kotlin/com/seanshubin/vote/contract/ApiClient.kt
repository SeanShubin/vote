package com.seanshubin.vote.contract

import com.seanshubin.vote.domain.ElectionSummary
import com.seanshubin.vote.domain.Ranking
import com.seanshubin.vote.domain.TableData
import com.seanshubin.vote.domain.Tally

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

    /** Clear the refresh cookie server-side. Idempotent. */
    suspend fun logout()

    /** Kick off a password reset — backend looks up user and emails a signed reset link. */
    suspend fun requestPasswordReset(nameOrEmail: String)

    /** Complete a password reset using the token from the email link. */
    suspend fun resetPassword(resetToken: String, newPassword: String)

    suspend fun listElections(authToken: String): List<ElectionSummary>
    suspend fun createElection(authToken: String, electionName: String): String
    suspend fun getElection(authToken: String, electionName: String): ElectionSummary
    suspend fun setCandidates(authToken: String, electionName: String, candidates: List<String>)
    suspend fun listCandidates(authToken: String, electionName: String): List<String>
    suspend fun setEligibleVoters(authToken: String, electionName: String, voters: List<String>)
    suspend fun launchElection(authToken: String, electionName: String)
    suspend fun castBallot(authToken: String, electionName: String, rankings: List<Ranking>): String
    suspend fun getTally(authToken: String, electionName: String): Tally

    /** Admin: physical DynamoDB table names (vote_data, vote_event_log). Empty for InMemory. */
    suspend fun listTables(authToken: String): List<String>

    /** Admin: raw rows of a physical DynamoDB table. */
    suspend fun tableData(authToken: String, tableName: String): TableData

    /** Admin: virtual relational table names projected from DynamoDB items. */
    suspend fun listDebugTables(authToken: String): List<String>

    /** Admin: relational projection of one virtual table (users, elections, ballots, ...). */
    suspend fun debugTableData(authToken: String, tableName: String): TableData

    fun logErrorToServer(error: Throwable)
}
