package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.domain.Ranking
import com.seanshubin.vote.domain.RankingKind
import com.seanshubin.vote.domain.RankingSide
import com.seanshubin.vote.domain.buildBallotText
import com.seanshubin.vote.domain.projectBallot
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.*
import org.jetbrains.compose.web.dom.*

/**
 * Ranked-ballot voting UI with dual-sided support.
 *
 * Each ballot has two independent sides — PUBLIC and SECRET — that share a
 * single ballot id (confirmation) on the server. Voters can fill in either
 * or both. The two sides never influence each other's tally.
 *
 * On screen, only the currently selected side is editable; the other
 * side's state is kept in memory so toggling back doesn't lose work. The
 * top-of-view side toggle picks which side is active (PUBLIC by default).
 * The "copy other side" button stamps the other side's rankings onto the
 * current side as a starting point.
 *
 * Persistence: a single castBallot call carries both sides' rankings
 * (each [Ranking] tagged with its [RankingSide]). The save logic fires
 * when either side changes from its last-saved snapshot. If neither side
 * has any candidate ranked, the ballot is deleted entirely so the voter
 * doesn't leave a phantom row behind.
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
    // Active ballot side. Owned by VoteApp so navigating away (e.g. to the
    // tally tab) and back keeps the same side; the body.secret-mode theme
    // swap also lives at app level so it stays applied across pages.
    currentSide: RankingSide,
    onSetSide: (RankingSide) -> Unit,
    // SECRET_BALLOT feature flag. When off, the side toggle / mirror /
    // copy controls are hidden and the voter only edits PUBLIC. Any
    // existing SECRET rankings are still loaded and round-tripped on
    // save (server doesn't strip), so toggling the flag back on
    // resurfaces the data intact.
    secretBallotEnabled: Boolean,
    // Owner-set pause flag from the root pause-state poller. When true the
    // auto-save loop short-circuits and never even fires the request — every
    // drag-reorder would otherwise hit a 503, generating noise. The 503 path
    // still works as a fallback in case the pause flag flips after the check.
    isEventLogPaused: Boolean,
    // Fired only on the transitions that actually change the server-side
    // ballot count: +1 on first cast, -1 on full-clear / explicit removal.
    // In-place rank reorderings (which auto-save but don't change the count)
    // do not fire this — the parent doesn't need to repatch its header.
    onBallotCountChanged: (Int) -> Unit,
    onError: (String) -> Unit,
) {
    var publicState by remember(electionName, candidates, tiers) {
        mutableStateOf(SideState.initial(tiers))
    }
    var secretState by remember(electionName, candidates, tiers) {
        mutableStateOf(SideState.initial(tiers))
    }
    var dragSource by remember { mutableStateOf<DragSource?>(null) }
    var isInitialized by remember(electionName, candidates, tiers) { mutableStateOf(false) }
    // Transient "Copied!" toast next to the copy button.
    var copyFeedback by remember { mutableStateOf<String?>(null) }
    var copyFeedbackToken by remember { mutableStateOf(0) }
    LaunchedEffect(copyFeedbackToken) {
        if (copyFeedback != null) {
            kotlinx.coroutines.delay(2000)
            copyFeedback = null
        }
    }

    DragAutoScroll(active = dragSource != null)

    val activeState = if (currentSide == RankingSide.PUBLIC) publicState else secretState
    val otherState = if (currentSide == RankingSide.PUBLIC) secretState else publicState
    // Public→secret mirror: as long as the two sides hold identical
    // rankings, every public-side edit also lands on the secret side. The
    // moment they diverge (because the voter edited secret directly, or
    // because the loaded ballot already differed), mirroring stops; it
    // resumes if the voter manually brings them back to equal (e.g. via
    // "Copy from public side"). Mirroring is one-way — secret-side edits
    // never propagate to public. Implementation is implicit: there's no
    // stored "mirror is on" flag; the equality check happens at each edit.
    fun setActive(transform: (SideState) -> SideState) {
        if (currentSide == RankingSide.PUBLIC) {
            val wasMirrored = publicState.ranked == secretState.ranked
            val updated = transform(publicState)
            publicState = updated
            if (wasMirrored) {
                secretState = secretState.copy(
                    ranked = updated.ranked,
                    arena = updated.arena,
                    selectedTierName = updated.selectedTierName,
                )
            }
        } else {
            secretState = transform(secretState)
        }
    }

    // Pre-populate from any existing ballot, splitting by side so PUBLIC and
    // SECRET each get their own starting state. Candidates removed since the
    // ballot was cast are dropped; new candidates land in each side's arena.
    LaunchedEffect(electionName, candidates, tiers) {
        if (candidates.isEmpty() && tiers.isEmpty()) {
            publicState = SideState.initial(tiers)
            secretState = SideState.initial(tiers)
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
        fun hydrate(side: RankingSide): SideState {
            // Server returns candidate-only rankings (tier markers are
            // materialized at compute time, never stored). Drop any whose
            // candidate name is no longer a candidate or whose tier annotation
            // is no longer a configured tier.
            val knownExisting = existing
                .filter { it.side == side }
                .filter { it.rank != null && it.candidateName in candidateSet }
                .map { r -> if (r.tier != null && r.tier !in tierSet) r.copy(tier = null) else r }
                .sortedBy { it.rank }

            val newRanked: List<RankedItem> = when {
                tiers.isEmpty() -> knownExisting.map { RankedItem.Candidate(it.candidateName) }
                knownExisting.isEmpty() -> tiers.map { RankedItem.TierMarker(it) }
                else -> rankingsToRankedItems(knownExisting, tiers, side)
            }
            val rankedCandidateNames = newRanked.filterIsInstance<RankedItem.Candidate>().map { it.name }.toSet()
            return SideState(
                arena = candidates.filter { it !in rankedCandidateNames },
                ranked = newRanked,
                savedRanked = if (knownExisting.isEmpty()) emptyList() else newRanked,
                selectedTierName = tiers.firstOrNull(),
            )
        }
        publicState = hydrate(RankingSide.PUBLIC)
        secretState = hydrate(RankingSide.SECRET)
        isInitialized = true
    }

    // Auto-save when either side diverges from its last-saved snapshot. We
    // batch both sides into one castBallot call (rankings list carries side
    // tags), so the wire view of the ballot is always the union of what the
    // voter has staged across the two sides. Keyed on isEventLogPaused too
    // so a pause→unpause flip re-fires this effect and the pending save
    // goes out automatically — the voter's draft survives the pause window
    // without them touching anything.
    LaunchedEffect(publicState, secretState, isEventLogPaused) {
        val pub = publicState.savedRanked ?: return@LaunchedEffect
        val sec = secretState.savedRanked ?: return@LaunchedEffect
        val pubChanged = publicState.ranked != pub
        val secChanged = secretState.ranked != sec
        if (!pubChanged && !secChanged) return@LaunchedEffect
        val pubHasCandidates = publicState.ranked.any { it is RankedItem.Candidate }
        val secHasCandidates = secretState.ranked.any { it is RankedItem.Candidate }
        // No candidates ranked on either side = no ballot at all.
        val anyCandidates = pubHasCandidates || secHasCandidates
        val hadSavedBallot = pub.isNotEmpty() || sec.isNotEmpty()
        if (!anyCandidates && !hadSavedBallot) return@LaunchedEffect
        // Short-circuit while the owner has paused the event log. Without
        // this every drag-settle during a maintenance window would fire a
        // 503, generating noise. The drafts survive in publicState/
        // secretState; pause-lift re-runs this effect via the key above.
        if (isEventLogPaused) return@LaunchedEffect
        kotlinx.coroutines.delay(250)
        val toSavePublic = publicState.ranked
        val toSaveSecret = secretState.ranked
        try {
            if (!anyCandidates) {
                apiClient.deleteMyBallot(electionName)
                publicState = publicState.copy(savedRanked = emptyList())
                secretState = secretState.copy(savedRanked = emptyList())
                onBallotCountChanged(-1)
            } else {
                val rankings = toSavePublic.toRankings(RankingSide.PUBLIC) +
                    toSaveSecret.toRankings(RankingSide.SECRET)
                apiClient.castBallot(electionName, rankings)
                val wasFirstCast = !hadSavedBallot
                publicState = publicState.copy(savedRanked = toSavePublic)
                secretState = secretState.copy(savedRanked = toSaveSecret)
                if (wasFirstCast) onBallotCountChanged(+1)
            }
        } catch (e: MaintenancePausedException) {
            // Race fallback: pause flipped after the early-return check above
            // but before the request landed. Banner already conveys the state;
            // skip log + onError so we don't trip the frontend-errors alarm
            // or stack a red box on top of the banner.
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
            publicState = SideState.initial(tiers).copy(
                arena = candidates,
                savedRanked = emptyList(),
            )
            secretState = SideState.initial(tiers).copy(
                arena = candidates,
                savedRanked = emptyList(),
            )
            onBallotCountChanged(-1)
        },
    )

    fun handleClickChip(name: String) {
        setActive { s ->
            val tierToInsertAt = s.selectedTierName
            val newRanked = if (tierToInsertAt == null) {
                s.ranked + RankedItem.Candidate(name)
            } else {
                val tierIndex = s.ranked.indexOfFirst {
                    it is RankedItem.TierMarker && it.name == tierToInsertAt
                }
                if (tierIndex < 0) s.ranked + RankedItem.Candidate(name)
                else s.ranked.toMutableList().apply { add(tierIndex, RankedItem.Candidate(name)) }
            }
            s.copy(
                arena = s.arena.filter { it != name },
                ranked = newRanked,
            )
        }
    }

    fun handleRemove(index: Int) {
        setActive { s ->
            if (index !in s.ranked.indices) return@setActive s
            val item = s.ranked[index]
            if (item !is RankedItem.Candidate) return@setActive s
            // If the arena was empty before this removal, snap selection
            // to the tier the removed candidate had cleared.
            val nextSelected = if (s.arena.isEmpty()) {
                val clearedTier = s.ranked.asSequence()
                    .drop(index + 1)
                    .filterIsInstance<RankedItem.TierMarker>()
                    .firstOrNull()
                clearedTier?.name ?: s.selectedTierName
            } else s.selectedTierName
            s.copy(
                ranked = s.ranked.toMutableList().apply { removeAt(index) },
                arena = s.arena + item.name,
                selectedTierName = nextSelected,
            )
        }
    }

    fun handleMove(i: Int, dir: Int) {
        setActive { s ->
            val j = i + dir
            if (j < 0 || j >= s.ranked.size) return@setActive s
            if (s.ranked[i] !is RankedItem.Candidate) return@setActive s
            // Candidate-tier swap is allowed only if it preserves tier order.
            if (s.ranked[j] is RankedItem.TierMarker) {
                val updated = s.ranked.toMutableList().apply {
                    val tmp = this[i]; this[i] = this[j]; this[j] = tmp
                }
                val originalTierOrder = s.ranked.filterIsInstance<RankedItem.TierMarker>().map { it.name }
                val newTierOrder = updated.filterIsInstance<RankedItem.TierMarker>().map { it.name }
                if (newTierOrder != originalTierOrder) return@setActive s
            }
            s.copy(
                ranked = s.ranked.toMutableList().apply {
                    val tmp = this[i]; this[i] = this[j]; this[j] = tmp
                },
            )
        }
    }

    fun rankedAfterDropOnTier(s: SideState, source: DragSource, tierName: String): List<RankedItem>? {
        val markerIdx = s.ranked.indexOfFirst {
            it is RankedItem.TierMarker && it.name == tierName
        }
        if (markerIdx < 0) return null
        return when (source) {
            is DragSource.FromArena -> s.ranked.toMutableList().apply {
                add(markerIdx, RankedItem.Candidate(source.name))
            }
            is DragSource.FromRanked -> {
                val srcIdx = source.index
                if (srcIdx !in s.ranked.indices) return null
                val moving = s.ranked[srcIdx]
                if (moving !is RankedItem.Candidate) return null
                if (srcIdx == markerIdx - 1) return null
                val insertPos = if (srcIdx < markerIdx) markerIdx - 1 else markerIdx
                s.ranked.toMutableList().apply {
                    removeAt(srcIdx)
                    add(insertPos, moving)
                }
            }
        }
    }

    fun handleDropOnTier(tierName: String) {
        val src = dragSource ?: return
        setActive { s ->
            val newRanked = rankedAfterDropOnTier(s, src, tierName) ?: return@setActive s
            val newArena = if (src is DragSource.FromArena) s.arena.filter { it != src.name } else s.arena
            s.copy(ranked = newRanked, arena = newArena)
        }
    }

    fun handleDragOverRow(idx: Int) {
        setActive { s ->
            when (val src = dragSource) {
                is DragSource.FromArena -> {
                    val newRanked = s.ranked.toMutableList().apply {
                        add(idx, RankedItem.Candidate(src.name))
                    }
                    dragSource = DragSource.FromRanked(idx)
                    s.copy(
                        arena = s.arena.filter { it != src.name },
                        ranked = newRanked,
                    )
                }
                is DragSource.FromRanked -> {
                    if (src.index == idx) return@setActive s
                    if (s.ranked[src.index] !is RankedItem.Candidate) return@setActive s
                    val moved = s.ranked.toMutableList().apply {
                        val it = removeAt(src.index)
                        add(idx, it)
                    }
                    val originalTierOrder = s.ranked.filterIsInstance<RankedItem.TierMarker>().map { it.name }
                    val newTierOrder = moved.filterIsInstance<RankedItem.TierMarker>().map { it.name }
                    if (newTierOrder != originalTierOrder) return@setActive s
                    dragSource = DragSource.FromRanked(idx)
                    s.copy(ranked = moved)
                }
                null -> s
            }
        }
    }

    // Sync/copy state — surfaced next to the side toggle below so the voter
    // sees the relationship between the two sides at a glance (and can act
    // on it) right where they're about to switch.
    val otherHasCandidates = otherState.ranked.any { it is RankedItem.Candidate }
    val activeHasCandidates = activeState.ranked.any { it is RankedItem.Candidate }
    val sidesEqual = activeState.ranked == otherState.ranked
    val otherLabel = if (currentSide == RankingSide.PUBLIC) "secret" else "public"

    Div({ classes("section") }) {
        H2 { Text("Vote") }

        if (secretBallotEnabled) {
            Div({ classes("ballot-side-toggle") }) {
                Button({
                    classes("ballot-side-button")
                    if (currentSide == RankingSide.PUBLIC) classes("ballot-side-button-active")
                    onClick { onSetSide(RankingSide.PUBLIC) }
                }) { Text("Public side") }
                Button({
                    classes("ballot-side-button")
                    if (currentSide == RankingSide.SECRET) classes("ballot-side-button-active")
                    onClick { onSetSide(RankingSide.SECRET) }
                }) { Text("Secret side") }
                // Sync / copy. Hidden when both sides are empty (nothing to
                // compare yet); disabled "in sync" indicator when the two
                // sides already match; active copy button otherwise.
                if (otherHasCandidates || activeHasCandidates) {
                    if (sidesEqual) {
                        Button({
                            classes("ballot-side-copy-button")
                            classes("ballot-side-copy-button-synced")
                            attr("disabled", "")
                            title("This side mirrors the $otherLabel side")
                        }) {
                            Text("✓ In sync with $otherLabel side")
                        }
                    } else if (otherHasCandidates) {
                        Button({
                            classes("ballot-side-copy-button")
                            onClick {
                                val source = otherState.ranked
                                val sourceCandidateNames = source
                                    .filterIsInstance<RankedItem.Candidate>()
                                    .map { it.name }
                                    .toSet()
                                setActive { s ->
                                    s.copy(
                                        ranked = source,
                                        arena = candidates.filter { it !in sourceCandidateNames },
                                    )
                                }
                            }
                        }) {
                            Text("Copy from $otherLabel side")
                        }
                    }
                }
            }
        }

        Div({ classes("ballot-public-notice") }) {
            if (!secretBallotEnabled) {
                Span({ classes("ballot-public-notice-lead") }) { Text("This is a public ballot. ") }
                Text("Everyone will be able to see how you voted.")
            } else if (currentSide == RankingSide.PUBLIC) {
                Span({ classes("ballot-public-notice-lead") }) { Text("This is the public side. ") }
                Text("Your rankings on this side appear with your name in the public tally.")
            } else {
                Span({ classes("ballot-public-notice-lead") }) { Text("This is the secret side. ") }
                Text(
                    "Your rankings on this side appear in the secret tally, " +
                        "but the explanatory pages do not show your name — only auditors " +
                        "can resolve which ballot is yours."
                )
            }
        }

        if (candidates.isEmpty()) {
            P { Text("No candidates yet — the election owner can add them via the Setup tab.") }
            return@Div
        }
        if (!isInitialized) {
            P { Text("Loading…") }
            return@Div
        }

        val ranked = activeState.ranked
        val arena = activeState.arena
        val selectedTierName = activeState.selectedTierName

        // Candidate-only display rank for each row in `ranked`.
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
                    Ol({
                        classes("ranked-ballot-list")
                        onDragOver { event ->
                            event.preventDefault()
                            val s = dragSource
                            if (s !is DragSource.FromArena) return@onDragOver
                            setActive { state ->
                                val newIdx = state.ranked.size
                                dragSource = DragSource.FromRanked(newIdx)
                                state.copy(
                                    arena = state.arena.filter { it != s.name },
                                    ranked = state.ranked + RankedItem.Candidate(s.name),
                                )
                            }
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
                    val showSelection = arena.isNotEmpty()
                    chunks.forEach { chunk ->
                        Div({
                            classes("ranked-ballot-tier")
                            if (showSelection && chunk.tierName == selectedTierName) {
                                classes("ranked-ballot-tier-selected")
                            }
                            onClick {
                                setActive { it.copy(selectedTierName = chunk.tierName) }
                            }
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
                            if (currentSide == RankingSide.SECRET) {
                                if (tiers.isNotEmpty())
                                    "You haven't cast a secret ballot yet. Click a tier and a candidate, " +
                                        "or use \"Copy from public side\" to start from your public ballot."
                                else
                                    "You haven't cast a secret ballot yet. Click a candidate above to begin, " +
                                        "or use \"Copy from public side\" to start from your public ballot."
                            } else {
                                if (tiers.isNotEmpty())
                                    "Click a tier to select it then click a candidate, or drag a candidate into any tier"
                                else
                                    "Click a candidate above to begin, or drag one into the list"
                            }
                        )
                    }
                }

                Div({ classes("ranked-ballot-toolbar") }) {
                    val hasSavedBallot = activeState.savedRanked?.isNotEmpty() == true ||
                        otherState.savedRanked?.isNotEmpty() == true
                    if (hasSavedBallot) {
                        Button({
                            classes("ranked-ballot-remove-button")
                            if (deleteBallotAction.isLoading) attr("disabled", "")
                            onClick {
                                val confirmed = window.confirm(
                                    "Remove your ballot from \"$electionName\"? Both sides' rankings will be cleared."
                                )
                                if (confirmed) deleteBallotAction.invoke()
                            }
                        }) {
                            Text(if (deleteBallotAction.isLoading) "Removing…" else "Remove my ballot")
                        }
                    }

                    val canCopy = ranked.any { it is RankedItem.Candidate }
                    if (canCopy) {
                        Button({
                            classes("ranked-ballot-copy-button")
                            onClick {
                                val rankings = ranked.toRankings(currentSide)
                                val text = buildBallotText(electionName, currentUserName, rankings)
                                copyTextToClipboard(text, apiClient)
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
 * Per-side ballot editing state. Two of these (one PUBLIC, one SECRET) live
 * in the composition and the side toggle picks which one is rendered. Both
 * are loaded once on mount from a single getMyRankings call (split by side)
 * and saved together via a single castBallot call carrying both sides.
 */
