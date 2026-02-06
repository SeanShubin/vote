package com.seanshubin.vote.integration.dsl

import com.seanshubin.vote.contract.AccessToken
import com.seanshubin.vote.domain.Ranking

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
}
