package com.seanshubin.vote.integration.dsl

import com.seanshubin.vote.domain.Tally

class ElectionContext(
    private val testContext: TestContext,
    val name: String,
    private val owner: UserContext
) {
    val electionName: String get() = name

    fun setCandidates(vararg names: String) {
        testContext.backend.setCandidates(owner.accessToken, name, names.toList())
        testContext.backend.synchronize()
    }

    fun delete() {
        testContext.backend.deleteElection(owner.accessToken, name)
        testContext.backend.synchronize()
    }

    val candidates: List<String>
        get() = testContext.database.listCandidates(name)

    fun tally(): Tally =
        testContext.backend.tally(owner.accessToken, name)

    fun getDetails(): com.seanshubin.vote.domain.ElectionDetail {
        testContext.backend.synchronize()
        return testContext.backend.getElection(owner.accessToken, name)
    }

    fun listCandidates(): List<String> {
        testContext.backend.synchronize()
        return testContext.backend.listCandidates(owner.accessToken, name)
    }
}
