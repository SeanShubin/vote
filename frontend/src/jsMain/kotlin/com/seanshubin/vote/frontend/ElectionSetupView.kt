package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.*
import org.jetbrains.compose.web.dom.*

@Composable
fun ElectionSetupView(
    apiClient: ApiClient,
    electionName: String,
    existingDescription: String,
    existingCandidates: List<String>,
    existingTiers: List<String>,
    ballotsExist: Boolean,
    onSuccess: (String) -> Unit,
    onError: (String) -> Unit,
) {
    var descriptionText by remember(existingDescription) {
        mutableStateOf(existingDescription)
    }
    var candidatesText by remember(existingCandidates) {
        mutableStateOf(existingCandidates.joinToString("\n"))
    }
    var tiersText by remember(existingTiers) {
        mutableStateOf(existingTiers.joinToString("\n"))
    }

    fun parseCandidates(): List<String> =
        candidatesText.split("\n").map { it.trim() }.filter { it.isNotBlank() }

    fun parseTiers(): List<String> =
        tiersText.split("\n").map { it.trim() }.filter { it.isNotBlank() }

    val saveDescriptionAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to save description",
        onError = onError,
        action = {
            apiClient.setElectionDescription(electionName, descriptionText)
            onSuccess("Description saved")
        },
    )

    val saveAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to save candidates",
        onError = onError,
        action = {
            apiClient.setCandidates(electionName, parseCandidates())
            onSuccess("Candidates saved")
        },
    )

    val saveTiersAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to save tiers",
        onError = onError,
        action = {
            apiClient.setTiers(electionName, parseTiers())
            onSuccess("Tiers saved")
        },
    )

    // Description editor — owners can update freely (no ballot lock since
    // the text isn't part of any ballot's meaning). Backend rejects the
    // PUT for non-owners; the field is shown to everyone in the Setup tab
    // for symmetry with the candidates / tiers fields.
    Div({ classes("section") }) {
        H2 { Text("Description") }

        P { Text("Shown to voters at the top of the election page. Optional.") }
        TextArea(descriptionText) {
            classes("textarea")
            attr("rows", "3")
            onInput { descriptionText = it.value }
        }

        Button({
            if (saveDescriptionAction.isLoading) attr("disabled", "")
            onClick { saveDescriptionAction.invoke() }
        }) {
            Text(if (saveDescriptionAction.isLoading) "Saving…" else "Save Description")
        }
    }

    Div({ classes("section") }) {
        H2 { Text("Candidates") }

        P { Text("Enter one candidate per line (an empty list is allowed):") }
        TextArea(candidatesText) {
            classes("textarea")
            attr("rows", "5")
            onInput { candidatesText = it.value }
        }

        Button({
            if (saveAction.isLoading) attr("disabled", "")
            onClick {
                val candidates = parseCandidates()

                // Warn about removed candidates before submitting. Removing a
                // candidate also strips it from existing ballot rankings, so
                // surface exactly which candidates are being dropped before
                // the user pulls the trigger.
                val removed = existingCandidates - candidates.toSet()
                if (removed.isNotEmpty()) {
                    val list = removed.joinToString(", ")
                    val message = "You are about to remove ${removed.size} candidate" +
                        (if (removed.size == 1) "" else "s") +
                        ": $list. " +
                        "This will also strip " +
                        (if (removed.size == 1) "it" else "them") +
                        " from any ballots already cast. Continue?"
                    if (!window.confirm(message)) return@onClick
                }

                saveAction.invoke()
            }
        }) {
            Text(if (saveAction.isLoading) "Saving…" else "Save Candidates")
        }
    }

    // Tiers — separate section per requirements. Locked once any ballot has
    // been cast: a tier name is part of the meaning of an existing ballot,
    // so renaming it would silently invalidate votes. Empty list disables
    // tier voting and reverts to plain candidate-only ranking.
    Div({ classes("section") }) {
        H2 { Text("Tiers (optional)") }

        if (ballotsExist) {
            P {
                Text(
                    "Tier names are locked while ballots exist. " +
                        "They can be edited again once all ballots have been removed."
                )
            }
        } else {
            P {
                Text(
                    "Enter one tier per line, top tier first. " +
                        "Leave blank for plain candidate-only ranking."
                )
            }
        }

        TextArea(tiersText) {
            classes("textarea")
            attr("rows", "4")
            if (ballotsExist) attr("disabled", "")
            onInput { tiersText = it.value }
        }

        Button({
            if (saveTiersAction.isLoading || ballotsExist) attr("disabled", "")
            onClick { saveTiersAction.invoke() }
        }) {
            Text(if (saveTiersAction.isLoading) "Saving…" else "Save Tiers")
        }
    }
}
