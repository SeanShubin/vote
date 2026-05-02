package com.seanshubin.vote.backend.auth

import aws.sdk.kotlin.services.ssm.SsmClient
import aws.sdk.kotlin.services.ssm.model.GetParameterRequest
import kotlinx.coroutines.runBlocking

/**
 * Reads the invite code from AWS SSM Parameter Store and caches it for
 * [cacheTtlMillis] milliseconds. Rotating the code in SSM takes effect within
 * one TTL window — no Lambda redeploy required.
 *
 * A blank or missing parameter disables the gate (registration is open).
 * Network/permission errors propagate; we'd rather a registration request
 * fail loudly than silently let everyone in.
 */
class SsmInviteCodeProvider(
    private val parameterName: String,
    private val region: String,
    private val cacheTtlMillis: Long = 5 * 60 * 1000,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val fetcher: () -> String? = { defaultFetch(parameterName, region) },
) : InviteCodeProvider {
    @Volatile
    private var cachedValue: String? = null

    // Nullable rather than a Long.MIN_VALUE sentinel — `now - Long.MIN_VALUE`
    // overflows two's complement to a negative number that always satisfies
    // `< cacheTtlMillis`, which would short-circuit every call to the (null)
    // cached value and silently disable the gate.
    @Volatile
    private var cachedAt: Long? = null

    override fun current(): String? {
        val now = nowMillis()
        val lastFetch = cachedAt
        if (lastFetch != null && now - lastFetch < cacheTtlMillis) return cachedValue
        val fetched = fetcher()
        cachedValue = fetched
        cachedAt = now
        return fetched
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
