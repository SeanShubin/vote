package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.domain.*
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.*
import org.jetbrains.compose.web.dom.*

/**
 * Header summary + tabs for one election.
 *
 * The previous Details tab was redundant once secret-ballot, allow-edit,
 * allow-vote, and noVotingBefore/After were dropped — owner is the only
 * remaining text field. Owner + candidate count + ballot count now live as
 * a small header above the tabs (Setup | Vote | Results).
 *
 * Elections are live as soon as they exist — no Launch step. Anyone
 * authenticated can vote on any election (no eligibility list).
 */
@Composable
fun ElectionDetailPage(
    apiClient: ApiClient,
    electionName: String,
    // Used to decide whether to show the Delete button: shown to the
    // election owner, or to any user with role >= ADMIN. Backend re-checks.
    currentUserName: String?,
    currentRole: Role?,
    onBack: () -> Unit,
    onElectionDeleted: () -> Unit,
) {
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var currentView by remember { mutableStateOf("setup") }

    val pageFetch = rememberFetchState(
        apiClient = apiClient,
        key = electionName,
        fallbackErrorMessage = "Failed to load election",
    ) {
        val election = apiClient.getElection(electionName)
        val candidates = apiClient.listCandidates(electionName)
        election to candidates
    }

    val deleteAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to delete election",
        onError = { errorMessage = it },
        action = {
            apiClient.deleteElection(electionName)
            onElectionDeleted()
        },
    )

    val pageState = pageFetch.state
    val loadedElection = (pageState as? FetchState.Success)?.value?.first

    Div({ classes("container") }) {
        H1 { Text("Election: $electionName") }

        // Header summary: owner + counts. Replaces the old Details tab.
        // Description (when provided) renders prominently right under the
        // election name so a voter casting a ballot knows what they're voting on.
        loadedElection?.let { e ->
            if (e.description.isNotBlank()) {
                Div({ classes("section", "election-description") }) {
                    P { Text(e.description) }
                }
            }
            Div({ classes("section") }) {
                P { Text("Owner: ${e.ownerName}") }
                P { Text("Candidates: ${e.candidateCount} • Ballots cast: ${e.ballotCount}") }
            }
        }

        if (errorMessage != null) {
            Div({ classes("error") }) { Text(errorMessage!!) }
        }
        if (successMessage != null) {
            Div({ classes("success") }) { Text(successMessage!!) }
        }

        when (pageState) {
            FetchState.Loading -> P { Text("Loading…") }
            is FetchState.Error -> Div({ classes("error") }) { Text(pageState.message) }
            is FetchState.Success -> {
                val (_, candidates) = pageState.value

                // Tabs (no more Details — the header above covers it).
                Div({ classes("tabs") }) {
                    Button({ onClick { currentView = "setup" } }) { Text("Setup") }
                    Button({ onClick { currentView = "vote" } }) { Text("Vote") }
                    Button({ onClick { currentView = "tally" } }) { Text("Results") }
                }

                when (currentView) {
                    "setup" -> ElectionSetupView(
                        apiClient = apiClient,
                        electionName = electionName,
                        existingCandidates = candidates,
                        onSuccess = { msg ->
                            successMessage = msg
                            pageFetch.reload()
                        },
                        onError = { errorMessage = it },
                    )
                    "vote" -> VotingView(
                        apiClient = apiClient,
                        electionName = electionName,
                        candidates = candidates,
                        onSuccess = { msg ->
                            successMessage = msg
                            pageFetch.reload()
                        },
                        onError = { errorMessage = it },
                    )
                    "tally" -> TallyView(
                        apiClient = apiClient,
                        electionName = electionName,
                    )
                }
            }
        }

        // Delete is shown to the election owner OR any user with role >= ADMIN
        // (moderators). Backend authorization is the real defense.
        val canDelete = loadedElection?.let { e ->
            e.ownerName == currentUserName ||
                (currentRole != null && currentRole >= Role.ADMIN)
        } ?: false

        Div({ classes("button-row") }) {
            Button({
                onClick { onBack() }
            }) {
                Text("Back to Elections")
            }

            if (canDelete) {
                Button({
                    if (deleteAction.isLoading) attr("disabled", "")
                    onClick {
                        val confirmed = window.confirm(
                            "Delete election \"$electionName\"? This cannot be undone."
                        )
                        if (confirmed) deleteAction.invoke()
                    }
                }) {
                    Text(if (deleteAction.isLoading) "Deleting…" else "Delete Election")
                }
            }
        }
    }
}

