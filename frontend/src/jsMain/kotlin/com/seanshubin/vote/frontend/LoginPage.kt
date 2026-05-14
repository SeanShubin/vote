package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.contract.LoginConfig
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.compose.web.attributes.*
import org.jetbrains.compose.web.dom.*
import org.w3c.dom.HTMLSelectElement

/**
 * Sign-in screen. Discord OAuth is the primary path — clicking the button
 * hits the backend to set the OAuth state cookie, then redirects the browser
 * to Discord; the /auth/discord/callback handler completes the round-trip.
 *
 * On local dev runs the backend also reports devLoginEnabled (see
 * [LoginConfig]), which unlocks a Discord-bypass panel: log in as an existing
 * user or create a new one. Production boots through the Lambda configuration,
 * which hard-codes devLoginEnabled false — so the panel never renders there
 * and the dev-login endpoints reject every request.
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

    // Null until the backend answers. devLoginEnabled gates the dev panel;
    // userNames backs its existing-user picker. A failed fetch just leaves
    // the panel hidden — Discord login still works.
    var loginConfig by remember { mutableStateOf<LoginConfig?>(null) }
    var userNames by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        try {
            val config = apiClient.loginConfig()
            loginConfig = config
            if (config.devLoginEnabled) {
                userNames = apiClient.devListUserNames()
            }
        } catch (e: Exception) {
            // logErrorToServer rethrows CancellationException so a dispose
            // mid-fetch cancels cleanly; any real failure just means no dev
            // panel this load.
            apiClient.logErrorToServer(e)
        }
    }

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

        if (loginConfig?.devLoginEnabled == true) {
            DevLoginPanel(
                apiClient = apiClient,
                userNames = userNames,
                onError = { errorMessage = it },
                coroutineScope = coroutineScope,
            )
        }
    }
}

/**
 * Discord-bypass login, dev environments only. Two distinct affordances:
 *
 * - **Existing-user picker** — a dropdown of every registered user, so there
 *   is no name to mistype and no need to remember who exists. Find-only: the
 *   backend rejects an unknown name rather than creating one.
 * - **Create field** — free text for a brand-new user. Create-only: the
 *   backend rejects a name that already exists, so a typo that collides with
 *   a real user is caught instead of silently logging in as them.
 *
 * On success the backend sets the refresh cookie (exactly like the Discord
 * callback), then we navigate to the app root so the normal cookie bootstrap
 * in [VoteApp] picks the session up — same path a returning user takes. We go
 * to "/" rather than reloading because the SPA's "/login" URL is client-side
 * only (history.replaceState); a real GET of "/login" 404s on the dev server,
 * which has no SPA fallback. The Discord callback lands on the root for the
 * same reason.
 */
@Composable
private fun DevLoginPanel(
    apiClient: ApiClient,
    userNames: List<String>,
    onError: (String) -> Unit,
    coroutineScope: CoroutineScope,
) {
    // Re-seeds to the first name whenever the list (re)loads.
    var selectedUser by remember(userNames) { mutableStateOf(userNames.firstOrNull()) }
    var newUserName by remember { mutableStateOf("") }

    val loginAsExistingAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Dev login failed",
        onError = onError,
        onSuccess = { window.location.href = "/" },
        coroutineScope = coroutineScope,
        action = {
            val name = selectedUser
            if (name != null) apiClient.devLoginAsExisting(name)
        },
    )

    val createAndLoginAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Could not create user",
        onError = onError,
        onSuccess = { window.location.href = "/" },
        coroutineScope = coroutineScope,
        action = {
            apiClient.devCreateAndLogin(newUserName.trim())
        },
    )

    Div({ classes("dev-login-panel") }) {
        H2 { Text("Local dev login") }
        P { Text("Bypass Discord — available in this local environment only.") }

        if (userNames.isNotEmpty()) {
            Div({ classes("dev-login-row") }) {
                Select(attrs = {
                    onChange { event ->
                        selectedUser = (event.target as HTMLSelectElement).value
                    }
                }) {
                    userNames.forEach { name ->
                        Option(
                            value = name,
                            attrs = { if (name == selectedUser) selected() },
                        ) {
                            Text(name)
                        }
                    }
                }
                Button({
                    attr("type", "button")
                    if (loginAsExistingAction.isLoading || selectedUser == null) attr("disabled", "")
                    onClick { loginAsExistingAction.invoke() }
                }) {
                    Text(if (loginAsExistingAction.isLoading) "Logging in…" else "Log in")
                }
            }
        }

        Form(attrs = {
            classes("dev-login-row")
            onSubmit { event ->
                event.preventDefault()
                if (newUserName.isNotBlank()) createAndLoginAction.invoke()
            }
        }) {
            Input(InputType.Text) {
                placeholder("New user name")
                value(newUserName)
                onInput { newUserName = it.value }
            }
            Button({
                attr("type", "submit")
                if (createAndLoginAction.isLoading || newUserName.isBlank()) attr("disabled", "")
            }) {
                Text(if (createAndLoginAction.isLoading) "Creating…" else "Create & log in")
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
