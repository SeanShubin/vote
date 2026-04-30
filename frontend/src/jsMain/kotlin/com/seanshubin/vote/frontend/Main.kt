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
    }
}

@Composable
fun VoteApp(apiClient: ApiClient) {
    val router = rememberRouter()
    var isBootstrapping by remember { mutableStateOf(true) }
    var authToken by remember { mutableStateOf<String?>(null) }
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
                authToken = auth.accessToken
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
            onLoginSuccess = { token, user, userRole ->
                authToken = token
                userName = user
                role = userRole
                router.replace(Page.Home)
            },
            onNavigateToRegister = { router.navigate(Page.Register) }
        )
        is Page.Register -> RegisterPage(
            apiClient = apiClient,
            onLoginSuccess = { token, user, userRole ->
                authToken = token
                userName = user
                role = userRole
                router.replace(Page.Home)
            },
            onNavigateToLogin = { router.navigate(Page.Login) }
        )
        is Page.Home -> HomePage(
            userName = userName ?: "Unknown",
            role = role,
            authToken = authToken ?: "",
            onNavigateToCreateElection = { router.navigate(Page.CreateElection) },
            onNavigateToElections = { router.navigate(Page.Elections) },
            onNavigateToRawTables = { router.navigate(Page.RawTables) },
            onNavigateToDebugTables = { router.navigate(Page.DebugTables) },
            onLogout = {
                scope.launch {
                    try {
                        apiClient.logout()
                    } catch (e: Exception) {
                        apiClient.logErrorToServer(e)
                    }
                    authToken = null
                    userName = null
                    role = null
                    router.replace(Page.Login)
                }
            }
        )
        is Page.CreateElection -> CreateElectionPage(
            apiClient = apiClient,
            authToken = authToken ?: "",
            onElectionCreated = { electionName ->
                router.navigate(Page.ElectionDetail(electionName))
            },
            onBack = { router.navigate(Page.Home) }
        )
        is Page.Elections -> ElectionsPage(
            apiClient = apiClient,
            authToken = authToken ?: "",
            onSelectElection = { electionName ->
                router.navigate(Page.ElectionDetail(electionName))
            },
            onBack = { router.navigate(Page.Home) }
        )
        is Page.ElectionDetail -> ElectionDetailPage(
            apiClient = apiClient,
            authToken = authToken ?: "",
            electionName = page.electionName,
            onBack = { router.navigate(Page.Elections) }
        )
        is Page.RawTables -> {
            val token = authToken ?: ""
            TablesPage(
                title = "Raw Tables",
                emptyMessage = "No raw tables (this backend has no physical tables to expose).",
                loadNames = { apiClient.listTables(token) },
                loadData = { name -> apiClient.tableData(token, name) },
                onError = { apiClient.logErrorToServer(it) },
                onBack = { router.navigate(Page.Home) },
            )
        }
        is Page.DebugTables -> {
            val token = authToken ?: ""
            TablesPage(
                title = "Debug Tables",
                emptyMessage = "No debug tables available.",
                loadNames = { apiClient.listDebugTables(token) },
                loadData = { name -> apiClient.debugTableData(token, name) },
                onError = { apiClient.logErrorToServer(it) },
                onBack = { router.navigate(Page.Home) },
            )
        }
    }
}

@Composable
private fun LoadingPage() {
    Div({ classes("container") }) {
        P { Text("Loading...") }
    }
}
