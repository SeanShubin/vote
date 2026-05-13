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
                        currentOwnerName = loadedElection?.ownerName ?: "",
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
