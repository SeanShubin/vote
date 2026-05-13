package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.domain.Ballot
import com.seanshubin.vote.domain.ElectionTally
import com.seanshubin.vote.domain.Preference
import com.seanshubin.vote.domain.Ranking.Companion.prefers
import org.jetbrains.compose.web.dom.*

/**
 * Pairwise preferences page. The viewer picks two candidates from a chip
 * arena and the page renders the head-to-head between just those two: the
 * raw count for each direction plus the actual voters whose ballots produced
 * that count. The point is to make every pairwise total auditable — a
 * total isn't an abstract number, it's a list of named voters you can
 * scroll through.
 *
 * Selection is sticky-plus-changing: the first pick stays put and the
 * second slot is what gets replaced when a third candidate is clicked.
 * Clicking a currently-selected candidate deselects just that one. With
 * fewer than two selected the detail panel is hidden.
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
 * Strongest Paths pages. Owns the sticky-plus-changing selection state:
 * the first pick is sticky, the second slot rotates as the user explores
 * other candidates against it. Delegates to [detailPanel] when (and only
 * when) two candidates are selected. Resetting [selected] when the
 * election changes prevents stale names from carrying across navigation.
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
                        selected = when {
                            isSelected -> selected - name
                            selected.size < 2 -> selected + name
                            else -> listOf(selected[0], name)
                        }
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
