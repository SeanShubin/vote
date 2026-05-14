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
    val electionsFetch = rememberCachedFetchState(
        apiClient = apiClient,
        cacheKey = "elections",
        fallbackErrorMessage = "Failed to load elections",
    ) {
        apiClient.listElections()
    }

    // Pick up elections created (or deleted) by other users without a manual
    // refresh. The cached fetch keeps the current list painted while the
    // refetch runs, so the list never flashes to Loading.
    rememberVersionPolling(apiClient) {
        electionsFetch.reload()
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
                                Span({ classes("election-owner") }) {
                                    Text("Owner: ${election.ownerName}")
                                }
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
