package com.seanshubin.vote.backend.validation

import com.seanshubin.vote.domain.Ranking

object Validation {
    private val whitespacePattern = Regex("\\s+")

    fun validateUserName(userName: String): String {
        val trimmed = userName.trim()
        require(trimmed.isNotEmpty()) { "User name must not be empty" }
        require(trimmed.length <= 200) { "User name must not be more than 200 characters long, was ${trimmed.length}" }
        return trimmed.replace(whitespacePattern, " ")
    }

    fun validateEmail(email: String): String {
        val trimmed = email.trim()
        require(trimmed.isNotEmpty()) { "Email must not be empty" }
        require(trimmed.length <= 200) { "Email must not be more than 200 characters long" }
        require(trimmed.count { it == '@' } == 1) { "Email must contain exactly one @ sign" }
        require(!trimmed.contains(whitespacePattern)) { "Email must not contain whitespace" }
        return trimmed
    }

    fun validatePassword(password: String): String {
        require(password.isNotEmpty()) { "Password must not be empty" }
        require(password.length <= 200) { "Password must not be more than 200 characters long" }
        return password
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

        val duplicates = validNames.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        require(duplicates.isEmpty()) {
            "Duplicate tier names found: ${duplicates.joinToString()}"
        }

        return validNames
    }

    fun validateNameOrEmail(nameOrEmail: String): String {
        val trimmed = nameOrEmail.trim()
        require(trimmed.isNotEmpty()) { "Name or email must not be empty" }
        return trimmed.replace(whitespacePattern, " ")
    }

    fun validateRankings(rankings: List<Ranking>): List<Ranking> {
        require(rankings.isNotEmpty()) { "Rankings list must not be empty" }

        return rankings.map { ranking ->
            val validCandidateName = validateCandidateName(ranking.candidateName)
            ranking.rank?.let { rank ->
                require(rank > 0) { "Rank must be positive, was $rank for candidate: $validCandidateName" }
            }
            Ranking(validCandidateName, ranking.rank)
        }
    }

    fun validateRankingsMatchCandidates(rankings: List<Ranking>, candidates: List<String>) {
        val rankedCandidates = rankings.map { it.candidateName }.toSet()
        val validCandidates = candidates.toSet()

        val unknownCandidates = rankedCandidates - validCandidates
        require(unknownCandidates.isEmpty()) {
            "Rankings contain unknown candidates: ${unknownCandidates.joinToString()}"
        }
    }

    fun validateCandidateNames(names: List<String>): List<String> {
        // Empty list is allowed — lets an owner clear all candidates (e.g. when
        // restarting an election setup). Each individual name still has to pass
        // validateCandidateName below, so empty *strings* in the list still fail.
        val validNames = names.map { validateCandidateName(it) }

        val duplicates = validNames.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        require(duplicates.isEmpty()) {
            "Duplicate candidate names found: ${duplicates.joinToString()}"
        }

        return validNames
    }

    fun validateVoterNames(names: List<String>): List<String> {
        require(names.isNotEmpty()) { "Voter list must not be empty" }

        val validNames = names.map { validateUserName(it) }

        val duplicates = validNames.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        require(duplicates.isEmpty()) {
            "Duplicate voter names found: ${duplicates.joinToString()}"
        }

        return validNames
    }
}
