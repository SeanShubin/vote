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
    onNavigateToPreferences: () -> Unit = {},
    onNavigateToStrongestPaths: () -> Unit = {},
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
    // Hold onto the most recent Success so a reload (which briefly flips
    // pageState back to Loading) doesn't unmount VotingView. Without this,
    // the user's in-progress ballot loses its remember-state every time
    // pageFetch.reload runs — clicking a candidate and triggering the
    // first-cast reload made the candidate "snap back" into the arena.
    var lastLoaded by remember(electionName) {
        mutableStateOf<Pair<ElectionDetail, List<String>>?>(null)
    }
    LaunchedEffect(pageState) {
        (pageState as? FetchState.Success)?.value?.let { lastLoaded = it }
    }
    val loadedElection = lastLoaded?.first

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

        when {
            lastLoaded == null && pageState is FetchState.Error ->
                Div({ classes("error") }) { Text(pageState.message) }
            lastLoaded == null ->
                P { Text("Loading…") }
            else -> {
                val (_, candidates) = lastLoaded!!

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
                        existingTiers = loadedElection?.tiers ?: emptyList(),
                        ballotsExist = (loadedElection?.ballotCount ?: 0) > 0,
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
                        tiers = loadedElection?.tiers ?: emptyList(),
                        onBallotSaved = { pageFetch.reload() },
                        onError = { errorMessage = it },
                    )
                    "tally" -> TallyView(
                        apiClient = apiClient,
                        electionName = electionName,
                        tiers = loadedElection?.tiers ?: emptyList(),
                        onNavigateToPreferences = onNavigateToPreferences,
                        onNavigateToStrongestPaths = onNavigateToStrongestPaths,
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
    existingTiers: List<String>,
    ballotsExist: Boolean,
    onSuccess: (String) -> Unit,
    onError: (String) -> Unit,
) {
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

/**
 * Ranked-ballot voting UI ported from `prototype/ranked-ballot.html`. Candidates
 * start in an "arena" pool (3-column grid). Click a chip to rank it (appends to
 * the ranked list with the next ordinal). Drag a row to reorder live. ↑/↓ keys
 * nudge the focused row. × returns a row to the arena. Ctrl+Z / Cmd+Z undoes
 * the last action (50-step session-only history).
 *
 * There is no submit step: every change to the ranked list is persisted
 * immediately via castBallot, so the on-screen ordering is always the
 * voter's current ballot. The wire format sends `List<Ranking>` where each
 * ranked entry gets `rank = position + 1` and a `kind` tag (CANDIDATE or
 * TIER). Unranked candidates are simply omitted; the tally treats absent
 * candidates as "least preferred, tied with each other" via
 * `rankingFor(name) ?: Int.MAX_VALUE` in `Ranking.kt`.
 *
 * Tier mode (when [tiers] is non-empty): the ranked list is pre-populated
 * with all tier markers in declared order, and the top tier is selected by
 * default. Clicking a candidate inserts it directly above the selected tier
 * marker (so it lands "in" that tier). Tier markers are not draggable and
 * have no remove/move buttons — their relative order is invariant. Clearing
 * every CANDIDATE row (only tier markers remain) is treated as "no ballot"
 * and deletes the ballot server-side.
 */
@Composable
fun VotingView(
    apiClient: ApiClient,
    electionName: String,
    candidates: List<String>,
    tiers: List<String>,
    onBallotSaved: () -> Unit,
    onError: (String) -> Unit,
) {
    var arena by remember(electionName, candidates, tiers) { mutableStateOf<List<String>>(emptyList()) }
    var ranked by remember(electionName, candidates, tiers) { mutableStateOf<List<RankedItem>>(emptyList()) }
    // savedRanked mirrors the server: empty list = no ballot, non-empty =
    // exactly what we last sent via castBallot. Updated after a successful
    // save (or delete). Kept separate from `ranked` so the auto-save effect
    // can tell user-initiated changes apart from the initial load.
    var savedRanked by remember(electionName, candidates, tiers) { mutableStateOf<List<RankedItem>?>(null) }
    // Selected tier — the tier a click on a candidate chip will drop into.
    // Stored as the tier name (not index) so insertions/deletions in `ranked`
    // don't invalidate it. Null when tiers are not configured.
    var selectedTierName by remember(electionName, tiers) { mutableStateOf<String?>(null) }
    var history by remember(electionName) {
        mutableStateOf<List<Pair<List<String>, List<RankedItem>>>>(emptyList())
    }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragMoved by remember { mutableStateOf(false) }
    var isInitialized by remember(electionName, candidates, tiers) { mutableStateOf(false) }

    // Pre-populate from any existing ballot so a returning voter sees their
    // prior pick instead of starting from scratch. New candidates added since
    // last vote land in the arena; candidates removed since last vote are
    // dropped from the previous ranking. logErrorToServer rethrows
    // CancellationException, so navigating away mid-fetch cancels cleanly.
    LaunchedEffect(electionName, candidates, tiers) {
        if (candidates.isEmpty() && tiers.isEmpty()) {
            arena = emptyList()
            ranked = emptyList()
            savedRanked = emptyList()
            selectedTierName = null
            isInitialized = true
            return@LaunchedEffect
        }
        val candidateSet = candidates.toSet()
        val tierSet = tiers.toSet()
        val existing = try {
            apiClient.getMyRankings(electionName)
        } catch (e: Exception) {
            apiClient.logErrorToServer(e)
            emptyList()
        }
        val sortedExisting = existing.filter { it.rank != null }.sortedBy { it.rank }
        // Drop entries whose name is no longer a candidate or tier (the
        // election may have changed since the ballot was cast). Election
        // owners can't change tier names while ballots exist, so a tier-rank
        // entry should always be valid; this is just defense in depth.
        val knownExisting = sortedExisting.filter {
            (it.kind == RankingKind.CANDIDATE && it.candidateName in candidateSet) ||
                (it.kind == RankingKind.TIER && it.candidateName in tierSet)
        }

        val newRanked: List<RankedItem> = if (tiers.isEmpty()) {
            knownExisting
                .filter { it.kind == RankingKind.CANDIDATE }
                .map { RankedItem(it.candidateName, RankingKind.CANDIDATE) }
        } else {
            // Tier mode. If the loaded ballot has all tier markers in
            // declared order, trust it. Otherwise reset to a fresh ballot
            // template (just the tier markers) and surface that the prior
            // ballot couldn't be reconstructed.
            val tierPositions = tiers.map { tier ->
                knownExisting.indexOfFirst { it.kind == RankingKind.TIER && it.candidateName == tier }
            }
            val tiersInOrder = tierPositions.none { it < 0 } && tierPositions == tierPositions.sorted()
            if (tiersInOrder && knownExisting.any { it.kind == RankingKind.TIER }) {
                knownExisting.map { RankedItem(it.candidateName, it.kind) }
            } else {
                tiers.map { RankedItem(it, RankingKind.TIER) }
            }
        }
        ranked = newRanked
        // savedRanked reflects the SERVER state. If the server had no ballot
        // (existing was empty), savedRanked is empty even when ranked has
        // tier markers — those markers are part of the on-screen draft, not
        // a ballot the user has cast.
        savedRanked = if (knownExisting.isEmpty()) emptyList() else newRanked
        val rankedCandidates = newRanked.filter { it.kind == RankingKind.CANDIDATE }.map { it.name }.toSet()
        arena = candidates.filter { it !in rankedCandidates }
        selectedTierName = tiers.firstOrNull()
        isInitialized = true
    }

    // Auto-save on every change to `ranked`. A short debounce coalesces the
    // burst of intermediate states produced by drag-reorder (onDragOver fires
    // many times during one drag). When `ranked` changes again before the
    // debounce expires, LaunchedEffect cancels the in-flight coroutine and
    // the older save never goes out, so the server only sees the settled state.
    //
    // "No candidates ranked" (only tier markers, or completely empty) is
    // treated as "this voter has no ballot" — we call deleteMyBallot so the
    // ballot row goes away entirely. The transitions that change the ballot
    // count (first cast OR full clear) trigger onBallotSaved to refresh the
    // header.
    LaunchedEffect(ranked) {
        val saved = savedRanked ?: return@LaunchedEffect
        val rankedHasCandidates = ranked.any { it.kind == RankingKind.CANDIDATE }
        val savedHasBallot = saved.isNotEmpty()
        if (!rankedHasCandidates && !savedHasBallot) return@LaunchedEffect
        if (rankedHasCandidates && ranked == saved) return@LaunchedEffect
        kotlinx.coroutines.delay(250)
        val toSave = ranked
        try {
            if (!rankedHasCandidates) {
                // Server should hold no ballot. Idempotent if already gone.
                apiClient.deleteMyBallot(electionName)
                savedRanked = emptyList()
                onBallotSaved()
            } else {
                val rankings = toSave.mapIndexed { i, item ->
                    Ranking(item.name, i + 1, item.kind)
                }
                apiClient.castBallot(electionName, rankings)
                val wasFirstCast = !savedHasBallot
                savedRanked = toSave
                if (wasFirstCast) onBallotSaved()
            }
        } catch (e: Exception) {
            apiClient.logErrorToServer(e)
            onError(e.message ?: "Failed to save ballot")
        }
    }

    val deleteBallotAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to remove ballot",
        onError = onError,
        action = {
            apiClient.deleteMyBallot(electionName)
            // Reset local state so the UI reflects the now-empty ballot. All
            // candidates return to the arena; tier markers go back to the
            // initial pre-populated template. The auto-save guard sees
            // !rankedHasCandidates && !savedHasBallot, so no follow-up fires.
            history = emptyList()
            val freshRanked = if (tiers.isEmpty()) emptyList()
                else tiers.map { RankedItem(it, RankingKind.TIER) }
            ranked = freshRanked
            savedRanked = emptyList()
            arena = candidates
            // Update the ballot-count header above the tabs.
            onBallotSaved()
        },
    )

    fun pushHistory() {
        val next = history + (arena to ranked)
        history = if (next.size > 50) next.drop(next.size - 50) else next
    }

    fun handleClickChip(name: String) {
        pushHistory()
        arena = arena.filter { it != name }
        val tierToInsertAt = selectedTierName
        if (tierToInsertAt == null) {
            // Plain mode — append at the end of the ranking.
            ranked = ranked + RankedItem(name, RankingKind.CANDIDATE)
        } else {
            // Insert just above the selected tier marker so the new
            // candidate lands "in" that tier (above the marker, below the
            // next-higher tier if any).
            val tierIndex = ranked.indexOfFirst {
                it.kind == RankingKind.TIER && it.name == tierToInsertAt
            }
            if (tierIndex < 0) {
                // Selected tier missing somehow — fall back to append.
                ranked = ranked + RankedItem(name, RankingKind.CANDIDATE)
            } else {
                ranked = ranked.toMutableList().apply {
                    add(tierIndex, RankedItem(name, RankingKind.CANDIDATE))
                }
            }
        }
    }

    fun handleRemove(index: Int) {
        if (index !in ranked.indices) return
        // Tier markers don't have an × button, but defend in depth.
        if (ranked[index].kind == RankingKind.TIER) return
        pushHistory()
        val name = ranked[index].name
        ranked = ranked.toMutableList().apply { removeAt(index) }
        arena = arena + name
    }

    fun handleMove(i: Int, dir: Int) {
        val j = i + dir
        if (j < 0 || j >= ranked.size) return
        // Tier markers cannot move (their relative order is invariant).
        // Candidates can swap with adjacent tier markers — that's just the
        // candidate moving into a different tier.
        if (ranked[i].kind == RankingKind.TIER) return
        if (ranked[j].kind == RankingKind.TIER && ranked[i].kind == RankingKind.TIER) return
        // If the candidate would swap PAST a tier marker (e.g. swap a
        // candidate at the bottom with the bottom tier marker), the tier
        // would end up in a different relative position vs. other tiers.
        // Prevent it by refusing the swap when the neighbor is a tier and
        // the swap would push the tier past the candidate's old position.
        if (ranked[j].kind == RankingKind.TIER) {
            // The swap moves the tier to position i. Check that the tier
            // remains in the same relative position vs other tiers.
            val tierName = ranked[j].name
            val tierIndices = ranked.indices.filter { ranked[it].kind == RankingKind.TIER }
            val newOrder = tierIndices.map { idx ->
                when (idx) {
                    j -> tierName  // tier moved to position i
                    else -> ranked[idx].name
                }
            }
            // Check the tier indices after the swap form the same name order
            val updatedRanked = ranked.toMutableList().apply {
                val tmp = this[i]; this[i] = this[j]; this[j] = tmp
            }
            val newTierOrder = updatedRanked.filter { it.kind == RankingKind.TIER }.map { it.name }
            val originalTierOrder = ranked.filter { it.kind == RankingKind.TIER }.map { it.name }
            if (newTierOrder != originalTierOrder) return
        }
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
        H2 { Text("Vote") }

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
                        Text(
                            if (tiers.isNotEmpty())
                                "Candidates — click to drop into the selected tier"
                            else
                                "Candidates — click to rank in order of preference"
                        )
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
                    ranked.forEachIndexed { index, item ->
                        val isTier = item.kind == RankingKind.TIER
                        Li({
                            classes("ranked-ballot-row")
                            if (isTier) classes("ranked-ballot-tier-row")
                            if (isTier && item.name == selectedTierName) classes("ranked-ballot-tier-selected")
                            if (draggingIndex == index) classes("dragging")
                            // Tier markers are not draggable: the requirement
                            // "tiers cannot be in a different order on a ballot"
                            // means tier rows are fixed anchors.
                            if (!isTier) attr("draggable", "true")
                            attr("tabindex", "0")
                            if (!isTier) {
                                onDragStart {
                                    draggingIndex = index
                                    dragMoved = false
                                }
                            }
                            onDragOver { event ->
                                event.preventDefault()
                                val src = draggingIndex ?: return@onDragOver
                                if (src == index) return@onDragOver
                                // Only candidates can move. Tier markers stay
                                // put (the source is checked above; the target
                                // index can be a tier row — that just means
                                // the candidate slots in just before the tier).
                                if (ranked[src].kind == RankingKind.TIER) return@onDragOver
                                if (!dragMoved) {
                                    pushHistory()
                                    dragMoved = true
                                }
                                val candidateMove = ranked.toMutableList().apply {
                                    val moved = removeAt(src)
                                    add(index, moved)
                                }
                                // Validate that the move didn't reorder tier
                                // markers relative to each other (it won't,
                                // since only a candidate moved, but defend
                                // in depth — drag math is easy to get wrong).
                                val originalTierOrder = ranked.filter { it.kind == RankingKind.TIER }.map { it.name }
                                val newTierOrder = candidateMove.filter { it.kind == RankingKind.TIER }.map { it.name }
                                if (newTierOrder != originalTierOrder) return@onDragOver
                                ranked = candidateMove
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
                            // Clicking a tier marker selects it as the
                            // drop target for subsequent candidate clicks.
                            if (isTier) {
                                onClick { selectedTierName = item.name }
                            }
                        }) {
                            // Numbered position is meaningful for candidates
                            // (their rank in your preferences) but not for tier
                            // markers (which are fixed labels). Render an empty
                            // placeholder for tiers so the column alignment
                            // stays consistent across rows.
                            Span({ classes("ranked-ballot-rank-num") }) {
                                if (!isTier) {
                                    Text((index + 1).toString().padStart(2, '0'))
                                }
                            }
                            Span({ classes("ranked-ballot-row-name") }) {
                                Text(if (isTier) "▸ ${item.name}" else item.name)
                            }
                            Span({ classes("ranked-ballot-row-buttons") }) {
                                if (!isTier) {
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
                }

                if (ranked.none { it.kind == RankingKind.CANDIDATE }) {
                    P({ classes("ranked-ballot-empty-hint") }) {
                        Text(
                            if (tiers.isNotEmpty())
                                "Click a tier above to select it, then click a candidate to drop them in"
                            else
                                "Click a candidate above to begin"
                        )
                    }
                }

                Div({ classes("ranked-ballot-toolbar") }) {
                    Button({
                        classes("ranked-ballot-undo-button")
                        if (history.isEmpty()) attr("disabled", "")
                        onClick { handleUndo() }
                    }) { Text("↩ Undo") }

                    // "Remove my ballot" is only meaningful when the server
                    // actually holds a ballot. With tiers, ranked always has
                    // tier markers, so we test savedRanked (server state)
                    // rather than ranked.
                    val hasSavedBallot = savedRanked?.isNotEmpty() == true
                    if (hasSavedBallot) {
                        Button({
                            classes("ranked-ballot-remove-button")
                            if (deleteBallotAction.isLoading) attr("disabled", "")
                            onClick {
                                val confirmed = window.confirm(
                                    "Remove your ballot from \"$electionName\"? Your rankings will be cleared."
                                )
                                if (confirmed) deleteBallotAction.invoke()
                            }
                        }) {
                            Text(if (deleteBallotAction.isLoading) "Removing…" else "Remove my ballot")
                        }
                    }

                    Span({ classes("ranked-ballot-toolbar-hint") }) {
                        Text("Focus a row · ↑/↓ to nudge · Ctrl+Z to undo")
                    }
                }
            }
        }
    }
}

private data class RankedItem(val name: String, val kind: RankingKind)

@Composable
fun TallyView(
    apiClient: ApiClient,
    electionName: String,
    tiers: List<String>,
    onNavigateToPreferences: () -> Unit,
    onNavigateToStrongestPaths: () -> Unit,
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
            is FetchState.Success -> renderTally(
                state.value,
                tiers = tiers,
                onNavigateToPreferences = onNavigateToPreferences,
                onNavigateToStrongestPaths = onNavigateToStrongestPaths,
            )
        }
    }
}

/**
 * Lets the viewer toggle individual ballots on/off and watch the Winners
 * list recompute against the active subset. Local-only: no persisted state,
 * no effect on other viewers — leaving the page resets to "all on". The full
 * Tally.countBallots pipeline (Schulze strongest paths + place grouping)
 * lives in the shared `domain` module so the frontend can rerun it directly.
 *
 * Only `Ballot.Revealed` ballots are toggleable: secret ballots strip the
 * voter identity and Tally.countBallots only accepts revealed input. With the
 * current `secretBallot = false` setting, every ballot is revealed.
 *
 * The toggle does not flow into the Preferences / Strongest Paths detail
 * pages — those are separate routes that fetch their own (unfiltered) tally.
 */
@Composable
private fun renderTally(
    serverTally: Tally,
    tiers: List<String>,
    onNavigateToPreferences: () -> Unit,
    onNavigateToStrongestPaths: () -> Unit,
) {
    val revealed = serverTally.ballots.filterIsInstance<Ballot.Revealed>()
    val totalToggleable = revealed.size

    var active by remember(serverTally.electionName, totalToggleable) {
        mutableStateOf(revealed.map { it.confirmation }.toSet())
    }

    val allOn = revealed.isEmpty() || active.size == totalToggleable
    val displayTally = if (allOn) {
        serverTally
    } else {
        Tally.countBallots(
            electionName = serverTally.electionName,
            secretBallot = serverTally.secretBallot,
            candidates = serverTally.candidateNames,
            ballots = revealed.filter { it.confirmation in active },
        )
    }

    P {
        Text(
            if (allOn) {
                "Total Ballots: ${serverTally.ballots.size}"
            } else {
                "Active Ballots: ${active.size} of $totalToggleable"
            }
        )
    }

    H3 { Text("Winners") }
    if (displayTally.places.isEmpty()) {
        P { Text("No winners yet") }
    } else {
        // Per the requirement: "To be 'in a tier' means to be ranked above
        // that tier, but below the above tier if any." So for each candidate,
        // the tier they're "in" is the next tier marker AFTER them in the
        // placings (the tier they beat). Tier markers themselves render as
        // distinguishable rows.
        val tierSet = tiers.toSet()
        displayTally.places.forEachIndexed { index, place ->
            val isTier = place.candidateName in tierSet
            if (isTier) {
                P({ classes("tally-tier-marker") }) {
                    Text("▸ ${place.candidateName}")
                }
            } else {
                val tierLabel = (index + 1 until displayTally.places.size)
                    .firstOrNull { displayTally.places[it].candidateName in tierSet }
                    ?.let { displayTally.places[it].candidateName }
                P {
                    Text("${ordinal(place.rank)}: ${place.candidateName}")
                    if (tierLabel != null) {
                        Text(" (in $tierLabel)")
                    }
                }
            }
        }
    }

    if (revealed.isNotEmpty()) {
        renderBallotToggles(
            ballots = revealed,
            active = active,
            onToggle = { confirmation ->
                active = if (confirmation in active) active - confirmation else active + confirmation
            },
            onSetAll = { all ->
                active = if (all) revealed.map { it.confirmation }.toSet() else emptySet()
            },
        )
    }

    // Detail tables (preferences, strongest paths) live on their own
    // admin-style pages — they get wide quickly and don't belong inside the
    // aesthetic election shell. See docs/style-guide.md.
    Div({ classes("button-row") }) {
        Button({ onClick { onNavigateToPreferences() } }) { Text("View Preferences") }
        Button({ onClick { onNavigateToStrongestPaths() } }) { Text("View Strongest Paths") }
    }
}

@Composable
private fun renderBallotToggles(
    ballots: List<Ballot.Revealed>,
    active: Set<String>,
    onToggle: (String) -> Unit,
    onSetAll: (Boolean) -> Unit,
) {
    H3 { Text("Ballots") }
    P {
        Text(
            "Toggle ballots off to see how the Winners would change without them. " +
                "This only affects your view — nothing is saved."
        )
    }

    Div({ classes("button-row") }) {
        Button({ onClick { onSetAll(true) } }) { Text("All") }
        Button({ onClick { onSetAll(false) } }) { Text("None") }
    }

    Div({ classes("ballot-toggle-list") }) {
        ballots.forEach { ballot ->
            val isOn = ballot.confirmation in active
            Div({
                classes("ballot-toggle-item")
                if (!isOn) classes("is-off")
                onClick { onToggle(ballot.confirmation) }
            }) {
                Span({ classes("ballot-toggle-switch") }) {}
                Span({ classes("ballot-toggle-name") }) { Text(ballot.voterName) }
            }
        }
    }
}

/**
 * Detailed view of the pairwise preferences matrix as a flat 3-column table
 * (winner | strength | loser), one row per ordered pair. Lives on its own
 * admin-style page because the table grows as N(N-1) and benefits from the
 * full browser width + horizontal scroll that `.admin-container` provides.
 */
@Composable
fun ElectionPreferencesPage(
    apiClient: ApiClient,
    electionName: String,
    onBack: () -> Unit,
) {
    val tallyFetch = rememberFetchState(
        apiClient = apiClient,
        key = electionName,
        fallbackErrorMessage = "Failed to load tally",
    ) {
        apiClient.getTally(electionName)
    }

    Div({ classes("admin-container") }) {
        H1 { Text("Preferences: $electionName") }

        when (val state = tallyFetch.state) {
            FetchState.Loading -> P { Text("Loading…") }
            is FetchState.Error -> Div({ classes("error") }) { Text(state.message) }
            is FetchState.Success -> renderPreferencesTable(state.value)
        }

        Button({ onClick { onBack() } }) { Text("Back to Election") }
    }
}

/**
 * Detailed view of the strongest-path matrix. Each row is the strongest path
 * from one candidate to another, with the weakest-link strength up front and
 * the per-hop path through intermediates. Variable-width: padded to maxHops
 * so all rows have the same column count.
 */
@Composable
fun ElectionStrongestPathsPage(
    apiClient: ApiClient,
    electionName: String,
    onBack: () -> Unit,
) {
    val tallyFetch = rememberFetchState(
        apiClient = apiClient,
        key = electionName,
        fallbackErrorMessage = "Failed to load tally",
    ) {
        apiClient.getTally(electionName)
    }

    Div({ classes("admin-container") }) {
        H1 { Text("Strongest Paths: $electionName") }

        when (val state = tallyFetch.state) {
            FetchState.Loading -> P { Text("Loading…") }
            is FetchState.Error -> Div({ classes("error") }) { Text(state.message) }
            is FetchState.Success -> renderStrongestPathsTable(state.value)
        }

        Button({ onClick { onBack() } }) { Text("Back to Election") }
    }
}

@Composable
private fun renderPreferencesTable(tally: Tally) {
    val candidates = tally.candidateNames
    if (candidates.size < 2) {
        P { Text("Not enough candidates to compare.") }
        return
    }
    Table({ classes("data-table") }) {
        Thead {
            Tr {
                Th { Text("winner") }
                Th { Text("strength") }
                Th { Text("loser") }
            }
        }
        Tbody {
            candidates.indices.forEach { i ->
                candidates.indices.forEach { j ->
                    if (i != j) {
                        Tr {
                            Td { Text(candidates[i]) }
                            Td { Text(tally.preferences[i][j].strength.toString()) }
                            Td { Text(candidates[j]) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun renderStrongestPathsTable(tally: Tally) {
    val candidates = tally.candidateNames
    if (candidates.size < 2) {
        P { Text("Not enough candidates to compute paths.") }
        return
    }

    // Off-diagonal entries only — diagonal is a self-loop and carries no path info.
    val rows: List<Preference> = candidates.indices.flatMap { i ->
        candidates.indices.mapNotNull { j ->
            if (i == j) null else tally.strongestPathMatrix[i][j]
        }
    }
    val maxHops = rows.maxOf { it.strengths.size }

    Table({ classes("data-table") }) {
        Thead {
            Tr {
                Th { Text("weakest link") }
                Th { Text("id") }
                repeat(maxHops) {
                    Th { Text("strength") }
                    Th { Text("id") }
                }
            }
        }
        Tbody {
            rows.forEach { pref ->
                Tr {
                    Td { Text(pref.strength.toString()) }
                    Td { Text(pref.path[0]) }
                    pref.strengths.forEachIndexed { idx, s ->
                        Td { Text(s.toString()) }
                        Td { Text(pref.path[idx + 1]) }
                    }
                    // Pad short paths so all rows have the same column count.
                    repeat(maxHops - pref.strengths.size) {
                        Td { Text("") }
                        Td { Text("") }
                    }
                }
            }
        }
    }
}

// Teens (11th, 12th, 13th) take "th" even though they end in 1/2/3.
private fun ordinal(n: Int): String {
    val suffix = when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$n$suffix"
}
