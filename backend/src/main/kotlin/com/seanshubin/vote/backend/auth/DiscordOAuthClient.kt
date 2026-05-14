package com.seanshubin.vote.backend.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * Server-side Discord OAuth helper. Only the bits this app needs:
 * build-authorize-url, exchange-code-for-access-token, fetch-user-identity,
 * check-rippaverse-membership. No Discord SDK dependency — Discord exposes
 * stable JSON HTTP endpoints that the JDK's [HttpClient] handles fine.
 *
 * Stateless. State / nonce / CSRF tracking lives in the caller (the service
 * layer), not here.
 */
class DiscordOAuthClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val authorizeBase: String = DEFAULT_AUTHORIZE,
    private val tokenEndpoint: String = DEFAULT_TOKEN,
    private val apiBase: String = DEFAULT_API,
) {
    /**
     * Identity payload returned from a successful OAuth handshake plus the
     * Rippaverse-membership check. The user is in the guild; the caller has
     * already decided to admit them.
     */
    data class DiscordIdentity(
        val discordId: String,
        val username: String,
        val globalName: String,
    ) {
        /** Display name — global_name when present, else username. */
        val displayName: String get() = globalName.ifBlank { username }
    }

    /** Outcome of [authenticate] — succeeded with an identity, or rejected. */
    sealed interface AuthResult {
        data class Ok(val identity: DiscordIdentity) : AuthResult
        /** Discord-side or HTTP error (bad code, expired, etc). [reason] is for logging. */
        data class Error(val reason: String) : AuthResult
        /** Auth succeeded but the user is not in the Rippaverse guild. */
        data object NotInGuild : AuthResult
    }

    /**
     * URL the user should be redirected to in order to authorize the app.
     * [state] should be a freshly generated random value the caller will
     * verify on the callback (CSRF protection).
     */
    fun buildAuthorizeUrl(config: DiscordConfig, state: String): String {
        val params = linkedMapOf(
            "client_id" to config.clientId,
            "response_type" to "code",
            "redirect_uri" to config.redirectUri,
            "scope" to "identify guilds",
            "state" to state,
        )
        return "$authorizeBase?" + params.entries.joinToString("&") { (k, v) -> "$k=${urlEncode(v)}" }
    }

    /**
     * End-to-end: exchange [code] for a Discord access token, fetch the
     * user's identity + guild list, and gate on Rippaverse membership.
     * Returns either an admitted identity or one of the rejection reasons.
     */
    fun authenticate(config: DiscordConfig, code: String): AuthResult {
        val accessToken = exchangeCode(config, code)
            ?: return AuthResult.Error("token exchange failed")
        val me = discordGet("/users/@me", accessToken)
            ?: return AuthResult.Error("/users/@me failed")
        val guilds = discordGet("/users/@me/guilds", accessToken)
            ?: return AuthResult.Error("/users/@me/guilds failed")

        val inRippaverse = Json.parseToJsonElement(guilds).jsonArray.any {
            it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == config.rippaverseGuildId
        }
        if (!inRippaverse) return AuthResult.NotInGuild

        val obj = Json.parseToJsonElement(me).jsonObject
        val id = obj["id"]?.jsonPrimitive?.contentOrNull
            ?: return AuthResult.Error("/users/@me missing id")
        val username = obj["username"]?.jsonPrimitive?.contentOrNull ?: ""
        val globalName = obj["global_name"]?.jsonPrimitive?.contentOrNull ?: ""
        return AuthResult.Ok(DiscordIdentity(id, username, globalName))
    }

    private fun exchangeCode(config: DiscordConfig, code: String): String? {
        val body = linkedMapOf(
            "client_id" to config.clientId,
            "client_secret" to config.clientSecret,
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to config.redirectUri,
        ).entries.joinToString("&") { (k, v) -> "$k=${urlEncode(v)}" }

        val request = HttpRequest.newBuilder(URI.create(tokenEndpoint))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in 200..299) return null
        return Json.parseToJsonElement(response.body()).jsonObject["access_token"]
            ?.jsonPrimitive?.contentOrNull
    }

    private fun discordGet(path: String, accessToken: String): String? {
        val request = HttpRequest.newBuilder(URI.create("$apiBase$path"))
            .header("Authorization", "Bearer $accessToken")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        return if (response.statusCode() in 200..299) response.body() else null
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)

    companion object {
        const val DEFAULT_AUTHORIZE = "https://discord.com/oauth2/authorize"
        const val DEFAULT_TOKEN = "https://discord.com/api/oauth2/token"
        const val DEFAULT_API = "https://discord.com/api"
    }
}
