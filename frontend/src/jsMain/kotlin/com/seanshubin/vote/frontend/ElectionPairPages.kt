package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.domain.Ballot
import com.seanshubin.vote.domain.ElectionTally
import com.seanshubin.vote.domain.Place
import com.seanshubin.vote.domain.RankedPairs
import com.seanshubin.vote.domain.Ranking.Companion.prefers
import com.seanshubin.vote.domain.RankingSide
import org.jetbrains.compose.web.dom.*

/**
 * Pairwise Head-to-Head page. The viewer picks two candidates from a chip
 * arena and the page renders, for just those two:
 *   - the direct head-to-head: the raw count for each direction plus the
 *     actual voters whose ballots produced that count, so every pairwise
 *     total is auditable — a total isn't an abstract number, it's a list
 *     of named voters you can scroll through.
 *   - what the Ranked Pairs (Tideman) tally did with the resulting
 *     contest: locked it in, skipped it because of a cycle (with the
 *     cycle that closed it), or treated the pair as a tied non-contest.
 *   - the two candidates' actual positions in the final ranking, read
 *     straight from the places list — the authoritative answer to "where
 *     did they end up", independent of the contest's fate.
 *
 * A skipped contest is the visual answer to "Goodyng beat Cursed 2-0, so
 * why does Cursed end up ranked higher?": the contest existed but the
 * lock-in order had to drop it to avoid a cycle.
 *
 * Selection is sticky-plus-changing: the first pick stays put and the
 * second slot is what gets replaced when a third candidate is clicked.
 * Clicking a currently-selected candidate deselects just that one. With
 * fewer than two selected the detail panel is hidden.
 */
@Composable
fun ElectionHeadToHeadPage(
    apiClient: ApiClient,
    electionName: String,
    currentSide: RankingSide,
    onSetSide: (RankingSide) -> Unit,
    secretBallotEnabled: Boolean,
    onBack: () -> Unit,
) {
    val tallyFetch = rememberFetchState(
        apiClient = apiClient,
        key = "$electionName:$currentSide",
        fallbackErrorMessage = "Failed to load tally",
    ) {
        apiClient.getTally(electionName, currentSide)
    }

    Div({ classes("admin-container") }) {
        H1 { Text("Head-to-Head: $electionName") }
        SideToggle(currentSide, onSetSide, enabled = secretBallotEnabled)
        P({ classes("pair-page-explainer") }) {
            Text(
                "Pick two candidates to see their direct head-to-head — the raw vote " +
                    "and the voters behind each total — and what the Ranked Pairs tally " +
                    "did with that contest: locked it into the final ranking, or skipped " +
                    "it to avoid a cycle. Voters who omit either candidate abstain from " +
                    "the contest."
            )
        }

        Div({ classes("admin-table-scroll") }) {
            when (val state = tallyFetch.state) {
                FetchState.Loading -> P { Text("Loading…") }
                is FetchState.Error -> Div({ classes("error") }) { Text(state.message) }
                is FetchState.Success -> {
                    renderPairView(state.value) { a, b ->
                        renderHeadToHeadDetail(state.value, a, b)
                    }
                }
            }
        }

        Div({ classes("button-row") }) {
            Button({ onClick { onBack() } }) { Text("Back to Election") }
        }
    }
}

/**
 * Full Tideman process page. Walks every directed contest in lock-in
 * order — strongest first — and shows for each contest whether it was
 * locked into the final DAG or skipped because of a cycle with earlier
 * locks. Acts as a global "what the algorithm did, step by step" view
 * that complements the per-pair Head-to-Head page.
 *
 * For a reader who wants to understand why one candidate beat another,
 * the typical flow is: read the per-pair Head-to-Head for the specific
 * pair in question, then come here to see where in the global order that
 * contest sat and which stronger contests forced its hand.
 */
@Composable
fun ElectionProcessPage(
    apiClient: ApiClient,
    electionName: String,
    currentSide: RankingSide,
    onSetSide: (RankingSide) -> Unit,
    secretBallotEnabled: Boolean,
    onBack: () -> Unit,
) {
    val tallyFetch = rememberFetchState(
        apiClient = apiClient,
        key = "$electionName:$currentSide",
        fallbackErrorMessage = "Failed to load tally",
    ) {
        apiClient.getTally(electionName, currentSide)
    }

    Div({ classes("admin-container") }) {
        H1 { Text("Ranked Pairs Process: $electionName") }
        SideToggle(currentSide, onSetSide, enabled = secretBallotEnabled)
        P({ classes("pair-page-explainer") }) {
            Text(
                "Every contest, grouped by strength bucket (winning votes first, " +
                    "less opposition breaking ties). Earlier buckets lock first. " +
                    "Within a bucket, every contest is evaluated against the same " +
                    "reference — the already-locked edges plus every other contest in " +
                    "this bucket — so the outcome doesn't depend on the order rows are " +
                    "listed in. A contest skips iff a path back from its loser to its " +
                    "winner exists in that reference. When a cycle is made entirely of " +
                    "contests of identical strength, every contest in the cycle skips " +
                    "and the candidates involved tie."
            )
        }

        Div({ classes("admin-table-scroll") }) {
            when (val state = tallyFetch.state) {
                FetchState.Loading -> P { Text("Loading…") }
                is FetchState.Error -> Div({ classes("error") }) { Text(state.message) }
                is FetchState.Success -> renderProcessDetail(state.value)
            }
        }

        Div({ classes("button-row") }) {
            Button({ onClick { onBack() } }) { Text("Back to Election") }
        }
    }
}

