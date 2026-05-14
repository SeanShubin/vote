package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.compose.web.dom.*

/**
 * Sign-in screen. Discord OAuth is the only path — there is no password
 * form, no registration, no forgot-password link. Clicking the button hits
 * the backend to set the OAuth state cookie, then redirects the browser to
 * Discord. The /auth/discord/callback handler completes the round-trip.
 */
@Composable
fun LoginPage(
    apiClient: ApiClient,
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
) {
    // The Discord OAuth callback redirects here with ?error=… on failure
    // (state mismatch, user not in the Rippaverse guild, Discord rejected
    // the code). Read it once on first composition and surface a friendly
    // version of each known code; unknown codes fall through to a generic
    // message so an operator-introduced new code still shows *something*.
    var errorMessage by remember { mutableStateOf<String?>(discordErrorMessage()) }

    val discordLoginAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Could not start Discord login",
        onError = { errorMessage = it },
        coroutineScope = coroutineScope,
        action = {
            errorMessage = null
            val url = apiClient.discordLoginStartUrl()
            // Hand the browser off to Discord for the OAuth handshake. The
            // backend has already set the state cookie that the callback will
            // verify, so we just need to navigate.
            window.location.href = url
        },
    )

    Div({ classes("container") }) {
        H1 { Text("Vote — Login") }

        if (errorMessage != null) {
            Div({ classes("error") }) {
                Text(errorMessage!!)
            }
        }

        Div({ classes("form") }) {
            Button({
                attr("type", "button")
                if (discordLoginAction.isLoading) attr("disabled", "")
                onClick { discordLoginAction.invoke() }
            }) {
                Text(if (discordLoginAction.isLoading) "Redirecting…" else "Sign in with Discord")
            }
        }
    }
}

/**
 * Read `?error=…` from the current URL and translate the known codes the
 * Discord OAuth callback sets. Returns null when there's no error param.
 *
 * The codes ("state_mismatch", "not_in_guild", "discord_failed",
 * "missing_code") are deliberately opaque on the wire — the backend logs
 * the detail; the user gets a sentence they can act on.
 */
private fun discordErrorMessage(): String? {
    val search = window.location.search
    if (!search.contains("error=")) return null
    val code = search.substringAfter("error=").substringBefore("&")
    return when (code) {
        "not_in_guild" ->
            "Your Discord account isn't a member of The Rippaverse server. " +
                "Join Rippaverse and try again."
        "state_mismatch", "missing_code" ->
            "Discord login was interrupted. Please try again."
        "discord_failed" ->
            "Discord rejected the login. Please try again."
        else -> "Login failed (code: $code)."
    }
}
