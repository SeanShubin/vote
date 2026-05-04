package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.domain.Role
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.dom.*
import org.jetbrains.compose.web.renderComposable

fun main() {
    val integrations = ProductionFrontendIntegrations(BuildConfig.API_BASE_URL)

    renderComposable(rootElementId = "root") {
        VoteApp(integrations.apiClient)
        BuildStamp()
    }
}

@Composable
private fun BuildStamp() {
    val short = BuildConfig.GIT_HASH.take(7)
    val href = if (BuildConfig.GIT_HASH == "dev") null
        else "https://github.com/SeanShubin/vote/commit/${BuildConfig.GIT_HASH}"
    Div({ classes("build-stamp") }) {
        if (href != null) {
            A(href = href, attrs = { attr("target", "_blank") }) {
                Text(short)
            }
        } else {
            Text(short)
        }
    }
}

@Composable
fun VoteApp(apiClient: ApiClient) {
    val router = rememberRouter()
    var isBootstrapping by remember { mutableStateOf(true) }
    // userName + role drive UI display only. The access token itself is owned
    // by the ApiClient — VoteApp doesn't see or pass it.
    var userName by remember { mutableStateOf<String?>(null) }
    var role by remember { mutableStateOf<Role?>(null) }
    val scope = rememberCoroutineScope()

    // Bootstrap session from the refresh cookie. On success the user stays on
    // whatever URL they landed on (so deep links like /elections/Foo work
    // straight from a bookmark). On failure, protected pages redirect to /login
    // — using replaceState so Back doesn't take them back to a page they
    // weren't authorized to see in the first place.
    //
    // The same `userName=null; role=null; replace(Login)` transition also
    // serves as the SPA-wide handler for "session evaporated mid-use" (user
    // was deleted server-side, refresh cookie expired, etc.). We register it
    // once via apiClient.onSessionLost so individual pages don't have to
    // catch+redirect each call site by hand.
    LaunchedEffect(Unit) {
        apiClient.onSessionLost = {
            userName = null
            role = null
            router.replace(Page.Login)
        }
        try {
            val auth = apiClient.refresh()
            if (auth != null) {
                userName = auth.userName
                role = auth.role
                // Bookmarked /login or /register but already authenticated?
                // Nudge them to Home — they don't need to log in again.
                if (isPublicPage(router.currentPage)) {
                    router.replace(Page.Home)
                }
            } else if (!isPublicPage(router.currentPage)) {
                router.replace(Page.Login)
            }
        } catch (e: Exception) {
            apiClient.logErrorToServer(e)
            if (!isPublicPage(router.currentPage)) {
                router.replace(Page.Login)
            }
        }
        isBootstrapping = false
    }

    if (isBootstrapping) {
        LoadingPage()
        return
    }

    // NO_ACCESS is the lock-out role: the user is registered but an admin has
    // not granted them any permissions. Every authenticated endpoint requires
    // VIEW_APPLICATION (≥ OBSERVER), so navigating into the app would just
    // produce a wall of 401s. Show a dedicated stub instead, with the only
    // actions they actually can take: log out, or self-delete their account.
    // Public pages (login/register/password-reset) still render normally so
    // they aren't trapped in the stub if they ended up there with stale state.
    if (role == Role.NO_ACCESS && !isPublicPage(router.currentPage)) {
        LockedPage(
            apiClient = apiClient,
            userName = userName ?: "Unknown",
            onLogout = {
                scope.launch {
                    try {
                        apiClient.logout()
                    } catch (e: Exception) {
                        apiClient.logErrorToServer(e)
                    }
                    userName = null
                    role = null
                    router.replace(Page.Login)
                }
            },
            onAccountDeleted = {
                userName = null
                role = null
                router.replace(Page.Login)
            },
        )
        return
    }

    when (val page = router.currentPage) {
        is Page.Login -> LoginPage(
            apiClient = apiClient,
            onLoginSuccess = { user, userRole ->
                userName = user
                role = userRole
                router.replace(Page.Home)
            },
            onNavigateToRegister = { router.navigate(Page.Register) },
            onNavigateToForgotPassword = { router.navigate(Page.PasswordResetRequest) }
        )
        is Page.Register -> RegisterPage(
            apiClient = apiClient,
            onLoginSuccess = { user, userRole ->
                userName = user
                role = userRole
                router.replace(Page.Home)
            },
            onNavigateToLogin = { router.navigate(Page.Login) }
        )
        is Page.Home -> HomePage(
            apiClient = apiClient,
            userName = userName ?: "Unknown",
            role = role,
            onNavigateToCreateElection = { router.navigate(Page.CreateElection) },
            onNavigateToElections = { router.navigate(Page.Elections) },
            onNavigateToRawTables = { router.navigate(Page.RawTables) },
            onNavigateToDebugTables = { router.navigate(Page.DebugTables) },
            onNavigateToUserManagement = { router.navigate(Page.UserManagement) },
            onNavigateToChangeMyPassword = { router.navigate(Page.ChangeMyPassword) },
            onLogout = {
                scope.launch {
                    try {
                        apiClient.logout()
                    } catch (e: Exception) {
                        apiClient.logErrorToServer(e)
                    }
                    userName = null
                    role = null
                    router.replace(Page.Login)
                }
            },
            onAccountDeleted = {
                userName = null
                role = null
                router.replace(Page.Login)
            },
        )
        is Page.CreateElection -> CreateElectionPage(
            apiClient = apiClient,
            onElectionCreated = { electionName ->
                router.navigate(Page.ElectionDetail(electionName))
            },
            onBack = { router.navigate(Page.Home) }
        )
        is Page.Elections -> ElectionsPage(
            apiClient = apiClient,
            onSelectElection = { electionName ->
                router.navigate(Page.ElectionDetail(electionName))
            },
            onBack = { router.navigate(Page.Home) }
        )
        is Page.ElectionDetail -> ElectionDetailPage(
            apiClient = apiClient,
            electionName = page.electionName,
            currentUserName = userName,
            currentRole = role,
            onBack = { router.navigate(Page.Elections) },
            onElectionDeleted = { router.replace(Page.Elections) },
            onNavigateToPreferences = {
                router.navigate(Page.ElectionPreferences(page.electionName))
            },
            onNavigateToStrongestPaths = {
                router.navigate(Page.ElectionStrongestPaths(page.electionName))
            },
        )
        is Page.ElectionPreferences -> ElectionPreferencesPage(
            apiClient = apiClient,
            electionName = page.electionName,
            // Land on the Results tab — that's where the buttons that open
            // this page live, so it's almost certainly where the user came
            // from. The hash is read by rememberHashTab on the destination.
            onBack = { router.navigate(Page.ElectionDetail(page.electionName), hash = "tally") },
        )
        is Page.ElectionStrongestPaths -> ElectionStrongestPathsPage(
            apiClient = apiClient,
            electionName = page.electionName,
            onBack = { router.navigate(Page.ElectionDetail(page.electionName), hash = "tally") },
        )
        is Page.RawTables -> TablesPage(
            apiClient = apiClient,
            title = "Raw Tables",
            emptyMessage = "No raw tables (this backend has no physical tables to expose).",
            loadNames = { apiClient.listTables() },
            loadData = { name -> apiClient.tableData(name) },
            onBack = { router.navigate(Page.Home) },
        )
        is Page.DebugTables -> TablesPage(
            apiClient = apiClient,
            title = "Debug Tables",
            emptyMessage = "No debug tables available.",
            loadNames = { apiClient.listDebugTables() },
            loadData = { name -> apiClient.debugTableData(name) },
            onBack = { router.navigate(Page.Home) },
        )
        is Page.UserManagement -> UserManagementPage(
            apiClient = apiClient,
            currentUserName = userName ?: "",
            currentRole = role,
            onSelfRoleChanged = { auth ->
                userName = auth.userName
                role = auth.role
            },
            onBack = { router.navigate(Page.Home) },
        )
        is Page.ChangeMyPassword -> ChangeMyPasswordPage(
            apiClient = apiClient,
            onSuccess = { router.navigate(Page.Home) },
            onCancel = { router.navigate(Page.Home) },
        )
        is Page.PasswordResetRequest -> PasswordResetRequestPage(
            apiClient = apiClient,
            onNavigateToLogin = { router.navigate(Page.Login) },
        )
        is Page.PasswordReset -> PasswordResetPage(
            apiClient = apiClient,
            resetToken = page.resetToken,
            // Successful reset → go to Login so user can sign in fresh.
            onResetComplete = { router.replace(Page.Login) },
            onNavigateToLogin = { router.navigate(Page.Login) },
        )
    }
}