/**
 * Chip arena + detail-panel scaffold for the Head-to-Head page. Owns the
 * sticky-plus-changing selection state: the first pick is sticky, the
 * second slot rotates as the user explores other candidates against it.
 * Delegates to [detailPanel] when (and only when) two candidates are
 * selected. Resetting [selected] when the election changes prevents stale
 * names from carrying across navigation.
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

/**
 * Detail panel for a chosen pair: the verdict header, the direct
 * head-to-head with named voters, the pair's actual final standing, and —
 * when Tideman skipped the contest — the cycle that forced the skip.
 */
@Composable
private fun renderHeadToHeadDetail(electionTally: ElectionTally, a: String, b: String) {
    val tally = electionTally.tally
    val names = tally.candidateNames
    val ai = names.indexOf(a)
    val bi = names.indexOf(b)
    val aOverB = tally.preferences[ai][bi].strength
    val bOverA = tally.preferences[bi][ai].strength
    val revealed = tally.ballots.filterIsInstance<Ballot.Identified>()

    // Tideman emits one directed contest per pair, or none when the pair
    // tied in pairwise count — so at most one contest matches either
    // direction.
    val contest = tally.contests.firstOrNull { c ->
        (c.winner == a && c.loser == b) || (c.winner == b && c.loser == a)
    }

    Div({ classes("pair-detail") }) {
        Div({ classes("pair-detail-header") }) {
            Text(headToHeadVerdict(a, b, aOverB, bOverA, contest))
        }
        renderDirectHeadToHead(a, b, aOverB, bOverA, revealed, (tally.side == RankingSide.SECRET))
        renderFinalStanding(a, b, tally.places)
        val outcome = contest?.outcome
        if (outcome is RankedPairs.Outcome.SkippedByCycle) {
            renderCycleSection(contest, outcome, tally.contests, electionTally::isTier)
        }
    }
}

/**
 * One-line verdict for the pair: the direct score plus what Tideman did
 * with the contest. The skipped wording is keyed on
 * [RankedPairs.Outcome.SkippedByCycle.forcedByStrongerContests] — never on
 * a guess at whether the candidates tie, which is the [renderFinalStanding]
 * line's job.
 */
