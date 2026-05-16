package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.domain.ElectionDetail
import com.seanshubin.vote.domain.Role
import kotlinx.browser.window
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    // Owner-set pause flag from the root pause-state poller. Passed into
    // VotingView so its auto-save can short-circuit instead of firing 503s
    // every drag during a maintenance window.
    isEventLogPaused: Boolean,
    onBack: () -> Unit,
    onElectionDeleted: () -> Unit,
    onNavigateToPreferences: () -> Unit = {},
    onNavigateToDecision: () -> Unit = {},
    onNavigateToProcess: () -> Unit = {},
) {
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var currentView by rememberHashTab("setup", setOf("setup", "vote", "tally"))

    // Two independent fetches:
    //   shellFetch — election + candidates, in parallel; gates the page UI.
    //   tallyFetch — the heavier endpoint (server runs the pairwise +
    //                Ranked Pairs pipeline and serializes ballots,
    //                preferences, and the contest lock-in record), kept off the
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

    // Keep this page live: when someone else casts a ballot (or edits the
    // election), the server version moves and we refetch. Both fetches are
    // reloaded — the shell carries the header ballot count, the tally carries
    // the results. tallyFetch is the cached variant so the Results tab keeps
    // the prior tally on screen during the refetch instead of flashing to
    // Loading; shellFetch is guarded the same way by lastLoadedShell below.
    rememberVersionPolling(apiClient) {
        shellFetch.reload()
        tallyFetch.reload()
    }

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

        // Setup is visible to the election owner, any co-manager the owner has
        // added, or any user with role >= ADMIN. Backend re-checks; this just
        // hides the UI surface so non-privileged voters don't see edit fields
        // they couldn't successfully save anyway.
        val canSetup = loadedElection?.let { e ->
            e.ownerName == currentUserName ||
                e.managers.contains(currentUserName) ||
                (currentRole != null && currentRole >= Role.ADMIN)
        } ?: false

        // Owner/ADMIN-only authority: editing the manager list, transferring
        // ownership, deleting the election. A co-manager gets the Setup tab
        // (content editing) but not these — so the Managers and Transfer
        // sections inside ElectionSetupView, and the Delete button below, are
        // gated on this rather than on canSetup.
        val canManageManagers = loadedElection?.let { e ->
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

                // Defense in depth: if the LaunchedEffect above hasn't fired
                // yet (first frame after load), treat "setup" as "vote" for
                // viewers who can't setup so they never see the edit pane.
                val effectiveView = if (currentView == "setup" && !canSetup) "vote" else currentView

                // Tabs (no more Details — the header above covers it).
                Div({ classes("tabs") }) {
                    if (canSetup) {
                        Button({
                            if (effectiveView == "setup") classes("active")
                            onClick { currentView = "setup" }
                        }) { Text("Setup") }
                    }
                    Button({
                        if (effectiveView == "vote") classes("active")
                        onClick { currentView = "vote" }
                    }) { Text("Vote") }
                    Button({
                        if (effectiveView == "tally") classes("active")
                        onClick { currentView = "tally" }
                    }) { Text("Results") }
                }

                when (effectiveView) {
                    "setup" -> ElectionSetupView(
                        apiClient = apiClient,
                        electionName = electionName,
                        existingDescription = loadedElection?.description ?: "",
                        existingCandidates = candidates,
                        existingTiers = loadedElection?.tiers ?: emptyList(),
                        existingManagers = loadedElection?.managers ?: emptyList(),
                        ballotsExist = (loadedElection?.ballotCount ?: 0) > 0,
                        currentOwnerName = loadedElection?.ownerName ?: "",
                        canManageManagers = canManageManagers,
                        onDescriptionSaved = { newDescription ->
                            successMessage = "Description saved"
                            errorMessage = null
                            // Patch in place — description is independent of
                            // candidates and tally, no refresh needed.
                            lastLoadedShell = lastLoadedShell?.let { (e, c) ->
                                e.copy(description = newDescription) to c
                            }
                        },
                        onCandidatesAdded = { added ->
                            successMessage = if (added.size == 1) "Added \"${added[0]}\""
                            else "Added ${added.size} candidates"
                            errorMessage = null
                            // Patch the candidate list and the header count
                            // immediately so the UI feels instant. The tally
                            // is now stale (preferences matrix is keyed on
                            // the candidate set) so reload it; the cached
                            // helper keeps the prior tally visible during
                            // the refetch, so the Results tab does not flash
                            // to Loading.
                            lastLoadedShell = lastLoadedShell?.let { (e, c) ->
                                val merged = (c + added).distinct()
                                e.copy(candidateCount = merged.size) to merged
                            }
                            tallyFetch.reload()
                        },
                        onCandidateRemoved = { removed ->
                            successMessage = "Removed \"$removed\""
                            errorMessage = null
                            lastLoadedShell = lastLoadedShell?.let { (e, c) ->
                                val remaining = c.filter { it != removed }
                                e.copy(candidateCount = remaining.size) to remaining
                            }
                            tallyFetch.reload()
                        },
                        onCandidateRenamed = { oldName, newName ->
                            successMessage = "Renamed \"$oldName\" to \"$newName\""
                            errorMessage = null
                            // Patch the local candidate list in place so the
                            // setup row re-sorts immediately; ballot counts
                            // are refetched by ElectionSetupView itself.
                            lastLoadedShell = lastLoadedShell?.let { (e, c) ->
                                e to c.map { if (it == oldName) newName else it }
                            }
                            tallyFetch.reload()
                        },
                        onTiersSaved = { newTiers ->
                            successMessage = "Tiers saved"
                            errorMessage = null
                            // Tier markers participate in strongest-path
                            // calculations, so the tally needs a refresh.
                            // The new model allows setTiers any time (the
                            // no-ballots lock is gone), so the tally may
                            // legitimately have non-empty rankings whose
                            // tier annotations just got cleared by a remove.
                            lastLoadedShell = lastLoadedShell?.let { (e, c) ->
                                e.copy(tiers = newTiers) to c
                            }
                            tallyFetch.reload()
                        },
                        onTierRenamed = { oldName, newName ->
                            successMessage = "Renamed tier \"$oldName\" to \"$newName\""
                            errorMessage = null
                            // Patch the local tier list so the row re-renders
                            // with the new label without a full shell refetch.
                            // The rename cascaded across ballot rankings on
                            // the server, so the tally rebuilds against the
                            // new label too — reload to refresh.
                            lastLoadedShell = lastLoadedShell?.let { (e, c) ->
                                e.copy(tiers = e.tiers.map { if (it == oldName) newName else it }) to c
                            }
                            tallyFetch.reload()
                        },
                        onManagerAdded = { added ->
                            successMessage = "Added manager \"$added\""
                            errorMessage = null
                            // Patch the manager list in place — independent of
                            // candidates and tally, so no refetch is needed.
                            lastLoadedShell = lastLoadedShell?.let { (e, c) ->
                                e.copy(managers = (e.managers + added).distinct()) to c
                            }
                        },
                        onManagerRemoved = { removed ->
                            successMessage = "Removed manager \"$removed\""
                            errorMessage = null
                            lastLoadedShell = lastLoadedShell?.let { (e, c) ->
                                e.copy(managers = e.managers.filter { it != removed }) to c
                            }
                        },
                        onOwnerTransferred = { newOwnerName ->
                            successMessage = "Ownership transferred to $newOwnerName"
                            errorMessage = null
                            // Patch the header so the new owner is reflected
                            // immediately. The canSetup gate above is derived
                            // from this and may flip false next render — if
                            // the transferrer no longer matches and isn't
                            // ADMIN+, the Setup tab disappears and the
                            // LaunchedEffect bounces them to "vote".
                            lastLoadedShell = lastLoadedShell?.let { (e, c) ->
                                e.copy(ownerName = newOwnerName) to c
                            }
                            // The Elections list still shows the old owner —
                            // invalidate so a navigation back picks up fresh.
                            PageCache.invalidate("elections")
                        },
                        onError = { errorMessage = it },
                    )
                    "vote" -> VotingView(
                        apiClient = apiClient,
                        electionName = electionName,
                        candidates = candidates,
                        tiers = loadedElection?.tiers ?: emptyList(),
                        currentUserName = currentUserName,
                        isEventLogPaused = isEventLogPaused,
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
                        onNavigateToDecision = onNavigateToDecision,
                        onNavigateToProcess = onNavigateToProcess,
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
