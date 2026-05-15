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
    existingManagers: List<String>,
    ballotsExist: Boolean,
    currentOwnerName: String,
    // True when the viewer is the election owner or an ADMIN+. Managers (who
    // can edit content) do NOT get this — the Managers and Transfer Ownership
    // sections are owner/admin-only, so they're hidden when this is false.
    canManageManagers: Boolean,
    onDescriptionSaved: (String) -> Unit,
    onCandidatesAdded: (added: List<String>) -> Unit,
    onCandidateRemoved: (removed: String) -> Unit,
    onCandidateRenamed: (oldName: String, newName: String) -> Unit,
    onTiersSaved: (List<String>) -> Unit,
    onTierRenamed: (oldName: String, newName: String) -> Unit,
    onManagerAdded: (String) -> Unit,
    onManagerRemoved: (String) -> Unit,
    onOwnerTransferred: (String) -> Unit,
    onError: (String) -> Unit,
) {
    DescriptionSection(
        apiClient = apiClient,
        electionName = electionName,
        existingDescription = existingDescription,
        onDescriptionSaved = onDescriptionSaved,
        onError = onError,
    )
    CandidatesSection(
        apiClient = apiClient,
        electionName = electionName,
        existingCandidates = existingCandidates,
        onCandidatesAdded = onCandidatesAdded,
        onCandidateRemoved = onCandidateRemoved,
        onCandidateRenamed = onCandidateRenamed,
        onError = onError,
    )
    TiersSection(
        apiClient = apiClient,
        electionName = electionName,
        existingTiers = existingTiers,
        onTiersSaved = onTiersSaved,
        onTierRenamed = onTierRenamed,
        onError = onError,
    )
    if (canManageManagers) {
        // Both manager-related sections need the user list; share one fetch.
        val userNamesFetch = rememberFetchState(
            apiClient = apiClient,
            key = currentOwnerName,
            fallbackErrorMessage = "Failed to load user list",
        ) {
            apiClient.listUserNames()
        }
        ManagersSection(
            apiClient = apiClient,
            electionName = electionName,
            existingManagers = existingManagers,
            currentOwnerName = currentOwnerName,
            userNamesFetch = userNamesFetch,
            onManagerAdded = onManagerAdded,
            onManagerRemoved = onManagerRemoved,
            onError = onError,
        )
        TransferOwnershipSection(
            apiClient = apiClient,
            electionName = electionName,
            currentOwnerName = currentOwnerName,
            userNamesFetch = userNamesFetch,
            onOwnerTransferred = onOwnerTransferred,
            onError = onError,
        )
    }
}

// Description editor — owners can update freely (no ballot lock since the
// text isn't part of any ballot's meaning). Backend rejects the PUT for
// non-owners; the field is shown to everyone in the Setup tab for symmetry
// with the candidates / tiers fields.
@Composable
private fun DescriptionSection(
    apiClient: ApiClient,
    electionName: String,
    existingDescription: String,
    onDescriptionSaved: (String) -> Unit,
    onError: (String) -> Unit,
) {
    var descriptionText by remember(existingDescription) {
        mutableStateOf(existingDescription)
    }

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
}

