package com.seanshubin.vote.integration.dsl

import com.seanshubin.vote.contract.AccessToken
import com.seanshubin.vote.domain.BallotSummary
import com.seanshubin.vote.domain.Ranking
import com.seanshubin.vote.domain.Role
import com.seanshubin.vote.domain.Tally
import com.seanshubin.vote.domain.UserUpdates

/**
 * Abstraction over how scenario operations are executed.
 * Allows the same scenario to run against direct service calls or HTTP API.
 */
interface ScenarioBackend {
    // User operations
    fun registerUser(name: String, email: String, password: String): AccessToken
    fun changePassword(token: AccessToken, userName: String, newPassword: String)
    fun setRole(token: AccessToken, targetUserName: String, newRole: Role)
    fun updateUser(token: AccessToken, userName: String, updates: UserUpdates)
    fun removeUser(token: AccessToken, targetUserName: String)

    // Election operations
    fun addElection(token: AccessToken, ownerName: String, electionName: String)
    fun setCandidates(token: AccessToken, electionName: String, candidateNames: List<String>)
    fun setEligibleVoters(token: AccessToken, electionName: String, voterNames: List<String>)
    fun launchElection(token: AccessToken, electionName: String, allowEdit: Boolean)
    fun finalizeElection(token: AccessToken, electionName: String)
    fun deleteElection(token: AccessToken, electionName: String)

    // Ballot operations
    fun castBallot(token: AccessToken, voterName: String, electionName: String, rankings: List<Ranking>)
    fun getBallot(token: AccessToken, voterName: String, electionName: String): BallotSummary?

    // Query operations
    fun tally(token: AccessToken, electionName: String): Tally

    // Synchronization (for event sourcing)
    fun synchronize()
}
