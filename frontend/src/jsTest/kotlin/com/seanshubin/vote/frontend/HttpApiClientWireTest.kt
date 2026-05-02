package com.seanshubin.vote.frontend

import com.seanshubin.vote.domain.Ranking
import com.seanshubin.vote.domain.Role
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies that HttpApiClient puts the right bytes on the wire for each
 * endpoint. The frontend's other tests use FakeApiClient and so never
 * exercise serialization — the original castBallot bug (sending
 * electionName as voterName) lived in this gap.
 *
 * After the auth refactor, the access token is internal state on the client.
 * Tests pass an [HttpApiClient.Session] directly to skip the
 * register/authenticate handshake; the token value itself is irrelevant
 * here (only headers and bodies are asserted).
 */
class HttpApiClientWireTest {

    private data class Captured(val url: String, val method: String?, val body: String)

    private val aliceSession = HttpApiClient.Session(
        accessToken = "test-access-token",
        userName = "alice",
        role = Role.USER,
    )

    private fun client(captured: MutableList<Captured>, responseBody: String): HttpApiClient {
        val fakeFetch: (String, RequestInit) -> Promise<Response> = { url, init ->
            captured.add(Captured(url, init.method, init.body as? String ?: ""))
            Promise.resolve(makeResponse(200, responseBody))
        }
        return HttpApiClient("https://api.example.com", fakeFetch, initialSession = aliceSession)
    }

    @Test
    fun castBallotPutsAuthenticatedUserInVoterNameField() = runTest {
        val captured = mutableListOf<Captured>()

        client(captured, "\"confirmation-id\"")
            .castBallot("MyElection", listOf(Ranking("Kotlin", 1)))

        assertEquals(1, captured.size)
        assertEquals("https://api.example.com/election/MyElection/ballot", captured[0].url)
        assertEquals("POST", captured[0].method)

        // The body must put the AUTHENTICATED user's name in voterName, not the election name.
        // This is exactly the regression that produced "User Foo is not eligible to vote in election Foo".
        val body = Json.parseToJsonElement(captured[0].body) as JsonObject
        assertEquals(JsonPrimitive("alice"), body["voterName"])
    }

    @Test
    fun castBallotEncodesElectionNameInUrl() = runTest {
        val captured = mutableListOf<Captured>()

        client(captured, "\"confirmation-id\"")
            .castBallot("Best Programming Language", listOf(Ranking("A", 1)))

        assertEquals("https://api.example.com/election/Best%20Programming%20Language/ballot", captured[0].url)
    }

    @Test
    fun deleteMyBallotIssuesDeleteAgainstAuthenticatedUserPath() = runTest {
        val captured = mutableListOf<Captured>()

        // Server replies with the standard {"status": "..."} envelope; the
        // client's DELETE helper just discards it, so any 200 body is fine.
        client(captured, """{"status":"ballot deleted"}""").deleteMyBallot("MyElection")

        assertEquals(1, captured.size)
        // URL embeds both the election (path arg) and the AUTHENTICATED user
        // name (alice from aliceSession) — the same identity rule castBallot uses.
        assertEquals("https://api.example.com/election/MyElection/ballot/alice", captured[0].url)
        assertEquals("DELETE", captured[0].method)
    }

    @Test
    fun createElectionPutsAuthenticatedUserInUserNameField() = runTest {
        val captured = mutableListOf<Captured>()

        // createElection's HTTP response is Unit, so the fake returns "{}".
        client(captured, "{}").createElection("MyElection")

        assertEquals(1, captured.size)
        assertEquals("https://api.example.com/election", captured[0].url)
        val body = Json.parseToJsonElement(captured[0].body) as JsonObject
        assertEquals(JsonPrimitive("alice"), body["userName"])
        assertEquals(JsonPrimitive("MyElection"), body["electionName"])
    }

    @Test
    fun registerSerializesAllFields() = runTest {
        val captured = mutableListOf<Captured>()
        val authJson = """{"accessToken":"some-jwt","userName":"alice","role":"USER"}"""

        client(captured, authJson).register("alice", "alice@example.com", "secret")

        val body = Json.parseToJsonElement(captured[0].body) as JsonObject
        assertEquals(JsonPrimitive("alice"), body["userName"])
        assertEquals(JsonPrimitive("alice@example.com"), body["email"])
        assertEquals(JsonPrimitive("secret"), body["password"])
    }

    /** Constructs a minimal Response stand-in — only the methods HttpApiClient actually calls. */
    private fun makeResponse(status: Int, bodyText: String): Response {
        val obj: dynamic = js("({})")
        obj.ok = (status in 200..299)
        obj.status = status
        obj.text = { Promise.resolve(bodyText) }
        return obj.unsafeCast<Response>()
    }
}
