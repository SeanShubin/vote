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
        testContext.backend.addElection(accessToken, userName, name)
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

    fun changePassword(newPassword: String) {
        testContext.backend.changePassword(accessToken, userName, newPassword)
        testContext.backend.synchronize()
    }

    fun setRole(targetUserName: String, newRole: Role) {
        testContext.backend.setRole(accessToken, targetUserName, newRole)
        testContext.backend.synchronize()
    }

    fun updateUser(newName: String? = null, newEmail: String? = null) {
        testContext.backend.updateUser(accessToken, userName, UserUpdates(userName = newName, email = newEmail))
        testContext.backend.synchronize()
    }
}
