package com.seanshubin.vote.tools.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.pair
import com.github.ajalt.clikt.parameters.options.required
import com.seanshubin.vote.domain.Ballot
import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.domain.Place
import com.seanshubin.vote.domain.RankedPairs
import com.seanshubin.vote.domain.Ranking.Companion.prefers
import com.seanshubin.vote.domain.RankingSide
import com.seanshubin.vote.domain.Tally
import com.seanshubin.vote.tools.lib.NarrativeEvent
import com.seanshubin.vote.tools.lib.Output
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.useLines

/**
 * Console-side Ranked Pairs diagnostic. Replays a JSONL event log
 * in-process, computes the tally for one election with the same
 * [Tally.countBallots] the backend uses, and prints the result.
 *
 * The point is to make the question "what did the algorithm do, and
 * why, for this specific election and this specific pair?" answerable
 * without spinning up DynamoDB, the backend, or the UI. Equivalent in
 * outcome to clicking through the Decision and Process pages — the
 * same code path produces the data — but everything renders to stdout.
 *
 * Two modes:
 *   - `--election <name>`:                full breakdown (places +
 *                                         contests grouped by strength
 *                                         bucket).
 *   - `--election <name> --pair A B`:     per-pair Decision view (direct
 *                                         contest + voter lists + cycle
 *                                         layout if skipped).
 *
 * Listing mode (`tally-from-snapshot --snapshot <file>` with no
 * election) enumerates every election the snapshot defines.
 */
class TallyFromSnapshot : CliktCommand(name = "tally-from-snapshot") {
    private val snapshotPath: String by option(
        "--snapshot",
        help = "Path to a JSONL event-log snapshot (e.g. a prod-snapshot under .local/prod-snapshots/)."
    ).required()

    private val electionNameOpt: String? by option(
        "--election",
        help = "Name of the election to tally. If omitted, lists every election found in the snapshot."
    )

    private val pairOpt: Pair<String, String>? by option(
        "--pair",
        help = "Two candidate names to drill into. Shows the per-pair Decision view " +
            "(direct contest + voter lists + cycle path if skipped). " +
            "Requires --election."
    ).pair()

    override fun help(context: Context) =
        "Replay a JSONL event log in-process, then compute and print the Ranked Pairs " +
            "tally for one election. Pass --election to pick the election; add --pair to drill " +
            "into one head-to-head."

    override fun run() {
        val file = Path.of(snapshotPath)
        if (!file.exists()) Output.error("Snapshot file not found: $file")

        val events = readEvents(file)
        val electionStates = projectAllElections(events)

        val name = electionNameOpt
        if (name == null) {
            if (pairOpt != null) Output.error("--pair requires --election.")
            listElections(electionStates)
            return
        }

        val state = electionStates[name]
            ?: Output.error("Election not found: $name. Run without --election to list available elections.")

        val tally = Tally.countBallots(
            electionName = name,
            side = RankingSide.PUBLIC,
            candidates = state.candidates.toList(),
            tiers = state.tiers.toList(),
            ballots = state.ballots.values.toList(),
        )

        val pair = pairOpt
        if (pair != null) {
            printPair(tally, pair.first, pair.second)
        } else {
            printFullTally(tally, state)
        }
    }

    private fun readEvents(file: Path): List<NarrativeEvent> {
        val parser = Json { ignoreUnknownKeys = true }
        return file.useLines { lines ->
            lines.filter { it.isNotBlank() }
                .map { parser.decodeFromString<NarrativeEvent>(it) }
                .toList()
        }
    }

    /**
     * The slice of election state the tally needs. Reconstructed by
     * folding [DomainEvent]s in event-log order. Mirrors the relevant
     * subset of the backend's projection but keeps only what
     * [Tally.countBallots] consumes.
     */
    private class ElectionState {
        val candidates: MutableList<String> = mutableListOf()
        val tiers: MutableList<String> = mutableListOf()
        // Keyed by confirmation. A new BallotCast for the same voter
        // replaces the prior one — confirmations are unique per cast.
        val ballots: MutableMap<String, Ballot.Identified> = mutableMapOf()
    }

