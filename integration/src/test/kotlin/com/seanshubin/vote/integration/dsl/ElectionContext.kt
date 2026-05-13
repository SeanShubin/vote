package com.seanshubin.vote.integration.dsl

import com.seanshubin.vote.domain.ElectionTally

class ElectionContext(
    private val testContext: TestContext,
    val name: String,
    private val owner: UserContext
) {
    val electionName: String get() = name

    /**
     * Test-DSL convenience: set the election's candidates to exactly
     * [names]. Backed by the new primitive-only API (one [addCandidates]
     * call + one [removeCandidate] call per dropped name); kept on the
     * DSL surface because most existing tests want "the candidate list is
     * now exactly X" semantics without spelling out the diff themselves.
     */
    fun setCandidates(vararg names: String) {
        val desired = names.toList()
        val existing = testContext.database.listCandidates(name)
        val toAdd = desired.filter { it !in existing }
        val toRemove = existing.filter { it !in desired }
        if (toAdd.isNotEmpty()) {
            testContext.backend.addCandidates(owner.accessToken, name, toAdd)
        }
        for (gone in toRemove) {
            testContext.backend.removeCandidate(owner.accessToken, name, gone)
        }
        testContext.backend.synchronize()
    }

    fun addCandidates(vararg names: String) {
        testContext.backend.addCandidates(owner.accessToken, name, names.toList())
        testContext.backend.synchronize()
    }

    fun removeCandidate(candidateName: String) {
        testContext.backend.removeCandidate(owner.accessToken, name, candidateName)
        testContext.backend.synchronize()
    }

    fun setTiers(vararg names: String) {
        testContext.backend.setTiers(owner.accessToken, name, names.toList())
        testContext.backend.synchronize()
    }

    fun delete() {
        testContext.backend.deleteElection(owner.accessToken, name)
        testContext.backend.synchronize()
    }

    fun transferOwnership(newOwnerName: String) {
        testContext.backend.transferElectionOwnership(owner.accessToken, name, newOwnerName)
        testContext.backend.synchronize()
    }

    val candidates: List<String>
        get() = testContext.database.listCandidates(name)

    fun tally(): ElectionTally =
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
