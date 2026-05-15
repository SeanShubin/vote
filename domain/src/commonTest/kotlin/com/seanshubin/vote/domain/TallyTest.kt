package com.seanshubin.vote.domain

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TallyTest {
    @Test
    fun `fully ranked ballots produce a Condorcet winner`() {
        // Apple beats Banana 2-1, Apple beats Cherry 3-0,
        // Banana beats Cherry 2-1 → Apple > Banana > Cherry.
        val tally = countBallots(
            candidates = listOf("Apple", "Banana", "Cherry"),
            ballot("bob", "Apple" to 1, "Banana" to 2, "Cherry" to 3),
            ballot("charlie", "Apple" to 1, "Cherry" to 2, "Banana" to 3),
            ballot("david", "Banana" to 1, "Apple" to 2, "Cherry" to 3),
        )
        assertEquals(listOf("Apple", "Banana", "Cherry"), tally.places.map { it.candidateName })
    }

    @Test
    fun `omitting a candidate from a ballot does NOT count as ranking it last`() {
        // Two ballots include Apple; the third omits Apple entirely. Under the
        // old "unranked = last" semantics, Apple would lose 2-1 to Banana.
        // Under the new "explicit only" semantics, the third ballot abstains
        // from the (Apple, Banana) contest, so Apple wins 2-0.
        val tally = countBallots(
            candidates = listOf("Apple", "Banana"),
            ballot("alice", "Apple" to 1, "Banana" to 2),
            ballot("bob", "Apple" to 1, "Banana" to 2),
            ballot("carol", "Banana" to 1), // Apple omitted
        )
        assertEquals("Apple", tally.places.first().candidateName)
    }

    @Test
    fun `candidate ranked on no ballot is filtered out of the tally entirely`() {
        // Cherry is a configured candidate but nobody ranked her.
        // She should not appear in tally.candidateNames or tally.places.
        val tally = countBallots(
            candidates = listOf("Apple", "Banana", "Cherry"),
            ballot("alice", "Apple" to 1, "Banana" to 2),
            ballot("bob", "Banana" to 1, "Apple" to 2),
        )
        assertEquals(listOf("Apple", "Banana"), tally.candidateNames.sorted())
        assertTrue(tally.places.none { it.candidateName == "Cherry" })
    }

    @Test
    fun `candidate present on ballot with null rank counts the same as omitted`() {
        // Carol's ballot lists Cherry with rank=null — same as not listing it.
        val tally = countBallots(
            candidates = listOf("Apple", "Cherry"),
            Ballot.Revealed(
                voterName = "carol",
                electionName = "election",
                confirmation = "c-1",
                whenCast = Instant.fromEpochMilliseconds(0),
                rankings = listOf(
                    Ranking("Apple", 1),
                    Ranking("Cherry", null),
                ),
            ),
        )
        // Cherry has no expressed rank anywhere → filtered out.
        assertEquals(listOf("Apple"), tally.candidateNames)
    }

    @Test
    fun `pairwise count uses only ballots that ranked both candidates`() {
        // 3 ballots:
        //   alice: A=1, B=2     → prefers A>B
        //   bob:   A=1          → no opinion on A vs B
        //   carol: B=1, A=2     → prefers B>A
        // A vs B: 1-1 tied. So A and B should share a tier in places.
        val tally = countBallots(
            candidates = listOf("A", "B"),
            ballot("alice", "A" to 1, "B" to 2),
            ballot("bob", "A" to 1),
            ballot("carol", "B" to 1, "A" to 2),
        )
        // Both candidates rank=1 (a tie at the top: the direct contest is
        // tied 1-1, so Tideman locks no edge and the DAG leaves them
        // unconstrained — they share place 1).
        assertEquals(setOf(1), tally.places.map { it.rank }.toSet())
        assertEquals(setOf("A", "B"), tally.places.map { it.candidateName }.toSet())
    }

    @Test
    fun `election with no rankings at all yields an empty tally`() {
        // Candidates exist but no voter has ranked any of them.
        val tally = countBallots(
            candidates = listOf("Apple", "Banana"),
            // No ballots — or equivalently, every ballot is empty.
        )
        assertEquals(emptyList(), tally.candidateNames)
        assertEquals(emptyList(), tally.places)
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
