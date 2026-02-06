package com.seanshubin.vote.contract

import com.seanshubin.vote.domain.ElectionUpdates
import com.seanshubin.vote.domain.Ranking
import com.seanshubin.vote.domain.Role
import kotlinx.datetime.Instant

interface CommandModel {
    fun setLastSynced(lastSynced: Long)
    fun initializeLastSynced(lastSynced: Long)

    fun createUser(
        authority: String,
        userName: String,
        email: String,
        salt: String,
        hash: String,
        role: Role
    )

    fun setRole(authority: String, userName: String, role: Role)
    fun removeUser(authority: String, userName: String)
    fun addElection(authority: String, owner: String, electionName: String)
    fun updateElection(authority: String, electionName: String, updates: ElectionUpdates)
    fun deleteElection(authority: String, electionName: String)
    fun addCandidates(authority: String, electionName: String, candidateNames: List<String>)
    fun removeCandidates(authority: String, electionName: String, candidateNames: List<String>)
    fun addVoters(authority: String, electionName: String, voterNames: List<String>)
    fun removeVoters(authority: String, electionName: String, voterNames: List<String>)

    fun castBallot(
        authority: String,
        voterName: String,
        electionName: String,
        rankings: List<Ranking>,
        confirmation: String,
        now: Instant
    )

    fun setRankings(authority: String, confirmation: String, electionName: String, rankings: List<Ranking>)
    fun updateWhenCast(authority: String, confirmation: String, now: Instant)
    fun setPassword(authority: String, userName: String, salt: String, hash: String)
    fun setUserName(authority: String, oldUserName: String, newUserName: String)
    fun setEmail(authority: String, userName: String, email: String)
}