@Composable
fun ElectionSetupView(
    apiClient: ApiClient,
    electionName: String,
    existingCandidates: List<String>,
    onSuccess: (String) -> Unit,
    onError: (String) -> Unit,
) {
    var candidatesText by remember(existingCandidates) {
        mutableStateOf(existingCandidates.joinToString("\n"))
    }

    fun parseCandidates(): List<String> =
        candidatesText.split("\n").map { it.trim() }.filter { it.isNotBlank() }

    val saveAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to save candidates",
        onError = onError,
        action = {
            apiClient.setCandidates(electionName, parseCandidates())
            onSuccess("Candidates saved")
        },
    )

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
}

/**
 * Ranked-ballot voting UI ported from `prototype/ranked-ballot.html`. Candidates
 * start in an "arena" pool (3-column grid). Click a chip to rank it (appends to
 * the ranked list with the next ordinal). Drag a row to reorder live. ↑/↓ keys
 * nudge the focused row. × returns a row to the arena. Ctrl+Z / Cmd+Z undoes
 * the last action (50-step session-only history).
 *
 * The wire format is unchanged — submit sends `List<Ranking>` where each
 * ranked candidate gets `rank = position + 1`. Unranked candidates are simply
 * omitted; the tally treats absent candidates as "least preferred, tied with
 * each other" via `rankingFor(name) ?: Int.MAX_VALUE` in `Ranking.kt`.
 */
