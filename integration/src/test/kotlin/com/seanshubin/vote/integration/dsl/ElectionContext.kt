package com.seanshubin.vote.integration.dsl

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

    val candidates: List<String>
        get() = testContext.database.listCandidates(name)

    val eligibleVoters: List<String>
        get() = testContext.database.listEligibleVoters(name)

    fun tally(): Tally =
        testContext.service.tally(owner.accessToken, name)
}
