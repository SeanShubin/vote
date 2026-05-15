package com.seanshubin.vote.domain

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RankedPairsTest {

    @Test
    fun `no cycle — Condorcet winner emerges and every contest locks`() {
        // A > B 3-0, A > C 3-0, B > C 2-1. No cycle, so every contest
        // locks in margin order: A>B (3-0), A>C (3-0), B>C (2-1).
        val tally = countBallots(
            candidates = listOf("A", "B", "C"),
            ballot("v1", "A" to 1, "B" to 2, "C" to 3),
            ballot("v2", "A" to 1, "B" to 2, "C" to 3),
            ballot("v3", "A" to 1, "C" to 2, "B" to 3),
        )
        assertEquals(listOf("A", "B", "C"), tally.places.map { it.candidateName })
        assertEquals(3, tally.contests.size)
        assertTrue(tally.contests.all { it.outcome is RankedPairs.Outcome.Locked })
    }

    @Test
    fun `Condorcet cycle — weakest contest is skipped and cycle path is recorded`() {
        // Rock-paper-scissors with 10 voters, three preference patterns:
        //   4× R > S > P    (rock first)
        //   3× P > R > S    (paper first)
        //   3× S > P > R    (scissors first)
        // Pairwise:
        //   R vs S: R wins 7-3 (4 from RSP, 3 from PRS)
        //   S vs P: S wins 7-3 (4 from RSP, 3 from SPR)
        //   P vs R: P wins 6-4 (3 from PRS + 3 from SPR)
        // Cycle: R > S > P > R. Tideman processes R>S (7-3), S>P (7-3),
        // P>R (6-4). The first two lock. P>R would close R>S>P>R into a
        // cycle, so it's skipped with cyclePath [R, S, P] (loser back to
        // winner through locked edges).
        val tally = countBallots(
            candidates = listOf("R", "P", "S"),
            ballot("rsp-1", "R" to 1, "S" to 2, "P" to 3),
            ballot("rsp-2", "R" to 1, "S" to 2, "P" to 3),
            ballot("rsp-3", "R" to 1, "S" to 2, "P" to 3),
            ballot("rsp-4", "R" to 1, "S" to 2, "P" to 3),
            ballot("prs-1", "P" to 1, "R" to 2, "S" to 3),
            ballot("prs-2", "P" to 1, "R" to 2, "S" to 3),
            ballot("prs-3", "P" to 1, "R" to 2, "S" to 3),
            ballot("spr-1", "S" to 1, "P" to 2, "R" to 3),
            ballot("spr-2", "S" to 1, "P" to 2, "R" to 3),
            ballot("spr-3", "S" to 1, "P" to 2, "R" to 3),
        )
        assertEquals(listOf("R", "S", "P"), tally.places.map { it.candidateName })

        val contestsByWinner = tally.contests.associateBy { it.winner to it.loser }
        val rs = contestsByWinner.getValue("R" to "S")
        val sp = contestsByWinner.getValue("S" to "P")
        val pr = contestsByWinner.getValue("P" to "R")

        assertEquals(7, rs.winningVotes)
        assertEquals(3, rs.losingVotes)
        assertTrue(rs.outcome is RankedPairs.Outcome.Locked)

        assertEquals(7, sp.winningVotes)
        assertEquals(3, sp.losingVotes)
        assertTrue(sp.outcome is RankedPairs.Outcome.Locked)

        assertEquals(6, pr.winningVotes)
        assertEquals(4, pr.losingVotes)
        val prOutcome = pr.outcome
        assertTrue(prOutcome is RankedPairs.Outcome.SkippedByCycle)
        // P>R was skipped because the locked path R → S → P already
        // connects the loser (R) to the winner (P).
        assertEquals(listOf("R", "S", "P"), prOutcome.cyclePath)
    }

    @Test
    fun `Cursed-Goodyng — Tideman honors a direct win that Schulze ties`() {
        // Distilled from the Cursed/Goodyng dynamic in prod that
        // motivated the Schulze→Tideman switch. Three candidates:
        //   G (Goodyng)   — broadly ranked; loses to B on raw pairwise.
        //   C (Cursed)    — sparsely ranked (only 2 voters); never
        //                    appears below G on those two ballots.
        //   B (Bloodruth) — a "bridge" candidate: low-ranked by the
        //                    informed voters but high-ranked by the
        //                    voters who omit C.
        //
        // Ballots:
        //   v1: G > C > B
        //   v2: G > C > B
        //   v3: G > B          (omits C — abstains on every C contest)
        //   v4: B > G          (omits C)
        //   v5: B > G          (omits C)
        //
        // Direct contests (informed-voter rule — abstainers don't vote):
        //   G vs C : 2-0  G   (v1, v2 only)
        //   G vs B : 3-2  G
        //   C vs B : 2-0  C   (v1, v2 only)
        //
        // Schulze on this same input ties G and C at rank 1: the
        // closure step manufactures a C→G path through B with
        // strength 2, equal to G→C's strength of 2. Tideman doesn't
        // do that. It locks G>B (3-2) first, then C>B (2-0) and G>C
        // (2-0). All three lock — no cycle — and topology gives a
        // strict G > C > B.
        //
        // The point of the test: under Tideman+abstention, a direct
        // 2-0 pairwise win is honored as long as the lock-in order
        // doesn't have to skip it to avoid a cycle. In this scenario
        // it doesn't, so G beats C in the final ranking even though
        // Schulze would have tied them.
        val tally = countBallots(
            candidates = listOf("G", "C", "B"),
            ballot("v1", "G" to 1, "C" to 2, "B" to 3),
            ballot("v2", "G" to 1, "C" to 2, "B" to 3),
            ballot("v3", "G" to 1, "B" to 2),
            ballot("v4", "B" to 1, "G" to 2),
            ballot("v5", "B" to 1, "G" to 2),
        )
        assertEquals(
            listOf(Place(1, "G"), Place(2, "C"), Place(3, "B")),
            tally.places,
        )
        // And the direct G-vs-C contest was actually locked in.
        val gOverC = tally.contests.first { it.winner == "G" && it.loser == "C" }
        assertEquals(2, gOverC.winningVotes)
        assertEquals(0, gOverC.losingVotes)
        assertTrue(gOverC.outcome is RankedPairs.Outcome.Locked)
    }

    @Test
    fun `tied pair emits no contest and the pair tie at the same place`() {
        // 2 voters who disagree symmetrically: A>B and B>A. The pair is
        // tied 1-1 in pairwise count, so Tideman emits no contest at
        // all — neither edge is locked, and the DAG leaves both
        // candidates unconstrained. They share place 1.
        val tally = countBallots(
            candidates = listOf("A", "B"),
            ballot("alice", "A" to 1, "B" to 2),
            ballot("bob", "B" to 1, "A" to 2),
        )
        assertTrue(tally.contests.isEmpty())
        assertEquals(setOf(1), tally.places.map { it.rank }.toSet())
    }

    @Test
    fun `single candidate produces a one-entry place list and no contests`() {
        val tally = countBallots(
            candidates = listOf("Solo"),
            ballot("v1", "Solo" to 1),
        )
        assertEquals(listOf(Place(1, "Solo")), tally.places)
        assertTrue(tally.contests.isEmpty())
    }

    @Test
    fun `contest sort order — winning votes desc then losing votes asc`() {
        // Two contests with equal winning votes but different losing votes.
        // Setup:
        //   A vs B: A wins 3-0   (3 voters rank both, all A>B)
        //   C vs D: C wins 3-1   (4 voters rank both, 3 prefer C, 1 prefers D)
        // Both have winning votes = 3, but A>B has losing votes 0 vs C>D's
        // losing votes 1, so A>B should appear first in the lock-in order.
        val tally = countBallots(
            candidates = listOf("A", "B", "C", "D"),
            ballot("v1", "A" to 1, "B" to 2, "C" to 3, "D" to 4),
            ballot("v2", "A" to 1, "B" to 2, "C" to 3, "D" to 4),
            ballot("v3", "A" to 1, "B" to 2, "C" to 3, "D" to 4),
            ballot("v4", "D" to 1, "C" to 2),  // C and D only; D>C in this ballot
        )
        // Find A>B and C>D in the contest list and verify ordering.
        val aB = tally.contests.indexOfFirst { it.winner == "A" && it.loser == "B" }
        val cD = tally.contests.indexOfFirst { it.winner == "C" && it.loser == "D" }
        assertTrue(aB >= 0 && cD >= 0, "Expected both contests; got ${tally.contests}")
        assertTrue(aB < cD, "A>B (3-0) should precede C>D (3-1); got order $aB, $cD")
    }

    private fun countBallots(candidates: List<String>, vararg ballots: Ballot.Revealed): Tally =
        Tally.countBallots(
            electionName = "election",
            secretBallot = false,
            candidates = candidates,
            tiers = emptyList(),
            ballots = ballots.toList(),
        )

    private fun ballot(voter: String, vararg rankings: Pair<String, Int>): Ballot.Revealed =
        Ballot.Revealed(
            voterName = voter,
            electionName = "election",
            confirmation = "c-$voter",
            whenCast = Instant.fromEpochMilliseconds(0),
            rankings = rankings.map { (name, rank) -> Ranking(name, rank) },
        )
}