private fun headToHeadVerdict(
    a: String,
    b: String,
    aOverB: Int,
    bOverA: Int,
    contest: RankedPairs.Contest?,
): String {
    if (contest == null) {
        return "$a and $b tied in the direct contest, $aOverB to $bOverA — " +
            "no contest was locked or skipped between them."
    }
    val score = "${contest.winningVotes} to ${contest.losingVotes}"
    return when (val outcome = contest.outcome) {
        is RankedPairs.Outcome.Locked ->
            "${contest.winner} beats ${contest.loser} $score — " +
                "locked into the final ranking."
        is RankedPairs.Outcome.SkippedByCycle ->
            if (outcome.forcedByStrongerContests) {
                "${contest.winner} beat ${contest.loser} $score directly, but this " +
                    "contest was skipped — stronger contests already locked outrank it."
            } else {
                "${contest.winner} beat ${contest.loser} $score directly, but this " +
                    "contest was skipped — it sits in a cycle of equal-strength contests."
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

/**
 * Direct head-to-head between [a] and [b]: the two pair-side panels (winner
 * highlighted, ordered with the winner on the left when one exists) plus
 * the abstain section. The verdict header sits above it and, when Tideman
 * skipped the contest, the cycle context sits below — see
 * [renderHeadToHeadDetail].
 */
@Composable
private fun renderDirectHeadToHead(
    a: String,
    b: String,
    aOverB: Int,
    bOverA: Int,
    revealed: List<Ballot.Identified>,
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
 * The two candidates' actual positions in the final ranking, read straight
 * from [places]. This is the authoritative answer to "where did they end
 * up" — and the only sound source for whether they tie. A skipped contest
 * never on its own implies a tie; the cycle section explains the skip,
 * this line states the outcome.
 */
@Composable
private fun renderFinalStanding(a: String, b: String, places: List<Place>) {
    val rankByName = places.associate { it.candidateName to it.rank }
    val rankA = rankByName[a] ?: return
    val rankB = rankByName[b] ?: return
    Div({ classes("pair-standing") }) {
        Text(
            if (rankA == rankB) {
                "Final ranking: $a and $b both place ${ordinal(rankA)}."
            } else {
                val leader = if (rankA < rankB) a else b
                val trailer = if (rankA < rankB) b else a
                "Final ranking: $leader places ${ordinal(minOf(rankA, rankB))}, " +
                    "$trailer places ${ordinal(maxOf(rankA, rankB))}."
            }
        )
    }
}

/**
 * Why-it-was-skipped explainer. Renders the cycle that locking the contest
 * would close — each edge as its own row with its real outcome (Locked or
 * Skipped) — and a closure indicator showing the loop closes back to its
 * start, making the closed-loop structure explicit even for N-cycles.
 *
 * The intro paragraph branches on
 * [RankedPairs.Outcome.SkippedByCycle.forcedByStrongerContests]:
 *   - forced: every other contest in this cycle is strictly stronger and
 *     already locked, so this contest is the uniquely weakest edge and its
 *     drop is forced.
 *   - not forced: every closing cycle includes a contest of equal strength,
 *     so the contest is dropped to avoid breaking an even tie arbitrarily.
 *
 * It deliberately makes no claim about whether the two candidates tie in
 * the final ranking — that is [renderFinalStanding]'s job, read from the
 * places list. A contest can sit in an all-equal cycle whose candidates
 * still land at different places.
 */
@Composable
private fun renderCycleSection(
    contest: RankedPairs.Contest,
    outcome: RankedPairs.Outcome.SkippedByCycle,
    allContests: List<RankedPairs.Contest>,
    isTier: (String) -> Boolean,
) {
    val byEdge: Map<Pair<String, String>, IndexedValue<RankedPairs.Contest>> =
        allContests.withIndex().associateBy { (_, c) -> c.winner to c.loser }
    val edges: List<IndexedValue<RankedPairs.Contest>> =
        outcome.cyclePath.zipWithNext().mapNotNull { (from, to) -> byEdge[from to to] }
    val cycleStart = outcome.cyclePath.first()
    val contestStep = allContests.indexOf(contest) + 1

    Div({ classes("rp-cycle-section") }) {
        H3 { Text("Why it was skipped") }
        P {
            Text(
                if (outcome.forcedByStrongerContests) {
                    "Locking ${contest.winner} → ${contest.loser} would close the cycle " +
                        "below, and every other contest in it is stronger and already " +
                        "locked. ${contest.winner} → ${contest.loser} " +
                        "(${contest.winningVotes} to ${contest.losingVotes}) is the " +
                        "weakest link, so it is the one dropped to break the cycle."
                } else {
                    "Locking ${contest.winner} → ${contest.loser} would close the cycle " +
                        "below. No contest in the cycle outranks the rest — at least one " +
                        "is equal in strength to this one — so the algorithm drops this " +
                        "contest rather than break an even tie arbitrarily."
                }
            )
        }

        Div({ classes("rp-cycle-comparison") }) {
            edges.forEach { (index, edgeContest) ->
                renderCycleEdgeRow(
                    step = index + 1,
                    contest = edgeContest,
                    isCurrentContest = false,
                    isTier = isTier,
                )
            }
            renderCycleEdgeRow(
                step = contestStep,
                contest = contest,
                isCurrentContest = true,
                isTier = isTier,
            )
            // Closure indicator: makes the loop's closed-loop structure
            // explicit for any N. Without this, a reader scanning N rows
            // top-to-bottom might miss that the last edge's loser is the
            // first edge's winner.
            Div({ classes("rp-cycle-closure") }) {
                Span({ classes("rp-cycle-closure-arrow") }) { Text("↩") }
                Span { Text(" closes back to ") }
                renderNameChip(cycleStart, isTier(cycleStart))
            }
        }
    }
}

@Composable
private fun renderCycleEdgeRow(
    step: Int,
    contest: RankedPairs.Contest,
    isCurrentContest: Boolean,
    isTier: (String) -> Boolean,
) {
    val locked = contest.outcome is RankedPairs.Outcome.Locked
    Div({
        classes("rp-cycle-row")
        if (locked) classes("rp-cycle-row-locked") else classes("rp-cycle-row-skipped")
        if (isCurrentContest) classes("rp-cycle-row-current")
    }) {
        Span({ classes("rp-cycle-row-step") }) { Text("Step $step") }
        Span({
            classes("rp-cycle-row-status")
            if (locked) classes("rp-process-status-locked") else classes("rp-process-status-skipped")
        }) { Text(if (locked) "Locked" else "Skipped") }
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
internal fun renderProcessDetail(electionTally: ElectionTally) {
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
                "Earlier buckets lock first. Within a bucket, contests are evaluated " +
                "atomically — each one against the same reference graph (already-locked " +
                "edges plus every other contest in this bucket) — so the order rows " +
                "appear in doesn't change any outcomes; it's display-only. When a cycle " +
                "is made of contests at the same strength, every contest in the cycle " +
                "skips together, and the candidates involved tie at the same place."
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
    ballots: List<Ballot.Identified>,
    a: String,
    b: String,
): List<String> = ballots
    .filter { it.rankings.prefers(a, b) }
    .map { it.voterName }
    .sorted()

private fun votersWhoAbstainOnPair(
    ballots: List<Ballot.Identified>,
    a: String,
    b: String,
): List<String> = ballots
    .filter { !it.rankings.prefers(a, b) && !it.rankings.prefers(b, a) }
    .map { it.voterName }
    .sorted()
