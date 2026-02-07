package com.seanshubin.vote.integration.dsl

import com.seanshubin.vote.domain.Ranking
import com.seanshubin.vote.domain.Tally

class ElectionContext(
    private val testContext: TestContext,
    val name: String,
    private val owner: UserContext
) {
    fun setCandidates(vararg names: String) {
        testContext.backend.setCandidates(owner.accessToken, name, names.toList())
        testContext.backend.synchronize()
    }

    fun setEligibleVoters(vararg names: String) {
        testContext.backend.setEligibleVoters(owner.accessToken, name, names.toList())
        testContext.backend.synchronize()
    }

    fun launch(allowEdit: Boolean = true) {
        testContext.backend.launchElection(owner.accessToken, name, allowEdit)
        testContext.backend.synchronize()
    }

    fun finalize() {
        testContext.backend.finalizeElection(owner.accessToken, name)
        testContext.backend.synchronize()
    }

    fun delete() {
        testContext.backend.deleteElection(owner.accessToken, name)
        testContext.backend.synchronize()
    }

    fun updateRankings(voterName: String, vararg rankings: Pair<String, Int>) {
        val ballot = testContext.backend.getBallot(owner.accessToken, voterName, name)
        val rankingObjects = rankings.map { (candidate, rank) ->
            Ranking(candidate, rank)
        }
        testContext.backend.castBallot(owner.accessToken, voterName, name, rankingObjects)
        testContext.backend.synchronize()
    }

    val candidates: List<String>
        get() = testContext.database.listCandidates(name)

    val eligibleVoters: List<String>
        get() = testContext.database.listEligibleVoters(name)

    fun tally(): Tally =
        testContext.backend.tally(owner.accessToken, name)

    // Query methods

    fun getDetails(): com.seanshubin.vote.domain.ElectionDetail {
        testContext.backend.synchronize()
        return testContext.backend.getElection(owner.accessToken, name)
    }

    fun listCandidates(): List<String> {
        testContext.backend.synchronize()
        return testContext.backend.listCandidates(owner.accessToken, name)
    }

    fun listEligibility(): List<com.seanshubin.vote.domain.VoterEligibility> {
        testContext.backend.synchronize()
        return testContext.backend.listEligibility(owner.accessToken, name)
    }

    fun isEligible(voterName: String): Boolean {
        testContext.backend.synchronize()
        return testContext.backend.isEligible(owner.accessToken, voterName, name)
    }
}
