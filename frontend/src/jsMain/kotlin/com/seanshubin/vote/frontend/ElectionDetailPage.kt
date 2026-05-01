package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.domain.*
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
    coroutineScope: CoroutineScope = rememberCoroutineScope()
) {
    var election by remember { mutableStateOf<ElectionDetail?>(null) }
    var candidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var currentView by remember { mutableStateOf("setup") }

    suspend fun reload() {
        election = apiClient.getElection(electionName)
        candidates = apiClient.listCandidates(electionName)
    }

    LaunchedEffect(Unit) {
        try {
            reload()
        } catch (e: Exception) {
            apiClient.logErrorToServer(e)
            errorMessage = e.message ?: "Failed to load election"
        } finally {
            isLoading = false
        }
    }

    Div({ classes("container") }) {
        H1 { Text("Election: $electionName") }

        // Header summary: owner + counts. Replaces the old Details tab.
        election?.let { e ->
            Div({ classes("section") }) {
                P { Text("Owner: ${e.ownerName}") }
                P { Text("Candidates: ${e.candidateCount} • Ballots cast: ${e.ballotCount}") }
            }
        }

        if (errorMessage != null) {
            Div({ classes("error") }) {
                Text(errorMessage!!)
            }
        }

        if (successMessage != null) {
            Div({ classes("success") }) {
                Text(successMessage!!)
            }
        }

        if (isLoading) {
            P { Text("Loading...") }
        } else if (election != null) {
            // Tabs (no more Details — the header above covers it).
            Div({ classes("tabs") }) {
                Button({ onClick { currentView = "setup" } }) { Text("Setup") }
                Button({ onClick { currentView = "vote" } }) { Text("Vote") }
                Button({ onClick { currentView = "tally" } }) { Text("Results") }
            }

            when (currentView) {
                "setup" -> ElectionSetupView(
                    apiClient, electionName, candidates,
                    onSuccess = { msg ->
                        successMessage = msg
                        coroutineScope.launch {
                            try { reload() } catch (_: Exception) { /* keep current view */ }
                        }
                    },
                    onError = { msg -> errorMessage = msg }
                )
                "vote" -> VotingView(
                    apiClient, electionName, candidates,
                    onSuccess = { msg ->
                        successMessage = msg
                        coroutineScope.launch {
                            try { reload() } catch (_: Exception) { /* keep current view */ }
                        }
                    },
                    onError = { msg -> errorMessage = msg }
                )
                "tally" -> TallyView(
                    apiClient, electionName,
                    onError = { msg -> errorMessage = msg }
                )
            }
        }

        // Delete is shown to the election owner OR any user with role >= ADMIN
        // (moderators). Backend authorization is the real defense.
        val canDelete = election != null && (
            election!!.ownerName == currentUserName ||
                (currentRole != null && currentRole >= Role.ADMIN)
            )
        Div({ classes("button-row") }) {
            Button({
                onClick { onBack() }
            }) {
                Text("Back to Elections")
            }

            if (canDelete) {
                Button({
                    onClick {
                        val confirmed = window.confirm("Delete election \"$electionName\"? This cannot be undone.")
                        if (confirmed) {
                            coroutineScope.launch {
                                try {
                                    apiClient.deleteElection(electionName)
                                    onElectionDeleted()
                                } catch (e: Exception) {
                                    apiClient.logErrorToServer(e)
                                    errorMessage = e.message ?: "Failed to delete election"
                                }
                            }
                        }
                    }
                }) {
                    Text("Delete Election")
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
    coroutineScope: CoroutineScope = rememberCoroutineScope()
) {
    var candidatesText by remember { mutableStateOf(existingCandidates.joinToString("\n")) }
    var isLoading by remember { mutableStateOf(false) }

    Div({ classes("section") }) {
        H2 { Text("Candidates") }

        P { Text("Enter one candidate per line (an empty list is allowed):") }
        TextArea(candidatesText) {
            classes("textarea")
            attr("rows", "5")
            onInput { candidatesText = it.value }
        }

        Button({
            onClick {
                if (!isLoading) {
                    isLoading = true
                    val candidates = candidatesText.split("\n")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                    coroutineScope.launch {
                        try {
                            apiClient.setCandidates(electionName, candidates)
                            onSuccess("Candidates saved")
                        } catch (e: Exception) {
                            apiClient.logErrorToServer(e)
                            onError(e.message ?: "Failed to save candidates")
                        } finally {
                            isLoading = false
                        }
                    }
                }
            }
        }) {
            Text(if (isLoading) "Saving..." else "Save Candidates")
        }
    }
}

@Composable
fun VotingView(
    apiClient: ApiClient,
    electionName: String,
    candidates: List<String>,
    onSuccess: (String) -> Unit,
    onError: (String) -> Unit,
    coroutineScope: CoroutineScope = rememberCoroutineScope()
) {
    var rankings by remember(candidates) {
        mutableStateOf(candidates.mapIndexed { index, name -> name to (index + 1) })
    }
    var isLoading by remember { mutableStateOf(false) }

    Div({ classes("section") }) {
        H2 { Text("Cast Ballot") }

        if (candidates.isEmpty()) {
            P { Text("No candidates yet — the election owner can add them via the Setup tab.") }
        } else {
            P { Text("Rank the candidates (1 = most preferred):") }

            rankings.forEach { (candidateName, rank) ->
                Div({ classes("ranking-row") }) {
                    Span { Text(candidateName) }
                    Input(InputType.Number) {
                        value(rank.toString())
                        onInput { event ->
                            val newRank = try {
                                event.value?.toString()?.toInt() ?: rank
                            } catch (e: Exception) {
                                rank
                            }
                            rankings = rankings.map { (name, r) ->
                                if (name == candidateName) name to newRank else name to r
                            }
                        }
                    }
                }
            }

            Button({
                onClick {
                    if (!isLoading) {
                        isLoading = true
                        val ballotRankings = rankings.map { (name, rank) ->
                            Ranking(name, rank)
                        }
                        coroutineScope.launch {
                            try {
                                val confirmation = apiClient.castBallot(electionName, ballotRankings)
                                onSuccess("Ballot cast successfully! Confirmation: $confirmation")
                            } catch (e: Exception) {
                                apiClient.logErrorToServer(e)
                                onError(e.message ?: "Failed to cast ballot")
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                }
            }) {
                Text(if (isLoading) "Submitting..." else "Submit Ballot")
            }
        }
    }
}

@Composable
fun TallyView(
    apiClient: ApiClient,
    electionName: String,
    onError: (String) -> Unit
) {
    var tally by remember { mutableStateOf<Tally?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            tally = apiClient.getTally(electionName)
        } catch (e: Exception) {
            apiClient.logErrorToServer(e)
            onError(e.message ?: "Failed to load tally")
        } finally {
            isLoading = false
        }
    }

    Div({ classes("section") }) {
        H2 { Text("Results") }

        if (isLoading) {
            P { Text("Loading results...") }
        } else if (tally == null) {
            P { Text("No tally data available.") }
        } else {
            P { Text("Total Ballots: ${tally!!.ballots.size}") }

            H3 { Text("Winners") }
            if (tally!!.places.isEmpty()) {
                P { Text("No winners yet") }
            } else {
                tally!!.places.forEach { place ->
                    P { Text("Place ${place.rank}: ${place.candidateName}") }
                }
            }

            H3 { Text("Preferences Matrix") }
            Table {
                Thead {
                    Tr {
                        Th { Text("") }
                        tally!!.candidateNames.forEach { candidate ->
                            Th { Text(candidate) }
                        }
                    }
                }
                Tbody {
                    tally!!.candidateNames.forEachIndexed { rowIndex, rowCandidate ->
                        Tr {
                            Td { Text(rowCandidate) }
                            tally!!.candidateNames.forEachIndexed { colIndex, _ ->
                                val preference = tally!!.preferences[rowIndex][colIndex]
                                Td { Text(preference.strength.toString()) }
                            }
                        }
                    }
                }
            }
        }
    }
}
