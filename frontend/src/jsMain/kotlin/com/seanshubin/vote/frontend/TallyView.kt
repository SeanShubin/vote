package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.domain.Ballot
import com.seanshubin.vote.domain.ElectionTally
import com.seanshubin.vote.domain.PasteTallyFormat
import com.seanshubin.vote.domain.Place
import com.seanshubin.vote.domain.RankingSide
import com.seanshubin.vote.domain.Tally
import com.seanshubin.vote.domain.TallySection
import org.jetbrains.compose.web.attributes.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*

@Composable
fun TallyView(
    apiClient: ApiClient,
    state: FetchState<ElectionTally>,
    currentSide: RankingSide,
    onSetSide: (RankingSide) -> Unit,
    secretBallotEnabled: Boolean,
    onNavigateToHeadToHead: () -> Unit,
    onNavigateToProcess: () -> Unit,
) {
    Div({ classes("section") }) {
        H2 { Text("Results") }
        SideToggle(currentSide, onSetSide, enabled = secretBallotEnabled)

        when (state) {
            FetchState.Loading -> P { Text("Loading results…") }
            is FetchState.Error -> Div({ classes("error") }) { Text(state.message) }
            is FetchState.Success -> renderTally(
                apiClient = apiClient,
                latestTally = state.value,
                onNavigateToHeadToHead = onNavigateToHeadToHead,
                onNavigateToProcess = onNavigateToProcess,
            )
        }
    }
}

/**
 * Lets the viewer toggle individual ballots on/off and watch the Winners
 * list recompute against the active subset. Local-only: no persisted state,
 * no effect on other viewers — leaving the page resets to "all on". The full
 * Tally.countBallots pipeline (pairwise matrix + Tideman ranked pairs + place
 * grouping) lives in the shared `domain` module so the frontend can rerun it
 * directly.
 *
 * Freeze-on-touch: with nothing toggled off the screen is a live leaderboard
 * and adopts every background refetch immediately. Once a ballot is toggled
 * off the viewer is mid-analysis, so the displayed tally pins to that snapshot
 * and later refetches surface as an "Update" banner rather than silently
 * changing the numbers. Adopting the banner — or returning to the unfiltered
 * state — re-syncs to the latest server tally.
 *
 * Only `Ballot.Identified` ballots are toggleable: anonymous ballots strip the
 * voter identity and Tally.countBallots only accepts identified input.
 *
 * The toggle does not flow into the Head-to-Head / Process detail pages —
 * those are separate routes that fetch their own (unfiltered) tally.
 */