@Composable
private fun CandidatesSection(
    apiClient: ApiClient,
    electionName: String,
    existingCandidates: List<String>,
    onCandidatesAdded: (added: List<String>) -> Unit,
    onCandidateRemoved: (removed: String) -> Unit,
    onCandidateRenamed: (oldName: String, newName: String) -> Unit,
    onError: (String) -> Unit,
) {
    var addCandidatesText by remember(existingCandidates) { mutableStateOf("") }

    // Inline rename state: which candidate the user is currently editing
    // (null when no row is in edit mode) and the text they're typing. Keyed
    // off the candidate list so navigating between elections discards any
    // pending edit instead of carrying it across.
    var renamingCandidate by remember(existingCandidates) { mutableStateOf<String?>(null) }
    var renameText by remember(existingCandidates) { mutableStateOf("") }

    // Which candidate is being removed right now (so we can show a
    // "Removing…" spinner on the right row instead of greying out the
    // whole list).
    var removingCandidate by remember(existingCandidates) { mutableStateOf<String?>(null) }

    // Ballot counts per candidate — fetched lazily and refreshed after every
    // mutation. Empty map until the first fetch returns; rows render with
    // "…" in place of the count during that window so the layout doesn't
    // shift when the numbers arrive.
    var ballotCounts by remember(existingCandidates) { mutableStateOf<Map<String, Int>?>(null) }

    // Transient "Copied!" toast next to the Copy Candidates button. Cleared
    // after a short delay; the token bumps each click so a rapid second
    // click restarts the timer instead of the older coroutine clearing the
    // message early.
    var copyFeedback by remember { mutableStateOf<String?>(null) }
    var copyFeedbackToken by remember { mutableStateOf(0) }
    LaunchedEffect(copyFeedbackToken) {
        if (copyFeedback != null) {
            kotlinx.coroutines.delay(2000)
            copyFeedback = null
        }
    }

    LaunchedEffect(electionName, existingCandidates) {
        try {
            ballotCounts = apiClient.candidateBallotCounts(electionName)
        } catch (e: Throwable) {
            apiClient.logErrorToServer(e)
        }
    }

    fun parseAddCandidates(): List<String> =
        addCandidatesText.split("\n").map { it.trim() }.filter { it.isNotBlank() }

    // Add: send the parsed textarea names directly. Backend filters to
    // "new only" so the caller can submit a superset without de-duping
    // against the existing list first.
    val addAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to add candidates",
        onError = onError,
        action = {
            val additions = parseAddCandidates()
            if (additions.isEmpty()) return@rememberAsyncAction
            apiClient.addCandidates(electionName, additions)
            addCandidatesText = ""
            onCandidatesAdded(additions)
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

    val removeAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to remove candidate",
        onError = onError,
        action = {
            val name = removingCandidate ?: return@rememberAsyncAction
            apiClient.removeCandidate(electionName, name)
            removingCandidate = null
            onCandidateRemoved(name)
            ballotCounts = apiClient.candidateBallotCounts(electionName)
        },
    )

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
                    CandidateRow(
                        candidateName = candidateName,
                        ballotCounts = ballotCounts,
                        isRenaming = renamingCandidate == candidateName,
                        renameText = renameText,
                        onRenameTextChange = { renameText = it },
                        onStartRename = {
                            renamingCandidate = candidateName
                            renameText = candidateName
                        },
                        onCancelRename = {
                            renamingCandidate = null
                            renameText = ""
                        },
                        onConfirmRename = { renameAction.invoke() },
                        onRemove = {
                            removingCandidate = candidateName
                            removeAction.invoke()
                        },
                        renameLoading = renameAction.isLoading,
                        removeLoading = removeAction.isLoading,
                        isRowRemoving = removeAction.isLoading && removingCandidate == candidateName,
                    )
                }
            }

            // Copy the candidate list to the clipboard, one name per line —
            // the same format the "Add candidates" textarea parses — so an
            // owner can paste it straight into a new election's setup.
            Div({ classes("candidate-copy-row") }) {
                Button({
                    onClick {
                        copyTextToClipboard(sorted.joinToString("\n"), apiClient)
                        copyFeedback = "Copied ${sorted.size} candidate" +
                            (if (sorted.size == 1) "" else "s") + "!"
                        copyFeedbackToken += 1
                    }
                }) { Text("Copy Candidates") }
                if (copyFeedback != null) {
                    Span({ classes("copy-feedback") }) { Text(copyFeedback!!) }
                }
            }
        }

        // Add candidates — paste one per line. The backend filters to
        // names not already present, so submitting a list that overlaps
        // existing candidates is a no-op for the overlap (no duplicate
        // events). Removal lives on each row (above) where the cascade is
        // confirmed against the ballot count for that specific candidate.
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
}

