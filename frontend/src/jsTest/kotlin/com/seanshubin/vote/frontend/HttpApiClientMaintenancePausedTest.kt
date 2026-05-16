package com.seanshubin.vote.frontend

import com.seanshubin.vote.domain.Role
import kotlinx.coroutines.test.runTest
import org.w3c.fetch.Response
import org.w3c.fetch.ResponseInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The wire-format contract: an HTTP 503 response from any write endpoint
 * surfaces as a distinct exception type so call sites (rememberAsyncAction,
 * VotingView's auto-save) can recognize the planned-maintenance case and
 * silently skip — the global banner is the single source of truth. Any other
 * non-2xx is a real error and must continue to surface as a plain
 * ApiException, otherwise the silent-skip logic above would swallow real
 * failures too.
 */
class HttpApiClientMaintenancePausedTest {

    @Test
    fun `503 response throws MaintenancePausedException carrying the server error message`() = runTest {
        val pausedMessage = "Event log is paused for maintenance — new writes are temporarily disabled"
        val client = clientReturning(status = 503, body = errorJson(pausedMessage))

        val thrown = assertFailsWith<MaintenancePausedException> {
            client.pauseEventLog()
        }
        assertEquals(pausedMessage, thrown.message)
    }

    @Test
    fun `MaintenancePausedException is a subtype of ApiException so generic catches still match`() = runTest {
        // The silent-skip catches handle MaintenancePausedException explicitly,
        // but anywhere code does `catch (e: ApiException)` (e.g. future caller
        // that doesn't know about pauses) the existing behavior is preserved.
        val client = clientReturning(status = 503, body = errorJson("paused"))
        val thrown = assertFailsWith<ApiException> {
            client.pauseEventLog()
        }
        assertTrue(
            thrown is MaintenancePausedException,
            "Expected MaintenancePausedException, got ${thrown::class.simpleName}",
        )
    }

    @Test
    fun `non-503 error status still throws plain ApiException, not the paused subtype`() = runTest {
        // Regression guard: the discriminator must be the status code, not just
        // "any error is paused." A 500 server error must NOT trigger the
        // silent-skip path — those are real bugs that need to surface.
        val client = clientReturning(status = 500, body = errorJson("kaboom"))
        val thrown = assertFailsWith<ApiException> {
            client.pauseEventLog()
        }
        assertTrue(
            thrown !is MaintenancePausedException,
            "Expected plain ApiException for 500, got MaintenancePausedException",
        )
        assertEquals("kaboom", thrown.message)
    }

    @Test
    fun `unparseable error body on 503 still throws MaintenancePausedException`() = runTest {
        // Defensive: an upstream proxy might return 503 with an HTML body
        // (e.g. CloudFront's "service unavailable" page). The discriminator
        // is status code, so the silent-skip path must still engage even
        // when handleResponse can't decode the body as ErrorResponse JSON.
        val client = clientReturning(status = 503, body = "<html>Service Unavailable</html>")
        assertFailsWith<MaintenancePausedException> {
            client.pauseEventLog()
        }
    }

    private fun clientReturning(status: Int, body: String): HttpApiClient {
        val fakeFetch: (String, dynamic) -> Promise<Response> = { _, _ ->
            Promise.resolve(Response(body, ResponseInit(status = status.toShort())))
        }
        return HttpApiClient(
            baseUrl = "http://test",
            fetch = fakeFetch.unsafeCast<(String, org.w3c.fetch.RequestInit) -> Promise<Response>>(),
            initialSession = HttpApiClient.Session(
                accessToken = "fake-jwt",
                userName = "alice",
                role = Role.OWNER,
            ),
        )
    }

    private fun errorJson(message: String): String =
        """{"error":${quoteJson(message)}}"""

    private fun quoteJson(s: String): String {
        // Tiny inline JSON-string escaper — only the characters the test
        // messages can contain (quote, backslash). Avoids dragging the full
        // serialization stack in to encode a one-field object.
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }
}
