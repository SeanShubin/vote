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
 *   frontend base URL, email from-address) MUST be present in the env;
 *   absence is a hard error rather than silently using a dev fallback.
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
            jwtSecret = env("JWT_SECRET") ?: DEV_JWT_SECRET,
            frontendBaseUrl = env("FRONTEND_BASE_URL") ?: DEV_FRONTEND_BASE_URL,
            cookieConfig = CookieConfig(
                secure = false,
                sameSite = SetCookie.SameSite.Lax,
                path = "/",
            ),
            emailFromAddress = env("EMAIL_FROM_ADDRESS"),
            inviteCodeParameterName = env("INVITE_CODE_PARAMETER_NAME")?.takeIf { it.isNotBlank() },
        )
    }

    fun parseLambdaConfiguration(): Configuration {
        val env = integrations.getEnv
        val region = env("AWS_REGION") ?: "us-east-1"
        return Configuration(
            port = 0,
            databaseConfig = DatabaseConfig.DynamoDB(endpoint = null, region = region),
            jwtSecret = env("JWT_SECRET") ?: error("JWT_SECRET env var is required"),
            frontendBaseUrl = env("FRONTEND_BASE_URL") ?: error("FRONTEND_BASE_URL env var is required"),
            cookieConfig = CookieConfig(
                domain = env("COOKIE_DOMAIN"),
                secure = true,
                sameSite = SetCookie.SameSite.Lax,
                path = "/api",
            ),
            emailFromAddress = env("EMAIL_FROM_ADDRESS") ?: error("EMAIL_FROM_ADDRESS env var is required"),
            inviteCodeParameterName = env("INVITE_CODE_PARAMETER_NAME")?.takeIf { it.isNotBlank() },
        )
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
