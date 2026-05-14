package com.seanshubin.vote.contract

import com.seanshubin.vote.domain.Ranking
import com.seanshubin.vote.domain.Role
import kotlinx.datetime.Instant

interface CommandModel {
    fun setLastSynced(lastSynced: Long)
    fun initializeLastSynced(lastSynced: Long)

    fun setRole(authority: String, userName: String, role: Role)
    fun removeUser(authority: String, userName: String)
    fun addElection(authority: String, owner: String, electionName: String, description: String)
    fun setElectionDescription(authority: String, electionName: String, description: String)
    fun setElectionOwner(authority: String, electionName: String, newOwnerName: String)
    fun deleteElection(authority: String, electionName: String)
    fun addCandidates(authority: String, electionName: String, candidateNames: List<String>)
    fun removeCandidates(authority: String, electionName: String, candidateNames: List<String>)
    fun renameCandidate(authority: String, electionName: String, oldName: String, newName: String)
    fun setTiers(authority: String, electionName: String, tierNames: List<String>)
    fun renameTier(authority: String, electionName: String, oldName: String, newName: String)

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
    fun deleteBallot(authority: String, voterName: String, electionName: String)
    fun setUserName(authority: String, oldUserName: String, newUserName: String)

    /**
     * Create a user that authenticated via Discord. With password login
     * retired this is the only path that brings a new user into existence.
     */
    fun createUserViaDiscord(
        authority: String,
        userName: String,
        discordId: String,
        discordDisplayName: String,
        role: Role,
    )

    /** Attach a Discord credential to an existing user. */
    fun linkDiscordCredential(
        authority: String,
        userName: String,
        discordId: String,
        discordDisplayName: String,
    )

    /** Refresh the cached Discord display name for an existing user. */
    fun setDiscordDisplayName(
        authority: String,
        userName: String,
        discordDisplayName: String,
    )
}
