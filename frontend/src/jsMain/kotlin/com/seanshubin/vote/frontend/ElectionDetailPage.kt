package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.domain.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.*
import org.jetbrains.compose.web.dom.*

@Composable
fun ElectionDetailPage(
    authToken: String,
    electionName: String,
    onBack: () -> Unit
) {
    var election by remember { mutableStateOf<ElectionSummary?>(null) }
    var candidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var currentView by remember { mutableStateOf("details") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            election = ApiClient.getElection(authToken, electionName)
            candidates = ApiClient.listCandidates(authToken, electionName)
        } catch (e: Exception) {
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
                    authToken, electionName, candidates,
                    onSuccess = { msg ->
                        successMessage = msg
                        scope.launch {
                            try {
                                candidates = ApiClient.listCandidates(authToken, electionName)
                            } catch (e: Exception) {
                                // Ignore
                            }
                        }
                    },
                    onError = { msg -> errorMessage = msg }
                )
                "vote" -> VotingView(
                    authToken, electionName, candidates,
                    onSuccess = { msg -> successMessage = msg },
                    onError = { msg -> errorMessage = msg }
                )
                "tally" -> TallyView(
                    authToken, electionName,
                    onError = { msg -> errorMessage = msg }
                )
            }
        }

        Button({
            onClick { onBack() }
        }) {
            Text("Back to Elections")
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
    authToken: String,
    electionName: String,
    existingCandidates: List<String>,
    onSuccess: (String) -> Unit,
    onError: (String) -> Unit
) {
    var candidatesText by remember { mutableStateOf(existingCandidates.joinToString("\n")) }
    var votersText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
                    scope.launch {
                        try {
                            ApiClient.setCandidates(authToken, electionName, candidates)
                            onSuccess("Candidates updated successfully")
                        } catch (e: Exception) {
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
                    scope.launch {
                        try {
                            ApiClient.setEligibleVoters(authToken, electionName, voters)
                            onSuccess("Eligible voters updated successfully")
                        } catch (e: Exception) {
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
                    scope.launch {
                        try {
                            ApiClient.launchElection(authToken, electionName)
                            onSuccess("Election launched successfully")
                        } catch (e: Exception) {
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
    authToken: String,
    electionName: String,
    candidates: List<String>,
    onSuccess: (String) -> Unit,
    onError: (String) -> Unit
) {
    var rankings by remember { mutableStateOf(candidates.mapIndexed { index, name -> name to (index + 1) }) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
                        scope.launch {
                            try {
                                val confirmation = ApiClient.castBallot(authToken, electionName, ballotRankings)
                                onSuccess("Ballot cast successfully! Confirmation: $confirmation")
                            } catch (e: Exception) {
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
    authToken: String,
    electionName: String,
    onError: (String) -> Unit
) {
    var tally by remember { mutableStateOf<Tally?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            tally = ApiClient.getTally(authToken, electionName)
        } catch (e: Exception) {
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
