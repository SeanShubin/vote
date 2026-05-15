package com.seanshubin.vote.contract

import com.seanshubin.vote.domain.*

interface QueryModel {
    fun findUserByName(name: String): User
    fun searchUserByName(name: String): User?

    /**
     * Look up the user whose Discord credential matches [discordId]. Returns
     * null when no user has linked that Discord ID — that is the trigger for
     * "first-time Discord login → create stub user". Discord IDs are immutable
     * snowflakes, so this is a stable lookup that survives username changes.
     */
    fun searchUserByDiscordId(discordId: String): User?
    fun userCount(): Int
    fun electionCount(): Int
    fun candidateCount(electionName: String): Int
    fun ballotCount(electionName: String): Int
    fun tableCount(): Int
    fun listUsers(): List<User>
    fun listElections(): List<ElectionSummary>
    fun roleHasPermission(role: Role, permission: Permission): Boolean
    fun lastSynced(): Long?
    fun searchElectionByName(name: String): ElectionSummary?
    fun listCandidates(electionName: String): List<String>

    /**
     * Co-managers of the election — users granted content-editing authority
     * by the owner. Does not include the owner. Empty when none have been
     * added or the election doesn't exist.
     */
    fun listElectionManagers(electionName: String): List<String>

    /**
     * For each candidate in the election, how many ballots mention that
     * candidate (with any rank, including null). Used by the candidate
     * editor to warn the owner about the blast radius of a rename — a
     * rename of a 0-ballot candidate is "free", a 47-ballot rename is
     * disruptive.
     */
    fun candidateBallotCounts(electionName: String): Map<String, Int>
    fun listTiers(electionName: String): List<String>
    fun listRankings(voterName: String, electionName: String): List<Ranking>
    fun listRankings(electionName: String): List<VoterElectionCandidateRank>
    fun searchBallot(voterName: String, electionName: String): BallotSummary?
    fun listBallots(electionName: String): List<Ballot.Revealed>
    fun listUserNames(): List<String>
    fun listPermissions(role: Role): List<Permission>
    fun electionsOwnedCount(userName: String): Int
    fun ballotsCastCount(userName: String): Int
}
