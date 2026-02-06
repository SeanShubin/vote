package com.seanshubin.vote.contract

import com.seanshubin.vote.domain.*

interface QueryModel {
    fun findUserByName(name: String): User
    fun findUserByEmail(email: String): User
    fun searchUserByName(name: String): User?
    fun searchUserByEmail(email: String): User?
    fun userCount(): Int
    fun electionCount(): Int
    fun candidateCount(electionName: String): Int
    fun voterCount(electionName: String): Int
    fun tableCount(): Int
    fun listUsers(): List<User>
    fun listElections(): List<ElectionSummary>
    fun roleHasPermission(role: Role, permission: Permission): Boolean
    fun lastSynced(): Long?
    fun searchElectionByName(name: String): ElectionSummary?
    fun listCandidates(electionName: String): List<String>
    fun listRankings(voterName: String, electionName: String): List<Ranking>
    fun listRankings(electionName: String): List<VoterElectionCandidateRank>
    fun searchBallot(voterName: String, electionName: String): BallotSummary?
    fun listBallots(electionName: String): List<RevealedBallot>
    fun listVoterNames(): List<String>
    fun listVotersForElection(electionName: String): List<String>
    fun listUserNames(): List<String>
    fun listPermissions(role: Role): List<Permission>
}
