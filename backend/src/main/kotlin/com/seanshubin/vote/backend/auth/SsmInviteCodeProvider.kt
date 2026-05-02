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
) : InviteCodeProvider {
    @Volatile
    private var cachedValue: String? = null
    @Volatile
    private var cachedAt: Long = Long.MIN_VALUE

    override fun current(): String? {
        val now = nowMillis()
        if (now - cachedAt < cacheTtlMillis) return cachedValue
        val fetched = fetch()
        cachedValue = fetched
        cachedAt = now
        return fetched
    }

    private fun fetch(): String? = runBlocking {
        SsmClient { region = this@SsmInviteCodeProvider.region }.use { client ->
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