private data class SideState(
    val arena: List<String>,
    val ranked: List<RankedItem>,
    // null = not yet loaded; emptyList() = server holds no ballot on this side;
    // non-empty = exact list last saved. Kept separate from `ranked` so the
    // auto-save effect can tell intermediate user edits from the initial load.
    val savedRanked: List<RankedItem>?,
    val selectedTierName: String?,
) {
    companion object {
        // Fresh template: tier markers laid out so the voter can drop
        // candidates into them. With no tiers configured, just an empty list.
        fun initial(tiers: List<String>): SideState = SideState(
            arena = emptyList(),
            ranked = if (tiers.isEmpty()) emptyList() else tiers.map { RankedItem.TierMarker(it) },
            savedRanked = null,
            selectedTierName = tiers.firstOrNull(),
        )
    }
}

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

internal sealed interface RankedItem {
    val name: String

    data class Candidate(override val name: String) : RankedItem
    data class TierMarker(override val name: String) : RankedItem
}

/**
 * Serialize the in-memory ranked list (with [RankedItem.TierMarker] entries
 * for the UI) into the storage form (candidate-only rankings tagged with
 * [side]). For each candidate we look forward in the list to the next tier
 * marker — that marker's name is the highest tier this candidate cleared.
 */
internal fun List<RankedItem>.toRankings(side: RankingSide): List<Ranking> {
    val result = mutableListOf<Ranking>()
    var rank = 0
    forEachIndexed { i, item ->
        if (item is RankedItem.Candidate) {
            rank++
            val tier = subList(i + 1, size)
                .firstOrNull { it is RankedItem.TierMarker }
                ?.name
            result += Ranking(item.name, rank, RankingKind.CANDIDATE, tier, side)
        }
    }
    return result
}

