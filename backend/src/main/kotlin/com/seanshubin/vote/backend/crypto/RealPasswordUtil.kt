package com.seanshubin.vote.backend.crypto

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*

object RealPasswordUtil : com.seanshubin.vote.contract.PasswordUtil {
    private val random = SecureRandom()
    private val charset = StandardCharsets.UTF_8

    override fun createSaltAndHash(password: String): com.seanshubin.vote.contract.PasswordUtil.SaltAndHash {
        val saltBytes = ByteArray(32)
        random.nextBytes(saltBytes)
        val salt = Base64.getEncoder().encodeToString(saltBytes)

        val passwordBytes = password.toByteArray(charset)
        val saltAndPasswordBytes = saltBytes + passwordBytes
        val hashBytes = sha256(saltAndPasswordBytes)
        val hash = Base64.getEncoder().encodeToString(hashBytes)

        return com.seanshubin.vote.contract.PasswordUtil.SaltAndHash(salt, hash)
    }

    override fun passwordMatches(password: String, salt: String, hash: String): Boolean {
        val saltBytes = Base64.getDecoder().decode(salt)
        val passwordBytes = password.toByteArray(charset)
        val actualHashBytes = sha256(saltBytes + passwordBytes)
        val actualHash = Base64.getEncoder().encodeToString(actualHashBytes)
        return actualHash == hash
    }

    private fun sha256(bytes: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes)
    }
}
