package com.seanshubin.vote.contract

import com.seanshubin.vote.domain.ElectionSummary
import com.seanshubin.vote.domain.Ranking
import com.seanshubin.vote.domain.Tally

interface ApiClient {
    suspend fun register(userName: String, email: String, password: String): AuthResponse
    suspend fun authenticate(userName: String, password: String): AuthResponse

    /**
     * Trade the refresh-token cookie (set by a prior register/authenticate)
     * for fresh tokens. Returns null if the browser has no valid cookie —
     * the caller should show the login screen in that case.
     */
    suspend fun refresh(): AuthResponse?

    /** Clear the refresh cookie server-side. Idempotent. */
    suspend fun logout()

    suspend fun listElections(authToken: String): List<ElectionSummary>
    suspend fun createElection(authToken: String, electionName: String): String
    suspend fun getElection(authToken: String, electionName: String): ElectionSummary
    suspend fun setCandidates(authToken: String, electionName: String, candidates: List<String>)
    suspend fun listCandidates(authToken: String, electionName: String): List<String>
    suspend fun setEligibleVoters(authToken: String, electionName: String, voters: List<String>)
    suspend fun launchElection(authToken: String, electionName: String)
    suspend fun castBallot(authToken: String, electionName: String, rankings: List<Ranking>): String
    suspend fun getTally(authToken: String, electionName: String): Tally
    fun logErrorToServer(error: Throwable)
}
