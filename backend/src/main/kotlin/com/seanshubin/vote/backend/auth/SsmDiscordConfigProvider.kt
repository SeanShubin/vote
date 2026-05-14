package com.seanshubin.vote.backend.auth

import aws.sdk.kotlin.services.ssm.SsmClient
import aws.sdk.kotlin.services.ssm.model.GetParameterRequest
import kotlinx.coroutines.runBlocking

/**
 * Reads Discord OAuth config from AWS SSM Parameter Store. Four parameters
 * are loaded: client id, client secret, redirect URI, Rippaverse guild ID.
 * Cached for [cacheTtlMillis] like [SsmInviteCodeProvider] — rotating
 * secrets in SSM takes effect within one TTL window.
 *
 * Any missing parameter disables Discord login (returns null). That makes
 * this safe to deploy before all four parameters have been set in SSM.
 */
class SsmDiscordConfigProvider(
    private val clientIdParameterName: String,
    private val clientSecretParameterName: String,
    private val redirectUriParameterName: String,
    private val guildIdParameterName: String,
    private val region: String,
    private val cacheTtlMillis: Long = 5 * 60 * 1000,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val fetcher: (String) -> String? = { name -> defaultFetch(name, region) },
) : DiscordConfigProvider {
    @Volatile
    private var cachedValue: DiscordConfig? = null

    @Volatile
    private var cachedAt: Long? = null

    override fun current(): DiscordConfig? {
        val now = nowMillis()
        val lastFetch = cachedAt
        if (lastFetch != null && now - lastFetch < cacheTtlMillis) return cachedValue
        val clientId = fetcher(clientIdParameterName) ?: ""
        val clientSecret = fetcher(clientSecretParameterName) ?: ""
        val redirectUri = fetcher(redirectUriParameterName) ?: ""
        val guildId = fetcher(guildIdParameterName) ?: ""
        val candidate = DiscordConfig(clientId, clientSecret, redirectUri, guildId)
        val resolved = if (candidate.isEnabled()) candidate else null
        cachedValue = resolved
        cachedAt = now
        return resolved
    }

    companion object {
        private fun defaultFetch(parameterName: String, region: String): String? = runBlocking {
            SsmClient { this.region = region }.use { client ->
                val response = client.getParameter(
                    GetParameterRequest {
                        name = parameterName
                        withDecryption = true
                    }
                )
                response.parameter?.value?.takeIf { it.isNotBlank() }
            }
        }
    }
}
