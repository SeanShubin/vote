package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.domain.Ballot
import com.seanshubin.vote.domain.ElectionTally
import com.seanshubin.vote.domain.RankedPairs
import com.seanshubin.vote.domain.Ranking.Companion.prefers
import com.seanshubin.vote.domain.RankingSide
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
                "What the Ranked Pairs tally did with the direct contest between these " +
                    "two candidates. A contest locks into the final ranking unless it " +
                    "would create a cycle with stronger contests already locked, or with " +
                    "other contests of identical strength that can't all coexist — in " +
                    "either case the contest is skipped, and contests skipped because of " +
                    "an equal-strength conflict leave the candidates tied."
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
    val verdict = when {
        aOverB > bOverA -> "$a beats $b $aOverB to $bOverA"
        bOverA > aOverB -> "$b beats $a $bOverA to $aOverB"
        else -> "$a and $b tied at $aOverB"
    }
    val revealed = tally.ballots.filterIsInstance<Ballot.Identified>()

    Div({ classes("pair-detail") }) {
        Div({ classes("pair-detail-header") }) { Text(verdict) }
        renderDirectHeadToHead(a, b, aOverB, bOverA, revealed, (tally.side == RankingSide.SECRET))
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
    val revealed = tally.ballots.filterIsInstance<Ballot.Identified>()

    // Find the directed contest between a and b in the Ranked Pairs
    // lock-in record, if one exists. A tied pair (aOverB == bOverA)
    // won't appear in `contests` at all because Tideman emits no contest
    // for ties.
    val contestIndex = tally.contests.indexOfFirst { contest ->
        (contest.winner == a && contest.loser == b) ||
            (contest.winner == b && contest.loser == a)
    }
    val contest = contestIndex.takeIf { it >= 0 }?.let { tally.contests[it] }
    val cycleAnalysis = contest?.let { analyzeCycle(it, contestIndex, tally.contests) }

    Div({ classes("pair-detail") }) {
        renderDecisionHeader(a, b, contest, contestIndex, tally.contests.size, cycleAnalysis)
        renderDirectHeadToHead(a, b, aOverB, bOverA, revealed, (tally.side == RankingSide.SECRET))
        if (cycleAnalysis != null) {
            renderCycleSection(contest, cycleAnalysis, electionTally::isTier)
        }
    }
}

/**
 * Classification of a skipped contest's cycle, used by both the verdict
 * header and the long-form "why" section so the two stay in sync.
 *
 * Three cases (N = cycle size):
 *   - **LonelyWeakest**: every other contest in the cycle is locked
 *     from an earlier (stronger) bucket. This contest is the uniquely
 *     weakest edge in the cycle and gets dropped to break the loop.
 *   - **PureTie**: every contest in the cycle is at the same strength
 *     as this one. None lock — the algorithm can't pick which to drop
 *     without an arbitrary tiebreak, so all N skip together.
 *   - **Mixed**: some contests in the cycle are stronger and locked,
 *     the remaining T (including this one) are tied at this strength
 *     and skip together for the same reason as PureTie.
 *
 * The same edge can never appear as Skipped-from-a-different-bucket:
 * a skipped contest's cycle path is BFS through
 * (locked edges) ∪ (own-bucket tentative edges), so any Skipped edge
 * in the path must be from this contest's own bucket.
 */
private enum class CycleKind { LONELY_WEAKEST, PURE_TIE, MIXED }

/** Cycle-path edge: step index (1-based) paired with the contest. */
private data class CycleEdge(val step: Int, val contest: RankedPairs.Contest)

/**
 * Analysis of [contest]'s cycle, or null if [contest] is not skipped.
 * [edges] lists the cycle-path contests in the order they appear in
 * the path (loser → winner direction), so the current contest
 * implicitly closes the loop by returning to [edges].first()'s winner.
 */
private data class CycleAnalysis(
    val kind: CycleKind,
    val edges: List<CycleEdge>,
    val skippedStep: Int,
    val totalSize: Int,
    val lockedCount: Int,
    val tiedCount: Int,
)

private fun analyzeCycle(
    contest: RankedPairs.Contest,
    contestIndex: Int,
    allContests: List<RankedPairs.Contest>,
): CycleAnalysis? {
    val outcome = contest.outcome as? RankedPairs.Outcome.SkippedByCycle ?: return null
    val byEdge: Map<Pair<String, String>, IndexedValue<RankedPairs.Contest>> =
        allContests.withIndex().associateBy { (_, c) -> c.winner to c.loser }
    val edges: List<CycleEdge> = outcome.cyclePath.zipWithNext().mapNotNull { (from, to) ->
        byEdge[from to to]?.let { CycleEdge(it.index + 1, it.value) }
    }
    val locked = edges.count { it.contest.outcome is RankedPairs.Outcome.Locked }
    val tied = edges.size - locked + 1  // +1 for the current contest itself
    val total = edges.size + 1
    val kind = when {
        tied == 1 -> CycleKind.LONELY_WEAKEST
        tied == total -> CycleKind.PURE_TIE
        else -> CycleKind.MIXED
    }
    return CycleAnalysis(
        kind = kind,
        edges = edges,
        skippedStep = contestIndex + 1,
        totalSize = total,
        lockedCount = locked,
        tiedCount = tied,
    )
}