@Composable
fun VotingView(
    apiClient: ApiClient,
    electionName: String,
    candidates: List<String>,
    onSuccess: (String) -> Unit,
    onError: (String) -> Unit,
) {
    var arena by remember(electionName, candidates) { mutableStateOf<List<String>>(emptyList()) }
    var ranked by remember(electionName, candidates) { mutableStateOf<List<String>>(emptyList()) }
    var history by remember(electionName) {
        mutableStateOf<List<Pair<List<String>, List<String>>>>(emptyList())
    }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragMoved by remember { mutableStateOf(false) }
    var isInitialized by remember(electionName, candidates) { mutableStateOf(false) }

    // Pre-populate from any existing ballot so a returning voter sees their
    // prior pick instead of starting from scratch. New candidates added since
    // last vote land in the arena; candidates removed since last vote are
    // dropped from the previous ranking. logErrorToServer rethrows
    // CancellationException, so navigating away mid-fetch cancels cleanly.
    LaunchedEffect(electionName, candidates) {
        if (candidates.isEmpty()) {
            arena = emptyList()
            ranked = emptyList()
            isInitialized = true
            return@LaunchedEffect
        }
        val candidateSet = candidates.toSet()
        val existing = try {
            apiClient.getMyRankings(electionName)
        } catch (e: Exception) {
            apiClient.logErrorToServer(e)
            emptyList()
        }
        val rankedNames = existing
            .filter { it.rank != null }
            .sortedBy { it.rank }
            .map { it.candidateName }
            .filter { it in candidateSet }
        val rankedSet = rankedNames.toSet()
        ranked = rankedNames
        arena = candidates.filter { it !in rankedSet }
        isInitialized = true
    }

    fun pushHistory() {
        val next = history + (arena to ranked)
        history = if (next.size > 50) next.drop(next.size - 50) else next
    }

    fun handleClickChip(name: String) {
        pushHistory()
        arena = arena.filter { it != name }
        ranked = ranked + name
    }

    fun handleRemove(index: Int) {
        if (index !in ranked.indices) return
        pushHistory()
        val name = ranked[index]
        ranked = ranked.toMutableList().apply { removeAt(index) }
        arena = arena + name
    }

    fun handleMove(i: Int, dir: Int) {
        val j = i + dir
        if (j < 0 || j >= ranked.size) return
        pushHistory()
        ranked = ranked.toMutableList().apply {
            val tmp = this[i]; this[i] = this[j]; this[j] = tmp
        }
    }

    fun handleUndo() {
        if (history.isEmpty()) return
        val (a, r) = history.last()
        history = history.dropLast(1)
        arena = a
        ranked = r
    }

    val submitAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to cast ballot",
        onError = onError,
        action = {
            val rankings = ranked.mapIndexed { i, n -> Ranking(n, i + 1) }
            val confirmation = apiClient.castBallot(electionName, rankings)
            onSuccess("Ballot cast successfully! Confirmation: $confirmation")
        },
    )

    // Window-level Ctrl+Z / Cmd+Z. The listener captures `handleUndo` once,
    // but `handleUndo` reads from `mutableStateOf`-backed delegates, so each
    // call sees current state.
    DisposableEffect(electionName) {
        val listener: (org.w3c.dom.events.Event) -> Unit = { event ->
            val ke = event.unsafeCast<org.w3c.dom.events.KeyboardEvent>()
            if ((ke.ctrlKey || ke.metaKey) && ke.key == "z") {
                ke.preventDefault()
                handleUndo()
            }
        }
        window.addEventListener("keydown", listener)
        onDispose { window.removeEventListener("keydown", listener) }
    }

    Div({ classes("section") }) {
        H2 { Text("Cast Ballot") }

        if (candidates.isEmpty()) {
            P { Text("No candidates yet — the election owner can add them via the Setup tab.") }
            return@Div
        }
        if (!isInitialized) {
            P { Text("Loading…") }
            return@Div
        }

        Div({ classes("ranked-ballot") }) {
            if (arena.isNotEmpty()) {
                Div({ classes("ranked-ballot-arena") }) {
                    Span({ classes("ranked-ballot-arena-label") }) {
                        Text("Candidates — click to rank in order of preference")
                    }
                    Div({ classes("ranked-ballot-arena-grid") }) {
                        arena.forEach { name ->
                            Button({
                                classes("ranked-ballot-chip")
                                onClick { handleClickChip(name) }
                            }) {
                                Text(name)
                            }
                        }
                    }
                }
            }

            Div {
                Div({ classes("ranked-ballot-list-header") }) {
                    Span({ classes("ranked-ballot-list-label") }) { Text("Your ranking") }
                    if (history.isNotEmpty()) {
                        Span({ classes("ranked-ballot-undo-count") }) {
                            Text("${history.size} step${if (history.size == 1) "" else "s"} undoable")
                        }
                    }
                }

                Ol({ classes("ranked-ballot-list") }) {
                    ranked.forEachIndexed { index, name ->
                        Li({
                            classes("ranked-ballot-row")
                            if (draggingIndex == index) classes("dragging")
                            attr("draggable", "true")
                            attr("tabindex", "0")
                            onDragStart {
                                draggingIndex = index
                                dragMoved = false
                            }
                            onDragOver { event ->
                                event.preventDefault()
                                val src = draggingIndex ?: return@onDragOver
                                if (src == index) return@onDragOver
                                if (!dragMoved) {
                                    pushHistory()
                                    dragMoved = true
                                }
                                ranked = ranked.toMutableList().apply {
                                    val item = removeAt(src)
                                    add(index, item)
                                }
                                draggingIndex = index
                            }
                            onDrop { event ->
                                event.preventDefault()
                                draggingIndex = null
                                dragMoved = false
                            }
                            onDragEnd {
                                draggingIndex = null
                                dragMoved = false
                            }
                            onKeyDown { event ->
                                when (event.key) {
                                    "ArrowUp" -> {
                                        event.preventDefault()
                                        handleMove(index, -1)
                                    }
                                    "ArrowDown" -> {
                                        event.preventDefault()
                                        handleMove(index, 1)
                                    }
                                }
                            }
                        }) {
                            Span({ classes("ranked-ballot-rank-num") }) {
                                Text((index + 1).toString().padStart(2, '0'))
                            }
                            Span({ classes("ranked-ballot-row-name") }) { Text(name) }
                            Span({ classes("ranked-ballot-row-buttons") }) {
                                Button({
                                    classes("ranked-ballot-row-button")
                                    title("Move up")
                                    if (index == 0) attr("disabled", "")
                                    onClick { event ->
                                        event.stopPropagation()
                                        handleMove(index, -1)
                                    }
                                }) { Text("↑") }
                                Button({
                                    classes("ranked-ballot-row-button")
                                    title("Move down")
                                    if (index == ranked.size - 1) attr("disabled", "")
                                    onClick { event ->
                                        event.stopPropagation()
                                        handleMove(index, 1)
                                    }
                                }) { Text("↓") }
                                Button({
                                    classes("ranked-ballot-row-button")
                                    classes("ranked-ballot-row-remove")
                                    title("Return to candidates")
                                    onClick { event ->
                                        event.stopPropagation()
                                        handleRemove(index)
                                    }
                                }) { Text("×") }
                            }
                        }
                    }
                }

                if (ranked.isEmpty()) {
                    P({ classes("ranked-ballot-empty-hint") }) {
                        Text("Click a candidate above to begin")
                    }
                }

                Div({ classes("ranked-ballot-toolbar") }) {
                    Button({
                        classes("ranked-ballot-undo-button")
                        if (history.isEmpty()) attr("disabled", "")
                        onClick { handleUndo() }
                    }) { Text("↩ Undo") }

                    Button({
                        if (submitAction.isLoading || ranked.isEmpty()) attr("disabled", "")
                        onClick {
                            if (ranked.isNotEmpty()) submitAction.invoke()
                        }
                    }) {
                        Text(if (submitAction.isLoading) "Submitting…" else "Submit Ballot")
                    }

                    Span({ classes("ranked-ballot-toolbar-hint") }) {
                        Text("Focus a row · ↑/↓ to nudge · Ctrl+Z to undo")
                    }
                }
            }
        }
    }
}

