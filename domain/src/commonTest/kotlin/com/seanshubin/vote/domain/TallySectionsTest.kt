package com.seanshubin.vote.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class TallySectionsTest {

    @Test
    fun `no tiers yields a single null-named section with 1 to N ranks`() {
        val places = listOf(
            Place(1, "Alice"),
            Place(2, "Bob"),
            Place(3, "Charlie"),
        )
        val sections = tallySections(places, tiers = emptyList())
        assertEquals(
            listOf(
                TallySection(
                    tierName = null,
                    places = listOf(
                        Place(1, "Alice"),
                        Place(2, "Bob"),
                        Place(3, "Charlie"),
                    ),
                ),
            ),
            sections,
        )
    }

    @Test
    fun `tier markers are stripped and the survivors are renumbered`() {
        // Schulze ranks (markers + candidates):
        //   1 Alice    (cleared Tier1)
        //   2 Tier1
        //   3 Bob      (cleared Tier2, not Tier1)
        //   4 Tier2
        //   5 Charlie  (cleared no tier)
        // Display: Alice 1st (Tier1), Bob 2nd (Tier2), Charlie 3rd (no tier).
        val places = listOf(
            Place(1, "Alice"),
            Place(2, "Tier1"),
            Place(3, "Bob"),
            Place(4, "Tier2"),
            Place(5, "Charlie"),
        )
        val sections = tallySections(places, tiers = listOf("Tier1", "Tier2"))
        assertEquals(
            listOf(
                TallySection("Tier1", listOf(Place(1, "Alice"))),
                TallySection("Tier2", listOf(Place(2, "Bob"))),
                TallySection(null, listOf(Place(3, "Charlie"))),
            ),
            sections,
        )
    }

    @Test
    fun `candidates with the same Schulze rank tie in the display`() {
        // Schulze: Alice and Bob tied at 1, Charlie at 3.
        // Display: Alice 1st, Bob 1st, Charlie 3rd (standard tied-rank skip).
        val places = listOf(
            Place(1, "Alice"),
            Place(1, "Bob"),
            Place(3, "Charlie"),
        )
        val sections = tallySections(places, tiers = emptyList())
        assertEquals(
            listOf(
                TallySection(
                    tierName = null,
                    places = listOf(
                        Place(1, "Alice"),
                        Place(1, "Bob"),
                        Place(3, "Charlie"),
                    ),
                ),
            ),
            sections,
        )
    }

    @Test
    fun `tying with a tier marker renders between that tier and the next`() {
        // Strict-precedence rule: a candidate clears tier T iff their
        // Schulze rank is *strictly less than* T's marker rank. Alice
        // tied with Tier1 here, so she does not clear it — but she also
        // didn't fall below it. She renders in a naked row list directly
        // after Tier1's (empty) card, visibly between Tier1 and Tier2.
        // Schulze ranks:
        //   1 Alice
        //   1 Tier1   (tied with Alice)
        //   3 Bob
        //   4 Tier2
        //   5 Charlie
        val places = listOf(
            Place(1, "Alice"),
            Place(1, "Tier1"),
            Place(3, "Bob"),
            Place(4, "Tier2"),
            Place(5, "Charlie"),
        )
        val sections = tallySections(places, tiers = listOf("Tier1", "Tier2"))
        assertEquals(
            listOf(
                // Tier1 is empty — Alice did not strictly clear it.
                TallySection("Tier1", emptyList()),
                // Naked row list between Tier1 and Tier2 — Alice tied at the boundary.
                TallySection(null, listOf(Place(1, "Alice"))),
                // Tier2 contains Bob (rank 3 < 4). Alice already claimed.
                TallySection("Tier2", listOf(Place(2, "Bob"))),
                // Charlie (rank 5) cleared no tier.
                TallySection(null, listOf(Place(3, "Charlie"))),
            ),
            sections,
        )
    }

    @Test
    fun `two candidates symmetrically tied with the top tier render between cards`() {
        // The motivating example: V1 ranks Alice > Excellent > Bob > Good,
        // V2 ranks Bob > Excellent > Alice > Good. Schulze pairwise:
        //   Alice ~ Bob, Alice ~ Excellent, Bob ~ Excellent
        //   Alice > Good, Bob > Good, Excellent > Good
        // After adjustForTies, Alice/Bob/Excellent share rank 1, Good is rank 4.
        val places = listOf(
            Place(1, "Alice"),
            Place(1, "Bob"),
            Place(1, "Excellent"),
            Place(4, "Good"),
        )
        val sections = tallySections(places, tiers = listOf("Excellent", "Good"))
        assertEquals(
            listOf(
                // Nobody strictly cleared Excellent.
                TallySection("Excellent", emptyList()),
                // Alice and Bob tied at the boundary — naked row between cards.
                TallySection(null, listOf(Place(1, "Alice"), Place(1, "Bob"))),
                // Nobody else cleared Good (Alice/Bob already claimed at the boundary).
                TallySection("Good", emptyList()),
            ),
            sections,
        )
    }

    @Test
    fun `tying with the bottom tier renders after that tier's card`() {
        val places = listOf(
            Place(1, "Alice"),
            Place(2, "Tier1"),
            Place(3, "Tier2"),
            Place(3, "Bob"),
        )
        val sections = tallySections(places, tiers = listOf("Tier1", "Tier2"))
        assertEquals(
            listOf(
                TallySection("Tier1", listOf(Place(1, "Alice"))),
                // Bob tied with Tier2 — emitted after Tier2's (empty) card.
                TallySection("Tier2", emptyList()),
                TallySection(null, listOf(Place(2, "Bob"))),
            ),
            sections,
        )
    }

    @Test
    fun `candidate below the bottom tier lands in the trailing no-tier section`() {
        val places = listOf(
            Place(1, "Alice"),
            Place(2, "Tier1"),
            Place(3, "Bob"),
        )
        val sections = tallySections(places, tiers = listOf("Tier1"))
        assertEquals(
            listOf(
                TallySection("Tier1", listOf(Place(1, "Alice"))),
                TallySection(null, listOf(Place(2, "Bob"))),
            ),
            sections,
        )
    }

    @Test
    fun `tier card with no candidates clearing it shows up empty`() {
        // Bob and Charlie both placed below Tier1 — nobody cleared it.
        val places = listOf(
            Place(1, "Tier1"),
            Place(2, "Bob"),
            Place(3, "Charlie"),
        )
        val sections = tallySections(places, tiers = listOf("Tier1"))
        assertEquals(
            listOf(
                TallySection("Tier1", emptyList()),
                TallySection(null, listOf(Place(1, "Bob"), Place(2, "Charlie"))),
            ),
            sections,
        )
    }

    @Test
    fun `candidates tied below a tier all share the next display rank`() {
        // Bob and Charlie tied at Schulze rank 4, both below Tier1.
        // Display: Alice 1st (Tier1), Bob and Charlie tied 2nd (no tier).
        val places = listOf(
            Place(1, "Alice"),
            Place(2, "Tier1"),
            Place(4, "Bob"),
            Place(4, "Charlie"),
        )
        val sections = tallySections(places, tiers = listOf("Tier1"))
        assertEquals(
            listOf(
                TallySection("Tier1", listOf(Place(1, "Alice"))),
                TallySection(null, listOf(Place(2, "Bob"), Place(2, "Charlie"))),
            ),
            sections,
        )
    }
}
