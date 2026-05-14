package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.domain.Ranking
import com.seanshubin.vote.domain.RankingKind
import com.seanshubin.vote.domain.buildBallotText
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.*
import org.jetbrains.compose.web.dom.*

/**
 * Ranked-ballot voting UI. Candidates start in the "arena" pool (3-column
 * grid). Click a chip to rank it, or drag it directly into the list / a tier.
 * Drag a row to reorder live. ↑/↓ keys nudge the focused row. × returns a row
 * to the arena.
 *
 * There is no submit step: every change to the ranked list is persisted
 * immediately via castBallot, so the on-screen ordering is always the
 * voter's current ballot. The wire format sends `List<Ranking>` where each
 * ranked entry gets `rank = position + 1` and a `kind` tag (CANDIDATE or
 * TIER). Unranked candidates are simply omitted; the tally only counts
 * pairwise preferences between candidates the voter actually ranked, so
 * leaving a candidate off a ballot means "I express no opinion about
 * this candidate" rather than "I rank this candidate last."
 *
 * Tier mode — the threshold metaphor. Tier markers are virtual candidates
 * the voter ranks alongside the real ones; ranking a candidate ahead of a
 * tier marker means "this candidate clears that tier." A candidate's tier
 * is the highest tier they cleared. The ballot is pre-populated with the
 * tier markers in declared order. Clicking a tier card selects it as the
 * default target for click-to-rank. The selection highlight only shows
 * while the arena still has unranked chips, since once everything is ranked
 * the selection has nowhere to be applied. When the arena empties to non-
 * empty via a candidate removal, the selection snaps to the tier that
 * candidate had cleared, so putting them back in the same tier is one
 * click. Tier markers are not draggable and have no remove/move buttons —
 * their relative order is invariant. Clearing every candidate row (only
 * tier markers remain) is treated as "no ballot" and deletes the ballot
 * server-side.
 *
 * The on-screen rank number ignores tier markers — voters see 01, 02, 03
 * over their candidates regardless of how the tier markers are interleaved.
 */
