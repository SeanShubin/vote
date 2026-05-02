package com.seanshubin.vote.backend.auth

import kotlin.test.Test
import kotlin.test.assertEquals

class SsmInviteCodeProviderTest {

    @Test
    fun `first call fetches from the source`() {
        // Regression: a previous version used Long.MIN_VALUE as the
        // "never fetched" sentinel and computed `now - cachedAt < ttl`.
        // For real epoch-millis `now`, that subtraction overflowed two's
        // complement to a negative number, satisfying the cache-hit branch
        // and returning the (null) cached value without ever invoking the
        // fetcher — silently disabling the registration invite-code gate.
        var fetchCount = 0
        val provider = SsmInviteCodeProvider(
            parameterName = "/stack/invite-code",
            region = "us-east-1",
            cacheTtlMillis = 5 * 60 * 1000,
            nowMillis = { 1_700_000_000_000L },
            fetcher = {
                fetchCount++
                "secret"
            },
        )

        assertEquals("secret", provider.current())
        assertEquals(1, fetchCount)
    }

    @Test
    fun `subsequent calls within ttl reuse the cached value`() {
        var fetchCount = 0
        var clock = 1_700_000_000_000L
        val provider = SsmInviteCodeProvider(
            parameterName = "/stack/invite-code",
            region = "us-east-1",
            cacheTtlMillis = 5 * 60 * 1000,
            nowMillis = { clock },
            fetcher = {
                fetchCount++
                "secret"
            },
        )

        provider.current()
        clock += 60_000
        provider.current()
        clock += 60_000
        provider.current()

        assertEquals(1, fetchCount)
    }

    @Test
    fun `call after ttl refetches`() {
        var fetchCount = 0
        var clock = 1_700_000_000_000L
        val values = listOf("first", "second")
        val provider = SsmInviteCodeProvider(
            parameterName = "/stack/invite-code",
            region = "us-east-1",
            cacheTtlMillis = 5 * 60 * 1000,
            nowMillis = { clock },
            fetcher = { values[fetchCount++] },
        )

        assertEquals("first", provider.current())
        clock += 5 * 60 * 1000
        assertEquals("second", provider.current())
        assertEquals(2, fetchCount)
    }

    @Test
    fun `null fetch result is cached for the ttl window`() {
        // A blank or missing parameter returns null and disables the gate.
        // The provider must still cache the null so it doesn't hit SSM on
        // every registration request when the gate is intentionally off.
        var fetchCount = 0
        var clock = 1_700_000_000_000L
        val provider = SsmInviteCodeProvider(
            parameterName = "/stack/invite-code",
            region = "us-east-1",
            cacheTtlMillis = 5 * 60 * 1000,
            nowMillis = { clock },
            fetcher = {
                fetchCount++
                null
            },
        )

        assertEquals(null, provider.current())
        clock += 60_000
        assertEquals(null, provider.current())
        assertEquals(1, fetchCount)
    }
}
