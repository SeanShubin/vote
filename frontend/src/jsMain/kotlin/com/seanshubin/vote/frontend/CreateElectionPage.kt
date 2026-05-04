package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.compose.web.attributes.*
import org.jetbrains.compose.web.dom.*

@Composable
fun CreateElectionPage(
    apiClient: ApiClient,
    onElectionCreated: (String) -> Unit,
    onBack: () -> Unit,
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
) {
    var electionName by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val createAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to create election",
        onSuccess = { onElectionCreated(electionName) },
        onError = { errorMessage = it },
        coroutineScope = coroutineScope,
        action = {
            errorMessage = null
            apiClient.createElection(electionName, description)
            // Invalidate cached list payloads that just changed so the user
            // sees their new election the moment they navigate to either
            // page, instead of a stale list with the new entry winking in.
            PageCache.invalidate("elections")
        },
    )

    Div({ classes("container") }) {
        H1 { Text("Create Election") }

        if (errorMessage != null) {
            Div({ classes("error") }) {
                Text(errorMessage!!)
            }
        }

        Form(attrs = {
            classes("form")
            onSubmit { event ->
                event.preventDefault()
                if (electionName.isNotBlank()) createAction.invoke()
            }
        }) {
            Input(InputType.Text) {
                placeholder("Election Name")
                value(electionName)
                onInput { electionName = it.value }
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
                attr("type", "submit")
                if (createAction.isLoading) attr("disabled", "")
            }) {
                Text(if (createAction.isLoading) "Creating…" else "Create")
            }

            Button({
                attr("type", "button")
                onClick { onBack() }
            }) {
                Text("Back")
            }
        }
    }
}
