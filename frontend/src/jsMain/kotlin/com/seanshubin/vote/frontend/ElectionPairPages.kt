package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.domain.Ballot
import com.seanshubin.vote.domain.ElectionTally
import com.seanshubin.vote.domain.RankedPairs
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
        P({ classes("pair-page-explainer") }) {
            Text(
                "Direct head-to-head between two candidates. Each total is the count of " +
                    "voters who ranked both candidates and put one above the other. " +
                    "Voters who omit either candidate abstain from this contest."
            )
        }

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
 * Per-pair Tideman decision page. For the selected pair the page shows:
 *   - the direct head-to-head, with the same voter-list grounding as the
 *     Preferences page (because every Tideman decision starts there).
 *   - what Tideman did with the resulting contest: locked it in, skipped
 *     it because of a cycle (with the locked-edge path that closed the
 *     cycle), or treated the pair as a tied non-contest. A skipped
 *     contest is the visual answer to "Goodyng beat Cursed 2-0, so why
 *     does Cursed end up ranked higher?" — the lock-in order put
 *     stronger contests in first and the direct verdict got squeezed
 *     out by a cycle.
 *
 * Tier markers are excluded from the chip arena (the page is for
 * candidate comparisons), but they can show up inside a cycle path the
 * algorithm reports, styled distinctly so a reader can tell.
 */
@Composable
fun ElectionDecisionPage(
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
        H1 { Text("Decision: $electionName") }
        P({ classes("pair-page-explainer") }) {
            Text(
                "What Tideman's Ranked Pairs did with the direct contest between these " +
                    "two candidates. A contest is locked into the final ranking unless " +
                    "doing so would create a cycle with already-locked stronger contests, " +
                    "in which case it's skipped."
            )
        }

        Div({ classes("admin-table-scroll") }) {
            when (val state = tallyFetch.state) {
                FetchState.Loading -> P { Text("Loading…") }
                is FetchState.Error -> Div({ classes("error") }) { Text(state.message) }
                is FetchState.Success -> {
                    renderPairView(state.value) { a, b ->
                        renderDecisionDetail(state.value, a, b)
                    }
                }
            }
        }

        Button({ onClick { onBack() } }) { Text("Back to Election") }
    }
}

/**
 * Full Tideman process page. Walks every directed contest in lock-in
 * order — strongest first — and shows for each contest whether it was
 * locked into the final DAG or skipped because of a cycle with earlier
 * locks. Acts as a global "what the algorithm did, step by step" view
 * that complements the per-pair Decision page.
 *
 * For a reader who wants to understand why one candidate beat another,
 * the typical flow is: read the per-pair Decision for the specific pair
 * in question, then come here to see where in the global order that
 * contest sat and which stronger contests forced its hand.
 */
@Composable
fun ElectionProcessPage(
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
        H1 { Text("Ranked Pairs Process: $electionName") }
        P({ classes("pair-page-explainer") }) {
            Text(
                "Every contest in the order Tideman's Ranked Pairs processed it: " +
                    "strongest first by winning votes, with less opposition breaking ties. " +
                    "Each contest is locked into the final ranking unless doing so would " +
                    "close a cycle with already-locked contests."
            )
        }

        Div({ classes("admin-table-scroll") }) {
            when (val state = tallyFetch.state) {
                FetchState.Loading -> P { Text("Loading…") }
                is FetchState.Error -> Div({ classes("error") }) { Text(state.message) }
                is FetchState.Success -> renderProcessDetail(state.value)
            }
        }

        Button({ onClick { onBack() } }) { Text("Back to Election") }
    }
}