@Composable
fun TallyView(
    apiClient: ApiClient,
    electionName: String,
) {
    val tallyFetch = rememberFetchState(
        apiClient = apiClient,
        key = electionName,
        fallbackErrorMessage = "Failed to load tally",
    ) {
        apiClient.getTally(electionName)
    }

    Div({ classes("section") }) {
        H2 { Text("Results") }

        when (val state = tallyFetch.state) {
            FetchState.Loading -> P { Text("Loading results…") }
            is FetchState.Error -> Div({ classes("error") }) { Text(state.message) }
            is FetchState.Success -> renderTally(state.value)
        }
    }
}

@Composable
private fun renderTally(tally: Tally) {
    P { Text("Total Ballots: ${tally.ballots.size}") }

    H3 { Text("Winners") }
    if (tally.places.isEmpty()) {
        P { Text("No winners yet") }
    } else {
        tally.places.forEach { place ->
            P { Text("Place ${place.rank}: ${place.candidateName}") }
        }
    }

    H3 { Text("Preferences Matrix") }
    Table {
        Thead {
            Tr {
                Th { Text("") }
                tally.candidateNames.forEach { candidate ->
                    Th { Text(candidate) }
                }
            }
        }
        Tbody {
            tally.candidateNames.forEachIndexed { rowIndex, rowCandidate ->
                Tr {
                    Td { Text(rowCandidate) }
                    tally.candidateNames.forEachIndexed { colIndex, _ ->
                        val preference = tally.preferences[rowIndex][colIndex]
                        Td { Text(preference.strength.toString()) }
                    }
                }
            }
        }
    }
}
