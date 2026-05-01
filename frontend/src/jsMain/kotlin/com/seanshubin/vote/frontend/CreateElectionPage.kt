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
    onElectionCreated: (String) -> Unit,
    onBack: () -> Unit,
    coroutineScope: CoroutineScope = rememberCoroutineScope()
) {
    var electionName by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val handleCreateElection = {
        if (!isLoading && electionName.isNotBlank()) {
            isLoading = true
            errorMessage = null
            coroutineScope.launch {
                try {
                    apiClient.createElection(electionName, description)
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
                onKeyDown { event ->
                    if (event.key == "Enter") {
                        handleCreateElection()
                    }
                }
            }

            // Description is optional — empty string means "no description". A
            // textarea (rather than a single-line input) keeps paragraph breaks
            // intact for voters reading the ballot.
            TextArea(description) {
                classes("textarea")
                placeholder("Description (optional)")
                attr("rows", "4")
                onInput { description = it.value }
            }

            Button({
                onClick { handleCreateElection() }
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
