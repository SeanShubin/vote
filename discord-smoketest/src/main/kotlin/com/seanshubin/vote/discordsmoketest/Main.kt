package com.seanshubin.vote.discordsmoketest

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import kotlin.system.exitProcess

/**
 * End-to-end Discord OAuth smoke test. No dependency on the rest of the
 * codebase — proves the OAuth app credentials, redirect URI, identify+guilds
 * scopes, and Rippaverse guild gate before any of this gets wired into the
 * real service.
 *
 * Required env vars:
 *   DISCORD_CLIENT_ID
 *   DISCORD_CLIENT_SECRET
 *   RIPPAVERSE_GUILD_ID
 * Optional:
 *   DISCORD_REDIRECT_URI (default http://localhost:8080/discord/callback)
 *
 * The redirect URI must be registered EXACTLY in the Discord developer portal
 * under OAuth2 → Redirects.
 */

private const val DISCORD_AUTHORIZE = "https://discord.com/oauth2/authorize"
private const val DISCORD_TOKEN = "https://discord.com/api/oauth2/token"
private const val DISCORD_API = "https://discord.com/api"

fun main() {
    val clientId = requireEnv("DISCORD_CLIENT_ID")
    val clientSecret = requireEnv("DISCORD_CLIENT_SECRET")
    val rippaverseGuildId = requireEnv("RIPPAVERSE_GUILD_ID")
    val redirectUri = System.getenv("DISCORD_REDIRECT_URI")
        ?: "http://localhost:8080/discord/callback"

    val redirectParsed = URI.create(redirectUri)
    val redirectPort = redirectParsed.port.takeIf { it > 0 } ?: 8080
    val redirectPath = redirectParsed.path.ifEmpty { "/" }

    val state = randomState()
    val scope = "identify guilds"
    val authUrl = buildAuthUrl(clientId, redirectUri, scope, state)

    println("=== Discord OAuth smoke test ===")
    println()
    println("1. Open this URL in your browser:")
    println()
    println("   $authUrl")
    println()
    println("2. Authorize the app. Discord will redirect to:")
    println("   $redirectUri")
    println()
    println("Listening on port $redirectPort path $redirectPath ...")
    println()

    val callback = waitForCallback(redirectPort, redirectPath)

    if (callback.error != null) {
        System.err.println("ERROR: Discord returned error: ${callback.error}")
        exitProcess(1)
    }
    if (callback.state != state) {
        System.err.println("ERROR: state mismatch (got '${callback.state}', expected '$state')")
        exitProcess(1)
    }
    if (callback.code == null) {
        System.err.println("ERROR: callback had no 'code' parameter")
        exitProcess(1)
    }

    println("Got authorization code, exchanging for access token...")
    val accessToken = exchangeCode(clientId, clientSecret, callback.code, redirectUri)
    println("Got access token.")
    println()

    val me = discordGet("/users/@me", accessToken).jsonObject
    val userId = me["id"]?.jsonPrimitive?.contentOrNull
    val username = me["username"]?.jsonPrimitive?.contentOrNull
    val globalName = me["global_name"]?.jsonPrimitive?.contentOrNull
    val email = me["email"]?.jsonPrimitive?.contentOrNull

    println("--- /users/@me ---")
    println("  id          : $userId")
    println("  username    : $username")
    println("  global_name : $globalName")
    println("  email       : ${email ?: "(not granted — needs 'email' scope)"}")
    println()

    val guilds = discordGet("/users/@me/guilds", accessToken).jsonArray
    val matching = guilds.firstOrNull {
        it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == rippaverseGuildId
    }

    println("--- /users/@me/guilds ---")
    println("  total guilds      : ${guilds.size}")
    println("  rippaverse guild  : $rippaverseGuildId")
    if (matching != null) {
        val name = matching.jsonObject["name"]?.jsonPrimitive?.contentOrNull
        println("  RESULT            : member of '$name' — gate would PASS")
    } else {
        println("  RESULT            : not in that guild — gate would BLOCK")
        println()
        println("  (For reference, the guild IDs you ARE in:)")
        guilds.forEach {
            val obj = it.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull
            val name = obj["name"]?.jsonPrimitive?.contentOrNull
            println("    $id  $name")
        }
    }
}

private fun requireEnv(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: run {
            System.err.println("ERROR: missing required env var $name")
            System.err.println()
            System.err.println("Required: DISCORD_CLIENT_ID, DISCORD_CLIENT_SECRET, RIPPAVERSE_GUILD_ID")
            System.err.println("Optional: DISCORD_REDIRECT_URI (default http://localhost:8080/discord/callback)")
            exitProcess(1)
        }

private fun randomState(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

private fun buildAuthUrl(clientId: String, redirectUri: String, scope: String, state: String): String {
    val params = linkedMapOf(
        "client_id" to clientId,
        "response_type" to "code",
        "redirect_uri" to redirectUri,
        "scope" to scope,
        "state" to state,
        "prompt" to "consent",
    )
    val query = params.entries.joinToString("&") { (k, v) -> "$k=${urlEncode(v)}" }
    return "$DISCORD_AUTHORIZE?$query"
}

private data class Callback(val code: String?, val state: String?, val error: String?)

private fun waitForCallback(port: Int, path: String): Callback {
    val future = CompletableFuture<Callback>()
    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.createContext(path) { exchange ->
        val params = parseQuery(exchange.requestURI.rawQuery ?: "")
        val html = """
            <html><body style="font-family: sans-serif">
            <h2>Discord callback received.</h2>
            <p>You can close this tab. Check the smoke test console.</p>
            </body></html>
        """.trimIndent()
        val bytes = html.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        future.complete(Callback(params["code"], params["state"], params["error"]))
    }
    server.executor = null
    server.start()
    val result = future.get()
    server.stop(0)
    return result
}

private fun parseQuery(raw: String): Map<String, String> =
    if (raw.isBlank()) emptyMap()
    else raw.split("&").mapNotNull { pair ->
        val idx = pair.indexOf('=')
        if (idx < 0) null
        else URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8) to
                URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8)
    }.toMap()

private val httpClient: HttpClient = HttpClient.newHttpClient()

private fun exchangeCode(
    clientId: String,
    clientSecret: String,
    code: String,
    redirectUri: String,
): String {
    val body = linkedMapOf(
        "client_id" to clientId,
        "client_secret" to clientSecret,
        "grant_type" to "authorization_code",
        "code" to code,
        "redirect_uri" to redirectUri,
    ).entries.joinToString("&") { (k, v) -> "$k=${urlEncode(v)}" }

    val request = HttpRequest.newBuilder(URI.create(DISCORD_TOKEN))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    if (response.statusCode() !in 200..299) {
        System.err.println("ERROR: token exchange failed: HTTP ${response.statusCode()}")
        System.err.println(response.body())
        exitProcess(1)
    }
    val obj = Json.parseToJsonElement(response.body()).jsonObject
    return obj["access_token"]?.jsonPrimitive?.contentOrNull
        ?: run {
            System.err.println("ERROR: no access_token in response: ${response.body()}")
            exitProcess(1)
        }
}

private fun discordGet(path: String, accessToken: String): JsonElement {
    val request = HttpRequest.newBuilder(URI.create("$DISCORD_API$path"))
        .header("Authorization", "Bearer $accessToken")
        .GET()
        .build()
    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    if (response.statusCode() !in 200..299) {
        System.err.println("ERROR: GET $path returned HTTP ${response.statusCode()}")
        System.err.println(response.body())
        exitProcess(1)
    }
    return Json.parseToJsonElement(response.body())
}

private fun urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)
