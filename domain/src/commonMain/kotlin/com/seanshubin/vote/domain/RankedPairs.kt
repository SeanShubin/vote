package com.seanshubin.vote.domain

import com.seanshubin.vote.domain.Place.Companion.adjustForTies
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Ranked Pairs (Tideman 1987), with a deterministic numeric-tie rule in
 * place of Tideman's original random-ballot tiebreaker. The Condorcet
 * completion that replaced this project's original Schulze pipeline.
 *
 * Input: the pairwise preference counts (already computed under the
 * informed-voter rule — a ballot only votes in a contest between A and B
 * if it ranked both).
 *
 * Process:
 *   1. For every unordered pair {a, b}, if one direction's count strictly
 *      exceeds the other, emit one directed contest. Equal counts produce
 *      no contest (a tie).
 *   2. Sort contests strongest first: winning votes descending, then
 *      losing votes ascending. Within an equal-strength bucket, the
 *      remaining alphabetical-sort key controls only the *display order*
 *      of contests in the report — it has no effect on which contests
 *      lock or skip (see step 3).
 *   3. Walk the sorted contests **bucket-at-a-time**, where a bucket is
 *      all contests with identical (winning votes, losing votes). For
 *      each bucket, decide each contest's outcome against the same
 *      reference graph: (already-locked edges from earlier buckets) ∪
 *      (every other contest in the current bucket). A contest is
 *      *skipped* iff its loser → winner has a path through that
 *      reference graph; otherwise it *locks*. Contests in the same
 *      bucket are evaluated independently — no order dependency — so
 *      the decision doesn't depend on alphabetical or any other arbitrary
 *      ordering. A cycle entirely within one bucket skips all of the
 *      cycle's contests, and the candidates in the cycle end up tied at
 *      the same place.
 *   4. Derive places by topological layering of the locked DAG: at each
 *      step the candidates with no incoming locked edge (among the
 *      still-remaining set) all share a place.
 *
 * Why bucket-at-a-time: Tideman's original 1987 paper resolves ties
 * between equal-strength contests using a randomly selected ballot. That
 * removes alphabetical bias at the cost of determinism — an election
 * with a perfect numeric tie among cycle members could come out
 * differently on different runs. Atomic buckets keep determinism without
 * the alphabetical bias: when the algorithm genuinely can't separate
 * contests by the numbers voters provided, it doesn't pretend to — the
 * candidates involved tie.
 *
 * Why Ranked Pairs over Schulze at all: when no cycle exists, the two
 * methods agree. When a cycle does exist, Ranked Pairs' resolution is a
 * story the report can quote contest by contest. Schulze's strongest-
 * path closure was algebraically elegant but not auditable in the same
 * way. See docs/tideman-ranked-pairs.md.
 *
 * Condorcet still holds. A candidate who beats every other candidate
 * directly has only outgoing edges in the contest graph — no incoming
 * edge exists at all, so no path can ever end at them, so no cycle can
 * form to skip any of their outgoing edges, regardless of bucket
 * processing. They land alone at place 1.
 */
object RankedPairs {
    @Serializable
    sealed interface Outcome {
        @Serializable
        @SerialName("locked")
        data object Locked : Outcome

        @Serializable
        @SerialName("skipped")
        data class SkippedByCycle(
            /**
             * Path from this contest's loser back to its winner through
             * already-locked contests. At least two nodes; the first is
             * the contest's loser and the last is the contest's winner.
             * Adding the contest would have closed this path into a cycle.
             */
            val cyclePath: List<String>,
        ) : Outcome
    }

    /**
     * One directed pairwise contest (a strict pairwise win — ties produce
     * no contest and so don't appear here at all). [winningVotes] is the
     * number of ballots that ranked [winner] above [loser]; [losingVotes]
     * is the symmetric count. [outcome] records whether this contest was
     * locked into the final DAG or skipped because doing so would create
     * a cycle with already-locked contests.
     */
    @Serializable
    data class Contest(
        val winner: String,
        val loser: String,
        val winningVotes: Int,
        val losingVotes: Int,
        val outcome: Outcome,
    )

