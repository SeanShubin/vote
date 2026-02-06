package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.domain.ElectionSummary
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.dom.*

@Composable
fun ElectionsPage(
    authToken: String,
    onSelectElection: (String) -> Unit,
    onBack: () -> Unit
) {
    var elections by remember { mutableStateOf<List<ElectionSummary>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            elections = ApiClient.listElections(authToken)
        } catch (e: Exception) {
            ApiClient.logErrorToServer(e)
            errorMessage = e.message ?: "Failed to load elections"
        } finally {
            isLoading = false
        }
    }

    Div({ classes("container") }) {
        H1 { Text("Elections") }

        if (errorMessage != null) {
            Div({ classes("error") }) {
                Text(errorMessage!!)
            }
        }

        if (isLoading) {
            P { Text("Loading elections...") }
        } else if (elections.isEmpty()) {
            P { Text("No elections found.") }
        } else {
            Div({ classes("elections-list") }) {
                elections.forEach { election ->
                    Div({ classes("election-item") }) {
                        Button({
                            onClick { onSelectElection(election.electionName) }
                        }) {
                            Text(election.electionName)
                        }
                        Span { Text(" - Owner: ${election.ownerName}") }
                    }
                }
            }
        }

        Button({
            onClick { onBack() }
        }) {
            Text("Back to Home")
        }
    }
}