@Composable
private fun CandidateRow(
    candidateName: String,
    ballotCounts: Map<String, Int>?,
    isRenaming: Boolean,
    renameText: String,
    onRenameTextChange: (String) -> Unit,
    onStartRename: () -> Unit,
    onCancelRename: () -> Unit,
    onConfirmRename: () -> Unit,
    onRemove: () -> Unit,
    renameLoading: Boolean,
    removeLoading: Boolean,
    isRowRemoving: Boolean,
) {
    Div({ classes("candidate-row") }) {
        if (isRenaming) {
            Input(InputType.Text) {
                classes("candidate-rename-input")
                value(renameText)
                onInput { onRenameTextChange(it.value) }
            }
            Button({
                if (renameLoading) attr("disabled", "")
                onClick { onConfirmRename() }
            }) {
                Text(if (renameLoading) "Saving…" else "Save")
            }
            Button({
                if (renameLoading) attr("disabled", "")
                onClick { onCancelRename() }
            }) { Text("Cancel") }
        } else {
            Span({ classes("candidate-name") }) { Text(candidateName) }
            // Ballot count: "…" while the first fetch is in flight, then the
            // real number. Singular vs plural matters here because a
            // "1 ballots" stutters.
            val countLabel = ballotCounts?.let { counts ->
                val n = counts[candidateName] ?: 0
                "$n ballot${if (n == 1) "" else "s"}"
            } ?: "…"
            Span({ classes("candidate-ballot-count") }) { Text(countLabel) }
            Button({
                val busy = renameLoading || removeLoading
                if (busy) attr("disabled", "")
                onClick { onStartRename() }
            }) { Text("Rename") }
            // Remove confirms with the ballot blast-radius so the owner sees
            // exactly how many ballots will be touched. Cascade on the
            // backend strips this candidate from every ranking.
            Button({
                val busy = renameLoading || removeLoading
                if (busy) attr("disabled", "")
                onClick {
                    val n = ballotCounts?.get(candidateName) ?: 0
                    val cascadeNote = if (n == 0) {
                        "No ballots reference this candidate."
                    } else {
                        "This will strip \"$candidateName\" from $n ballot" +
                            (if (n == 1) "" else "s") + "."
                    }
                    val confirmed = window.confirm(
                        "Remove \"$candidateName\" from the election? $cascadeNote"
                    )
                    if (confirmed) onRemove()
                }
            }) {
                Text(if (isRowRemoving) "Removing…" else "Remove")
            }
        }
    }
}

