package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.domain.ElectionDetail
import com.seanshubin.vote.domain.Role
import kotlinx.browser.window
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
            lastLoaded == null && pageState is FetchState.Error ->
                Div({ classes("error") }) { Text(pageState.message) }
            lastLoaded == null ->
                P { Text("Loading…") }
            else -> {
                val (_, candidates) = lastLoaded!!

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
