package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.domain.*
import com.seanshubin.vote.domain.Ranking.Companion.prefers
import kotlinx.browser.window
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    var currentView by rememberHashTab("setup", setOf("setup", "vote", "tally"))

    // Two independent fetches:
    //   shellFetch — election + candidates, in parallel; gates the page UI.
    //   tallyFetch — the heavier endpoint (server runs Schulze + serializes
    //                ballots/preferences/strongest-paths), kept off the
    //                page's critical path so the shell renders the moment
    //                the two light fetches return. The tally streams in on
    //                its own track and the Results tab consumes it as a
    //                FetchState (Loading → Success). Cached for stale-while-
    //                revalidate so revisits and post-mutation refreshes
    //                paint instantly with the prior value while the new one
    //                arrives.
    val shellFetch = rememberFetchState(
        apiClient = apiClient,
        key = electionName,
        fallbackErrorMessage = "Failed to load election",
    ) {
        coroutineScope {
            val election = async { apiClient.getElection(electionName) }
            val candidates = async { apiClient.listCandidates(electionName) }
            election.await() to candidates.await()
        }
    }
    val tallyFetch = rememberCachedFetchState(
        apiClient = apiClient,
        cacheKey = "tally:$electionName",
        key = electionName,
        fallbackErrorMessage = "Failed to load tally",
    ) {
        apiClient.getTally(electionName)
    }

    val deleteAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to delete election",
        onError = { errorMessage = it },
        action = {
            apiClient.deleteElection(electionName)
            // The deleted election would otherwise linger in the cached
            // Elections list — invalidate so the redirect lands on a fresh fetch.
            PageCache.invalidate("elections")
            onElectionDeleted()
        },
    )

    val shellState = shellFetch.state
    // Hold onto the most recent shell Success so a reload doesn't unmount
    // VotingView. The patch callbacks below mutate this directly, but the
    // pattern also covers the case where shellFetch.reload() is called
    // explicitly in the future. (The original problem this guarded against:
    // a first-cast reload would tear down VotingView's remember state and
    // make the just-clicked candidate "snap back" into the arena.)
    var lastLoadedShell by remember(electionName) {
        mutableStateOf<Pair<ElectionDetail, List<String>>?>(null)
    }
    LaunchedEffect(shellState) {
        (shellState as? FetchState.Success)?.value?.let { lastLoadedShell = it }
    }
    val loadedElection = lastLoadedShell?.first

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

        // Setup is restricted to the election owner or any user with role >=
        // ADMIN. Same gate as the Delete button. Backend re-checks; this just
        // hides the UI surface so non-privileged voters don't see edit fields
        // they couldn't successfully save anyway.
        val canSetup = loadedElection?.let { e ->
            e.ownerName == currentUserName ||
                (currentRole != null && currentRole >= Role.ADMIN)
        } ?: false

        // Snap a deep-linked or stale `#setup` hash back to the vote tab when
        // the viewer isn't allowed to see it. Runs once `canSetup` is known
        // (post-load) so we don't bounce away while loading.
        LaunchedEffect(canSetup, currentView) {
            if (!canSetup && currentView == "setup") {
                currentView = "vote"
            }
        }

        when {
            lastLoadedShell == null && shellState is FetchState.Error ->
                Div({ classes("error") }) { Text(shellState.message) }
            lastLoadedShell == null ->
                P { Text("Loading…") }
            else -> {
                val (_, candidates) = lastLoadedShell!!

                // Tabs (no more Details — the header above covers it).
                Div({ classes("tabs") }) {
                    if (canSetup) {
                        Button({ onClick { currentView = "setup" } }) { Text("Setup") }
                    }
                    Button({ onClick { currentView = "vote" } }) { Text("Vote") }
                    Button({ onClick { currentView = "tally" } }) { Text("Results") }
                }

                // Defense in depth: if the LaunchedEffect above hasn't fired
                // yet (first frame after load), treat "setup" as "vote" for
                // viewers who can't setup so they never see the edit pane.
                val effectiveView = if (currentView == "setup" && !canSetup) "vote" else currentView
                when (effectiveView) {
                    "setup" -> ElectionSetupView(
                        apiClient = apiClient,
                        electionName = electionName,
                        existingDescription = loadedElection?.description ?: "",
                        existingCandidates = candidates,
                        existingTiers = loadedElection?.tiers ?: emptyList(),
                        ballotsExist = (loadedElection?.ballotCount ?: 0) > 0,
                        onDescriptionSaved = { newDescription ->
                            successMessage = "Description saved"
                            errorMessage = null
                            // Patch in place — description is independent of
                            // candidates and tally, no refresh needed.
                            lastLoadedShell = lastLoadedShell?.let { (e, c) ->
                                e.copy(description = newDescription) to c
                            }
                        },
                        onCandidatesSaved = { newCandidates ->
                            successMessage = "Candidates saved"
                            errorMessage = null
                            // Patch the candidate list and the header count
                            // immediately so the UI feels instant. The tally
                            // is now stale (preferences matrix is keyed on
                            // the candidate set) so reload it; the cached
                            // helper keeps the prior tally visible during
                            // the refetch, so the Results tab does not flash
                            // to Loading.
                            lastLoadedShell = lastLoadedShell?.let { (e, _) ->
                                e.copy(candidateCount = newCandidates.size) to newCandidates
                            }
                            tallyFetch.reload()
                        },
                        onTiersSaved = { newTiers ->
                            successMessage = "Tiers saved"
                            errorMessage = null
                            // Tier markers participate in strongest-path
                            // calculations, so the tally needs the same
                            // refresh treatment as candidates. (Tiers are
                            // locked while ballots exist, so in practice
                            // the tally will be empty here, but the refresh
                            // keeps the invariant honest.)
                            lastLoadedShell = lastLoadedShell?.let { (e, c) ->
                                e.copy(tiers = newTiers) to c
                            }
                            tallyFetch.reload()
                        },
                        onError = { errorMessage = it },
                    )
                    "vote" -> VotingView(
                        apiClient = apiClient,
                        electionName = electionName,
                        candidates = candidates,
                        tiers = loadedElection?.tiers ?: emptyList(),
                        // Patch the header count locally and refresh the
                        // tally — the cached helper avoids the Loading
                        // flash on the Results tab.
                        onBallotCountChanged = { delta ->
                            lastLoadedShell = lastLoadedShell?.let { (e, c) ->
                                e.copy(ballotCount = e.ballotCount + delta) to c
                            }
                            // The user's ballotsCastCount on the Home activity
                            // panel just changed — drop the cached entry so a
                            // navigation back to Home shows the updated count.
                            currentUserName?.let { PageCache.invalidate("userActivity:$it") }
                            tallyFetch.reload()
                        },
                        onError = { errorMessage = it },
                    )
                    "tally" -> TallyView(
                        state = tallyFetch.state,
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
    existingDescription: String,
    existingCandidates: List<String>,
    existingTiers: List<String>,
    ballotsExist: Boolean,
    onDescriptionSaved: (String) -> Unit,
    onCandidatesSaved: (List<String>) -> Unit,
    onTiersSaved: (List<String>) -> Unit,
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

                // Single source of row rendering used by both the plain (no
                // tiers) and tier modes — captures the surrounding state so
                // we don't have to thread a dozen parameters through.
                val renderCandidateRow: @Composable (Int) -> Unit = { idx ->
                    val item = ranked[idx] as RankedItem.Candidate
                    val displayRank = candidateRankByIndex[idx]
                    Li({
                        classes("ranked-ballot-row")
                        val src = dragSource
                        if (src is DragSource.FromRanked && src.index == idx) classes("dragging")
                        attr("draggable", "true")
                        attr("tabindex", "0")
                        onDragStart {
                            dragSource = DragSource.FromRanked(idx)
                        }
                        onDragOver { event ->
                            event.preventDefault()
                            when (val s = dragSource) {
                                is DragSource.FromArena -> {
                                    // Insert the dragged arena chip in front
                                    // of this row, then convert the source
                                    // into a FromRanked so further dragOver
                                    // ticks reorder rather than re-insert.
                                    arena = arena.filter { it != s.name }
                                    ranked = ranked.toMutableList().apply {
                                        add(idx, RankedItem.Candidate(s.name))
                                    }
                                    dragSource = DragSource.FromRanked(idx)
                                }
                                is DragSource.FromRanked -> {
                                    if (s.index == idx) return@onDragOver
                                    if (ranked[s.index] !is RankedItem.Candidate) return@onDragOver
                                    val moved = ranked.toMutableList().apply {
                                        val it = removeAt(s.index)
                                        add(idx, it)
                                    }
                                    // A candidate move must not permute tier
                                    // markers — reject if the relative tier
                                    // order would change.
                                    val originalTierOrder = ranked.filterIsInstance<RankedItem.TierMarker>().map { it.name }
                                    val newTierOrder = moved.filterIsInstance<RankedItem.TierMarker>().map { it.name }
                                    if (newTierOrder != originalTierOrder) return@onDragOver
                                    ranked = moved
                                    dragSource = DragSource.FromRanked(idx)
                                }
                                null -> Unit
                            }
                        }
                        onDrop { event ->
                            event.preventDefault()
                            dragSource = null
                        }
                        onDragEnd {
                            dragSource = null
                        }
                        onKeyDown { event ->
                            when (event.key) {
                                "ArrowUp" -> {
                                    event.preventDefault()
                                    handleMove(idx, -1)
                                }
                                "ArrowDown" -> {
                                    event.preventDefault()
                                    handleMove(idx, 1)
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
                                if (idx == 0) attr("disabled", "")
                                onClick { event ->
                                    event.stopPropagation()
                                    handleMove(idx, -1)
                                }
                            }) { Text("↑") }
                            Button({
                                classes("ranked-ballot-row-button")
                                title("Move down")
                                if (idx == ranked.size - 1) attr("disabled", "")
                                onClick { event ->
                                    event.stopPropagation()
                                    handleMove(idx, 1)
                                }
                            }) { Text("↓") }
                            Button({
                                classes("ranked-ballot-row-button")
                                classes("ranked-ballot-row-remove")
                                title("Return to candidates")
                                onClick { event ->
                                    event.stopPropagation()
                                    handleRemove(idx)
                                }
                            }) { Text("×") }
                        }
                    }
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
                        ranked.indices.forEach { idx -> renderCandidateRow(idx) }
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
                                        renderCandidateRow(idx)
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

                    Span({ classes("ranked-ballot-toolbar-hint") }) {
                        Text("Focus a row · ↑/↓ to nudge")
                    }
                }
            }
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
private sealed interface RankedItem {
    val name: String

    data class Candidate(override val name: String) : RankedItem
    data class TierMarker(override val name: String) : RankedItem
}

private fun RankedItem.toRanking(rank: Int): Ranking = when (this) {
    is RankedItem.Candidate -> Ranking(name, rank, RankingKind.CANDIDATE)
    is RankedItem.TierMarker -> Ranking(name, rank, RankingKind.TIER)
}

private fun Ranking.toRankedItem(): RankedItem = when (kind) {
    RankingKind.CANDIDATE -> RankedItem.Candidate(candidateName)
    RankingKind.TIER -> RankedItem.TierMarker(candidateName)
}

/**
 * Source of an in-progress drag. Drop targets dispatch on this so a chip
 * still in the arena and a row already in the list do the right thing
 * without the drop handler having to reach into either collection itself.
 */
private sealed interface DragSource {
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

@Composable
fun TallyView(
    state: FetchState<ElectionTally>,
    onNavigateToPreferences: () -> Unit,
    onNavigateToStrongestPaths: () -> Unit,
) {
    Div({ classes("section") }) {
        H2 { Text("Results") }

        when (state) {
            FetchState.Loading -> P { Text("Loading results…") }
            is FetchState.Error -> Div({ classes("error") }) { Text(state.message) }
            is FetchState.Success -> renderTally(
                state.value,
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
    serverTally: ElectionTally,
    onNavigateToPreferences: () -> Unit,
    onNavigateToStrongestPaths: () -> Unit,
) {
    val revealed = serverTally.tally.ballots.filterIsInstance<Ballot.Revealed>()
    val totalToggleable = revealed.size

    var active by remember(serverTally.tally.electionName, totalToggleable) {
        mutableStateOf(revealed.map { it.confirmation }.toSet())
    }

    val allOn = revealed.isEmpty() || active.size == totalToggleable
    // Memoize the Schulze recomputation against (serverTally, active). The
    // strongest-paths pass inside Tally.countBallots is O(n³) on the candidate
    // count, so without the remember Compose would re-run it on every
    // recomposition (parent reposes, hover, etc.) — not just when the active
    // set actually changes. Equality on Set<String> is structural, so toggling
    // a ballot invalidates the cache by design and triggers exactly one
    // recompute.
    val displaySections = if (allOn) {
        serverTally.sections
    } else {
        remember(serverTally, active) {
            val recomputed = Tally.countBallots(
                electionName = serverTally.tally.electionName,
                secretBallot = serverTally.tally.secretBallot,
                candidates = serverTally.tally.candidateNames,
                ballots = revealed.filter { it.confirmation in active },
            )
            tallySections(recomputed.places, serverTally.tiers)
        }
    }

    P {
        Text(
            if (allOn) {
                "Total Ballots: ${serverTally.tally.ballots.size}"
            } else {
                "Active Ballots: ${active.size} of $totalToggleable"
            }
        )
    }

    H3 { Text("Winners") }
    if (displaySections.all { it.places.isEmpty() }) {
        P { Text("No winners yet") }
    } else {
        val sections = displaySections

        val renderPlaceList: @Composable (List<Place>) -> Unit = { places ->
            Ol({ classes("ranked-ballot-list") }) {
                places.forEach { place ->
                    Li({ classes("ranked-ballot-row") }) {
                        Span({ classes("ranked-ballot-rank-num") }) { Text(ordinal(place.rank)) }
                        Span({ classes("ranked-ballot-row-name") }) { Text(place.candidateName) }
                    }
                }
            }
        }

        Div({ classes("tally-results") }) {
            sections.forEach { section ->
                val tierName = section.tierName
                if (tierName != null) {
                    Div({ classes("ranked-ballot-tier") }) {
                        Span({ classes("ranked-ballot-tier-title") }) { Text(tierName) }
                        if (section.places.isNotEmpty()) renderPlaceList(section.places)
                    }
                } else {
                    renderPlaceList(section.places)
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
 * Pairwise preferences page. The viewer picks two candidates from a chip
 * arena and the page renders the head-to-head between just those two: the
 * raw count for each direction plus the actual voters whose ballots produced
 * that count. The point is to make every pairwise total auditable — a
 * total isn't an abstract number, it's a list of named voters you can
 * scroll through.
 *
 * Selection is a rolling window of two: clicking a third candidate evicts
 * the older of the two selections. Clicking a currently-selected candidate
 * deselects it. With fewer than two selected the detail panel is hidden.
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

        Div({ classes("admin-table-scroll") }) {
            when (val state = tallyFetch.state) {
                FetchState.Loading -> P { Text("Loading…") }
                is FetchState.Error -> Div({ classes("error") }) { Text(state.message) }
                is FetchState.Success -> {
                    renderPairView(state.value) { a, b ->
                        renderPreferencesDetail(state.value, a, b)
                    }
                }
            }
        }

        Button({ onClick { onBack() } }) { Text("Back to Election") }
    }
}

/**
 * Strongest-paths page. Same chip-pair selector as the Preferences page,
 * but the detail panel shows each direction's multi-hop Schulze path with
 * a per-hop voter list under it: for a path A→C→B, the A→C card lists the
 * voters who ranked A above C, the C→B card lists the voters who ranked
 * C above B. Each hop's voter count IS the hop's strength, so the binding
 * hop can be read directly off the size of its voter list.
 *
 * Tier markers can show up as intermediate nodes inside a path even though
 * they aren't selectable in the chip arena: a path A → T → B is voters'
 * collective judgment that A cleared tier T and B didn't, and that's how a
 * direct head-to-head tie between A and B can still be broken on the
 * strongest path. Tier-styled chips inside paths flag those nodes so a
 * reader doesn't mistake them for candidates.
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
        P({ classes("pair-page-explainer") }) {
            Text(
                "A path between two candidates may pass through a tier marker — that's " +
                    "voters' collective judgment that one side cleared it and the other didn't."
            )
        }

        Div({ classes("admin-table-scroll") }) {
            when (val state = tallyFetch.state) {
                FetchState.Loading -> P { Text("Loading…") }
                is FetchState.Error -> Div({ classes("error") }) { Text(state.message) }
                is FetchState.Success -> {
                    renderPairView(state.value) { a, b ->
                        renderStrongestPathsDetail(state.value, a, b)
                    }
                }
            }
        }

        Button({ onClick { onBack() } }) { Text("Back to Election") }
    }
}

/**
 * Chip arena + detail-panel scaffold shared by the Preferences and
 * Strongest Paths pages. Owns the rolling-window-of-two selection state;
 * delegates to [detailPanel] when (and only when) two candidates are
 * selected. Resetting [selected] when the election changes prevents stale
 * names from carrying across navigation.
 *
 * Tier markers are intentionally excluded from the selection chips — the
 * page is for comparing candidates. Tiers can still appear inside the
 * detail panel (notably as intermediate nodes in strongest-path renderings),
 * styled distinctly so a reader can tell they're tier markers and not
 * candidates the comparison was about.
 */
@Composable
private fun renderPairView(
    tally: ElectionTally,
    detailPanel: @Composable (String, String) -> Unit,
) {
    val candidates = tally.tally.candidateNames.filterNot { tally.isTier(it) }
    if (candidates.size < 2) {
        P { Text("Not enough candidates to compare.") }
        return
    }
    var selected by remember(tally.tally.electionName, candidates) {
        mutableStateOf<List<String>>(emptyList())
    }
    Div({ classes("pair-selector-arena") }) {
        Span({ classes("pair-selector-hint") }) {
            Text("Pick two candidates to compare")
        }
        Div({ classes("pair-selector-grid") }) {
            candidates.forEach { name ->
                val isSelected = name in selected
                Button({
                    classes("pair-selector-chip")
                    if (isSelected) classes("pair-selector-chip-selected")
                    onClick {
                        selected = if (isSelected) selected - name
                            else (selected + name).takeLast(2)
                    }
                }) {
                    Text(name)
                }
            }
        }
    }
    if (selected.size == 2) {
        detailPanel(selected[0], selected[1])
    } else {
        Div({ classes("pair-selector-empty") }) {
            Text(
                if (selected.isEmpty())
                    "Select two candidates above to see the comparison."
                else
                    "Select one more candidate."
            )
        }
    }
}

@Composable
private fun renderPreferencesDetail(electionTally: ElectionTally, a: String, b: String) {
    val tally = electionTally.tally
    val names = tally.candidateNames
    val ai = names.indexOf(a)
    val bi = names.indexOf(b)
    val aOverB = tally.preferences[ai][bi].strength
    val bOverA = tally.preferences[bi][ai].strength
    val aWins = aOverB > bOverA
    val bWins = bOverA > aOverB
    val verdict = when {
        aWins -> "$a beats $b $aOverB to $bOverA"
        bWins -> "$b beats $a $bOverA to $aOverB"
        else -> "$a and $b tied at $aOverB"
    }

    val revealed = tally.ballots.filterIsInstance<Ballot.Revealed>()
    val aVoters = votersWhoPrefer(revealed, a, b)
    val bVoters = votersWhoPrefer(revealed, b, a)
    val abstainVoters = votersWhoAbstainOnPair(revealed, a, b)

    Div({ classes("pair-detail") }) {
        Div({ classes("pair-detail-header") }) { Text(verdict) }

        Div({ classes("pair-side-row") }) {
            renderPairSide(name = a, voters = aVoters, count = aOverB, isWinner = aWins, isSecret = tally.secretBallot)
            renderPairSide(name = b, voters = bVoters, count = bOverA, isWinner = bWins, isSecret = tally.secretBallot)
        }

        if (tally.secretBallot || abstainVoters.isNotEmpty()) {
            Div({ classes("pair-abstain") }) {
                H3 { Text("No expressed preference (${abstainVoters.size})") }
                if (tally.secretBallot) {
                    P({ classes("pair-secret-note") }) { Text("(ballots are secret)") }
                } else {
                    renderVoterList(abstainVoters)
                }
            }
        }
    }
}

@Composable
private fun renderPairSide(
    name: String,
    voters: List<String>,
    count: Int,
    isWinner: Boolean,
    isSecret: Boolean,
) {
    Div({
        classes("pair-side")
        if (isWinner) classes("pair-side-winner")
    }) {
        Div({ classes("pair-side-name") }) { Text(name) }
        Div({ classes("pair-side-count") }) { Text(count.toString()) }
        Div({ classes("pair-side-label") }) {
            Text(if (count == 1) "vote" else "votes")
        }
        if (isSecret) {
            P({ classes("pair-secret-note") }) { Text("(ballots are secret)") }
        } else {
            renderVoterList(voters)
        }
    }
}

@Composable
private fun renderStrongestPathsDetail(electionTally: ElectionTally, a: String, b: String) {
    val tally = electionTally.tally
    val names = tally.candidateNames
    val ai = names.indexOf(a)
    val bi = names.indexOf(b)
    val forward = tally.strongestPathMatrix[ai][bi]
    val reverse = tally.strongestPathMatrix[bi][ai]
    val forwardWins = forward.strength > reverse.strength
    val reverseWins = reverse.strength > forward.strength
    val verdict = when {
        forwardWins -> "$a beats $b on strongest path: ${forward.strength} vs ${reverse.strength}"
        reverseWins -> "$b beats $a on strongest path: ${reverse.strength} vs ${forward.strength}"
        else -> "$a and $b tied on strongest path at ${forward.strength}"
    }
    val revealed = tally.ballots.filterIsInstance<Ballot.Revealed>()
    // Winning direction first so the claim sits above its evidence.
    val (first, firstWins, second, secondWins) =
        if (reverseWins) PathOrder(reverse, true, forward, false)
        else PathOrder(forward, forwardWins, reverse, reverseWins)

    Div({ classes("pair-detail") }) {
        Div({ classes("pair-detail-header") }) { Text(verdict) }
        renderPathBreakdown(first, isWinner = firstWins, ballots = revealed, isSecret = tally.secretBallot, isTier = electionTally::isTier)
        renderPathBreakdown(second, isWinner = secondWins, ballots = revealed, isSecret = tally.secretBallot, isTier = electionTally::isTier)
    }
}

private data class PathOrder(
    val first: Preference,
    val firstWins: Boolean,
    val second: Preference,
    val secondWins: Boolean,
)

@Composable
private fun renderPathBreakdown(
    pref: Preference,
    isWinner: Boolean,
    ballots: List<Ballot.Revealed>,
    isSecret: Boolean,
    isTier: (String) -> Boolean,
) {
    val highlightBinding = pref.strengths.size > 1
    val weakest = pref.strength
    Div({
        classes("pair-path")
        if (isWinner) classes("pair-path-winner")
    }) {
        Div({ classes("pair-path-summary") }) {
            renderPathName(pref.path[0], isTier(pref.path[0]))
            pref.strengths.forEachIndexed { idx, s ->
                Span({ classes("pair-path-arrow") }) { Text("→") }
                val isBinding = highlightBinding && s == weakest
                Span({
                    classes("pair-path-strength")
                    if (isBinding) classes("pair-path-strength-binding")
                }) {
                    Text(s.toString())
                }
                Span({ classes("pair-path-arrow") }) { Text("→") }
                renderPathName(pref.path[idx + 1], isTier(pref.path[idx + 1]))
            }
            Span({ classes("pair-path-overall") }) {
                Text("(overall strength ${pref.strength})")
            }
        }

        Div({ classes("pair-path-hops") }) {
            pref.strengths.forEachIndexed { idx, s ->
                val from = pref.path[idx]
                val to = pref.path[idx + 1]
                val isBinding = highlightBinding && s == weakest
                Div({
                    classes("pair-path-hop")
                    if (isBinding) classes("pair-path-hop-binding")
                }) {
                    Div({ classes("pair-path-hop-header") }) {
                        renderPathName(from, isTier(from))
                        Text(" → ")
                        renderPathName(to, isTier(to))
                        Text(" ")
                        Span({ classes("pair-path-hop-strength") }) {
                            Text("$s ${if (s == 1) "voter" else "voters"}")
                        }
                    }
                    if (isSecret) {
                        P({ classes("pair-secret-note") }) { Text("(ballots are secret)") }
                    } else {
                        renderVoterList(votersWhoPrefer(ballots, from, to))
                    }
                }
            }
        }
    }
}

@Composable
private fun renderPathName(name: String, isTier: Boolean) {
    Span({
        classes("pair-path-name")
        if (isTier) classes("pair-path-name-tier")
    }) { Text(name) }
}

@Composable
private fun renderVoterList(voters: List<String>) {
    if (voters.isEmpty()) {
        P({ classes("pair-no-voters") }) { Text("(no voters)") }
        return
    }
    Div({ classes("pair-voter-list") }) {
        voters.forEach { v ->
            Span({ classes("pair-voter") }) { Text(v) }
        }
    }
}

private fun votersWhoPrefer(
    ballots: List<Ballot.Revealed>,
    a: String,
    b: String,
): List<String> = ballots
    .filter { it.rankings.prefers(a, b) }
    .map { it.voterName }
    .sorted()

private fun votersWhoAbstainOnPair(
    ballots: List<Ballot.Revealed>,
    a: String,
    b: String,
): List<String> = ballots
    .filter { !it.rankings.prefers(a, b) && !it.rankings.prefers(b, a) }
    .map { it.voterName }
    .sorted()

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