    /**
     * Result of one Tideman run. [contests] is in lock-in order — the
     * order Tideman processed them — so the report page can render them
     * top-to-bottom and walk a reader through the decisions. [places] is
     * the final ranking derived from the locked DAG, with ties indicated
     * by repeated rank values (standard skip-style: 1, 1, 1, 4, 4, 6, …).
     */
    data class Result(
        val contests: List<Contest>,
        val places: List<Place>,
    )

    fun run(
        candidates: List<String>,
        preferences: List<List<Preference>>,
    ): Result {
        if (candidates.size <= 1) {
            val places = candidates.map { Place(1, it) }
            return Result(emptyList(), places)
        }
        val sorted = sortedContests(candidates, preferences)
        val processed = processInOrder(sorted)
        val locked = processed
            .filter { it.outcome is Outcome.Locked }
            .map { it.winner to it.loser }
            .toSet()
        val places = computePlaces(candidates, locked).adjustForTies()
        return Result(processed, places)
    }

    /**
     * Build the directed contest list and sort it strongest-first.
     *
     * For each unordered pair {a, b} we emit at most one directed contest
     * — the direction with the higher pairwise count. Equal counts mean a
     * tied pair, which produces no contest (the algorithm never tries to
     * lock in a tie, so the DAG leaves such pairs unconstrained and
     * they'll surface as a tied place at the end).
     *
     * Sort order:
     *   1. winning votes descending — louder wins lock in earlier.
     *   2. losing votes ascending — less opposition is more decisive.
     *   3. winner name ascending, then loser name — *display* order only.
     *      Outcomes within a bucket are decided by atomic-bucket
     *      processing in [processInOrder], which is independent of this
     *      tiebreak. Keeping the sort here just makes the Process report
     *      render rows in a stable, scannable order.
     */
    private fun sortedContests(
        candidates: List<String>,
        preferences: List<List<Preference>>,
    ): List<Contest> {
        val contests = mutableListOf<Contest>()
        for (i in candidates.indices) {
            for (j in i + 1 until candidates.size) {
                val a = candidates[i]
                val b = candidates[j]
                val aOverB = preferences[i][j].strength
                val bOverA = preferences[j][i].strength
                when {
                    aOverB > bOverA -> contests += Contest(a, b, aOverB, bOverA, Outcome.Locked)
                    bOverA > aOverB -> contests += Contest(b, a, bOverA, aOverB, Outcome.Locked)
                    // equal → tied pair, no contest emitted
                }
            }
        }
        return contests.sortedWith(
            compareByDescending<Contest> { it.winningVotes }
                .thenBy { it.losingVotes }
                .thenBy { it.winner }
                .thenBy { it.loser }
        )
    }

