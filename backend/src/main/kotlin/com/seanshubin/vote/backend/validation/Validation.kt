package com.seanshubin.vote.backend.validation

import com.seanshubin.vote.domain.Ranking
import com.seanshubin.vote.domain.RankingKind

object Validation {
    private val whitespacePattern = Regex("\\s+")

    fun validateUserName(userName: String): String {
        val trimmed = userName.trim()
        require(trimmed.isNotEmpty()) { "User name must not be empty" }
        require(trimmed.length <= 200) { "User name must not be more than 200 characters long, was ${trimmed.length}" }
        return trimmed.replace(whitespacePattern, " ")
    }

    fun validateElectionName(electionName: String): String {
        val trimmed = electionName.trim()
        require(trimmed.isNotEmpty()) { "Election name must not be empty" }
        require(trimmed.length <= 200) { "Election name must not be more than 200 characters long" }
        return trimmed.replace(whitespacePattern, " ")
    }

    /**
     * Description is optional — empty string means "no description". Allows
     * longer text than the name (which is a single-line label) since the
     * description is a free-form blurb for voters. Whitespace is preserved
     * apart from trimming the outer edges so paragraph breaks survive.
     */
    fun validateElectionDescription(description: String): String {
        val trimmed = description.trim()
        require(trimmed.length <= 2000) {
            "Election description must not be more than 2000 characters long, was ${trimmed.length}"
        }
        return trimmed
    }

    fun validateCandidateName(candidateName: String): String {
        val trimmed = candidateName.trim()
        require(trimmed.isNotEmpty()) { "Candidate name must not be empty" }
        require(trimmed.length <= 200) { "Candidate name must not be more than 200 characters long" }
        return trimmed.replace(whitespacePattern, " ")
    }

    fun validateTierName(tierName: String): String {
        val trimmed = tierName.trim()
        require(trimmed.isNotEmpty()) { "Tier name must not be empty" }
        require(trimmed.length <= 200) { "Tier name must not be more than 200 characters long" }
        return trimmed.replace(whitespacePattern, " ")
    }

    fun validateTierNames(names: List<String>): List<String> {
        // Empty list is allowed — clearing tiers reverts the election to
        // candidate-only voting (the no-tiers code path).
        val validNames = names.map { validateTierName(it) }
        requireNoCaseInsensitiveDuplicates(validNames, "tier")
        return validNames
    }

    fun validateRankings(rankings: List<Ranking>): List<Ranking> {
        require(rankings.isNotEmpty()) { "Rankings list must not be empty" }

        return rankings.map { ranking ->
            val validCandidateName = validateCandidateName(ranking.candidateName)
            ranking.rank?.let { rank ->
                require(rank > 0) { "Rank must be positive, was $rank for candidate: $validCandidateName" }
            }
            // Validation rule for the post-projection storage form:
            // voters cast candidate rankings only. Tier markers are
            // materialized by the projection at compute time, so an
            // incoming tier-kind ranking would mean the caller skipped
            // serialization rules — reject loudly rather than store it
            // and trip the projection's guard at tally time.
            require(ranking.kind == RankingKind.CANDIDATE) {
                "Ranking must be CANDIDATE-kind; tier markers are produced by projection, not cast: $validCandidateName"
            }
            // rank=null means "I abstain on this candidate." That voter
            // hasn't expressed a tier judgment about them either, so a
            // tier annotation on a null-rank ranking is incoherent.
            if (ranking.rank == null) {
                require(ranking.tier == null) {
                    "Ranking with rank=null cannot carry a tier annotation: $validCandidateName"
                }
            }
            val validTier = ranking.tier?.let { validateTierName(it) }
            // Preserve `side` — reconstructing without it would silently
            // collapse every SECRET-side ranking back to the PUBLIC default,
            // which is exactly the bug that hid copied secret ballots from
            // the secret-side tally before this line was fixed.
            Ranking(validCandidateName, ranking.rank, ranking.kind, validTier, ranking.side)
        }
    }

    fun validateRankingsMatchCandidates(
        rankings: List<Ranking>,
        candidates: List<String>,
        tiers: List<String>,
    ) {
        // Set ops use lowercase keys so a ballot's "Alice" matches the
        // election's stored "alice" (and vice versa). Names are otherwise
        // case-insensitive in this app; only passwords are case-sensitive.
        val candidateKeys = candidates.map { it.lowercase() }.toSet()
        val unknownCandidates = rankings.map { it.candidateName }
            .filter { it.lowercase() !in candidateKeys }
            .distinct()
        require(unknownCandidates.isEmpty()) {
            "Rankings contain unknown candidates: ${unknownCandidates.joinToString()}"
        }
        // Each per-ranking tier annotation must reference a tier configured
        // on the election. The voter's UI picks from the election's tier
        // list, so a mismatched annotation here means the ballot was built
        // against a stale view of the election (or hand-crafted) — reject
        // it so a stray label can't sneak into the projected ballot.
        val tierKeys = tiers.map { it.lowercase() }.toSet()
        val unknownTiers = rankings.mapNotNull { it.tier }
            .filter { it.lowercase() !in tierKeys }
            .distinct()
        require(unknownTiers.isEmpty()) {
            "Rankings reference unknown tiers: ${unknownTiers.joinToString()}"
        }
    }

    /**
     * Reject when any candidate name collides with any tier name. Comparison
     * is case-insensitive and whitespace-insensitive (the names have already
     * been normalized by [validateCandidateName]/[validateTierName] which
     * collapse internal whitespace and trim ends; we lowercase here so
     * "Pass" and "pass" still collide). Detail pages classify each name as
     * candidate-or-tier by membership lookup, so an ambiguous name would
     * show up incorrectly in one of the two categories.
     */
    fun validateCandidatesAndTiersDistinct(candidates: List<String>, tiers: List<String>) {
        val candidateKeys = candidates.associateBy { it.lowercase() }
        val tierKeys = tiers.associateBy { it.lowercase() }
        val collidingKeys = candidateKeys.keys.intersect(tierKeys.keys)
        require(collidingKeys.isEmpty()) {
            val pairs = collidingKeys.map { key ->
                val candidate = candidateKeys.getValue(key)
                val tier = tierKeys.getValue(key)
                if (candidate == tier) "'$candidate'" else "'$candidate' and '$tier'"
            }
            "Candidate and tier names must be distinct, found collision(s): ${pairs.joinToString()}"
        }
    }

    fun validateCandidateNames(names: List<String>): List<String> {
        // Empty list is allowed — lets an owner clear all candidates (e.g. when
        // restarting an election setup). Each individual name still has to pass
        // validateCandidateName below, so empty *strings* in the list still fail.
        val validNames = names.map { validateCandidateName(it) }
        requireNoCaseInsensitiveDuplicates(validNames, "candidate")
        return validNames
    }

    fun validateVoterNames(names: List<String>): List<String> {
        require(names.isNotEmpty()) { "Voter list must not be empty" }
        val validNames = names.map { validateUserName(it) }
        requireNoCaseInsensitiveDuplicates(validNames, "voter")
        return validNames
    }

    // Report the *original-case* names that collide so the operator can see
    // which inputs are the offending pair (e.g. "Alice" and "alice"), not
    // the lowercased key.
    private fun requireNoCaseInsensitiveDuplicates(names: List<String>, kind: String) {
        val collisions = names.groupBy { it.lowercase() }.values.filter { it.size > 1 }
        require(collisions.isEmpty()) {
            val groups = collisions.joinToString("; ") { it.joinToString("/") }
            "Duplicate $kind names found: $groups"
        }
    }
}
