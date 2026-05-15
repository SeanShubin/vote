package com.seanshubin.vote.domain

import com.seanshubin.vote.domain.Place.Companion.adjustForTies
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Tideman's Ranked Pairs (TRS), 1987. The Condorcet completion that
 * replaced this project's original Schulze pipeline.
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
 *      losing votes ascending (less opposition is a stronger contest),
 *      then winner / loser name alphabetically for determinism across
 *      contests that tie on those numbers.
 *   3. Walk the sorted list. Lock each contest in unless doing so creates
 *      a cycle with already-locked contests. A skipped contest records the
 *      path through already-locked contests that closes the cycle, so the
 *      report can quote it.
 *   4. Derive places by topological layering of the locked DAG: at each
 *      step the candidates with no incoming locked edge (among the
 *      still-remaining set) all share a place.
 *
 * Why Tideman over Schulze: when no cycle exists, the two methods agree.
 * When a cycle does exist, Tideman's resolution is a story the report can
 * quote contest by contest. Schulze's strongest-path closure was
 * algebraically elegant but not auditable in the same way — voters could
 * see the matrix but not the reasoning. See docs/tideman-ranked-pairs.md.
 *
 * Abstention semantics are preserved: the preferences matrix this object
 * consumes was built with the "only voters who ranked both" rule, and
 * Tideman processes it as-is. The implication ("absence shielding": a
 * candidate ranked by few voters has fewer opportunities to be beaten)
 * is the same here as it was under Schulze and is documented in the same
 * doc.
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
     * tied pair, which produces no contest (Tideman never tries to lock
     * in a tie, so the DAG can leave such pairs unconstrained and they'll
     * surface as a tied place at the end).
     *
     * Sort order:
     *   1. winning votes descending — louder wins lock in earlier.
     *   2. losing votes ascending — less opposition is more decisive.
     *   3. winner name ascending, then loser name — determinism across
     *      otherwise-equal contests so the report is stable across runs.
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
     * Walk [sorted] in order. For each contest, check if adding the edge
     * winner → loser would close an existing locked-edge path from loser
     * back to winner into a cycle. If yes, mark it skipped and record the
     * closing path; if no, lock it in.
     */
    private fun processInOrder(sorted: List<Contest>): List<Contest> {
        val outgoing = mutableMapOf<String, MutableList<String>>()
        val result = mutableListOf<Contest>()
        for (contest in sorted) {
            val cycle = findPath(outgoing, from = contest.loser, to = contest.winner)
            if (cycle == null) {
                outgoing.getOrPut(contest.winner) { mutableListOf() }.add(contest.loser)
                result += contest
            } else {
                result += contest.copy(outcome = Outcome.SkippedByCycle(cycle))
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
