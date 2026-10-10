package com.seanshubin.vote.domain

import com.seanshubin.vote.domain.Place.Companion.appearanceCounts
import com.seanshubin.vote.domain.Place.Companion.orderTiesByAppearances
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Ordering within a tie.
 *
 * A tie means the pairwise process found no order, so the list order is a
 * presentation choice. It should be the candidate more voters ranked, not
 * the one whose name sorts earlier.
 */
class PlaceTieOrderTest {

    @Test
    fun `the candidate on more ballots comes first within a tie`() {
        // The reported case: two candidates tied at 5th, one on more ballots.
        val places = listOf(
            Place(5, "Zalen"),
            Place(5, "Alphacore"),
        )
        val ordered = places.orderTiesByAppearances(mapOf("Zalen" to 9, "Alphacore" to 2))

        assertEquals(listOf(Place(5, "Zalen"), Place(5, "Alphacore")), ordered)
    }

    @Test
    fun `appearances beat alphabetical, which was the old order`() {
        // Same input, reversed counts: the answer must flip, proving the
        // order follows the counts rather than the names.
        val places = listOf(Place(5, "Zalen"), Place(5, "Alphacore"))
        val ordered = places.orderTiesByAppearances(mapOf("Zalen" to 2, "Alphacore" to 9))

        assertEquals(listOf(Place(5, "Alphacore"), Place(5, "Zalen")), ordered)
    }

    @Test
    fun `ranks are never changed, only the order within them`() {
        // A tie must still read as a tie. This reorders; it does not break
        // ties into distinct places.
        val places = listOf(Place(1, "A"), Place(2, "B"), Place(2, "C"), Place(4, "D"))
        val ordered = places.orderTiesByAppearances(mapOf("C" to 7, "B" to 1, "A" to 1, "D" to 9))

        assertEquals(listOf(1, 2, 2, 4), ordered.map { it.rank })
        assertEquals(listOf("A", "C", "B", "D"), ordered.map { it.candidateName })
    }

    @Test
    fun `separate ranks keep their rank order regardless of appearances`() {
        // The tie-break applies only inside a rank. A 2nd-place candidate on
        // few ballots still outranks a 3rd-place candidate on many.
        val places = listOf(Place(2, "Sparse"), Place(3, "Popular"))
        val ordered = places.orderTiesByAppearances(mapOf("Sparse" to 1, "Popular" to 99))

        assertEquals(listOf("Sparse", "Popular"), ordered.map { it.candidateName })
    }

    @Test
    fun `equal appearances fall back to alphabetical for a stable order`() {
        val places = listOf(Place(1, "Charlie"), Place(1, "alice"), Place(1, "Bob"))
        val counts = mapOf("Charlie" to 4, "alice" to 4, "Bob" to 4)

        assertEquals(
            places.orderTiesByAppearances(counts),
            places.reversed().orderTiesByAppearances(counts),
            "order must not depend on input order",
        )
    }

    @Test
    fun `a name with no recorded appearances sorts last in its group`() {
        // How tier markers behave: they are virtual candidates that exist
        // only in the projection, so they appear on no ballot.
        val places = listOf(Place(3, "Tier: Great"), Place(3, "Isom"))
        val ordered = places.orderTiesByAppearances(mapOf("Isom" to 1))

        assertEquals(listOf("Isom", "Tier: Great"), ordered.map { it.candidateName })
    }

    @Test
    fun `appearanceCounts counts ballots, not ranking positions`() {
        // Being ranked first is not worth more than being ranked last; the
        // question is how many voters mentioned the candidate at all.
        val counts = appearanceCounts(
            listOf(
                ballot("v1", "Zalen" to 1, "Isom" to 2),
                ballot("v2", "Zalen" to 9),
                ballot("v3", "Isom" to 1),
                ballot("v4", "Zalen" to 3),
            ),
        )
        assertEquals(3, counts["Zalen"])
        assertEquals(2, counts["Isom"])
    }

    @Test
    fun `appearanceCounts ignores rankings with no rank`() {
        // A ranking carrying a null rank is an unranked candidate; the voter
        // did not put it on their ballot.
        val counts = appearanceCounts(listOf(ballot("v1", "Ranked" to 1, "Unranked" to null)))

        assertEquals(1, counts["Ranked"])
        assertEquals(null, counts["Unranked"])
    }

    @Test
    fun `appearanceCounts counts a candidate once per ballot`() {
        // Defensive: a ballot contributing twice for one candidate would let
        // a single voter outweigh several.
        val counts = appearanceCounts(listOf(ballot("v1", "Zalen" to 1, "Zalen" to 2)))

        assertEquals(1, counts["Zalen"])
    }

    @Test
    fun `no ballots means every candidate is equal and order is alphabetical`() {
        val places = listOf(Place(1, "Zalen"), Place(1, "Isom"))
        val ordered = places.orderTiesByAppearances(appearanceCounts(emptyList()))

        assertEquals(listOf("Isom", "Zalen"), ordered.map { it.candidateName })
    }

    private fun ballot(voter: String, vararg rankings: Pair<String, Int?>) = Ballot.Identified(
        voterName = voter,
        electionName = "Favorite Rippaverse Book",
        confirmation = "confirm-$voter",
        whenCast = Instant.parse("2026-10-09T00:00:00Z"),
        rankings = rankings.map { (name, rank) ->
            Ranking(candidateName = name, rank = rank)
        },
    )
}
