package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.*
import org.jetbrains.compose.web.dom.*

@Composable
fun CreateElectionPage(
    authToken: String,
    onElectionCreated: (String) -> Unit,
    onBack: () -> Unit
) {
    var electionName by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
                        scope.launch {
                            try {
                                ApiClient.createElection(authToken, electionName)
                                onElectionCreated(electionName)
                            } catch (e: Exception) {
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
