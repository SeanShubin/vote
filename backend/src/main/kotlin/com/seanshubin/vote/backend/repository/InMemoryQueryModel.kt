package com.seanshubin.vote.backend.repository

import com.seanshubin.vote.contract.QueryModel
import com.seanshubin.vote.domain.*

class InMemoryQueryModel : QueryModel {
    override fun findUserByName(name: String): User {
        throw NotImplementedError("Stub implementation")
    }

    override fun findUserByEmail(email: String): User {
        throw NotImplementedError("Stub implementation")
    }

    override fun searchUserByName(name: String): User? {
        return null
    }

    override fun searchUserByEmail(email: String): User? {
        return null
    }

    override fun userCount(): Int = 0

    override fun electionCount(): Int = 0

    override fun candidateCount(electionName: String): Int = 0

    override fun voterCount(electionName: String): Int = 0

    override fun tableCount(): Int = 0

    override fun listUsers(): List<User> = emptyList()

    override fun listElections(): List<ElectionSummary> = emptyList()

    override fun roleHasPermission(role: Role, permission: Permission): Boolean {
        // Default permission logic
        return when (permission) {
            Permission.VIEW_APPLICATION -> role >= Role.OBSERVER
            Permission.VOTE -> role >= Role.VOTER
            Permission.USE_APPLICATION -> role >= Role.USER
            Permission.MANAGE_USERS -> role >= Role.ADMIN
            Permission.VIEW_SECRETS -> role >= Role.AUDITOR
            Permission.TRANSFER_OWNER -> role == Role.OWNER
        }
    }

    override fun lastSynced(): Long? = null

    override fun searchElectionByName(name: String): ElectionSummary? = null

    override fun listCandidates(electionName: String): List<String> = emptyList()

    override fun listRankings(voterName: String, electionName: String): List<Ranking> = emptyList()

    override fun listRankings(electionName: String): List<VoterElectionCandidateRank> = emptyList()

    override fun searchBallot(voterName: String, electionName: String): BallotSummary? = null

    override fun listBallots(electionName: String): List<RevealedBallot> = emptyList()

    override fun listVoterNames(): List<String> = emptyList()

    override fun listVotersForElection(electionName: String): List<String> = emptyList()

    override fun listUserNames(): List<String> = emptyList()

    override fun listPermissions(role: Role): List<Permission> {
        return Permission.entries.filter { roleHasPermission(role, it) }
    }
}
