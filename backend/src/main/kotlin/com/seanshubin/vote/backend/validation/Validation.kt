package com.seanshubin.vote.backend.validation

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

    fun validateCandidateName(candidateName: String): String {
        val trimmed = candidateName.trim()
        require(trimmed.isNotEmpty()) { "Candidate name must not be empty" }
        require(trimmed.length <= 200) { "Candidate name must not be more than 200 characters long" }
        return trimmed.replace(whitespacePattern, " ")
    }

    fun validateNameOrEmail(nameOrEmail: String): String {
        val trimmed = nameOrEmail.trim()
        require(trimmed.isNotEmpty()) { "Name or email must not be empty" }
        return trimmed.replace(whitespacePattern, " ")
    }
}