@Composable
private fun LoadingPage() {
    Div({ classes("container") }) {
        P { Text("Loading...") }
    }
}

@Composable
private fun LockedPage(
    apiClient: ApiClient,
    userName: String,
    onLogout: () -> Unit,
    onAccountDeleted: () -> Unit,
) {
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val deleteAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to delete account",
        onError = { errorMessage = it },
        action = {
            apiClient.removeUser(userName)
            onAccountDeleted()
        },
    )

    Div({ classes("container") }) {
        H1 { Text("Awaiting approval") }
        P { Text("Hello, $userName.") }
        P {
            Text(
                "Your account is registered, but an administrator has not yet " +
                    "granted you access to the application. Once they assign you " +
                    "a role, you will be able to log in and use the app. Try " +
                    "again in a little while."
            )
        }

        if (errorMessage != null) {
            Div({ classes("error") }) { Text(errorMessage!!) }
        }

        Div({ classes("button-row") }) {
            Button({ onClick { onLogout() } }) { Text("Log out") }
            Button({
                if (deleteAction.isLoading) attr("disabled", "")
                onClick {
                    val confirmed = kotlinx.browser.window.confirm(
                        "Delete your account? This cannot be undone."
                    )
                    if (confirmed) deleteAction.invoke()
                }
            }) {
                Text(if (deleteAction.isLoading) "Deleting…" else "Delete Account")
            }
        }
    }
}