/**
 * Chip arena + detail-panel scaffold shared by the Preferences and
 * Decision pages. Owns the sticky-plus-changing selection state: the
 * first pick is sticky, the second slot rotates as the user explores
 * other candidates against it. Delegates to [detailPanel] when (and only
 * when) two candidates are selected. Resetting [selected] when the
 * election changes prevents stale names from carrying across navigation.
 *
 * Tier markers are intentionally excluded from the selection chips — the
 * page is for comparing candidates. Tiers can still appear inside the
 * detail panel (e.g. as nodes in a cycle path the algorithm reports),
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
            if (bWins) {
                renderPairSide(name = b, voters = bVoters, count = bOverA, isWinner = true, isSecret = tally.secretBallot)
                renderPairSide(name = a, voters = aVoters, count = aOverB, isWinner = false, isSecret = tally.secretBallot)
            } else {
                renderPairSide(name = a, voters = aVoters, count = aOverB, isWinner = aWins, isSecret = tally.secretBallot)
                renderPairSide(name = b, voters = bVoters, count = bOverA, isWinner = false, isSecret = tally.secretBallot)
            }
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
private fun renderDecisionDetail(electionTally: ElectionTally, a: String, b: String) {
    val tally = electionTally.tally
    val names = tally.candidateNames
    val ai = names.indexOf(a)
    val bi = names.indexOf(b)
    val aOverB = tally.preferences[ai][bi].strength
    val bOverA = tally.preferences[bi][ai].strength
    val revealed = tally.ballots.filterIsInstance<Ballot.Revealed>()

    // Find the directed contest between a and b in the Tideman lock-in
    // record, if one exists. A tied pair (aOverB == bOverA) won't appear
    // in `contests` at all because Tideman emits no contest for ties.
    val contestIndex = tally.contests.indexOfFirst { contest ->
        (contest.winner == a && contest.loser == b) ||
            (contest.winner == b && contest.loser == a)
    }
    val contest = contestIndex.takeIf { it >= 0 }?.let { tally.contests[it] }

    Div({ classes("pair-detail") }) {
        renderDecisionHeader(a, b, contest, contestIndex, tally.contests.size)
        renderDecisionDirect(a, b, aOverB, bOverA, revealed, tally.secretBallot)
        if (contest?.outcome is RankedPairs.Outcome.SkippedByCycle) {
            renderCycleSection(contest, contestIndex, tally.contests, electionTally::isTier)
        }
    }
}

@Composable
private fun renderDecisionHeader(
    a: String,
    b: String,
    contest: RankedPairs.Contest?,
    contestIndex: Int,
    contestTotal: Int,
) {
    val verdict = when {
        contest == null ->
            "$a and $b tied in the direct contest — no edge locked, no edge skipped."
        contest.outcome is RankedPairs.Outcome.Locked ->
            "${contest.winner} beats ${contest.loser} — contest locked into the final ranking " +
                "(step ${contestIndex + 1} of $contestTotal)."
        contest.outcome is RankedPairs.Outcome.SkippedByCycle ->
            "${contest.winner} beat ${contest.loser} directly, but the contest was skipped " +
                "(step ${contestIndex + 1} of $contestTotal) — locking it in would have " +
                "closed a cycle with earlier, stronger contests."
        else -> ""
    }
    Div({ classes("pair-detail-header") }) { Text(verdict) }
}

@Composable
private fun renderDecisionDirect(
    a: String,
    b: String,
    aOverB: Int,
    bOverA: Int,
    revealed: List<Ballot.Revealed>,
    isSecret: Boolean,
) {
    val aVoters = votersWhoPrefer(revealed, a, b)
    val bVoters = votersWhoPrefer(revealed, b, a)
    val abstainVoters = votersWhoAbstainOnPair(revealed, a, b)
    val aWins = aOverB > bOverA
    val bWins = bOverA > aOverB

    Div({ classes("pair-side-row") }) {
        if (bWins) {
            renderPairSide(name = b, voters = bVoters, count = bOverA, isWinner = true, isSecret = isSecret)
            renderPairSide(name = a, voters = aVoters, count = aOverB, isWinner = false, isSecret = isSecret)
        } else {
            renderPairSide(name = a, voters = aVoters, count = aOverB, isWinner = aWins, isSecret = isSecret)
            renderPairSide(name = b, voters = bVoters, count = bOverA, isWinner = false, isSecret = isSecret)
        }
    }

    if (isSecret || abstainVoters.isNotEmpty()) {
        Div({ classes("pair-abstain") }) {
            H3 { Text("No expressed preference (${abstainVoters.size})") }
            if (isSecret) {
                P({ classes("pair-secret-note") }) { Text("(ballots are secret)") }
            } else {
                renderVoterList(abstainVoters)
            }
        }
    }
}

/**
 * Why-it-was-skipped explainer for the Decision page. Shows the stronger
 * contests that locked first (each with its step number and strength)
 * next to the skipped contest's own step and strength, so a reader can
 * see *why* those contests had priority: by the lock-in order — winning
 * votes descending, then losing votes ascending — they sit ahead of
 * this one.
 *
 * The "stronger" rows are the locked contests that form the cycle's
 * back path: for each adjacent pair (path[i], path[i+1]) in the cycle
 * path, there's a corresponding locked contest with winner=path[i] and
 * loser=path[i+1]. We render them in the order they appear in the
 * cycle path (which is also lock-in step order, because BFS through
 * locked-first edges).
 */