@Composable
fun VotingView(
    apiClient: ApiClient,
    electionName: String,
    candidates: List<String>,
    tiers: List<String>,
    // Used as the "Sean's Rankings" header when the voter copies their ballot
    // as plain text. Null falls back to "Your Rankings" so the feature still
    // works in any context where the username isn't threaded through.
    currentUserName: String?,
    // Fired only on the transitions that actually change the server-side
    // ballot count: +1 on first cast, -1 on full-clear / explicit removal.
    // In-place rank reorderings (which auto-save but don't change the count)
    // do not fire this — the parent doesn't need to repatch its header.
    onBallotCountChanged: (Int) -> Unit,
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
    var dragSource by remember { mutableStateOf<DragSource?>(null) }
    var isInitialized by remember(electionName, candidates, tiers) { mutableStateOf(false) }
    // Transient "Copied!" toast next to the copy button. Cleared after a
    // short delay; copyFeedbackToken bumps each click so a second click
    // while the first toast is still up restarts the timer rather than
    // letting the older coroutine clear the message early.
    var copyFeedback by remember { mutableStateOf<String?>(null) }
    var copyFeedbackToken by remember { mutableStateOf(0) }
    LaunchedEffect(copyFeedbackToken) {
        if (copyFeedback != null) {
            kotlinx.coroutines.delay(2000)
            copyFeedback = null
        }
    }

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
                .map { RankedItem.Candidate(it.candidateName) }
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
                knownExisting.map { it.toRankedItem() }
            } else {
                tiers.map { RankedItem.TierMarker(it) }
            }
        }
        ranked = newRanked
        // savedRanked reflects the SERVER state. If the server had no ballot
        // (existing was empty), savedRanked is empty even when ranked has
        // tier markers — those markers are part of the on-screen draft, not
        // a ballot the user has cast.
        savedRanked = if (knownExisting.isEmpty()) emptyList() else newRanked
        val rankedCandidates = newRanked.filterIsInstance<RankedItem.Candidate>().map { it.name }.toSet()
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
        val rankedHasCandidates = ranked.any { it is RankedItem.Candidate }
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
                onBallotCountChanged(-1)
            } else {
                val rankings = toSave.mapIndexed { i, item -> item.toRanking(i + 1) }
                apiClient.castBallot(electionName, rankings)
                val wasFirstCast = !savedHasBallot
                savedRanked = toSave
                if (wasFirstCast) onBallotCountChanged(+1)
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
            val freshRanked = if (tiers.isEmpty()) emptyList()
                else tiers.map { RankedItem.TierMarker(it) }
            ranked = freshRanked
            savedRanked = emptyList()
            arena = candidates
            // Update the ballot-count header above the tabs.
            onBallotCountChanged(-1)
        },
    )

    fun handleClickChip(name: String) {
        arena = arena.filter { it != name }
        val tierToInsertAt = selectedTierName
        if (tierToInsertAt == null) {
            // Plain mode — append at the end of the ranking.
            ranked = ranked + RankedItem.Candidate(name)
        } else {
            // The candidate clears the selected tier — insert directly
            // ahead of that tier marker so the marker is the highest tier
            // they cleared, and any harder tier above stays uncleared.
            val tierIndex = ranked.indexOfFirst {
                it is RankedItem.TierMarker && it.name == tierToInsertAt
            }
            if (tierIndex < 0) {
                // Selected tier missing somehow — fall back to append.
                ranked = ranked + RankedItem.Candidate(name)
            } else {
                ranked = ranked.toMutableList().apply {
                    add(tierIndex, RankedItem.Candidate(name))
                }
            }
        }
    }

    fun handleRemove(index: Int) {
        if (index !in ranked.indices) return
        val item = ranked[index]
        if (item !is RankedItem.Candidate) return
        // If the arena was empty before this removal, there's no visible
        // tier selection (the highlight is suppressed when arena is empty).
        // Snap the selection to the tier the removed candidate had cleared
        // — i.e. the next tier marker after them in `ranked` — so putting
        // them back is one click. If they cleared no tier (no marker after
        // them), leave the prior selection alone.
        if (arena.isEmpty()) {
            val clearedTier = ranked.asSequence()
                .drop(index + 1)
                .filterIsInstance<RankedItem.TierMarker>()
                .firstOrNull()
            if (clearedTier != null) selectedTierName = clearedTier.name
        }
        ranked = ranked.toMutableList().apply { removeAt(index) }
        arena = arena + item.name
    }

    fun handleMove(i: Int, dir: Int) {
        val j = i + dir
        if (j < 0 || j >= ranked.size) return
        if (ranked[i] !is RankedItem.Candidate) return
        // Candidate can swap with an adjacent tier marker — that's just the
        // candidate moving into a different tier — but only if doing so
        // preserves the *relative* order of tier markers. (Bumping the
        // bottom tier past the candidate at position 0 would invert the
        // tier order, for example.)
        if (ranked[j] is RankedItem.TierMarker) {
            val updated = ranked.toMutableList().apply {
                val tmp = this[i]; this[i] = this[j]; this[j] = tmp
            }
            val originalTierOrder = ranked.filterIsInstance<RankedItem.TierMarker>().map { it.name }
            val newTierOrder = updated.filterIsInstance<RankedItem.TierMarker>().map { it.name }
            if (newTierOrder != originalTierOrder) return
        }
        ranked = ranked.toMutableList().apply {
            val tmp = this[i]; this[i] = this[j]; this[j] = tmp
        }
    }

    // Returns the new `ranked` list after dropping `source` so the candidate
    // clears `tierName` and no harder tier — i.e. positioned immediately
    // ahead of that tier marker. null means the drop is a no-op or invalid;
    // caller should bail without touching state. Used by the tier-card-level
    // drop target.
    fun rankedAfterDropOnTier(source: DragSource, tierName: String): List<RankedItem>? {
        val markerIdx = ranked.indexOfFirst {
            it is RankedItem.TierMarker && it.name == tierName
        }
        if (markerIdx < 0) return null
        return when (source) {
            is DragSource.FromArena -> ranked.toMutableList().apply {
                add(markerIdx, RankedItem.Candidate(source.name))
            }
            is DragSource.FromRanked -> {
                val srcIdx = source.index
                if (srcIdx !in ranked.indices) return null
                val moving = ranked[srcIdx]
                if (moving !is RankedItem.Candidate) return null
                if (srcIdx == markerIdx - 1) return null
                val insertPos = if (srcIdx < markerIdx) markerIdx - 1 else markerIdx
                ranked.toMutableList().apply {
                    removeAt(srcIdx)
                    add(insertPos, moving)
                }
            }
        }
    }

    fun handleDropOnTier(tierName: String) {
        val src = dragSource ?: return
        val newRanked = rankedAfterDropOnTier(src, tierName) ?: return
        if (src is DragSource.FromArena) {
            arena = arena.filter { it != src.name }
        }
        ranked = newRanked
    }

    // While dragging an arena chip over a row, the chip is inserted in front
    // of that row and the source becomes a FromRanked drag — subsequent
    // dragOver ticks reorder rather than re-insert. While dragging a ranked
    // row over another, swap candidate-only (rejecting moves that would
    // permute tier markers' relative order).
    fun handleDragOverRow(idx: Int) {
        when (val s = dragSource) {
            is DragSource.FromArena -> {
                arena = arena.filter { it != s.name }
                ranked = ranked.toMutableList().apply {
                    add(idx, RankedItem.Candidate(s.name))
                }
                dragSource = DragSource.FromRanked(idx)
            }
            is DragSource.FromRanked -> {
                if (s.index == idx) return
                if (ranked[s.index] !is RankedItem.Candidate) return
                val moved = ranked.toMutableList().apply {
                    val it = removeAt(s.index)
                    add(idx, it)
                }
                val originalTierOrder = ranked.filterIsInstance<RankedItem.TierMarker>().map { it.name }
                val newTierOrder = moved.filterIsInstance<RankedItem.TierMarker>().map { it.name }
                if (newTierOrder != originalTierOrder) return
                ranked = moved
                dragSource = DragSource.FromRanked(idx)
            }
            null -> Unit
        }
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

        // Candidate-only display rank for each row in `ranked`. Tier markers
        // hold ranked positions but the voter shouldn't see "01, ▸ Tier1, 03,
        // 04" — they should see "01, ▸ Tier1, 02, 03". Computed once per
        // recomposition, keyed off the row index.
        val candidateRankByIndex = IntArray(ranked.size).also { arr ->
            var n = 0
            ranked.forEachIndexed { i, item ->
                if (item is RankedItem.Candidate) {
                    n += 1
                    arr[i] = n
                }
            }
        }

        Div({ classes("ranked-ballot") }) {
            if (arena.isNotEmpty()) {
                Div({ classes("ranked-ballot-arena") }) {
                    Span({ classes("ranked-ballot-arena-label") }) {
                        Text(
                            if (tiers.isNotEmpty())
                                "Candidates — click to drop into the selected tier, or drag into any tier"
                            else
                                "Candidates — click to rank, or drag into the list"
                        )
                    }
                    Div({ classes("ranked-ballot-arena-grid") }) {
                        arena.forEach { name ->
                            Button({
                                classes("ranked-ballot-chip")
                                attr("draggable", "true")
                                onDragStart {
                                    dragSource = DragSource.FromArena(name)
                                }
                                onDragEnd {
                                    dragSource = null
                                }
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
                }

                if (tiers.isEmpty()) {
                    // Plain mode: flat ordered list of candidate rows. The
                    // Ol itself is a drop target so dropping below all rows
                    // (or onto an empty list) appends to the ranking.
                    Ol({
                        classes("ranked-ballot-list")
                        onDragOver { event ->
                            event.preventDefault()
                            val s = dragSource
                            if (s !is DragSource.FromArena) return@onDragOver
                            arena = arena.filter { it != s.name }
                            val newIdx = ranked.size
                            ranked = ranked + RankedItem.Candidate(s.name)
                            dragSource = DragSource.FromRanked(newIdx)
                        }
                        onDrop { event ->
                            event.preventDefault()
                            dragSource = null
                        }
                    }) {
                        ranked.indices.forEach { idx ->
                            RankedRow(
                                idx = idx,
                                item = ranked[idx] as RankedItem.Candidate,
                                displayRank = candidateRankByIndex[idx],
                                isFirst = idx == 0,
                                isLast = idx == ranked.size - 1,
                                isDragging = dragSource is DragSource.FromRanked && (dragSource as DragSource.FromRanked).index == idx,
                                onDragStart = { dragSource = DragSource.FromRanked(idx) },
                                onDragOver = { handleDragOverRow(idx) },
                                onDrop = { dragSource = null },
                                onDragEnd = { dragSource = null },
                                onMove = ::handleMove,
                                onRemove = ::handleRemove,
                            )
                        }
                    }
                } else {
                    // Tier mode: each tier is its own card showing the
                    // candidates that cleared it (the run between the
                    // previous tier marker and this one). The whole card is
                    // the click target to select it, AND a drop target —
                    // dropping anywhere inside (other than on a candidate
                    // row, which has its own row-position drop logic) lands
                    // the dragged item just ahead of this tier's marker so
                    // it clears this tier.
                    val chunks = buildList {
                        var bufferStart = 0
                        ranked.forEachIndexed { idx, item ->
                            if (item is RankedItem.TierMarker) {
                                val cands = (bufferStart until idx).toList()
                                add(TierChunk(item.name, idx, cands))
                                bufferStart = idx + 1
                            }
                        }
                    }
                    // Selection only matters while there are still chips in
                    // the arena; once everything is ranked, every tier card
                    // looks the same.
                    val showSelection = arena.isNotEmpty()
                    chunks.forEach { chunk ->
                        Div({
                            classes("ranked-ballot-tier")
                            if (showSelection && chunk.tierName == selectedTierName) {
                                classes("ranked-ballot-tier-selected")
                            }
                            onClick { selectedTierName = chunk.tierName }
                            onDragOver { event -> event.preventDefault() }
                            onDrop { event ->
                                event.preventDefault()
                                handleDropOnTier(chunk.tierName)
                                dragSource = null
                            }
                        }) {
                            Span({ classes("ranked-ballot-tier-title") }) { Text(chunk.tierName) }

                            if (chunk.candidateIndices.isEmpty()) {
                                Div({ classes("ranked-ballot-tier-empty") }) {
                                    Text("(empty — drop a candidate here)")
                                }
                            } else {
                                Ol({ classes("ranked-ballot-list") }) {
                                    chunk.candidateIndices.forEach { idx ->
                                        RankedRow(
                                            idx = idx,
                                            item = ranked[idx] as RankedItem.Candidate,
                                            displayRank = candidateRankByIndex[idx],
                                            isFirst = idx == 0,
                                            isLast = idx == ranked.size - 1,
                                            isDragging = dragSource is DragSource.FromRanked && (dragSource as DragSource.FromRanked).index == idx,
                                            onDragStart = { dragSource = DragSource.FromRanked(idx) },
                                            onDragOver = { handleDragOverRow(idx) },
                                            onDrop = { dragSource = null },
                                            onDragEnd = { dragSource = null },
                                            onMove = ::handleMove,
                                            onRemove = ::handleRemove,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (ranked.none { it is RankedItem.Candidate }) {
                    P({ classes("ranked-ballot-empty-hint") }) {
                        Text(
                            if (tiers.isNotEmpty())
                                "Click a tier to select it then click a candidate, or drag a candidate into any tier"
                            else
                                "Click a candidate above to begin, or drag one into the list"
                        )
                    }
                }

                Div({ classes("ranked-ballot-toolbar") }) {
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

                    // Copy-as-text uses the on-screen `ranked` (not the saved
                    // server state) so the user can copy whatever they're
                    // currently looking at, even mid-edit before the debounce
                    // settles. Disabled when no candidates are ranked since
                    // there's nothing meaningful to copy in that case.
                    val canCopy = ranked.any { it is RankedItem.Candidate }
                    if (canCopy) {
                        Button({
                            classes("ranked-ballot-copy-button")
                            onClick {
                                val rankings = ranked.mapIndexed { i, item -> item.toRanking(i + 1) }
                                val text = buildBallotText(electionName, currentUserName, rankings)
                                copyTextToClipboard(text)
                                copyFeedback = "Copied!"
                                copyFeedbackToken = copyFeedbackToken + 1
                            }
                        }) {
                            Text("Copy ballot as text")
                        }
                    }

                    if (copyFeedback != null) {
                        Span({ classes("ranked-ballot-copy-feedback") }) {
                            Text(copyFeedback!!)
                        }
                    }

                    Span({ classes("ranked-ballot-toolbar-hint") }) {
                        Text("Focus a row · ↑/↓ to nudge")
                    }
                }
            }
        }
    }
}

/**
 * One row of the ranked-ballot list. Pulled out so [VotingView]'s body
 * doesn't carry the ~100-line drag/key/button machinery inline. Callbacks
 * receive the row's index back so the caller can mutate ranked-list state
 * without this composable needing to know about it.
 */
@Composable
private fun RankedRow(
    idx: Int,
    item: RankedItem.Candidate,
    displayRank: Int,
    isFirst: Boolean,
    isLast: Boolean,
    isDragging: Boolean,
    onDragStart: () -> Unit,
    onDragOver: () -> Unit,
    onDrop: () -> Unit,
    onDragEnd: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    Li({
        classes("ranked-ballot-row")
        if (isDragging) classes("dragging")
        attr("draggable", "true")
        attr("tabindex", "0")
        onDragStart { onDragStart() }
        onDragOver { event ->
            event.preventDefault()
            onDragOver()
        }
        onDrop { event ->
            event.preventDefault()
            onDrop()
        }
        onDragEnd { onDragEnd() }
        onKeyDown { event ->
            when (event.key) {
                "ArrowUp" -> {
                    event.preventDefault()
                    onMove(idx, -1)
                }
                "ArrowDown" -> {
                    event.preventDefault()
                    onMove(idx, 1)
                }
            }
        }
    }) {
        Span({ classes("ranked-ballot-rank-num") }) {
            Text(displayRank.toString().padStart(2, '0'))
        }
        Span({ classes("ranked-ballot-row-name") }) { Text(item.name) }
        Span({ classes("ranked-ballot-row-buttons") }) {
            Button({
                classes("ranked-ballot-row-button")
                title("Move up")
                if (isFirst) attr("disabled", "")
                onClick { event ->
                    event.stopPropagation()
                    onMove(idx, -1)
                }
            }) { Text("↑") }
            Button({
                classes("ranked-ballot-row-button")
                title("Move down")
                if (isLast) attr("disabled", "")
                onClick { event ->
                    event.stopPropagation()
                    onMove(idx, 1)
                }
            }) { Text("↓") }
            Button({
                classes("ranked-ballot-row-button")
                classes("ranked-ballot-row-remove")
                title("Return to candidates")
                onClick { event ->
                    event.stopPropagation()
                    onRemove(idx)
                }
            }) { Text("×") }
        }
    }
}

/**
 * One entry in the on-screen ranked list. Tier markers and candidates coexist
 * as siblings in `ranked`; making this a sealed hierarchy (rather than a flat
 * record + `kind` field) forces every consumer through an exhaustive `when`,
 * which is the language feature we lean on instead of remembering to test
 * `kind == TIER` in every place that treats them differently.
 */
internal sealed interface RankedItem {
    val name: String

    data class Candidate(override val name: String) : RankedItem
    data class TierMarker(override val name: String) : RankedItem
}

internal fun RankedItem.toRanking(rank: Int): Ranking = when (this) {
    is RankedItem.Candidate -> Ranking(name, rank, RankingKind.CANDIDATE)
    is RankedItem.TierMarker -> Ranking(name, rank, RankingKind.TIER)
}

internal fun Ranking.toRankedItem(): RankedItem = when (kind) {
    RankingKind.CANDIDATE -> RankedItem.Candidate(candidateName)
    RankingKind.TIER -> RankedItem.TierMarker(candidateName)
}

/**
 * Source of an in-progress drag. Drop targets dispatch on this so a chip
 * still in the arena and a row already in the list do the right thing
 * without the drop handler having to reach into either collection itself.
 */
internal sealed interface DragSource {
    data class FromArena(val name: String) : DragSource
    data class FromRanked(val index: Int) : DragSource
}

/**
 * One tier's slice of the ranked list: the tier's name, the index of the
 * tier marker in `ranked` (used as the drop position for empty tiers), and
 * the indices of the candidates that cleared this tier — the rows between
 * the previous tier marker (or the start of the list) and this one. A
 * candidate clears tier T iff they sit ahead of T's marker on the ballot.
 */
private data class TierChunk(
    val tierName: String,
    val tierIndex: Int,
    val candidateIndices: List<Int>,
)

/**
 * Best-effort write to the system clipboard via the async Clipboard API.
 * Failures (permission denied, unavailable in insecure contexts) are
 * surfaced as a console error rather than thrown — the caller has already
 * shown a "Copied!" toast and there's no graceful in-UI recovery here.
 */
private fun copyTextToClipboard(text: String) {
    try {
        val clipboard = window.navigator.asDynamic().clipboard
        if (clipboard != null && clipboard != js("undefined")) {
            clipboard.writeText(text)
        }
    } catch (e: Throwable) {
        console.error("Clipboard write failed:", e)
    }
}
