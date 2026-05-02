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
    LaunchedEffect(Unit) {
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
            onBack = { router.navigate(Page.ElectionDetail(page.electionName)) },
        )
        is Page.ElectionStrongestPaths -> ElectionStrongestPathsPage(
            apiClient = apiClient,
            electionName = page.electionName,
            onBack = { router.navigate(Page.ElectionDetail(page.electionName)) },
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
            onSelfRoleChanged = { auth ->
                userName = auth.userName
                role = auth.role
            },
            onBack = { router.navigate(Page.Home) },
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