@Composable
private fun renderTally(
    apiClient: ApiClient,
    latestTally: ElectionTally,
    onNavigateToHeadToHead: () -> Unit,
    onNavigateToProcess: () -> Unit,
) {
    // The viewer's explicit "off" choices, tracked as the excluded set (not
    // the kept set) so adopting a fresh tally reconciles for free: a newly
    // cast ballot is simply absent from `excluded` and shows on. Keyed on
    // election + side so it clears when either changes, since each side has
    // its own ballot set with non-overlapping confirmations.
    var excluded by remember(latestTally.tally.electionName, latestTally.tally.side) {
        mutableStateOf(emptySet<String>())
    }

    // Freeze-on-touch. While `excluded` is empty the screen is a live
    // leaderboard and `displayedTally` tracks the latest server tally via the
    // effect below. Once a ballot is toggled off the viewer is doing what-if
    // analysis, so the display pins to that snapshot — every later toggle
    // recomputes against the same ballot set — and incoming refetches surface
    // as an Update banner instead of shifting the numbers underfoot.
    var displayedTally by remember(latestTally.tally.electionName, latestTally.tally.side) {
        mutableStateOf(latestTally)
    }
    LaunchedEffect(latestTally, excluded) {
        if (excluded.isEmpty()) displayedTally = latestTally
    }

    val revealed = displayedTally.tally.ballots.filterIsInstance<Ballot.Identified>()
    val totalToggleable = revealed.size
    val currentConfirmations = revealed.map { it.confirmation }.toSet()
    val active = currentConfirmations - excluded

    // A background refetch landed while the viewer was filtering: the pinned
    // snapshot is now behind the server. The banner lets them adopt it; or
    // they return to the unfiltered state and the effect above re-syncs.
    val pendingUpdate = excluded.isNotEmpty() && latestTally != displayedTally
    val newBallotCount = remember(latestTally, displayedTally) {
        val shown = displayedTally.tally.ballots
            .filterIsInstance<Ballot.Identified>().map { it.confirmation }.toSet()
        latestTally.tally.ballots
            .filterIsInstance<Ballot.Identified>().count { it.confirmation !in shown }
    }

    val allOn = revealed.isEmpty() || active.size == totalToggleable
    // Coverage runs over every ballot — Identified or Anonymous — because
    // both carry rankings. On the SECRET side for callers without
    // VIEW_SECRETS, ballots come through as Anonymous and the toggle UI is
    // hidden, so allOn is always true and we use the full ballot list here.
    // When toggling is in play (only when ballots are Identified), narrow
    // to the active subset.
    val coverageBallots: List<Ballot> = if (allOn) displayedTally.tally.ballots
        else revealed.filter { it.confirmation in active }
    val totalActiveBallots = coverageBallots.size
    // Each ballot contributes at most once per candidate. A candidate "shows
    // up on" a ballot when that ballot has a Ranking for it with a non-null
    // rank — same predicate Tally.countBallots uses to decide a ballot
    // expresses a preference involving the candidate.
    val ballotsPerCandidate: Map<String, Int> = remember(displayedTally, active) {
        coverageBallots
            .flatMap { ballot ->
                ballot.rankings
                    .filter { it.rank != null }
                    .map { it.candidateName }
                    .toSet()
            }
            .groupingBy { it }
            .eachCount()
    }
    // Memoize the tally recomputation against (displayedTally, active). The
    // Tideman lock-in pass inside Tally.countBallots is at worst O(n³) on
    // the candidate count (BFS per skipped contest), so without the
    // remember Compose would re-run it on every recomposition (parent
    // reposes, hover, etc.) — not just when the active set actually
    // changes. Equality on Set<String> is structural, so toggling a ballot
    // invalidates the cache by design and triggers exactly one recompute.
    val activeTally: Tally = if (allOn) {
        displayedTally.tally
    } else {
        remember(displayedTally, active) {
            // The server's tally.candidateNames is real candidates + tier
            // markers (the matrix node list). Split it back into the two
            // inputs countBallots wants: real candidates and tiers.
            val tierSet = displayedTally.tiers.toSet()
            val realCandidates = displayedTally.tally.candidateNames.filterNot { it in tierSet }
            Tally.countBallots(
                electionName = displayedTally.tally.electionName,
                side = displayedTally.tally.side,
                candidates = realCandidates,
                tiers = displayedTally.tiers,
                ballots = revealed.filter { it.confirmation in active },
            )
        }
    }
    val displaySections = if (allOn) {
        displayedTally.sections
    } else {
        remember(activeTally) {
            TallySection.compute(activeTally.places, displayedTally.tiers)
        }
    }

    P {
        Text(
            if (allOn) {
                "Total Ballots: ${displayedTally.tally.ballots.size}"
            } else {
                "Active Ballots: ${active.size} of $totalToggleable"
            }
        )
    }

    // While the viewer is filtering, a background refetch is held back rather
    // than applied. This banner reports how stale the pinned snapshot is and
    // lets them pull in the latest without losing their toggled-off choices.
    if (pendingUpdate) {
        Div({ classes("tally-update-banner") }) {
            Span({ classes("tally-update-banner-text") }) {
                Text(
                    when (newBallotCount) {
                        0 -> "Results have changed since you started filtering."
                        1 -> "1 new ballot since you started filtering."
                        else -> "$newBallotCount new ballots since you started filtering."
                    }
                )
            }
            Button({
                onClick { displayedTally = latestTally }
            }) { Text("Update") }
        }
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
                    // Coverage = share of active ballots that ranked this
                    // candidate. Higher coverage means the placement rests on
                    // more voter input, so it's more trustworthy. Shown
                    // passively as a fill bar (scan) plus an exact count
                    // (read), since the hover tooltip is invisible on mobile.
                    val coveragePercent =
                        if (totalActiveBallots == 0) 0.0
                        else appearances * 100.0 / totalActiveBallots
                    Li({
                        classes("ranked-ballot-row")
                        title("Ranked on $appearances of $totalActiveBallots $ballotNoun")
                    }) {
                        Div({ classes("ranked-ballot-coverage-content") }) {
                            Span({ classes("ranked-ballot-rank-num") }) { Text(ordinal(place.rank)) }
                            Span({ classes("ranked-ballot-row-name") }) { Text(place.candidateName) }
                            Span({ classes("ranked-ballot-coverage-count") }) {
                                Text("$appearances/$totalActiveBallots")
                            }
                        }
                        Div({ classes("ranked-ballot-coverage-bar") }) {
                            Div({
                                classes("ranked-ballot-coverage-bar-fill")
                                style { width(coveragePercent.percent) }
                            }) {}
                        }
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

        // Token bumps reset the auto-clear timer so a fresh click restarts
        // the 2s window instead of an older coroutine clearing it early.
        var copyFeedback by remember { mutableStateOf<String?>(null) }
        var copyFeedbackToken by remember { mutableStateOf(0) }
        LaunchedEffect(copyFeedbackToken) {
            if (copyFeedback != null) {
                kotlinx.coroutines.delay(2000)
                copyFeedback = null
            }
        }
        Div({ classes("button-row") }) {
            Button({
                onClick {
                    val text = buildTallyText(
                        electionName = displayedTally.tally.electionName,
                        sections = sections,
                        ballotsPerCandidate = ballotsPerCandidate,
                        totalBallots = displayedTally.tally.ballots.size,
                        activeBallots = totalActiveBallots,
                    )
                    copyTextToClipboard(text, apiClient)
                    copyFeedback = "Copied!"
                    copyFeedbackToken += 1
                }
            }) { Text("Copy results as text") }
            Button({
                attr(
                    "title",
                    "Copy every pairwise comparison grouped by candidate, in results order. " +
                        "Pairs with no preference information (no voter ranked both) are omitted."
                )
                onClick {
                    val text = buildPairwiseReportText(activeTally)
                    copyTextToClipboard(text, apiClient)
                    copyFeedback = "Copied as pairwise report!"
                    copyFeedbackToken += 1
                }
            }) { Text("Copy as pairwise report") }
            Button({
                attr(
                    "title",
                    "Copy this election in the format the paste-tally page accepts, " +
                        "so it can be re-tallied or shared without an account."
                )
                onClick {
                    val text = PasteTallyFormat.renderAsPasteText(
                        candidateNames = displayedTally.tally.candidateNames,
                        ballots = displayedTally.tally.ballots,
                        electionName = displayedTally.tally.electionName,
                        tiers = displayedTally.tiers,
                    )
                    copyTextToClipboard(text, apiClient)
                    copyFeedback = "Copied as paste-tally format!"
                    copyFeedbackToken += 1
                }
            }) { Text("Copy as paste-tally format") }
            if (copyFeedback != null) {
                Span({ classes("copy-feedback") }) { Text(copyFeedback!!) }
            }
        }
    }

    if (revealed.isNotEmpty()) {
        renderBallotToggles(
            ballots = revealed,
            active = active,
            onToggle = { confirmation ->
                excluded = if (confirmation in excluded) excluded - confirmation else excluded + confirmation
            },
            onSetAll = { all ->
                excluded = if (all) emptySet() else currentConfirmations
            },
        )
    }

    // Detail tables (head-to-head, process) live on their own admin-style
    // pages — they get wide quickly and don't belong inside the aesthetic
    // election shell. See docs/style-guide.md and
    // docs/tideman-ranked-pairs.md for what each report shows.
    Div({ classes("button-row") }) {
        Button({ onClick { onNavigateToHeadToHead() } }) { Text("View Head-to-Head") }
        Button({ onClick { onNavigateToProcess() } }) { Text("View Process") }
    }
}

@Composable
private fun renderBallotToggles(
    ballots: List<Ballot.Identified>,
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
 * Plain-text rendering of the winners list for paste into chat / email.
 * Mirrors the on-screen Results: same section split (tier cards + naked
 * lists for boundary ties / trailing remainder), same display ranks (ties
 * preserved), same coverage counts (appearances / active ballots).
 *
 * When [activeBallots] equals [totalBallots] the header reads as the
 * full count; when filtered (some ballots toggled off in the viewer) it
 * shows the active-of-total form so the reader knows the numbers reflect
 * a subset.
 */
internal fun buildTallyText(
    electionName: String,
    sections: List<TallySection>,
    ballotsPerCandidate: Map<String, Int>,
    totalBallots: Int,
    activeBallots: Int,
): String {
    val lines = mutableListOf<String>()
    lines += electionName
    lines += if (activeBallots == totalBallots) {
        "Total Ballots: $totalBallots"
    } else {
        "Active Ballots: $activeBallots of $totalBallots"
    }
    lines += "Each row: place, candidate, appears on x of y ballots"
    sections.forEach { section ->
        lines += ""
        val tierName = section.tierName
        if (tierName != null) {
            lines += tierName
        }
        section.places.forEach { place ->
            val count = ballotsPerCandidate[place.candidateName] ?: 0
            lines += "- ${ordinal(place.rank)}: ${place.candidateName} ($count/$activeBallots)"
        }
    }
    return lines.joinToString("\n")
}

/**
 * Plain-text pairwise report: one section per candidate (in tally-results
 * order) listing every head-to-head against the other candidates in the
 * same order. Pairs where no voter ranked both candidates are dropped —
 * a 0-0 line carries no information.
 *
 * Mirrors the `tally-from-snapshot --pairwise-report` CLI output but
 * without the banner equals lines, since clipboard targets are usually
 * chat / docs where the equals stripes look like noise.
 */
internal fun buildPairwiseReportText(tally: Tally): String {
    val lines = mutableListOf<String>()
    lines += "Pairwise: ${tally.electionName}"
    val names = tally.candidateNames
    names.forEachIndexed { i, candidate ->
        lines += ""
        lines += candidate
        names.forEachIndexed { j, opponent ->
            if (i == j) return@forEachIndexed
            val forStrength = tally.preferences[i][j].strength
            val againstStrength = tally.preferences[j][i].strength
            val line = when {
                forStrength == 0 && againstStrength == 0 -> null
                forStrength > againstStrength -> "beats $opponent, $forStrength to $againstStrength"
                againstStrength > forStrength -> "loses to $opponent, $forStrength to $againstStrength"
                else -> "ties $opponent, $forStrength to $againstStrength"
            }
            if (line != null) lines += "  $line"
        }
    }
    return lines.joinToString("\n")
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
