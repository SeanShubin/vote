package com.seanshubin.vote.backend.auth

import com.seanshubin.vote.backend.http.SetCookie
import java.time.Duration

/**
 * Refresh-cookie attributes. Defaults match production (HTTPS only,
 * cross-subdomain via .pairwisevote.com, scoped to the /api path so
 * the browser doesn't ship the cookie on every static asset request).
 * Local dev should override [secure] = false.
 *
 * @property name cookie name; matches the original's "Refresh"
 * @property path scope — limits which paths the browser sends the cookie on
 */
data class CookieConfig(
    val name: String = "Refresh",
    val path: String = "/api",
    val domain: String? = null,
    val secure: Boolean = true,
    val sameSite: SetCookie.SameSite = SetCookie.SameSite.Lax,
    val maxAge: Duration = Duration.ofDays(30),
) {
    fun makeSetCookie(jwt: String): SetCookie =
        SetCookie(
            name = name,
            value = jwt,
            httpOnly = true,
            secure = secure,
            sameSite = sameSite,
            path = path,
            domain = domain,
            maxAgeSeconds = maxAge.seconds,
        )

    /** Cookie that overwrites the existing one with an empty value + immediate expiry. */
    fun makeClearCookie(): SetCookie =
        SetCookie(
            name = name,
            value = "",
            httpOnly = true,
            secure = secure,
            sameSite = sameSite,
            path = path,
            domain = domain,
            maxAgeSeconds = 0,
        )
}
