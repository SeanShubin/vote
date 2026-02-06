package com.seanshubin.vote.contract

interface PasswordUtil {
    data class SaltAndHash(val salt: String, val hash: String)

    fun createSaltAndHash(password: String): SaltAndHash
    fun passwordMatches(password: String, salt: String, hash: String): Boolean
}