    private fun projectAllElections(events: List<NarrativeEvent>): Map<String, ElectionState> {
        val states = mutableMapOf<String, ElectionState>()
        events.forEach { envelope ->
            when (val e = envelope.event) {
                is DomainEvent.ElectionCreated -> {
                    states.getOrPut(e.electionName) { ElectionState() }
                }
                is DomainEvent.ElectionDeleted -> {
                    states.remove(e.electionName)
                }
                is DomainEvent.CandidatesAdded -> {
                    states[e.electionName]?.candidates?.addAll(e.candidateNames)
                }
                is DomainEvent.CandidatesRemoved -> {
                    val state = states[e.electionName] ?: return@forEach
                    state.candidates.removeAll(e.candidateNames.toSet())
                    // Drop the removed candidate from every cast ranking too.
                    val removed = e.candidateNames.toSet()
                    state.ballots.replaceAll { _, ballot ->
                        ballot.copy(rankings = ballot.rankings.filterNot { it.candidateName in removed })
                    }
                }
                is DomainEvent.CandidateRenamed -> {
                    val state = states[e.electionName] ?: return@forEach
                    val idx = state.candidates.indexOf(e.oldName)
                    if (idx >= 0) state.candidates[idx] = e.newName
                    state.ballots.replaceAll { _, ballot ->
                        ballot.copy(rankings = ballot.rankings.map { r ->
                            if (r.candidateName == e.oldName) r.copy(candidateName = e.newName) else r
                        })
                    }
                }
                is DomainEvent.TiersSet -> {
                    val state = states[e.electionName] ?: return@forEach
                    state.tiers.clear()
                    state.tiers.addAll(e.tierNames)
                    // Removed tiers: clear annotations on existing rankings.
                    val present = e.tierNames.toSet()
                    state.ballots.replaceAll { _, ballot ->
                        ballot.copy(rankings = ballot.rankings.map { r ->
                            if (r.tier != null && r.tier !in present) r.copy(tier = null) else r
                        })
                    }
                }
                is DomainEvent.TierRenamed -> {
                    val state = states[e.electionName] ?: return@forEach
                    val idx = state.tiers.indexOf(e.oldName)
                    if (idx >= 0) state.tiers[idx] = e.newName
                    state.ballots.replaceAll { _, ballot ->
                        ballot.copy(rankings = ballot.rankings.map { r ->
                            if (r.tier == e.oldName) r.copy(tier = e.newName) else r
                        })
                    }
                }
                is DomainEvent.BallotCast -> {
                    val state = states[e.electionName] ?: return@forEach
                    // A new BallotCast supersedes the voter's prior ballot —
                    // remove any existing entry for this voter first.
                    state.ballots.entries.removeAll { it.value.voterName == e.voterName }
                    state.ballots[e.confirmation] = Ballot.Identified(
                        voterName = e.voterName,
                        electionName = e.electionName,
                        confirmation = e.confirmation,
                        whenCast = e.whenCast,
                        rankings = e.rankings,
                    )
                }
                is DomainEvent.BallotRankingsChanged -> {
                    val state = states[e.electionName] ?: return@forEach
                    val existing = state.ballots[e.confirmation] ?: return@forEach
                    state.ballots[e.confirmation] = existing.copy(rankings = e.newRankings)
                }
                is DomainEvent.BallotTimestampUpdated -> {
                    // Timestamp doesn't affect the tally — ignored.
                }
                is DomainEvent.BallotDeleted -> {
                    val state = states[e.electionName] ?: return@forEach
                    state.ballots.entries.removeAll { it.value.voterName == e.voterName }
                }
                else -> Unit // user/owner events don't affect the tally.
            }
        }
        return states
    }

    private fun listElections(states: Map<String, ElectionState>) {
        if (states.isEmpty()) {
            println("No elections found in snapshot.")
            return
        }
        Output.banner("Elections in snapshot")
        states.entries.sortedBy { it.key }.forEach { (name, state) ->
            println(
                "  $name -- ${state.candidates.size} candidates, " +
                    "${state.ballots.size} ballots, " +
                    "${state.tiers.size} tiers"
            )
        }
        println()
        println("Re-run with --election <name> to tally a specific election.")
    }

    private fun printFullTally(tally: Tally, state: ElectionState) {
        Output.banner("Tally: ${tally.electionName}")
        println("Candidates ranked: ${tally.candidateNames.size} of ${state.candidates.size}")
        println("Ballots:           ${tally.ballots.size}")
        println("Tiers:             ${if (tally.candidateNames.size > state.candidates.size) state.tiers.joinToString(", ") else state.tiers.ifEmpty { listOf("(none)") }.joinToString(", ")}")

        Output.section("Places")
        printPlaces(tally.places)

        Output.section("Contests (in lock-in order)")
        printContestsByBucket(tally.contests)
    }

    private fun printPlaces(places: List<Place>) {
        if (places.isEmpty()) {
            println("  (no candidates ranked)")
            return
        }
        val grouped = places.groupBy { it.rank }.toSortedMap()
        grouped.forEach { (rank, entries) ->
            val names = entries.map { it.candidateName }.sorted()
            if (names.size == 1) {
                println("  ${formatRank(rank)} ${names[0]}")
            } else {
                println("  ${formatRank(rank)} ${names.joinToString(", ")}  (${names.size}-way tie)")
            }
        }
    }

