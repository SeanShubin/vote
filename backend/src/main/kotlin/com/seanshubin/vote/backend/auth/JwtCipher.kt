package com.seanshubin.vote.backend.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import java.time.Clock
import java.time.Duration
import java.util.Date

/**
 * Stateless JWT signer/verifier. HMAC-SHA256 with a single shared secret —
 * simpler than the RSA pattern in the original condorcet-backend, and equally
 * appropriate when only one Lambda function reads its own tokens. Both access
 * and refresh tokens are signed by the same instance with different durations
 * encoded as the `exp` claim.
 */
class JwtCipher(
    secret: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val algorithm: Algorithm = Algorithm.HMAC256(secret)
    private val verifier = JWT.require(algorithm).build()

    /** Encode a flat string-keyed claim map plus an `exp` claim derived from `validFor`. */
    fun encode(claims: Map<String, String>, validFor: Duration): String {
        val builder = JWT.create()
        val now = clock.instant()
        builder.withIssuedAt(Date.from(now))
        builder.withExpiresAt(Date.from(now.plus(validFor)))
        claims.forEach { (k, v) -> builder.withClaim(k, v) }
        return builder.sign(algorithm)
    }

    /**
     * Verify signature + expiration; return the claims map, or null if either fails.
     * Only string claims are surfaced (matches the encode contract).
     */
    fun decode(token: String): Map<String, String>? {
        return try {
            val decoded = verifier.verify(token)
            decoded.claims.entries
                .mapNotNull { (k, v) -> v.asString()?.let { k to it } }
                .toMap()
        } catch (_: JWTVerificationException) {
            null
        }
    }
}
