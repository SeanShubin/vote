package com.seanshubin.vote.integration.dsl

import com.seanshubin.vote.contract.AccessToken
import com.seanshubin.vote.domain.Ranking
import com.seanshubin.vote.domain.Role
import com.seanshubin.vote.domain.UserUpdates

class UserContext(
    private val testContext: TestContext,
    val userName: String,
    val accessToken: AccessToken
) {
    fun createElection(
        name: String = "Election ${testContext.integrations.sequentialIdGenerator.generate()}",
        description: String = "",
    ): ElectionContext {
        testContext.backend.addElection(accessToken, userName, name, description)
        testContext.backend.synchronize()
        return ElectionContext(testContext, name, this)
    }

    fun castBallot(election: ElectionContext, rankings: List<Pair<String, Int>>) {
        val rankingObjects = rankings.map { (candidate, rank) ->
            Ranking(candidate, rank)
        }
        testContext.backend.castBallot(accessToken, userName, election.name, rankingObjects)
        testContext.backend.synchronize()
    }

    fun castBallot(election: ElectionContext, vararg rankings: Pair<String, Int>) {
        castBallot(election, rankings.toList())
    }

    fun removeUser(targetUserName: String) {
        testContext.backend.removeUser(accessToken, targetUserName)
        testContext.backend.synchronize()
    }

    fun setRole(targetUserName: String, newRole: Role) {
        testContext.backend.setRole(accessToken, targetUserName, newRole)
        testContext.backend.synchronize()
    }

    fun changeMyPassword(oldPassword: String, newPassword: String) {
        testContext.backend.changeMyPassword(accessToken, oldPassword, newPassword)
        testContext.backend.synchronize()
    }

    fun adminSetPassword(targetUserName: String, newPassword: String) {
        testContext.backend.adminSetPassword(accessToken, targetUserName, newPassword)
        testContext.backend.synchronize()
    }

    fun updateUser(newName: String? = null, newEmail: String? = null) {
        testContext.backend.updateUser(accessToken, userName, UserUpdates(userName = newName, email = newEmail))
        testContext.backend.synchronize()
    }

    // Query methods

    fun getMyProfile(): com.seanshubin.vote.domain.UserNameEmail {
        testContext.backend.synchronize()
        return testContext.backend.getUser(accessToken, userName)
    }

    fun listElections(): List<com.seanshubin.vote.domain.ElectionSummary> {
        testContext.backend.synchronize()
        return testContext.backend.listElections(accessToken)
    }

    fun getElection(electionName: String): com.seanshubin.vote.domain.ElectionDetail {
        testContext.backend.synchronize()
        return testContext.backend.getElection(accessToken, electionName)
    }

    fun getBallot(electionName: String): com.seanshubin.vote.domain.BallotSummary? {
        testContext.backend.synchronize()
        return testContext.backend.getBallot(accessToken, userName, electionName)
    }

    fun listRankings(electionName: String): List<Ranking> {
        testContext.backend.synchronize()
        return testContext.backend.listRankings(accessToken, userName, electionName)
    }

    fun listUsers(): List<com.seanshubin.vote.domain.UserNameRole> {
        testContext.backend.synchronize()
        return testContext.backend.listUsers(accessToken)
    }

    fun getUser(userName: String): com.seanshubin.vote.domain.UserNameEmail {
        testContext.backend.synchronize()
        return testContext.backend.getUser(accessToken, userName)
    }
}
