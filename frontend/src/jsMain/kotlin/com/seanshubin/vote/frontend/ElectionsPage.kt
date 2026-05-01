package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import org.jetbrains.compose.web.dom.*

@Composable
fun ElectionsPage(
    apiClient: ApiClient,
    onSelectElection: (String) -> Unit,
    onBack: () -> Unit,
) {
    val electionsFetch = rememberFetchState(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to load elections",
    ) {
        apiClient.listElections()
    }

    Div({ classes("container") }) {
        H1 { Text("Elections") }

        when (val state = electionsFetch.state) {
            FetchState.Loading -> P { Text("Loading elections…") }
            is FetchState.Error -> Div({ classes("error") }) { Text(state.message) }
            is FetchState.Success -> {
                if (state.value.isEmpty()) {
                    P { Text("No elections found.") }
                } else {
                    Div({ classes("elections-list") }) {
                        state.value.forEach { election ->
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
            }
        }

        Button({
            onClick { onBack() }
        }) {
            Text("Back to Home")
        }
    }
}
