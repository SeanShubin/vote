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
    currentOwnerName: String,
    onDescriptionSaved: (String) -> Unit,
    onCandidatesSaved: (List<String>) -> Unit,
    onTiersSaved: (List<String>) -> Unit,
    onOwnerTransferred: (String) -> Unit,
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
    var transferFilter by remember(currentOwnerName) { mutableStateOf("") }
    var transferTarget by remember(currentOwnerName) { mutableStateOf<String?>(null) }

    val userNamesFetch = rememberFetchState(
        apiClient = apiClient,
        key = currentOwnerName,
        fallbackErrorMessage = "Failed to load user list",
    ) {
        apiClient.listUserNames()
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
            val newDescription = descriptionText
            apiClient.setElectionDescription(electionName, newDescription)
            onDescriptionSaved(newDescription)
        },
    )

    val saveAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to save candidates",
        onError = onError,
        action = {
            val newCandidates = parseCandidates()
            apiClient.setCandidates(electionName, newCandidates)
            onCandidatesSaved(newCandidates)
        },
    )

    val saveTiersAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to save tiers",
        onError = onError,
        action = {
            val newTiers = parseTiers()
            apiClient.setTiers(electionName, newTiers)
            onTiersSaved(newTiers)
        },
    )

    val transferOwnerAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to transfer ownership",
        onError = onError,
        action = {
            val target = transferTarget ?: return@rememberAsyncAction
            apiClient.transferElectionOwnership(electionName, target)
            onOwnerTransferred(target)
            transferTarget = null
            transferFilter = ""
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

    // Hand the election off to another user. After a successful transfer the
    // current viewer loses owner-only authority on this election, so the
    // backend's owner-or-ADMIN gate will start rejecting further edits unless
    // the viewer has ADMIN+. The page reloads the shell after the callback
    // fires, which will hide the Setup tab on the next render for a plain
    // user who just transferred away their last claim to it.
    //
    // The picker (filter input + clickable list) replaces free-text entry so
    // the user can only pick a real registered user — backend still validates
    // existence, but the UI rules out typos before submission. Matching is by
    // subsequence (each filter character must appear in order somewhere in
    // the candidate's name), case-insensitive, so "abi" matches "Sean Shubin".
    Div({ classes("section") }) {
        H2 { Text("Transfer Ownership") }

        P {
            Text(
                "Current owner: $currentOwnerName. " +
                    "Type to filter, then click a user to transfer to them."
            )
        }

        Input(InputType.Text) {
            classes("input")
            value(transferFilter)
            placeholder("filter users")
            onInput {
                transferFilter = it.value
                transferTarget = null
            }
        }

        when (val namesState = userNamesFetch.state) {
            FetchState.Loading -> P { Text("Loading users…") }
            is FetchState.Error -> Div({ classes("error") }) { Text(namesState.message) }
            is FetchState.Success -> {
                val matches = namesState.value
                    .filter { !it.equals(currentOwnerName, ignoreCase = true) }
                    .filter { matchesSubsequence(it, transferFilter) }
                if (matches.isEmpty()) {
                    P { Text("No matching users.") }
                } else {
                    Div({ classes("transfer-owner-list") }) {
                        matches.forEach { candidate ->
                            val isPending = transferOwnerAction.isLoading &&
                                transferTarget == candidate
                            Button({
                                classes("transfer-owner-item")
                                if (transferOwnerAction.isLoading) attr("disabled", "")
                                onClick {
                                    val confirmed = window.confirm(
                                        "Transfer ownership of \"$electionName\" to " +
                                            "\"$candidate\"? You will no longer be able to " +
                                            "edit or delete this election (unless you are an ADMIN)."
                                    )
                                    if (confirmed) {
                                        transferTarget = candidate
                                        transferOwnerAction.invoke()
                                    }
                                }
                            }) {
                                Text(if (isPending) "Transferring to $candidate…" else candidate)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Subsequence (not substring) match, case-insensitive: every character of
 * [query] must appear in [target] in order, but not necessarily contiguously.
 * "abi" matches "Sean Shubin" — a, b, i appear in that order. Empty query
 * matches everything. Used by the transfer-ownership picker to narrow a
 * potentially long user list with very little typing.
 */
internal fun matchesSubsequence(target: String, query: String): Boolean {
    if (query.isEmpty()) return true
    val haystack = target.lowercase()
    val needle = query.lowercase()
    var ni = 0
    for (c in haystack) {
        if (c == needle[ni]) {
            ni++
            if (ni == needle.length) return true
        }
    }
    return false
}
