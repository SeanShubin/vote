package com.seanshubin.vote.frontend

import com.seanshubin.vote.domain.Role
import kotlinx.coroutines.test.runTest
import org.w3c.fetch.Response
import org.w3c.fetch.ResponseInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pin the wire-format encoding for path-segment parameters. AWS API Gateway
 * HTTP API v2 URL-decodes the path once before passing it to Lambda and
 * re-parses the decoded string for a query separator — so a single-encoded
 * `?` (`%3F`) arrives at Lambda with everything after it stripped off.
 *
 * The frontend's workaround is to encode each path segment twice. The
 * backend's URLDecoder.decode then collapses the remaining layer. These
 * tests pin the produced URL so a refactor of the encoder helper can't
 * silently revert to single-encoding (which would break only in prod, where
 * the issue is hardest to debug).
 */
class HttpApiClientUrlEncodingTest {

    @Test
    fun `getElection double-encodes ? in the election name`() = runTest {
        val client = clientCapturing()
        // Drain expected to fail (fake fetch returns 200 with empty body);
        // we only care about the URL that was issued.
        runCatching { client.client.getElection("a?b") }
        assertTrue(
            client.urls.any { it.endsWith("/election/a%253Fb") },
            "Expected double-encoded URL ending in /election/a%253Fb, got: ${client.urls}",
        )
    }

    @Test
    fun `getElection double-encodes spaces too (defense in depth)`() = runTest {
        // Spaces don't hit the API Gateway truncation bug, but the helper
        // change applies uniformly, so we pin the wire format here too.
        val client = clientCapturing()
        runCatching { client.client.getElection("Favorite Book") }
        assertTrue(
            client.urls.any { it.endsWith("/election/Favorite%2520Book") },
            "Expected double-encoded space %2520, got: ${client.urls}",
        )
    }

    private class Capturing(val client: HttpApiClient, val urls: MutableList<String>)

    private fun clientCapturing(): Capturing {
        val urls = mutableListOf<String>()
        val fakeFetch: (String, dynamic) -> Promise<Response> = { url, _ ->
            urls.add(url)
            Promise.resolve(Response("{}", ResponseInit(status = 200)))
        }
        val client = HttpApiClient(
            baseUrl = "http://test",
            fetch = fakeFetch.unsafeCast<(String, org.w3c.fetch.RequestInit) -> Promise<Response>>(),
            initialSession = HttpApiClient.Session(
                accessToken = "fake-jwt",
                userName = "alice",
                role = Role.OWNER,
            ),
        )
        return Capturing(client, urls)
    }
}