@Composable
private fun renderCycleSection(
    contest: RankedPairs.Contest,
    skippedStep: Int,
    allContests: List<RankedPairs.Contest>,
    isTier: (String) -> Boolean,
) {
    val skipped = contest.outcome as RankedPairs.Outcome.SkippedByCycle
    val byEdge = allContests.withIndex()
        .associateBy { (_, c) -> c.winner to c.loser }
    val cycleEdges: List<Pair<Int, RankedPairs.Contest>> =
        skipped.cyclePath.zipWithNext().mapNotNull { (from, to) ->
            byEdge[from to to]?.let { it.index + 1 to it.value }
        }

    Div({ classes("rp-cycle-section") }) {
        H3 { Text("Why it was skipped") }
        P {
            Text(
                "By the time Tideman reached this contest, the contests below had " +
                    "already locked into the ranking. They were processed first because " +
                    "the lock-in order sorts contests strongest-first — winning votes " +
                    "descending, then losing votes ascending — and each had higher " +
                    "priority than this one on that ordering. Together they form a path " +
                    "from ${contest.loser} back to ${contest.winner}, so locking " +
                    "${contest.winner} → ${contest.loser} now would close it into a cycle."
            )
        }
        Div({ classes("rp-cycle-comparison") }) {
            cycleEdges.forEach { (step, locked) ->
                renderCycleEdgeRow(
                    step = step,
                    contest = locked,
                    label = "Locked",
                    rowKind = CycleRowKind.LOCKED,
                    isTier = isTier,
                )
            }
            renderCycleEdgeRow(
                step = skippedStep,
                contest = contest,
                label = "Skipped",
                rowKind = CycleRowKind.SKIPPED,
                isTier = isTier,
            )
        }
    }
}

private enum class CycleRowKind { LOCKED, SKIPPED }

@Composable
private fun renderCycleEdgeRow(
    step: Int,
    contest: RankedPairs.Contest,
    label: String,
    rowKind: CycleRowKind,
    isTier: (String) -> Boolean,
) {
    Div({
        classes("rp-cycle-row")
        when (rowKind) {
            CycleRowKind.LOCKED -> classes("rp-cycle-row-locked")
            CycleRowKind.SKIPPED -> classes("rp-cycle-row-skipped")
        }
    }) {
        Span({ classes("rp-cycle-row-step") }) { Text("Step $step") }
        Span({
            classes("rp-cycle-row-status")
            when (rowKind) {
                CycleRowKind.LOCKED -> classes("rp-process-status-locked")
                CycleRowKind.SKIPPED -> classes("rp-process-status-skipped")
            }
        }) { Text(label) }
        Span({ classes("rp-cycle-row-edge") }) {
            renderNameChip(contest.winner, isTier(contest.winner))
            Span({ classes("rp-cycle-arrow") }) { Text("→") }
            renderNameChip(contest.loser, isTier(contest.loser))
        }
        Span({ classes("rp-cycle-row-strength") }) {
            Text("${contest.winningVotes} winning · ${contest.losingVotes} losing")
        }
    }
}