// Tiers — separate section per requirements. Under the tier-as-annotation
// model a tier name is a label on each candidate's ranking; renaming is a
// cascading UPDATE so it's safe whether or not ballots exist. The textarea
// below replaces the whole list (adds, removes, reorders); the per-tier
// "Rename" button uses the dedicated rename endpoint so ballots track the
// label change.
@Composable
private fun TiersSection(
    apiClient: ApiClient,
    electionName: String,
    existingTiers: List<String>,
    onTiersSaved: (List<String>) -> Unit,
    onTierRenamed: (oldName: String, newName: String) -> Unit,
    onError: (String) -> Unit,
) {
    var tiersText by remember(existingTiers) {
        mutableStateOf(existingTiers.joinToString("\n"))
    }
    // Per-tier inline rename: which tier the user is currently renaming
    // (null when no row is in edit mode) and the text they're typing.
    var renamingTier by remember(existingTiers) { mutableStateOf<String?>(null) }
    var renameTierText by remember(existingTiers) { mutableStateOf("") }

    fun parseTiers(): List<String> =
        tiersText.split("\n").map { it.trim() }.filter { it.isNotBlank() }

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

    val renameTierAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to rename tier",
        onError = onError,
        action = {
            val oldName = renamingTier ?: return@rememberAsyncAction
            val newName = renameTierText.trim()
            if (newName.isBlank() || newName == oldName) {
                renamingTier = null
                renameTierText = ""
                return@rememberAsyncAction
            }
            apiClient.renameTier(electionName, oldName, newName)
            renamingTier = null
            renameTierText = ""
            onTierRenamed(oldName, newName)
        },
    )

    Div({ classes("section") }) {
        H2 { Text("Tiers (optional)") }

        // Per-tier inline rename — mirrors the candidate-rename pattern
        // above. Tiers are inherently ordered (top prestige first), so
        // render them in declared order.
        if (existingTiers.isNotEmpty()) {
            Div({ classes("candidate-list") }) {
                existingTiers.forEach { tierName ->
                    Div({ classes("candidate-row") }) {
                        if (renamingTier == tierName) {
                            Input(InputType.Text) {
                                classes("candidate-rename-input")
                                value(renameTierText)
                                onInput { renameTierText = it.value }
                            }
                            Button({
                                if (renameTierAction.isLoading) attr("disabled", "")
                                onClick { renameTierAction.invoke() }
                            }) {
                                Text(if (renameTierAction.isLoading) "Saving…" else "Save")
                            }
                            Button({
                                if (renameTierAction.isLoading) attr("disabled", "")
                                onClick {
                                    renamingTier = null
                                    renameTierText = ""
                                }
                            }) { Text("Cancel") }
                        } else {
                            Span({ classes("candidate-name") }) { Text(tierName) }
                            Button({
                                onClick {
                                    renamingTier = tierName
                                    renameTierText = tierName
                                }
                            }) { Text("Rename") }
                        }
                    }
                }
            }
        }

        P {
            Text(
                "Enter one tier per line, top tier first. Leave blank for " +
                    "plain candidate-only ranking. Removing a tier here clears " +
                    "its annotation on every existing ballot ranking."
            )
        }

        TextArea(tiersText) {
            classes("textarea")
            attr("rows", "4")
            onInput { tiersText = it.value }
        }

        Button({
            if (saveTiersAction.isLoading) attr("disabled", "")
            onClick { saveTiersAction.invoke() }
        }) {
            Text(if (saveTiersAction.isLoading) "Saving…" else "Save Tiers")
        }
    }
}

