package com.seanshubin.vote.backend.auth

import com.seanshubin.vote.contract.AccessToken
import com.seanshubin.vote.contract.RefreshToken
import com.seanshubin.vote.domain.Role
import java.time.Duration

/**
 * Maps between domain token data classes (AccessToken, RefreshToken) and
 * signed JWT strings. The HTTP layer is the only place that handles
 * encoded tokens; the Service layer always works with the data classes.
 */
class TokenEncoder(
    private val cipher: JwtCipher,
    private val accessTokenDuration: Duration = Duration.ofMinutes(10),
    private val refreshTokenDuration: Duration = Duration.ofDays(30),
    private val resetTokenDuration: Duration = Duration.ofHours(1),
) {
    fun encodeAccessToken(token: AccessToken): String =
        cipher.encode(
            mapOf("userName" to token.userName, "role" to token.role.name),
            accessTokenDuration,
        )

    fun encodeRefreshToken(token: RefreshToken): String =
        cipher.encode(mapOf("userName" to token.userName), refreshTokenDuration)

    /** Returns null if the JWT is missing, expired, or has an invalid signature. */
    fun decodeAccessToken(jwt: String?): AccessToken? {
        if (jwt.isNullOrBlank()) return null
        val claims = cipher.decode(jwt) ?: return null
        val userName = claims["userName"] ?: return null
        val roleName = claims["role"] ?: return null
        val role = runCatching { Role.valueOf(roleName) }.getOrNull() ?: return null
        return AccessToken(userName, role)
    }

    fun decodeRefreshToken(jwt: String?): RefreshToken? {
        if (jwt.isNullOrBlank()) return null
        val claims = cipher.decode(jwt) ?: return null
        val userName = claims["userName"] ?: return null
        return RefreshToken(userName)
    }

    /**
     * Reset tokens carry a `purpose=reset` claim and a short TTL (1h).
     * The purpose claim guarantees a leaked access or refresh token can't
     * be replayed against the password-reset endpoint.
     */
    fun encodeResetToken(userName: String): String =
        cipher.encode(
            mapOf("userName" to userName, "purpose" to "reset"),
            resetTokenDuration,
        )

    /** Returns the userName the token was minted for, or null if invalid/expired/wrong-purpose. */
    fun decodeResetToken(jwt: String?): String? {
        if (jwt.isNullOrBlank()) return null
        val claims = cipher.decode(jwt) ?: return null
        if (claims["purpose"] != "reset") return null
        return claims["userName"]
    }
}