    /**
     * Walk [sorted] in strength-bucket atomic groups. A "bucket" is a
     * maximal run of contests sharing identical (winning, losing) votes.
     * For each bucket, every contest is decided independently against
     * the same reference graph: (already-locked edges) ∪ (every other
     * contest in the current bucket). A contest skips iff a path
     * loser → winner exists in that reference graph after removing the
     * contest's own edge; otherwise it locks.
     *
     * The same-reference-graph rule is what removes order-dependence
     * within a bucket. Two contests in the same bucket are never
     * "considered" in any sequence relative to each other; either both
     * lock (no conflict), one locks and one skips (only one was in a
     * cycle with the rest), or both skip (each was in a cycle with the
     * other or with stronger locked edges).
     *
     * Cycles entirely within a bucket therefore drop all of the cycle's
     * edges — none of them can lock without picking an arbitrary
     * survivor. The candidates involved then tie in the topological
     * layering step.
     */
    private fun processInOrder(sorted: List<Contest>): List<Contest> {
        val locked = mutableMapOf<String, MutableList<String>>()
        val result = mutableListOf<Contest>()

        // Group consecutive contests of identical strength into buckets.
        // [sorted] is already in strength order, so adjacent grouping is
        // sufficient — no need to re-sort.
        val buckets = mutableListOf<MutableList<Contest>>()
        for (contest in sorted) {
            val current = buckets.lastOrNull()
            if (current != null &&
                current.first().winningVotes == contest.winningVotes &&
                current.first().losingVotes == contest.losingVotes
            ) {
                current.add(contest)
            } else {
                buckets.add(mutableListOf(contest))
            }
        }

        for (bucket in buckets) {
            // Reference graph for this bucket's decisions: already-locked
            // edges plus every contest in the current bucket. Each
            // contest's decision is made by removing only its own edge
            // and looking for a closing path.
            val reference = locked.mapValuesTo(mutableMapOf()) { it.value.toMutableList() }
            for (contest in bucket) {
                reference.getOrPut(contest.winner) { mutableListOf() }.add(contest.loser)
            }

            // First pass: decide each contest against the (reference minus
            // its own edge). Defer locking the keepers so the reference
            // graph stays the same for every contest's decision.
            val keepers = mutableListOf<Contest>()
            for (contest in bucket) {
                reference[contest.winner]?.remove(contest.loser)
                val cycle = findPath(reference, from = contest.loser, to = contest.winner)
                reference.getOrPut(contest.winner) { mutableListOf() }.add(contest.loser)

                if (cycle == null) {
                    keepers += contest
                    result += contest
                } else {
                    result += contest.copy(outcome = Outcome.SkippedByCycle(cycle))
                }
            }

            // Second pass: commit the keepers to the locked graph so the
            // next bucket sees them.
            for (contest in keepers) {
                locked.getOrPut(contest.winner) { mutableListOf() }.add(contest.loser)
            }
        }

        return result
    }

    /**
     * BFS through [adjacency] from [from] looking for [to]. Returns the
     * path from `from` to `to` inclusive (at least two nodes) when one
     * exists, else null. BFS rather than DFS so we return the shortest
     * cycle path — easier to read in the report.
     */
    private fun findPath(
        adjacency: Map<String, List<String>>,
        from: String,
        to: String,
    ): List<String>? {
        if (from == to) return null
        val queue = ArrayDeque<List<String>>()
        queue.addLast(listOf(from))
        val visited = mutableSetOf(from)
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            val node = path.last()
            for (next in adjacency[node].orEmpty()) {
                if (next == to) return path + next
                if (next !in visited) {
                    visited += next
                    queue.addLast(path + next)
                }
            }
        }
        return null
    }

    /**
     * Topological layering of [candidates] under [lockedEdges]. Each
     * iteration peels off the candidates that have no incoming locked
     * edge from any remaining candidate — those candidates tie at the
     * current place. Place numbers advance by the size of the peeled
     * layer (so a 3-way tie at place 1 makes the next layer place 4),
     * matching the skip-style convention applied by [adjustForTies].
     *
     * Within a layer, names are sorted alphabetically before being
     * appended so the [Place] list is stable across runs.
     */
    private fun computePlaces(
        candidates: List<String>,
        lockedEdges: Set<Pair<String, String>>,
    ): List<Place> {
        val remaining = candidates.toMutableSet()
        val places = mutableListOf<Place>()
        var currentRank = 1
        while (remaining.isNotEmpty()) {
            val topNodes = remaining.filter { node ->
                remaining.none { other -> other != node && (other to node) in lockedEdges }
            }
            check(topNodes.isNotEmpty()) {
                "Topological layering stuck — locked edges are not a DAG: $lockedEdges"
            }
            topNodes.sorted().forEach { node ->
                places += Place(currentRank, node)
            }
            currentRank += topNodes.size
            remaining.removeAll(topNodes.toSet())
        }
        return places
    }
}
