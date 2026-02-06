package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import org.jetbrains.compose.web.renderComposable

fun main() {
    renderComposable(rootElementId = "root") {
        VoteApp()
    }
}

@Composable
fun VoteApp() {
    var currentPage by remember { mutableStateOf<Page>(Page.Login) }
    var authToken by remember { mutableStateOf<String?>(null) }
    var userName by remember { mutableStateOf<String?>(null) }

    when (currentPage) {
        is Page.Login -> LoginPage(
            onLoginSuccess = { token, user ->
                authToken = token
                userName = user
                currentPage = Page.Home
            },
            onNavigateToRegister = { currentPage = Page.Register }
        )
        is Page.Register -> RegisterPage(
            onLoginSuccess = { token, user ->
                authToken = token
                userName = user
                currentPage = Page.Home
            },
            onNavigateToLogin = { currentPage = Page.Login }
        )
        is Page.Home -> HomePage(
            userName = userName ?: "Unknown",
            authToken = authToken ?: "",
            onNavigateToCreateElection = { currentPage = Page.CreateElection },
            onNavigateToElections = { currentPage = Page.Elections },
            onLogout = {
                authToken = null
                userName = null
                currentPage = Page.Login
            }
        )
        is Page.CreateElection -> CreateElectionPage(
            authToken = authToken ?: "",
            onElectionCreated = { electionName ->
                currentPage = Page.ElectionDetail(electionName)
            },
            onBack = { currentPage = Page.Home }
        )
        is Page.Elections -> ElectionsPage(
            authToken = authToken ?: "",
            onSelectElection = { electionName ->
                currentPage = Page.ElectionDetail(electionName)
            },
            onBack = { currentPage = Page.Home }
        )
        is Page.ElectionDetail -> {
            val electionName = (currentPage as Page.ElectionDetail).electionName
            ElectionDetailPage(
                authToken = authToken ?: "",
                electionName = electionName,
                onBack = { currentPage = Page.Elections }
            )
        }
    }
}

sealed class Page {
    object Login : Page()
    object Register : Page()
    object Home : Page()
    object CreateElection : Page()
    object Elections : Page()
    data class ElectionDetail(val electionName: String) : Page()
}
