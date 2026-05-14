package com.seanshubin.vote.backend.dependencies

/**
 * Process-environment variable names the backend reads.
 *
 * Centralized here so callers reference the names through one source of
 * truth — typos surface at compile time, and ops people grep one file
 * when they need to know what Lambda env keys to set.
 *
 * Convention: the SSM-parameter values themselves never appear in code or
 * Lambda env vars. The Lambda env carries only the SSM parameter PATHS
 * (e.g. `DISCORD_CLIENT_SECRET_PARAMETER_NAME` points to
 * `/<stack>/discord/client-secret`); the actual secret value is read at
 * runtime from SSM SecureString and cached for five minutes. Rotating a
 * secret needs `aws ssm put-parameter` only — no redeploy.
 */
object EnvVars {
    /** HMAC256 secret used to sign access + refresh JWTs. Required in Lambda. */
    const val JWT_SECRET = "JWT_SECRET"

    /** Public origin the SPA is served from. Used in OAuth redirects. */
    const val FRONTEND_BASE_URL = "FRONTEND_BASE_URL"

    /** AWS region, used for the SSM and DynamoDB clients. */
    const val AWS_REGION = "AWS_REGION"

    /** Optional domain to scope the refresh cookie to (e.g. `pairwisevote.com`). */
    const val COOKIE_DOMAIN = "COOKIE_DOMAIN"

    /** SSM parameter path for the Discord OAuth client id. */
    const val DISCORD_CLIENT_ID_PARAMETER_NAME = "DISCORD_CLIENT_ID_PARAMETER_NAME"

    /** SSM parameter path (SecureString) for the Discord OAuth client secret. */
    const val DISCORD_CLIENT_SECRET_PARAMETER_NAME = "DISCORD_CLIENT_SECRET_PARAMETER_NAME"

    /** SSM parameter path for the Discord OAuth redirect URI. */
    const val DISCORD_REDIRECT_URI_PARAMETER_NAME = "DISCORD_REDIRECT_URI_PARAMETER_NAME"

    /** SSM parameter path for the gated Discord guild id. */
    const val DISCORD_GUILD_ID_PARAMETER_NAME = "DISCORD_GUILD_ID_PARAMETER_NAME"
}
