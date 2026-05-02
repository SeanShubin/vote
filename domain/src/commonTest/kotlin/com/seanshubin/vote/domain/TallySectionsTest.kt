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
    fun `tying with a tier marker means did not clear`() {
        // Strict-precedence rule: a candidate clears tier T iff their
        // Schulze rank is *strictly less than* T's marker rank. Alice
        // tied with Tier1 here, so she does not clear it; she falls into
        // the next-lower section (Tier2, which she beats strictly).
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
                // Tier2 contains Alice (rank 1 < 4) and Bob (rank 3 < 4).
                TallySection("Tier2", listOf(Place(1, "Alice"), Place(2, "Bob"))),
                // Charlie (rank 5) cleared no tier.
                TallySection(null, listOf(Place(3, "Charlie"))),
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
