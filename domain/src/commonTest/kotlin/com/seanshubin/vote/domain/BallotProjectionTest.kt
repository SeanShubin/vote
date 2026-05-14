package com.seanshubin.vote.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BallotProjectionTest {
    /** Shorthand: a candidate ranking, with optional tier annotation. */
    private fun c(name: String, rank: Int?, tier: String? = null): Ranking =
        Ranking(name, rank, RankingKind.CANDIDATE, tier)

    /** Shorthand for the expected projected output (post-projection only). */
    private fun pc(name: String, rank: Int, tier: String? = null): Ranking =
        Ranking(name, rank, RankingKind.CANDIDATE, tier)

    private fun pt(name: String, rank: Int): Ranking =
        Ranking(name, rank, RankingKind.TIER, null)

    @Test
    fun `no tiers configured leaves candidate rankings dense and in rank order`() {
        // No tiers means no markers to interleave — projection just renumbers
        // and orders the candidates by their input rank.
        val out = projectBallot(
            rankings = listOf(c("Alice", 2), c("Bob", 1), c("Carol", 3)),
            electionTiers = emptyList(),
        )
        assertEquals(listOf(pc("Bob", 1), pc("Alice", 2), pc("Carol", 3)), out)
    }

    @Test
    fun `candidates with rank null are dropped`() {
        // rank=null means abstain — that candidate doesn't appear in any
        // pairwise contest, so the marker placement around them is moot.
        val out = projectBallot(
            rankings = listOf(c("Alice", 1), c("Bob", null), c("Carol", 2)),
            electionTiers = emptyList(),
        )
        assertEquals(listOf(pc("Alice", 1), pc("Carol", 2)), out)
    }

    @Test
    fun `single tier puts cleared candidates above the marker and uncleared below`() {
        // Alice cleared S, Bob did too, Carol did not. Election only has S.
        // Expected virtual ballot: Alice, Bob, [S], Carol.
        val out = projectBallot(
            rankings = listOf(
                c("Alice", 1, tier = "S"),
                c("Bob", 2, tier = "S"),
                c("Carol", 3, tier = null),
            ),
            electionTiers = listOf("S"),
        )
        assertEquals(
            listOf(pc("Alice", 1, "S"), pc("Bob", 2, "S"), pt("S", 3), pc("Carol", 4)),
            out,
        )
    }

    @Test
    fun `multiple tiers interleave markers top-to-bottom`() {
        // Tiers S > A > B. Alice cleared S; Bob cleared A; Carol cleared B;
        // Dave cleared none. Virtual order: Alice, [S], Bob, [A], Carol, [B], Dave.
        val out = projectBallot(
            rankings = listOf(
                c("Alice", 1, tier = "S"),
                c("Bob", 2, tier = "A"),
                c("Carol", 3, tier = "B"),
                c("Dave", 4, tier = null),
            ),
            electionTiers = listOf("S", "A", "B"),
        )
        assertEquals(
            listOf(
                pc("Alice", 1, "S"),
                pt("S", 2),
                pc("Bob", 3, "A"),
                pt("A", 4),
                pc("Carol", 5, "B"),
                pt("B", 6),
                pc("Dave", 7),
            ),
            out,
        )
    }

    @Test
    fun `empty tier still gets its marker materialized`() {
        // No candidate cleared A, but A is still a configured tier. The
        // marker for A must appear in the virtual ballot so it can act as
        // a separator (any future ballot or compute step that grouped by
        // tier needs the marker present even when no one landed in it).
        val out = projectBallot(
            rankings = listOf(c("Alice", 1, tier = "S"), c("Dave", 2, tier = null)),
            electionTiers = listOf("S", "A"),
        )
        assertEquals(
            listOf(pc("Alice", 1, "S"), pt("S", 2), pt("A", 3), pc("Dave", 4)),
            out,
        )
    }

    @Test
    fun `within a tier candidates sort by their input rank, not declaration order`() {
        // Both Alice and Bob are S-tier. Alice has rank=5, Bob has rank=2.
        // The projection must respect the voter's intra-tier preference
        // — Bob before Alice — not the order they appear in `rankings`.
        val out = projectBallot(
            rankings = listOf(c("Alice", 5, tier = "S"), c("Bob", 2, tier = "S")),
            electionTiers = listOf("S"),
        )
        assertEquals(
            listOf(pc("Bob", 1, "S"), pc("Alice", 2, "S"), pt("S", 3)),
            out,
        )
    }

    @Test
    fun `tier-kind ranking in input is rejected`() {
        // Storage rule: voters cast candidate rankings with tier
        // annotations, never tier-kind rankings. The projection is what
        // produces tier-kind entries; receiving one as input means the
        // caller skipped serialization rules and would double-project.
        assertFailsWith<IllegalArgumentException> {
            projectBallot(
                rankings = listOf(
                    c("Alice", 1, tier = "S"),
                    Ranking("S", 2, RankingKind.TIER),
                ),
                electionTiers = listOf("S"),
            )
        }
    }

    @Test
    fun `equivalence to inline-marker form for a representative ballot`() {
        // Concretely demonstrates the new model produces the same virtual
        // ballot as the old inline-marker form. Old form a voter might have
        // cast under the legacy UI:
        //   Alice=1, [S]=2, Bob=3, [A]=4, Carol=5
        // Reading meaning: Alice cleared S; Bob cleared A but not S; Carol
        // cleared no tier. The new-form input encoding that intent:
        val out = projectBallot(
            rankings = listOf(
                c("Alice", 1, tier = "S"),
                c("Bob", 2, tier = "A"),
                c("Carol", 3, tier = null),
            ),
            electionTiers = listOf("S", "A"),
        )
        // Same virtual order, same pairwise contests the Schulze pass will see.
        assertEquals(
            listOf(
                pc("Alice", 1, "S"),
                pt("S", 2),
                pc("Bob", 3, "A"),
                pt("A", 4),
                pc("Carol", 5),
            ),
            out,
        )
    }
}
