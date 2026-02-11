package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.*
import org.jetbrains.compose.web.dom.*

@Composable
fun CreateElectionPage(
    apiClient: ApiClient,
    authToken: String,
    onElectionCreated: (String) -> Unit,
    onBack: () -> Unit,
    coroutineScope: CoroutineScope = rememberCoroutineScope()
) {
    var electionName by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    Div({ classes("container") }) {
        H1 { Text("Create Election") }

        if (errorMessage != null) {
            Div({ classes("error") }) {
                Text(errorMessage!!)
            }
        }

        Div({ classes("form") }) {
            Input(InputType.Text) {
                placeholder("Election Name")
                value(electionName)
                onInput { electionName = it.value }
            }

            Button({
                onClick {
                    if (!isLoading && electionName.isNotBlank()) {
                        isLoading = true
                        errorMessage = null
                        coroutineScope.launch {
                            try {
                                apiClient.createElection(authToken, electionName)
                                onElectionCreated(electionName)
                            } catch (e: Exception) {
                                apiClient.logErrorToServer(e)
                                errorMessage = e.message ?: "Failed to create election"
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                }
            }) {
                Text(if (isLoading) "Creating..." else "Create")
            }

            Button({
                onClick { onBack() }
            }) {
                Text("Back")
            }
        }
    }
}
