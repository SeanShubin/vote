package com.seanshubin.vote.backend.http

/**
 * Outbound HTTP cookie. Renders to a Set-Cookie header value via [render].
 * Used by [RequestRouter] for refresh-token cookies, then serialized by
 * the runtime adapter (Jetty servlet or APIGW response).
 */
data class SetCookie(
    val name: String,
    val value: String,
    val httpOnly: Boolean = true,
    val secure: Boolean = true,
    val sameSite: SameSite = SameSite.Lax,
    val path: String = "/",
    val domain: String? = null,
    /** Null means session cookie. Negative = expire immediately (for clear). */
    val maxAgeSeconds: Long? = null,
) {
    enum class SameSite { Strict, Lax, None }

    fun render(): String = buildString {
        append("$name=$value")
        append("; Path=$path")
        if (domain != null) append("; Domain=$domain")
        if (maxAgeSeconds != null) append("; Max-Age=$maxAgeSeconds")
        if (httpOnly) append("; HttpOnly")
        if (secure) append("; Secure")
        append("; SameSite=${sameSite.name}")
    }
}