    private fun printContestsByBucket(contests: List<RankedPairs.Contest>) {
        if (contests.isEmpty()) {
            println("  (no contests -- every pair tied, or not enough candidates)")
            return
        }
        var step = 0
        var prevKey: Pair<Int, Int>? = null
        contests.forEach { contest ->
            step += 1
            val key = contest.winningVotes to contest.losingVotes
            if (key != prevKey) {
                println()
                println("  Bucket ${contest.winningVotes} winning, ${contest.losingVotes} losing")
                prevKey = key
            }
            val status = if (contest.outcome is RankedPairs.Outcome.Locked) "LOCKED " else "SKIPPED"
            println("    Step ${step.toString().padStart(3)}  $status  ${contest.winner}  -> ${contest.loser}")
            val outcome = contest.outcome
            if (outcome is RankedPairs.Outcome.SkippedByCycle) {
                println("                          cycle: ${outcome.cyclePath.joinToString(" -> ")} -> ${contest.winner}")
            }
        }
    }

    private fun printPair(tally: Tally, a: String, b: String) {
        val candidateNames = tally.candidateNames
        if (a !in candidateNames) Output.error("Candidate not found in tally: $a")
        if (b !in candidateNames) Output.error("Candidate not found in tally: $b")

        val ai = candidateNames.indexOf(a)
        val bi = candidateNames.indexOf(b)
        val aOverB = tally.preferences[ai][bi].strength
        val bOverA = tally.preferences[bi][ai].strength

        Output.banner("Pair: $a vs $b")

        Output.section("Direct contest")
        when {
            aOverB > bOverA -> println("  $a beats $b, $aOverB to $bOverA.")
            bOverA > aOverB -> println("  $b beats $a, $bOverA to $aOverB.")
            else -> println("  Tied, $aOverB to $bOverA (no contest emitted).")
        }
        val revealed = tally.ballots.filterIsInstance<Ballot.Identified>()
        val aVoters = revealed.filter { it.rankings.prefers(a, b) }.map { it.voterName }.sorted()
        val bVoters = revealed.filter { it.rankings.prefers(b, a) }.map { it.voterName }.sorted()
        val abstain = revealed.filterNot { it.rankings.prefers(a, b) || it.rankings.prefers(b, a) }
            .map { it.voterName }.sorted()
        println("    Voters for $a: ${aVoters.ifEmpty { listOf("(none)") }.joinToString(", ")}")
        println("    Voters for $b: ${bVoters.ifEmpty { listOf("(none)") }.joinToString(", ")}")
        if (abstain.isNotEmpty()) {
            println("    Abstained:     ${abstain.joinToString(", ")}")
        }

        val contestIndex = tally.contests.indexOfFirst {
            (it.winner == a && it.loser == b) || (it.winner == b && it.loser == a)
        }
        if (contestIndex < 0) {
            Output.section("Algorithm decision")
            println("  No contest -- pair is tied in pairwise count.")
            return
        }
        val contest = tally.contests[contestIndex]

        Output.section("Algorithm decision")
        println("  Step ${contestIndex + 1} of ${tally.contests.size}")
        println("  Bucket: ${contest.winningVotes} winning, ${contest.losingVotes} losing")
        when (val outcome = contest.outcome) {
            is RankedPairs.Outcome.Locked -> println("  Outcome: LOCKED into the final ranking.")
            is RankedPairs.Outcome.SkippedByCycle -> {
                println("  Outcome: SKIPPED -- would have closed a cycle.")
                printCycleRows(contest, outcome, tally.contests)
            }
        }
    }

    private fun printCycleRows(
        contest: RankedPairs.Contest,
        outcome: RankedPairs.Outcome.SkippedByCycle,
        allContests: List<RankedPairs.Contest>,
    ) {
        val byEdge = allContests.withIndex().associateBy { (_, c) -> c.winner to c.loser }
        val rows = outcome.cyclePath.zipWithNext().mapNotNull { (from, to) ->
            byEdge[from to to]?.let { it.index + 1 to it.value }
        }
        val locked = rows.count { it.second.outcome is RankedPairs.Outcome.Locked }
        val tied = rows.size - locked + 1 // includes current
        val cycleSize = rows.size + 1
        val kind = when {
            tied == 1 -> "LonelyWeakest"
            tied == cycleSize -> "PureTie"
            else -> "Mixed (locked=$locked, tied=$tied)"
        }
        println("  Cycle size: $cycleSize ($kind)")
        println()
        rows.forEach { (step, c) ->
            val status = if (c.outcome is RankedPairs.Outcome.Locked) "LOCKED " else "SKIPPED"
            println("    Step ${step.toString().padStart(3)}  $status  ${c.winner}  -> ${c.loser}  (${c.winningVotes} winning, ${c.losingVotes} losing)")
        }
        val mark = "<-- this contest"
        println("    Step ${(allContests.indexOf(contest) + 1).toString().padStart(3)}  SKIPPED  ${contest.winner}  -> ${contest.loser}  (${contest.winningVotes} winning, ${contest.losingVotes} losing)  $mark")
        println("    ^-- closes back to ${outcome.cyclePath.first()}")
    }

    private fun formatRank(rank: Int): String {
        val suffix = when {
            rank % 100 in 11..13 -> "th"
            rank % 10 == 1 -> "st"
            rank % 10 == 2 -> "nd"
            rank % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$rank$suffix".padEnd(5)
    }
}