@Composable
private fun renderDecisionHeader(
    a: String,
    b: String,
    contest: RankedPairs.Contest?,
    contestIndex: Int,
    contestTotal: Int,
    cycleAnalysis: CycleAnalysis?,
) {
    val verdict = when {
        contest == null ->
            "$a and $b tied in the direct contest — no edge locked, no edge skipped."
        contest.outcome is RankedPairs.Outcome.Locked ->
            "${contest.winner} beats ${contest.loser} — contest locked into the final ranking " +
                "(step ${contestIndex + 1} of $contestTotal)."
        cycleAnalysis != null -> {
            val stepInfo = "step ${contestIndex + 1} of $contestTotal"
            when (cycleAnalysis.kind) {
                CycleKind.LONELY_WEAKEST ->
                    "${contest.winner} beat ${contest.loser} directly, but the contest was " +
                        "skipped ($stepInfo) — stronger contests in its " +
                        "${cycleAnalysis.totalSize}-cycle already locked, so dropping this " +
                        "one is what breaks the cycle."
                CycleKind.PURE_TIE -> {
                    val n = cycleAnalysis.totalSize
                    "${contest.winner} beat ${contest.loser} directly, but the contest was " +
                        "skipped ($stepInfo) — it sits in a cycle of $n equally-strong " +
                        "contests; none lock, and the candidates involved tie."
                }
                CycleKind.MIXED -> {
                    val tied = cycleAnalysis.tiedCount
                    val others = tied - 1
                    val otherWord = if (others == 1) "other contest" else "other contests"
                    "${contest.winner} beat ${contest.loser} directly, but the contest was " +
                        "skipped ($stepInfo) — it's tied in strength with $others $otherWord " +
                        "in its ${cycleAnalysis.totalSize}-cycle, and the algorithm refuses " +
                        "to pick which of the tied contests to keep."
                }
            }
        }
        else -> ""
    }
    Div({ classes("pair-detail-header") }) { Text(verdict) }
}

/**
 * Direct head-to-head between [a] and [b]: the two pair-side panels (winner
 * highlighted, ordered with the winner on the left when one exists) plus
 * the abstain section. Shared by the Preferences page (which adds a verdict
 * header above) and the Decision page (which adds the Tideman cycle context
 * around it).
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
 * Why-it-was-skipped explainer for the Decision page. Lays out each
 * contest in the cycle (including this one) as its own row with the
 * actual outcome attached — Locked (from an earlier, stronger bucket)
 * or Skipped (same bucket as this contest, tied and dropped together).
 * After the last row, a closure indicator shows that the loop closes
 * back to the cycle's starting node, making the closed-loop structure
 * explicit even for N-cycles with N > 3.
 *
 * The intro paragraph branches on the cycle's classification:
 *   - LonelyWeakest: this contest is the uniquely weakest; others locked.
 *   - PureTie:       all N contests are at the same strength; all skip.
 *   - Mixed:         L stronger ones locked, T tied (this one included).
 *
 * The wording scales to any N because it talks in terms of counts
 * (L, T, totalSize) rather than enumerating specific positions.
 */
@Composable
private fun renderCycleSection(
    contest: RankedPairs.Contest,
    cycleAnalysis: CycleAnalysis,
    isTier: (String) -> Boolean,
) {
    val outcome = contest.outcome as RankedPairs.Outcome.SkippedByCycle
    val cycleStart = outcome.cyclePath.first()

    Div({ classes("rp-cycle-section") }) {
        H3 { Text("Why it was skipped") }
        P { Text(cycleExplanation(contest, cycleAnalysis)) }

        Div({ classes("rp-cycle-comparison") }) {
            cycleAnalysis.edges.forEach { edge ->
                renderCycleEdgeRow(
                    step = edge.step,
                    contest = edge.contest,
                    isCurrentContest = false,
                    isTier = isTier,
                )
            }
            renderCycleEdgeRow(
                step = cycleAnalysis.skippedStep,
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

private fun cycleExplanation(
    contest: RankedPairs.Contest,
    analysis: CycleAnalysis,
): String {
    val n = analysis.totalSize
    val l = analysis.lockedCount
    val t = analysis.tiedCount
    val w = contest.winningVotes
    val ls = contest.losingVotes
    return when (analysis.kind) {
        CycleKind.LONELY_WEAKEST ->
            "These $n contests together form a cycle. Every other contest in the cycle " +
                "locked into the ranking from a stronger bucket. This contest, at $w " +
                "winning and $ls losing, is the weakest of the $n — so it's the one " +
                "that gets dropped to break the loop."
        CycleKind.PURE_TIE ->
            "These $n contests together form a cycle, and all $n are tied at the same " +
                "strength ($w winning · $ls losing). Together they create a cycle, so we " +
                "can keep at most ${n - 1}. But picking which one to drop would require an " +
                "arbitrary tiebreaker — the vote counts give no reason to prefer one over " +
                "the others. So all $n skip together, and the candidates involved end up " +
                "tied in the final ranking."
        CycleKind.MIXED -> {
            val contestWord = if (l == 1) "contest" else "contests"
            val keepCount = t - 1
            val tiedWord = if (t == 1) "contest" else "contests"
            val keepWord = if (keepCount == 1) "one" else "${keepCount} of them"
            val coherentNote = if (keepCount == 0) {
                ""
            } else {
                "Keeping any $keepWord would be a coherent outcome, but " +
                    "picking which to keep would be arbitrary. "
            }
            "These $n contests together form a cycle. $l stronger $contestWord in the " +
                "cycle locked into the ranking from earlier buckets. The remaining $t " +
                "$tiedWord — including this one — are tied at this strength " +
                "($w winning · $ls losing). ${coherentNote}All $t skip together."
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
