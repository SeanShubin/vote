package com.seanshubin.vote.backend.dependencies

import com.seanshubin.vote.backend.auth.CookieConfig

/**
 * Fully-resolved runtime configuration. Populated by [Bootstrap] from a
 * combination of command-line args and the env accessor on
 * [com.seanshubin.vote.contract.Integrations]. No code beyond Bootstrap
 * should read environment variables directly — pass values through here
 * instead so the wiring stages stay testable.
 */
data class Configuration(
    val port: Int,
    val databaseConfig: DatabaseConfig,
    val jwtSecret: String,
    val frontendBaseUrl: String,
    val cookieConfig: CookieConfig,
    val discordParameterNames: DiscordParameterNames? = null,
) {
    /**
     * SSM parameter names for the four Discord OAuth values. All four must be
     * set to enable Discord login; null means Discord login is disabled in
     * this environment (which is the dev/test default).
     */
    data class DiscordParameterNames(
        val clientId: String,
        val clientSecret: String,
        val redirectUri: String,
        val guildId: String,
    )

    companion object {
        /**
         * Convenience for tests and one-off recorders that just need a
         * runnable server with dev defaults for everything except [port]
         * and [databaseConfig].
         */
        fun forTesting(port: Int, databaseConfig: DatabaseConfig): Configuration =
            Configuration(
                port = port,
                databaseConfig = databaseConfig,
                jwtSecret = Bootstrap.DEV_JWT_SECRET,
                frontendBaseUrl = Bootstrap.DEV_FRONTEND_BASE_URL,
                cookieConfig = CookieConfig(secure = false, path = "/"),
                discordParameterNames = null,
            )
    }
}
