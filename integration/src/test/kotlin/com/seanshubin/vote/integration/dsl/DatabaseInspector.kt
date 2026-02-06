package com.seanshubin.vote.integration.dsl

import com.seanshubin.vote.contract.QueryModel
import com.seanshubin.vote.domain.*

class DatabaseInspector(private val queryModel: QueryModel) {
    fun findUser(name: String): User =
        queryModel.findUserByName(name)

    fun searchUser(name: String): User? =
        queryModel.searchUserByName(name)

    fun findBallot(voterName: String, electionName: String): BallotSummary =
        queryModel.searchBallot(voterName, electionName)
            ?: error("No ballot found for $voterName in $electionName")

    fun searchBallot(voterName: String, electionName: String): BallotSummary? =
        queryModel.searchBallot(voterName, electionName)

    fun listCandidates(electionName: String): List<String> =
        queryModel.listCandidates(electionName)

    fun listElections(): List<ElectionSummary> =
        queryModel.listElections()

    fun findElection(electionName: String): ElectionSummary =
        queryModel.searchElectionByName(electionName)
            ?: error("No election found: $electionName")

    fun searchElection(electionName: String): ElectionSummary? =
        queryModel.searchElectionByName(electionName)

    fun userCount(): Int =
        queryModel.userCount()

    fun electionCount(): Int =
        queryModel.electionCount()

    fun listUsers(): List<User> =
        queryModel.listUsers()

    fun listEligibleVoters(electionName: String): List<String> =
        queryModel.listVotersForElection(electionName)

    fun listBallots(electionName: String): List<RevealedBallot> =
        queryModel.listBallots(electionName)
}
