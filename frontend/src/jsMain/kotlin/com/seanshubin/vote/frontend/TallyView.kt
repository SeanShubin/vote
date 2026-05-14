package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.domain.Ballot
import com.seanshubin.vote.domain.ElectionTally
import com.seanshubin.vote.domain.Place
import com.seanshubin.vote.domain.Tally
import com.seanshubin.vote.domain.TallySection
import org.jetbrains.compose.web.attributes.*
import org.jetbrains.compose.web.dom.*

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
    val activeBallots = revealed.filter { it.confirmation in active }
    val totalActiveBallots = activeBallots.size
    // Each ballot contributes at most once per candidate. A candidate "shows
    // up on" a ballot when that ballot has a Ranking for it with a non-null
    // rank — same predicate Tally.countBallots uses to decide a ballot
    // expresses a preference involving the candidate.
    val ballotsPerCandidate: Map<String, Int> = remember(serverTally, active) {
        revealed.filter { it.confirmation in active }
            .flatMap { ballot ->
                ballot.rankings
                    .filter { it.rank != null }
                    .map { it.candidateName }
                    .toSet()
            }
            .groupingBy { it }
            .eachCount()
    }
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
            // The server's tally.candidateNames is real candidates + tier
            // markers (the matrix node list). Split it back into the two
            // inputs countBallots wants: real candidates and tiers.
            val tierSet = serverTally.tiers.toSet()
            val realCandidates = serverTally.tally.candidateNames.filterNot { it in tierSet }
            val recomputed = Tally.countBallots(
                electionName = serverTally.tally.electionName,
                secretBallot = serverTally.tally.secretBallot,
                candidates = realCandidates,
                tiers = serverTally.tiers,
                ballots = revealed.filter { it.confirmation in active },
            )
            TallySection.compute(recomputed.places, serverTally.tiers)
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

        val ballotNoun = if (totalActiveBallots == 1) "ballot" else "ballots"
        val renderPlaceList: @Composable (List<Place>) -> Unit = { places ->
            Ol({ classes("ranked-ballot-list") }) {
                places.forEach { place ->
                    val appearances = ballotsPerCandidate[place.candidateName] ?: 0
                    Li({
                        classes("ranked-ballot-row")
                        title("Ranked on $appearances of $totalActiveBallots $ballotNoun")
                    }) {
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

// Teens (11th, 12th, 13th) take "th" even though they end in 1/2/3.
internal fun ordinal(n: Int): String {
    val suffix = when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$n$suffix"
}