// Managers — co-managers the owner has granted content-editing authority.
// Owner/ADMIN-only section (gated by canManageManagers in the parent); a
// manager who can see the Setup tab still can't recruit more managers. The
// add-picker reuses the same filter-then-click pattern as Transfer Ownership.
@Composable
private fun ManagersSection(
    apiClient: ApiClient,
    electionName: String,
    existingManagers: List<String>,
    currentOwnerName: String,
    userNamesFetch: Fetched<List<String>>,
    onManagerAdded: (String) -> Unit,
    onManagerRemoved: (String) -> Unit,
    onError: (String) -> Unit,
) {
    // Manager add-picker filter + the user currently being added (so the right
    // row shows an "Adding…" label). Keyed off the manager list so navigating
    // away discards any pending pick.
    var managerFilter by remember(existingManagers) { mutableStateOf("") }
    var addingManagerTarget by remember(existingManagers) { mutableStateOf<String?>(null) }
    // Which manager is being removed right now, for a per-row "Removing…" label.
    var removingManager by remember(existingManagers) { mutableStateOf<String?>(null) }

    val addManagerAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to add manager",
        onError = onError,
        action = {
            val target = addingManagerTarget ?: return@rememberAsyncAction
            apiClient.addElectionManager(electionName, target)
            onManagerAdded(target)
            addingManagerTarget = null
            managerFilter = ""
        },
    )

    val removeManagerAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to remove manager",
        onError = onError,
        action = {
            val name = removingManager ?: return@rememberAsyncAction
            apiClient.removeElectionManager(electionName, name)
            removingManager = null
            onManagerRemoved(name)
        },
    )

    Div({ classes("section") }) {
        H2 { Text("Managers") }

        P {
            Text(
                "Managers can edit the description, candidates, and tiers — " +
                    "but cannot delete or transfer the election, or change this list."
            )
        }

        if (existingManagers.isNotEmpty()) {
            Div({ classes("candidate-list") }) {
                existingManagers.forEach { managerName ->
                    Div({ classes("candidate-row") }) {
                        Span({ classes("candidate-name") }) { Text(managerName) }
                        Button({
                            val busy = addManagerAction.isLoading || removeManagerAction.isLoading
                            if (busy) attr("disabled", "")
                            onClick {
                                val confirmed = window.confirm(
                                    "Remove \"$managerName\" as a manager of \"$electionName\"?"
                                )
                                if (confirmed) {
                                    removingManager = managerName
                                    removeManagerAction.invoke()
                                }
                            }
                        }) {
                            val isThisRowRemoving = removeManagerAction.isLoading &&
                                removingManager == managerName
                            Text(if (isThisRowRemoving) "Removing…" else "Remove")
                        }
                    }
                }
            }
        } else {
            P { Text("No managers yet.") }
        }

        H3 { Text("Add a manager") }
        P { Text("Type to filter, then click a user to grant them management.") }

        Input(InputType.Text) {
            classes("input")
            value(managerFilter)
            placeholder("filter users")
            onInput {
                managerFilter = it.value
                addingManagerTarget = null
            }
        }

        when (val namesState = userNamesFetch.state) {
            FetchState.Loading -> P { Text("Loading users…") }
            is FetchState.Error -> Div({ classes("error") }) { Text(namesState.message) }
            is FetchState.Success -> {
                // Exclude the owner (already has full authority) and anyone
                // already managing — backend re-checks both, this just keeps
                // the picker from offering pointless choices.
                val matches = namesState.value
                    .filter { !it.equals(currentOwnerName, ignoreCase = true) }
                    .filter { name -> existingManagers.none { it.equals(name, ignoreCase = true) } }
                    .filter { matchesSubsequence(it, managerFilter) }
                if (matches.isEmpty()) {
                    P { Text("No matching users.") }
                } else {
                    Div({ classes("transfer-owner-list") }) {
                        matches.forEach { candidate ->
                            val isPending = addManagerAction.isLoading &&
                                addingManagerTarget == candidate
                            Button({
                                classes("transfer-owner-item")
                                if (addManagerAction.isLoading) attr("disabled", "")
                                onClick {
                                    addingManagerTarget = candidate
                                    addManagerAction.invoke()
                                }
                            }) {
                                Text(if (isPending) "Adding $candidate…" else candidate)
                            }
                        }
                    }
                }
            }
        }
    }
}

// Hand the election off to another user. After a successful transfer the
// current viewer loses owner-only authority on this election, so the
// backend's owner-or-ADMIN gate will start rejecting further edits unless
// the viewer has ADMIN+.
//
// The picker (filter input + clickable list) replaces free-text entry so
// the user can only pick a real registered user — backend still validates
// existence, but the UI rules out typos before submission. Matching is by
// subsequence (each filter character must appear in order somewhere in
// the candidate's name), case-insensitive, so "abi" matches "Sean Shubin".
//
// Owner/ADMIN-only, same gate as the Managers section.
@Composable
private fun TransferOwnershipSection(
    apiClient: ApiClient,
    electionName: String,
    currentOwnerName: String,
    userNamesFetch: Fetched<List<String>>,
    onOwnerTransferred: (String) -> Unit,
    onError: (String) -> Unit,
) {
    var transferFilter by remember(currentOwnerName) { mutableStateOf("") }
    var transferTarget by remember(currentOwnerName) { mutableStateOf<String?>(null) }

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
