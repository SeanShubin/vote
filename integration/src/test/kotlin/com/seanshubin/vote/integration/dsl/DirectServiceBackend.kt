package com.seanshubin.vote.integration.dsl

import com.seanshubin.vote.contract.AccessToken
import com.seanshubin.vote.contract.Service
import com.seanshubin.vote.domain.BallotSummary
import com.seanshubin.vote.domain.Ranking
import com.seanshubin.vote.domain.Role
import com.seanshubin.vote.domain.Tally
import com.seanshubin.vote.domain.UserUpdates

/**
 * Backend that executes operations by directly calling the Service implementation.
 * Used for event log, SQL, and DynamoDB documentation generation.
 */
class DirectServiceBackend(private val service: Service) : ScenarioBackend {
    override fun registerUser(name: String, email: String, password: String): AccessToken {
        return service.register(name, email, password).accessToken
    }

    override fun changePassword(token: AccessToken, userName: String, newPassword: String) {
        service.changePassword(token, userName, newPassword)
    }

    override fun setRole(token: AccessToken, targetUserName: String, newRole: Role) {
        service.setRole(token, targetUserName, newRole)
    }

    override fun updateUser(token: AccessToken, userName: String, updates: UserUpdates) {
        service.updateUser(token, userName, updates)
    }

    override fun removeUser(token: AccessToken, targetUserName: String) {
        service.removeUser(token, targetUserName)
    }

    override fun addElection(token: AccessToken, ownerName: String, electionName: String) {
        service.addElection(token, ownerName, electionName)
    }

    override fun setCandidates(token: AccessToken, electionName: String, candidateNames: List<String>) {
        service.setCandidates(token, electionName, candidateNames)
    }

    override fun setEligibleVoters(token: AccessToken, electionName: String, voterNames: List<String>) {
        service.setEligibleVoters(token, electionName, voterNames)
    }

    override fun launchElection(token: AccessToken, electionName: String, allowEdit: Boolean) {
        service.launchElection(token, electionName, allowEdit)
    }

    override fun finalizeElection(token: AccessToken, electionName: String) {
        service.finalizeElection(token, electionName)
    }

    override fun deleteElection(token: AccessToken, electionName: String) {
        service.deleteElection(token, electionName)
    }

    override fun castBallot(token: AccessToken, voterName: String, electionName: String, rankings: List<Ranking>) {
        service.castBallot(token, voterName, electionName, rankings)
    }

    override fun getBallot(token: AccessToken, voterName: String, electionName: String): BallotSummary? {
        return service.getBallot(token, voterName, electionName)
    }

    override fun tally(token: AccessToken, electionName: String): Tally {
        return service.tally(token, electionName)
    }

    override fun synchronize() {
        service.synchronize()
    }
}
