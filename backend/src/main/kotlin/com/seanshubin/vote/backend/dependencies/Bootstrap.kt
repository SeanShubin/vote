package com.seanshubin.vote.backend.dependencies

import com.seanshubin.vote.backend.auth.CookieConfig
import com.seanshubin.vote.backend.http.SetCookie
import com.seanshubin.vote.contract.Integrations

/**
 * Resolves a [Configuration] from command-line args and the env accessor
 * supplied via [Integrations]. Two flavors:
 *
 * - [parseDevConfiguration]: every value has a sensible local-dev fallback
 *   (no env required to start a server). Used by the Jetty entry point and
 *   the integration tests.
 * - [parseLambdaConfiguration]: required production secrets (JWT secret,
 *   frontend base URL) MUST be present in the env; absence is a hard error
 *   rather than silently using a dev fallback.
 *
 * Tests construct [Configuration] directly, so they don't go through here.
 */
class Bootstrap(
    private val integrations: Integrations
) {
    fun parseDevConfiguration(): Configuration {
        val args = integrations.commandLineArgs
        val port = args.getOrNull(0)?.toIntOrNull() ?: 8080
        val dbType = args.getOrNull(1) ?: "memory"
        val databaseConfig = parseDatabaseConfig(dbType, awsRegion = "us-east-1")
        integrations.emitLine("Starting with database type: $dbType")

        val env = integrations.getEnv
        return Configuration(
            port = port,
            databaseConfig = databaseConfig,
            jwtSecret = env(EnvVars.JWT_SECRET) ?: DEV_JWT_SECRET,
            frontendBaseUrl = env(EnvVars.FRONTEND_BASE_URL) ?: DEV_FRONTEND_BASE_URL,
            cookieConfig = CookieConfig(
                secure = false,
                sameSite = SetCookie.SameSite.Lax,
                path = "/",
            ),
            discordParameterNames = parseDiscordParameterNames(env),
            // Local dev only. The Discord-bypass login is for running a
            // prod snapshot locally (scripts/dev.ps1 launch-from-snapshot);
            // every local entry point reaches the server through here.
            devLoginEnabled = true,
        )
    }

    fun parseLambdaConfiguration(): Configuration {
        val env = integrations.getEnv
        val region = env(EnvVars.AWS_REGION) ?: "us-east-1"
        return Configuration(
            port = 0,
            databaseConfig = DatabaseConfig.DynamoDB(endpoint = null, region = region),
            jwtSecret = env(EnvVars.JWT_SECRET) ?: error("${EnvVars.JWT_SECRET} env var is required"),
            frontendBaseUrl = env(EnvVars.FRONTEND_BASE_URL)
                ?: error("${EnvVars.FRONTEND_BASE_URL} env var is required"),
            cookieConfig = CookieConfig(
                domain = env(EnvVars.COOKIE_DOMAIN),
                secure = true,
                sameSite = SetCookie.SameSite.Lax,
                path = "/api",
            ),
            discordParameterNames = parseDiscordParameterNames(env),
            // Never in production. The Discord-bypass login mints a session
            // for any user name — keeping this false here is the structural
            // guarantee that it cannot exist in the deployed environment.
            devLoginEnabled = false,
        )
    }

    /**
     * All four env vars must be set for Discord login to be enabled. Missing
     * any of them returns null — the resulting null DiscordConfigProvider
     * causes ServiceImpl to reject Discord login attempts with UNSUPPORTED.
     */
    private fun parseDiscordParameterNames(env: (String) -> String?): Configuration.DiscordParameterNames? {
        val clientId = env(EnvVars.DISCORD_CLIENT_ID_PARAMETER_NAME)?.takeIf { it.isNotBlank() } ?: return null
        val clientSecret = env(EnvVars.DISCORD_CLIENT_SECRET_PARAMETER_NAME)?.takeIf { it.isNotBlank() } ?: return null
        val redirectUri = env(EnvVars.DISCORD_REDIRECT_URI_PARAMETER_NAME)?.takeIf { it.isNotBlank() } ?: return null
        val guildId = env(EnvVars.DISCORD_GUILD_ID_PARAMETER_NAME)?.takeIf { it.isNotBlank() } ?: return null
        return Configuration.DiscordParameterNames(clientId, clientSecret, redirectUri, guildId)
    }

    private fun parseDatabaseConfig(dbType: String, awsRegion: String): DatabaseConfig =
        when (dbType.lowercase()) {
            "memory" -> DatabaseConfig.InMemory
            "mysql" -> DatabaseConfig.MySql()
            "dynamodb" -> DatabaseConfig.DynamoDB(region = awsRegion)
            else -> {
                integrations.emitLine("Unknown database type: $dbType. Using in-memory.")
                DatabaseConfig.InMemory
            }
        }

    companion object {
        const val DEV_JWT_SECRET: String = "dev-jwt-secret-DO-NOT-USE-IN-PROD"
        const val DEV_FRONTEND_BASE_URL: String = "http://localhost:3000"
    }
}
