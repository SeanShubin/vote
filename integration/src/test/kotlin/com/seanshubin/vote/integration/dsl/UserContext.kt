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
        name: String = "Election ${testContext.integrations.sequentialIdGenerator.generate()}"
    ): ElectionContext {
        testContext.service.addElection(accessToken, userName, name)
        testContext.service.synchronize()
        return ElectionContext(testContext, name, this)
    }

    fun castBallot(election: ElectionContext, rankings: List<Pair<String, Int>>) {
        val rankingObjects = rankings.map { (candidate, rank) ->
            Ranking(candidate, rank)
        }
        testContext.service.castBallot(accessToken, userName, election.name, rankingObjects)
        testContext.service.synchronize()
    }

    fun castBallot(election: ElectionContext, vararg rankings: Pair<String, Int>) {
        castBallot(election, rankings.toList())
    }

    fun removeUser(targetUserName: String) {
        testContext.service.removeUser(accessToken, targetUserName)
        testContext.service.synchronize()
    }

    fun changePassword(newPassword: String) {
        testContext.service.changePassword(accessToken, userName, newPassword)
        testContext.service.synchronize()
    }

    fun setRole(targetUserName: String, newRole: Role) {
        testContext.service.setRole(accessToken, targetUserName, newRole)
        testContext.service.synchronize()
    }

    fun updateUser(newName: String? = null, newEmail: String? = null) {
        testContext.service.updateUser(accessToken, userName, UserUpdates(userName = newName, email = newEmail))
        testContext.service.synchronize()
    }
}
