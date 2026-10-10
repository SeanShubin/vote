package com.seanshubin.vote.integration

import com.seanshubin.vote.domain.RankingSide
import com.seanshubin.vote.integration.dsl.TestContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The tie-break reaching the real tally, not just the comparator.
 *
 * Two candidates no ballot ever compares get no locked edge between them,
 * so Ranked Pairs puts them in the same layer — a genuine tie. The names are
 * chosen so alphabetical order and appearance order disagree, which is the
 * only way to tell the new behavior from the old.
 */
class TieOrderTest {

    @Test
    fun `tied candidates are listed with the more widely ranked one first`() {
        val context = TestContext()
        val (alice, _) = context.registerUsers("alice")
        val (bob, _) = context.registerUsers("bob")
        val (carol, _) = context.registerUsers("carol")
        val election = alice.createElection("Favorite Rippaverse Book")
        election.addCandidates("Zalen", "Alphacore")

        // Nobody ranks both, so the pair is never compared and the two tie.
        // Zalen lands on two ballots, Alphacore on one.
        alice.castBallot(election, "Zalen" to 1)
        bob.castBallot(election, "Zalen" to 1)
        carol.castBallot(election, "Alphacore" to 1)

        val tally = election.tally().tally

        assertEquals(
            listOf(1, 1),
            tally.places.map { it.rank },
            "precondition: these two must actually tie, or the test proves nothing",
        )
        assertEquals(
            listOf("Zalen", "Alphacore"),
            tally.places.map { it.candidateName },
            "the candidate on more ballots should come first; alphabetical would have said Alphacore",
        )
    }

    @Test
    fun `a tie with equal appearances stays alphabetical`() {
        // Run stability: with nothing to choose between them, the order must
        // still be deterministic rather than whatever the graph walk emits.
        val context = TestContext()
        val (alice, _) = context.registerUsers("alice")
        val (bob, _) = context.registerUsers("bob")
        val election = alice.createElection("Equal Footing")
        election.addCandidates("Zalen", "Alphacore")

        alice.castBallot(election, "Zalen" to 1)
        bob.castBallot(election, "Alphacore" to 1)

        val tally = election.tally().tally

        assertEquals(listOf(1, 1), tally.places.map { it.rank })
        assertEquals(listOf("Alphacore", "Zalen"), tally.places.map { it.candidateName })
    }

    @Test
    fun `rank still outranks appearances`() {
        // A candidate who actually won a pairwise contest stays ahead of one
        // on more ballots. The tie-break must not leak across ranks.
        val context = TestContext()
        val (alice, _) = context.registerUsers("alice")
        val (bob, _) = context.registerUsers("bob")
        val (carol, _) = context.registerUsers("carol")
        val election = alice.createElection("Decided")
        election.addCandidates("Winner", "Loser")

        // Two voters rank Winner above Loser: a locked edge, so Winner is
        // strictly first. Loser appears on every ballot, Winner on two.
        alice.castBallot(election, "Winner" to 1, "Loser" to 2)
        bob.castBallot(election, "Winner" to 1, "Loser" to 2)
        carol.castBallot(election, "Loser" to 1)

        val tally = election.tally().tally

        assertEquals(
            listOf("Winner", "Loser"),
            tally.places.map { it.candidateName },
            "a decided contest must not be reordered by appearance counts",
        )
        assertEquals(listOf(1, 2), tally.places.map { it.rank })
    }

    @Test
    fun `appearances are counted per side`() {
        // The tally is per side and its ballots are already filtered to that
        // side, so the counts driving the order describe the side on screen.
        val context = TestContext()
        val (alice, _) = context.registerUsers("alice")
        val election = alice.createElection("Sided")
        election.addCandidates("Zalen", "Alphacore")
        alice.castBallot(election, "Zalen" to 1)

        val tally = election.tally().tally

        assertEquals(RankingSide.PUBLIC, tally.side)
        assertEquals(
            "Zalen",
            tally.places.first().candidateName,
            "the only ranked candidate should lead its tie group",
        )
    }
}
