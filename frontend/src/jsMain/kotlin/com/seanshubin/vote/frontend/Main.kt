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
    var currentPage by remember { mutableStateOf<Page>(Page.Loading) }
    var authToken by remember { mutableStateOf<String?>(null) }
    var userName by remember { mutableStateOf<String?>(null) }
    var role by remember { mutableStateOf<Role?>(null) }
    val scope = rememberCoroutineScope()

    // Bootstrap session from the refresh cookie. If the browser has a valid
    // Refresh cookie we land on Home; otherwise the server returns 401 (null)
    // and we drop to the Login screen.
    LaunchedEffect(Unit) {
        try {
            val auth = apiClient.refresh()
            if (auth != null) {
                authToken = auth.accessToken
                userName = auth.userName
                role = auth.role
                currentPage = Page.Home
            } else {
                currentPage = Page.Login
            }
        } catch (e: Exception) {
            apiClient.logErrorToServer(e)
            currentPage = Page.Login
        }
    }

    when (currentPage) {
        is Page.Loading -> LoadingPage()
        is Page.Login -> LoginPage(
            apiClient = apiClient,
            onLoginSuccess = { token, user, userRole ->
                authToken = token
                userName = user
                role = userRole
                currentPage = Page.Home
            },
            onNavigateToRegister = { currentPage = Page.Register }
        )
        is Page.Register -> RegisterPage(
            apiClient = apiClient,
            onLoginSuccess = { token, user, userRole ->
                authToken = token
                userName = user
                role = userRole
                currentPage = Page.Home
            },
            onNavigateToLogin = { currentPage = Page.Login }
        )
        is Page.Home -> HomePage(
            userName = userName ?: "Unknown",
            role = role,
            authToken = authToken ?: "",
            onNavigateToCreateElection = { currentPage = Page.CreateElection },
            onNavigateToElections = { currentPage = Page.Elections },
            onNavigateToRawTables = { currentPage = Page.RawTables },
            onNavigateToDebugTables = { currentPage = Page.DebugTables },
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
                    currentPage = Page.Login
                }
            }
        )
        is Page.CreateElection -> CreateElectionPage(
            apiClient = apiClient,
            authToken = authToken ?: "",
            onElectionCreated = { electionName ->
                currentPage = Page.ElectionDetail(electionName)
            },
            onBack = { currentPage = Page.Home }
        )
        is Page.Elections -> ElectionsPage(
            apiClient = apiClient,
            authToken = authToken ?: "",
            onSelectElection = { electionName ->
                currentPage = Page.ElectionDetail(electionName)
            },
            onBack = { currentPage = Page.Home }
        )
        is Page.ElectionDetail -> {
            val electionName = (currentPage as Page.ElectionDetail).electionName
            ElectionDetailPage(
                apiClient = apiClient,
                authToken = authToken ?: "",
                electionName = electionName,
                onBack = { currentPage = Page.Elections }
            )
        }
        is Page.RawTables -> {
            val token = authToken ?: ""
            TablesPage(
                title = "Raw Tables",
                emptyMessage = "No raw tables (this backend has no physical tables to expose).",
                loadNames = { apiClient.listTables(token) },
                loadData = { name -> apiClient.tableData(token, name) },
                onError = { apiClient.logErrorToServer(it) },
                onBack = { currentPage = Page.Home },
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
                onBack = { currentPage = Page.Home },
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

sealed class Page {
    object Loading : Page()
    object Login : Page()
    object Register : Page()
    object Home : Page()
    object CreateElection : Page()
    object Elections : Page()
    object RawTables : Page()
    object DebugTables : Page()
    data class ElectionDetail(val electionName: String) : Page()
}
