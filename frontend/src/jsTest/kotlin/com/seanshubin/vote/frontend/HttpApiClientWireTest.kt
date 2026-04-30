package com.seanshubin.vote.frontend

import com.seanshubin.vote.domain.Ranking
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
 */
class HttpApiClientWireTest {

    private data class Captured(val url: String, val method: String?, val body: String)

    // Stub JWT whose middle (payload) segment base64url-decodes to {"userName":"alice"}.
    // HttpApiClient.extractUserName reads the unverified payload — server-side verification
    // is the real defense. Header and signature segments aren't inspected in these tests.
    private val aliceToken: String = "header.eyJ1c2VyTmFtZSI6ImFsaWNlIn0.signature"

    private fun client(captured: MutableList<Captured>, responseBody: String): HttpApiClient {
        val fakeFetch: (String, RequestInit) -> Promise<Response> = { url, init ->
            captured.add(Captured(url, init.method, init.body as? String ?: ""))
            Promise.resolve(makeResponse(200, responseBody))
        }
        return HttpApiClient("https://api.example.com", fakeFetch)
    }

    @Test
    fun castBallotPutsAuthenticatedUserInVoterNameField() = runTest {
        val captured = mutableListOf<Captured>()

        client(captured, "\"confirmation-id\"")
            .castBallot(aliceToken, "MyElection", listOf(Ranking("Kotlin", 1)))

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
            .castBallot(aliceToken, "Best Programming Language", listOf(Ranking("A", 1)))

        assertEquals("https://api.example.com/election/Best%20Programming%20Language/ballot", captured[0].url)
    }

    @Test
    fun createElectionPutsAuthenticatedUserInUserNameField() = runTest {
        val captured = mutableListOf<Captured>()

        // createElection's HTTP response is Unit, so the fake returns "{}".
        client(captured, "{}").createElection(aliceToken, "MyElection")

        assertEquals(1, captured.size)
        assertEquals("https://api.example.com/election", captured[0].url)
        val body = Json.parseToJsonElement(captured[0].body) as JsonObject
        assertEquals(JsonPrimitive("alice"), body["userName"])
        assertEquals(JsonPrimitive("MyElection"), body["electionName"])
    }

    @Test
    fun registerSerializesAllFields() = runTest {
        val captured = mutableListOf<Captured>()
        val authJson = """{"accessToken":"header.eyJ1c2VyTmFtZSI6ImFsaWNlIn0.signature","userName":"alice","role":"USER"}"""

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