/**
 * Process page renderer. Walks the contests in lock-in order and groups
 * adjacent contests with identical (winning votes, losing votes) into
 * "strength buckets," emitting a section header at each bucket boundary.
 *
 * The grouping is the visual answer to "why was that one locked before
 * this one?" — the bucket header announces the strength tier, and rows
 * within the bucket are alphabetical (the final tiebreaker), so the
 * order is fully explained by what the reader can see on the page.
 *
 * Contests within the same bucket can still differ in outcome (locked
 * vs skipped): the first contest in the bucket might lock, and a later
 * contest in the same bucket might be skipped because the earlier lock
 * closed a path. The row's status pill makes this visible.
 */
@Composable
private fun renderProcessDetail(electionTally: ElectionTally) {
    val contests = electionTally.tally.contests
    if (contests.isEmpty()) {
        P { Text("No contests — every candidate pair is tied or there are not enough candidates.") }
        return
    }
    val locked = contests.count { it.outcome is RankedPairs.Outcome.Locked }
    val skipped = contests.size - locked
    Div({ classes("rp-process-summary") }) {
        Text("$locked locked · $skipped skipped · ${contests.size} total contests")
    }
    P({ classes("rp-process-explainer") }) {
        Text(
            "Contests are grouped by strength bucket (winning votes, then losing votes). " +
                "Earlier buckets locked first; within a bucket, contests are processed " +
                "alphabetically by winner then loser — a deterministic tiebreaker for " +
                "contests with identical strength."
        )
    }
    Div({ classes("rp-process-list") }) {
        var prevKey: Pair<Int, Int>? = null
        var bucketSize = 0
        // Pre-compute bucket sizes for header counts.
        val bucketCounts: Map<Pair<Int, Int>, Int> = contests
            .groupingBy { it.winningVotes to it.losingVotes }
            .eachCount()
        contests.forEachIndexed { idx, contest ->
            val key = contest.winningVotes to contest.losingVotes
            if (key != prevKey) {
                renderBucketHeader(
                    winning = contest.winningVotes,
                    losing = contest.losingVotes,
                    count = bucketCounts.getValue(key),
                )
                prevKey = key
                bucketSize = 0
            }
            bucketSize++
            renderProcessRow(idx + 1, contest, electionTally::isTier)
        }
    }
}

@Composable
private fun renderBucketHeader(winning: Int, losing: Int, count: Int) {
    Div({ classes("rp-process-bucket-header") }) {
        Span({ classes("rp-process-bucket-label") }) {
            Text("$winning winning · $losing losing")
        }
        Span({ classes("rp-process-bucket-count") }) {
            Text(if (count == 1) "1 contest" else "$count contests")
        }
    }
}

@Composable
private fun renderProcessRow(
    step: Int,
    contest: RankedPairs.Contest,
    isTier: (String) -> Boolean,
) {
    val locked = contest.outcome is RankedPairs.Outcome.Locked
    Div({
        classes("rp-process-row")
        if (locked) classes("rp-process-row-locked") else classes("rp-process-row-skipped")
    }) {
        Div({ classes("rp-process-row-header") }) {
            Span({ classes("rp-process-step") }) { Text("Step $step") }
            Span({
                classes("rp-process-status")
                if (locked) classes("rp-process-status-locked") else classes("rp-process-status-skipped")
            }) {
                Text(if (locked) "Locked" else "Skipped")
            }
        }
        Div({ classes("rp-process-contest") }) {
            renderNameChip(contest.winner, isTier(contest.winner))
            Span({ classes("rp-process-arrow") }) { Text("→") }
            renderNameChip(contest.loser, isTier(contest.loser))
            Span({ classes("rp-process-margin") }) {
                Text("${contest.winningVotes} to ${contest.losingVotes}")
            }
        }
        val outcome = contest.outcome
        if (outcome is RankedPairs.Outcome.SkippedByCycle) {
            Div({ classes("rp-process-reason") }) {
                Span({ classes("rp-process-reason-label") }) { Text("Cycle: ") }
                outcome.cyclePath.forEachIndexed { i, name ->
                    if (i > 0) Span({ classes("rp-cycle-arrow") }) { Text("→") }
                    renderNameChip(name, isTier(name))
                }
            }
        }
    }
}

@Composable
private fun renderNameChip(name: String, isTier: Boolean) {
    Span({
        classes("rp-name-chip")
        if (isTier) classes("rp-name-chip-tier")
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
