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
    onCandidateRenamed: (oldName: String, newName: String) -> Unit,
    onTiersSaved: (List<String>) -> Unit,
    onOwnerTransferred: (String) -> Unit,
    onError: (String) -> Unit,
) {
    var descriptionText by remember(existingDescription) {
        mutableStateOf(existingDescription)
    }
    var addCandidatesText by remember(existingCandidates) { mutableStateOf("") }
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

    // Inline rename state: which candidate the user is currently editing
    // (null when no row is in edit mode) and the text they're typing. Keyed
    // off the candidate list so navigating between elections discards any
    // pending edit instead of carrying it across.
    var renamingCandidate by remember(existingCandidates) { mutableStateOf<String?>(null) }
    var renameText by remember(existingCandidates) { mutableStateOf("") }

    // Ballot counts per candidate — fetched lazily and refreshed after every
    // mutation. Empty map until the first fetch returns; rows render with
    // "…" in place of the count during that window so the layout doesn't
    // shift when the numbers arrive.
    var ballotCounts by remember(existingCandidates) { mutableStateOf<Map<String, Int>?>(null) }

    LaunchedEffect(electionName, existingCandidates) {
        try {
            ballotCounts = apiClient.candidateBallotCounts(electionName)
        } catch (e: Throwable) {
            apiClient.logErrorToServer(e)
        }
    }

    fun parseAddCandidates(): List<String> =
        addCandidatesText.split("\n").map { it.trim() }.filter { it.isNotBlank() }

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

    // Add: union the textarea entries with the existing list, then commit
    // via setCandidates. Set-diff semantics mean any name already present
    // is silently kept rather than emitting a duplicate add event.
    val addAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to add candidates",
        onError = onError,
        action = {
            val additions = parseAddCandidates()
            if (additions.isEmpty()) return@rememberAsyncAction
            val newCandidates = (existingCandidates + additions).distinct()
            apiClient.setCandidates(electionName, newCandidates)
            addCandidatesText = ""
            onCandidatesSaved(newCandidates)
            ballotCounts = apiClient.candidateBallotCounts(electionName)
        },
    )

    val renameAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to rename candidate",
        onError = onError,
        action = {
            val oldName = renamingCandidate ?: return@rememberAsyncAction
            val newName = renameText.trim()
            if (newName.isBlank() || newName == oldName) {
                renamingCandidate = null
                renameText = ""
                return@rememberAsyncAction
            }
            apiClient.renameCandidate(electionName, oldName, newName)
            renamingCandidate = null
            renameText = ""
            onCandidateRenamed(oldName, newName)
            ballotCounts = apiClient.candidateBallotCounts(electionName)
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

        // Existing candidates — alphabetized list with per-row rename and
        // ballot-count badge. Order is fixed (alphabetical) so a rename
        // doesn't shuffle unrelated rows under the owner's mouse, and so
        // there's no ambient "candidate ordering" that voters could be
        // primed by. Comparison is case-insensitive; identical-case ties
        // fall back to the original string compare.
        if (existingCandidates.isNotEmpty()) {
            val sorted = existingCandidates.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
            Div({ classes("candidate-list") }) {
                sorted.forEach { candidateName ->
                    Div({ classes("candidate-row") }) {
                        if (renamingCandidate == candidateName) {
                            Input(InputType.Text) {
                                classes("candidate-rename-input")
                                value(renameText)
                                onInput { renameText = it.value }
                            }
                            Button({
                                if (renameAction.isLoading) attr("disabled", "")
                                onClick { renameAction.invoke() }
                            }) {
                                Text(if (renameAction.isLoading) "Saving…" else "Save")
                            }
                            Button({
                                if (renameAction.isLoading) attr("disabled", "")
                                onClick {
                                    renamingCandidate = null
                                    renameText = ""
                                }
                            }) { Text("Cancel") }
                        } else {
                            Span({ classes("candidate-name") }) { Text(candidateName) }
                            // Ballot count: "…" while the first fetch is in
                            // flight, then the real number. Singular vs plural
                            // matters here because a "1 ballots" stutters.
                            val countLabel = ballotCounts?.let { counts ->
                                val n = counts[candidateName] ?: 0
                                "$n ballot${if (n == 1) "" else "s"}"
                            } ?: "…"
                            Span({ classes("candidate-ballot-count") }) { Text(countLabel) }
                            Button({
                                onClick {
                                    renamingCandidate = candidateName
                                    renameText = candidateName
                                }
                            }) { Text("Rename") }
                        }
                    }
                }
            }
        }

        // Add candidates — paste one per line. Adds only; doesn't remove
        // existing entries. The previous "save replaces the whole list"
        // textarea was retired alongside per-row renames so the owner
        // can't accidentally wipe ballot references by deleting a name
        // from the textarea.
        H3 { Text("Add candidates") }
        P { Text("Enter one candidate per line:") }
        TextArea(addCandidatesText) {
            classes("textarea")
            attr("rows", "4")
            onInput { addCandidatesText = it.value }
        }

        Button({
            if (addAction.isLoading || parseAddCandidates().isEmpty()) attr("disabled", "")
            onClick { addAction.invoke() }
        }) {
            Text(if (addAction.isLoading) "Adding…" else "Add Candidates")
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
