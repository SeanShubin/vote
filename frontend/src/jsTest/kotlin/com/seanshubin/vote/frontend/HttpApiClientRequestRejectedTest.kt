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
 * The wire-format contract for a declined request: an HTTP 4xx surfaces as
 * RequestRejectedException so logErrorToServer can leave it out of the
 * frontend-error alarm, while the server's reason still reaches the user.
 *
 * The discriminator must be the status code and nothing else. A 5xx is a
 * real fault and has to keep reporting, so the split is the whole value of
 * the type — if 5xx ever started arriving as a rejection, the channel would
 * go quiet exactly when something was actually broken.
 */
class HttpApiClientRequestRejectedTest {

    @Test
    fun `400 response throws RequestRejectedException carrying the server reason`() = runTest {
        // The case that prompted this: validation rejecting an over-long
        // review. The reason names the cap and the actual length, and the
        // user needs to read it, so it must survive the round trip intact.
        val reason = "Note must not be more than 10000 characters long, was 10016"
        val client = clientReturning(status = 400, body = errorJson(reason))

        val thrown = assertFailsWith<RequestRejectedException> {
            client.setCandidateNote("Favorite Rippaverse Book", "Zalen: What Kills Must Die", "x")
        }
        assertEquals(reason, thrown.message)
    }

    @Test
    fun `RequestRejectedException is a subtype of ApiException so generic catches still match`() = runTest {
        val client = clientReturning(status = 400, body = errorJson("too long"))
        val thrown = assertFailsWith<ApiException> {
            client.setCandidateNote("e", "c", "x")
        }
        assertTrue(
            thrown is RequestRejectedException,
            "Expected RequestRejectedException, got ${thrown::class.simpleName}",
        )
    }

    @Test
    fun `404 and 409 are rejections too, not faults`() = runTest {
        // Election deleted from another tab, or a name that collides. Both
        // are answers about the request, not defects in this frontend.
        listOf(404, 409).forEach { status ->
            val client = clientReturning(status = status, body = errorJson("nope"))
            assertFailsWith<RequestRejectedException>("status $status should be a rejection") {
                client.setCandidateNote("e", "c", "x")
            }
        }
    }

    @Test
    fun `500 stays a plain ApiException so it keeps being reported`() = runTest {
        // Regression guard on the split. A server fault must not be filtered
        // out of the alarm by being mistaken for a rejection.
        val client = clientReturning(status = 500, body = errorJson("kaboom"))
        val thrown = assertFailsWith<ApiException> {
            client.setCandidateNote("e", "c", "x")
        }
        assertTrue(
            thrown !is RequestRejectedException,
            "Expected plain ApiException for 500, got RequestRejectedException",
        )
        assertEquals("kaboom", thrown.message)
    }

    @Test
    fun `503 keeps its own subtype rather than becoming a generic rejection`() = runTest {
        // 503 is outside the 4xx range, so this is really a guard against a
        // future widening of the rejection band swallowing the paused case
        // and its global banner.
        val client = clientReturning(status = 503, body = errorJson("paused"))
        val thrown = assertFailsWith<ApiException> {
            client.setCandidateNote("e", "c", "x")
        }
        assertTrue(
            thrown is MaintenancePausedException,
            "Expected MaintenancePausedException, got ${thrown::class.simpleName}",
        )
    }

    @Test
    fun `unparseable error body on 400 still throws RequestRejectedException`() = runTest {
        // An upstream proxy can answer 4xx with HTML. The discriminator is
        // the status code, so the type must not depend on decoding the body.
        val client = clientReturning(status = 400, body = "<html>Bad Request</html>")
        assertFailsWith<RequestRejectedException> {
            client.setCandidateNote("e", "c", "x")
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
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }
}