/**
 * Inverse of [toRankings] for the load path. Takes candidate-only rankings
 * (each tagged with its [side]) and reconstructs the in-memory list with
 * tier markers in the right positions. Tags the synthetic markers with
 * [side] so [projectBallot]'s same-side invariant holds.
 */
internal fun rankingsToRankedItems(
    rankings: List<Ranking>,
    electionTiers: List<String>,
    side: RankingSide,
): List<RankedItem> {
    val projected = projectBallot(rankings, electionTiers, side)
    return projected.map { r ->
        when (r.kind) {
            RankingKind.CANDIDATE -> RankedItem.Candidate(r.candidateName)
            RankingKind.TIER -> RankedItem.TierMarker(r.candidateName)
        }
    }
}

internal sealed interface DragSource {
    data class FromArena(val name: String) : DragSource
    data class FromRanked(val index: Int) : DragSource
}

private data class TierChunk(
    val tierName: String,
    val tierIndex: Int,
    val candidateIndices: List<Int>,
)

@Composable
private fun DragAutoScroll(active: Boolean) {
    DisposableEffect(active) {
        if (!active) {
            onDispose { }
        } else {
            val edgeZone = 60.0
            val maxSpeed = 20.0
            var pointerY = -1.0
            var rafId = 0

            fun step(timestamp: Double) {
                val viewportH = window.innerHeight.toDouble()
                val delta = when {
                    pointerY < 0 -> 0.0
                    pointerY < edgeZone ->
                        -maxSpeed * ((edgeZone - pointerY) / edgeZone).coerceIn(0.0, 1.0)
                    pointerY > viewportH - edgeZone ->
                        maxSpeed * ((pointerY - (viewportH - edgeZone)) / edgeZone).coerceIn(0.0, 1.0)
                    else -> 0.0
                }
                if (delta != 0.0) window.scrollBy(0.0, delta)
                rafId = window.requestAnimationFrame(::step)
            }

            val onDragOver: (org.w3c.dom.events.Event) -> Unit = { event ->
                pointerY = (event as org.w3c.dom.events.MouseEvent).clientY.toDouble()
            }
            window.addEventListener("dragover", onDragOver)
            rafId = window.requestAnimationFrame(::step)

            onDispose {
                window.cancelAnimationFrame(rafId)
                window.removeEventListener("dragover", onDragOver)
            }
        }
    }
}

internal fun copyTextToClipboard(text: String, apiClient: ApiClient) {
    try {
        val clipboard = window.navigator.asDynamic().clipboard
        if (clipboard != null && clipboard != js("undefined")) {
            clipboard.writeText(text)
        }
    } catch (e: Throwable) {
        apiClient.logErrorToServer(e)
    }
}
