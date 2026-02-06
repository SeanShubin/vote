package com.seanshubin.vote.backend.repository

import com.seanshubin.vote.contract.CommandModel
import com.seanshubin.vote.domain.ElectionUpdates
import com.seanshubin.vote.domain.Ranking
import com.seanshubin.vote.domain.Role
import kotlinx.datetime.Instant

class InMemoryCommandModel : CommandModel {
    private var lastSynced: Long? = null

    override fun setLastSynced(lastSynced: Long) {
        this.lastSynced = lastSynced
    }

    override fun initializeLastSynced(lastSynced: Long) {
        if (this.lastSynced == null) {
            this.lastSynced = lastSynced
        }
    }

    override fun createUser(
        authority: String,
        userName: String,
        email: String,
        salt: String,
        hash: String,
        role: Role
    ) {
        // In-memory implementation - state changes tracked here
    }

    override fun setRole(authority: String, userName: String, role: Role) {
        // In-memory implementation
    }

    override fun removeUser(authority: String, userName: String) {
        // In-memory implementation
    }

    override fun addElection(authority: String, owner: String, electionName: String) {
        // In-memory implementation
    }

    override fun updateElection(authority: String, electionName: String, updates: ElectionUpdates) {
        // In-memory implementation
    }

    override fun deleteElection(authority: String, electionName: String) {
        // In-memory implementation
    }

    override fun addCandidates(authority: String, electionName: String, candidateNames: List<String>) {
        // In-memory implementation
    }

    override fun removeCandidates(authority: String, electionName: String, candidateNames: List<String>) {
        // In-memory implementation
    }

    override fun addVoters(authority: String, electionName: String, voterNames: List<String>) {
        // In-memory implementation
    }

    override fun removeVoters(authority: String, electionName: String, voterNames: List<String>) {
        // In-memory implementation
    }

    override fun castBallot(
        authority: String,
        voterName: String,
        electionName: String,
        rankings: List<Ranking>,
        confirmation: String,
        now: Instant
    ) {
        // In-memory implementation
    }

    override fun setRankings(
        authority: String,
        confirmation: String,
        electionName: String,
        rankings: List<Ranking>
    ) {
        // In-memory implementation
    }

    override fun updateWhenCast(authority: String, confirmation: String, now: Instant) {
        // In-memory implementation
    }

    override fun setPassword(authority: String, userName: String, salt: String, hash: String) {
        // In-memory implementation
    }

    override fun setUserName(authority: String, oldUserName: String, newUserName: String) {
        // In-memory implementation
    }

    override fun setEmail(authority: String, userName: String, email: String) {
        // In-memory implementation
    }
}
