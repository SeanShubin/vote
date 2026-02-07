package com.seanshubin.vote.integration.dsl

import com.seanshubin.vote.domain.Ranking
import com.seanshubin.vote.domain.Tally

class ElectionContext(
    private val testContext: TestContext,
    val name: String,
    private val owner: UserContext
) {
    fun setCandidates(vararg names: String) {
        testContext.service.setCandidates(owner.accessToken, name, names.toList())
        testContext.service.synchronize()
    }

    fun setEligibleVoters(vararg names: String) {
        testContext.service.setEligibleVoters(owner.accessToken, name, names.toList())
        testContext.service.synchronize()
    }

    fun launch(allowEdit: Boolean = true) {
        testContext.service.launchElection(owner.accessToken, name, allowEdit)
        testContext.service.synchronize()
    }

    fun finalize() {
        testContext.service.finalizeElection(owner.accessToken, name)
        testContext.service.synchronize()
    }

    fun delete() {
        testContext.service.deleteElection(owner.accessToken, name)
        testContext.service.synchronize()
    }

    fun updateRankings(voterName: String, vararg rankings: Pair<String, Int>) {
        val ballot = testContext.service.getBallot(owner.accessToken, voterName, name)
        val rankingObjects = rankings.map { (candidate, rank) ->
            Ranking(candidate, rank)
        }
        testContext.service.castBallot(owner.accessToken, voterName, name, rankingObjects)
        testContext.service.synchronize()
    }

    val candidates: List<String>
        get() = testContext.database.listCandidates(name)

    val eligibleVoters: List<String>
        get() = testContext.database.listEligibleVoters(name)

    fun tally(): Tally =
        testContext.service.tally(owner.accessToken, name)
}
