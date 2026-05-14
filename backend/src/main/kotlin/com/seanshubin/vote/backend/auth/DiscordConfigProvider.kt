package com.seanshubin.vote.backend.auth

/**
 * Discord OAuth + Rippaverse-gate configuration. All four fields must be set
 * for Discord login to be enabled; if any are blank, [DiscordConfig.isEnabled]
 * returns false and the service rejects Discord-login requests with
 * UNSUPPORTED. That null-disables-the-feature shape mirrors
 * [InviteCodeProvider] and lets dev/test runs operate without Discord
 * credentials.
 */
data class DiscordConfig(
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
    val rippaverseGuildId: String,
) {
    fun isEnabled(): Boolean =
        clientId.isNotBlank() &&
            clientSecret.isNotBlank() &&
            redirectUri.isNotBlank() &&
            rippaverseGuildId.isNotBlank()
}

fun interface DiscordConfigProvider {
    /** Current config; null when Discord login is disabled in this environment. */
    fun current(): DiscordConfig?
}
