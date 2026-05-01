package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.domain.*
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.*
import org.jetbrains.compose.web.dom.*

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
    var election by remember { mutableStateOf<ElectionSummary?>(null) }
    var candidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var currentView by remember { mutableStateOf("details") }

    LaunchedEffect(Unit) {
        try {
            election = apiClient.getElection(electionName)
            candidates = apiClient.listCandidates(electionName)
        } catch (e: Exception) {
            apiClient.logErrorToServer(e)
            errorMessage = e.message ?: "Failed to load election"
        } finally {
            isLoading = false
        }
    }

    Div({ classes("container") }) {
        H1 { Text("Election: $electionName") }

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
            // Navigation tabs
            Div({ classes("tabs") }) {
                Button({
                    onClick { currentView = "details" }
                }) {
                    Text("Details")
                }
                Button({
                    onClick { currentView = "setup" }
                }) {
                    Text("Setup")
                }
                Button({
                    onClick { currentView = "vote" }
                }) {
                    Text("Vote")
                }
                Button({
                    onClick { currentView = "tally" }
                }) {
                    Text("Results")
                }
            }

            // Content based on current view
            when (currentView) {
                "details" -> ElectionDetailsView(election!!)
                "setup" -> ElectionSetupView(
                    apiClient, electionName, candidates,
                    onSuccess = { msg ->
                        successMessage = msg
                        coroutineScope.launch {
                            try {
                                candidates = apiClient.listCandidates(electionName)
                            } catch (e: Exception) {
                                // Ignore
                            }
                        }
                    },
                    onError = { msg -> errorMessage = msg }
                )
                "vote" -> VotingView(
                    apiClient, electionName, candidates,
                    onSuccess = { msg -> successMessage = msg },
                    onError = { msg -> errorMessage = msg }
                )
                "tally" -> TallyView(
                    apiClient, electionName,
                    onError = { msg -> errorMessage = msg }
                )
            }
        }

        Button({
            onClick { onBack() }
        }) {
            Text("Back to Elections")
        }

        // Delete is shown to the election owner OR any user with role >= ADMIN
        // (moderators). Backend authorization is the real defense — this is just
        // the visibility shortcut so non-owners don't see a button they can't use.
        val canDelete = election != null && (
            election!!.ownerName == currentUserName ||
                (currentRole != null && currentRole!! >= Role.ADMIN)
        )
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

@Composable
fun ElectionDetailsView(election: ElectionSummary) {
    Div({ classes("section") }) {
        H2 { Text("Details") }
        P { Text("Owner: ${election.ownerName}") }
        P { Text("Secret Ballot: ${election.secretBallot}") }
        P { Text("Allow Edit: ${election.allowEdit}") }
        P { Text("Allow Vote: ${election.allowVote}") }
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
    var votersText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    Div({ classes("section") }) {
        H2 { Text("Setup Candidates") }

        P { Text("Enter one candidate per line:") }
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
                            onSuccess("Candidates updated successfully")
                        } catch (e: Exception) {
                            apiClient.logErrorToServer(e)
                            onError(e.message ?: "Failed to update candidates")
                        } finally {
                            isLoading = false
                        }
                    }
                }
            }
        }) {
            Text(if (isLoading) "Saving..." else "Save Candidates")
        }

        H2 { Text("Setup Eligible Voters") }

        P { Text("Enter one voter username per line:") }
        TextArea(votersText) {
            classes("textarea")
            attr("rows", "5")
            onInput { votersText = it.value }
        }

        Button({
            onClick {
                if (!isLoading) {
                    isLoading = true
                    val voters = votersText.split("\n")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                    coroutineScope.launch {
                        try {
                            apiClient.setEligibleVoters(electionName, voters)
                            onSuccess("Eligible voters updated successfully")
                        } catch (e: Exception) {
                            apiClient.logErrorToServer(e)
                            onError(e.message ?: "Failed to update eligible voters")
                        } finally {
                            isLoading = false
                        }
                    }
                }
            }
        }) {
            Text(if (isLoading) "Saving..." else "Save Eligible Voters")
        }

        H2 { Text("Launch Election") }

        Button({
            onClick {
                if (!isLoading) {
                    isLoading = true
                    coroutineScope.launch {
                        try {
                            apiClient.launchElection(electionName)
                            onSuccess("Election launched successfully")
                        } catch (e: Exception) {
                            apiClient.logErrorToServer(e)
                            onError(e.message ?: "Failed to launch election")
                        } finally {
                            isLoading = false
                        }
                    }
                }
            }
        }) {
            Text(if (isLoading) "Launching..." else "Launch Election")
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
    var rankings by remember { mutableStateOf(candidates.mapIndexed { index, name -> name to (index + 1) }) }
    var isLoading by remember { mutableStateOf(false) }

    Div({ classes("section") }) {
        H2 { Text("Cast Ballot") }

        if (candidates.isEmpty()) {
            P { Text("No candidates available yet.") }
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
    val scope = rememberCoroutineScope()

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
