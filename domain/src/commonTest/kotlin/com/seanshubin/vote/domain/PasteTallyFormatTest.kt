package com.seanshubin.vote.domain

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class PasteTallyFormatTest {

    @Test
    fun `happy path with comments blank lines and mixed casing`() {
        val text = """
            # A friendly comment
            A = Alice Johnson
            B = Bob Smith
            C = Carol Davis

            ---
            5: A > B > C
            3: b = c > a    # case-insensitive
            2: c > a        # truncated
            A > B           # implicit count of 1
        """.trimIndent()

        val success = parseOrFail(text)
        assertEquals(listOf("Alice Johnson", "Bob Smith", "Carol Davis"), success.candidates.map { it.fullName })
        assertEquals(listOf(5, 3, 2, 1), success.ballots.map { it.count })
        assertEquals(
            listOf(listOf("Alice Johnson"), listOf("Bob Smith"), listOf("Carol Davis")),
            success.ballots[0].rankedGroups,
        )
        assertEquals(
            listOf(listOf("Bob Smith", "Carol Davis"), listOf("Alice Johnson")),
            success.ballots[1].rankedGroups,
        )
        assertEquals(
            listOf(listOf("Carol Davis"), listOf("Alice Johnson")),
            success.ballots[2].rankedGroups,
        )
        assertEquals(emptyList(), success.warnings)
    }

    @Test
    fun `multi-character abbreviations are allowed`() {
        val text = """
            AJ = Alice Johnson
            BS = Bob Smith
            ---
            2: AJ > BS
            1: bs > aj
        """.trimIndent()
        val success = parseOrFail(text)
        assertEquals(listOf("AJ", "BS"), success.candidates.map { it.abbreviation })
        assertEquals(
            listOf(listOf("Alice Johnson"), listOf("Bob Smith")),
            success.ballots[0].rankedGroups,
        )
    }

    @Test
    fun `end-of-line comments strip cleanly`() {
        val text = """
            A = Alice # this is alice
            B = Bob
            ---
            1: A > B  # a beats b
        """.trimIndent()
        val success = parseOrFail(text)
        assertEquals("Alice", success.candidates[0].fullName)
        assertEquals(listOf(listOf("Alice"), listOf("Bob")), success.ballots[0].rankedGroups)
    }

    @Test
    fun `missing separator is an error`() {
        val text = """
            A = Alice
            B = Bob
            1: A > B
        """.trimIndent()
        val errors = parseExpectingFailure(text)
        assertTrue(errors.any { "Missing '---'" in it.message }, "got: $errors")
    }

    @Test
    fun `multiple separators are an error`() {
        val text = """
            A = Alice
            ---
            1: A
            ---
            1: A
        """.trimIndent()
        val errors = parseExpectingFailure(text)
        assertTrue(errors.any { "Multiple '---'" in it.message }, "got: $errors")
    }

    @Test
    fun `duplicate abbreviation reports both lines`() {
        val text = """
            A = Alice
            A = Anna
            ---
            1: A
        """.trimIndent()
        val errors = parseExpectingFailure(text)
        val msg = errors.single { "Duplicate abbreviation" in it.message }
        assertEquals(2, msg.line)
        assertTrue("line 1" in msg.message, "expected reference to line 1, got: ${msg.message}")
    }

    @Test
    fun `duplicate abbreviation is case-insensitive`() {
        val text = """
            A = Alice
            a = Anna
            ---
            1: A
        """.trimIndent()
        val errors = parseExpectingFailure(text)
        assertTrue(errors.any { "Duplicate abbreviation" in it.message }, "got: $errors")
    }

    @Test
    fun `duplicate full name is an error`() {
        val text = """
            A = Alice Johnson
            B = alice johnson
            ---
            1: A
        """.trimIndent()
        val errors = parseExpectingFailure(text)
        assertTrue(errors.any { "Duplicate candidate" in it.message }, "got: $errors")
    }

    @Test
    fun `unknown abbreviation is an error`() {
        val text = """
            A = Alice
            B = Bob
            ---
            1: A > X
        """.trimIndent()
        val errors = parseExpectingFailure(text)
        val msg = errors.single { "Unknown abbreviation" in it.message }
        assertEquals(4, msg.line)
        assertTrue("'X'" in msg.message, "got: ${msg.message}")
    }

    @Test
    fun `empty ballot is an error`() {
        val text = """
            A = Alice
            ---
            5:
        """.trimIndent()
        val errors = parseExpectingFailure(text)
        assertTrue(errors.any { "no candidates" in it.message }, "got: $errors")
    }

    @Test
    fun `non-positive count is an error`() {
        val text = """
            A = Alice
            ---
            0: A
        """.trimIndent()
        val errors = parseExpectingFailure(text)
        assertTrue(errors.any { "positive" in it.message }, "got: $errors")
    }

    @Test
    fun `non-numeric count is an error`() {
        val text = """
            A = Alice
            ---
            two: A
        """.trimIndent()
        val errors = parseExpectingFailure(text)
        assertTrue(errors.any { "Invalid ballot count" in it.message }, "got: $errors")
    }

    @Test
    fun `malformed declaration line is an error`() {
        val text = """
            Alice Johnson
            ---
            1: Alice
        """.trimIndent()
        val errors = parseExpectingFailure(text)
        assertTrue(errors.any { "Expected '<abbreviation>" in it.message }, "got: $errors")
    }

    @Test
    fun `empty abbreviation in index is an error`() {
        val text = """
             = Alice
            ---
            1: A
        """.trimIndent()
        val errors = parseExpectingFailure(text)
        assertTrue(errors.any { "Abbreviation is empty" in it.message }, "got: $errors")
    }

    @Test
    fun `empty full name in index is an error`() {
        val text = """
            A =
            ---
            1: A
        """.trimIndent()
        val errors = parseExpectingFailure(text)
        assertTrue(errors.any { "Full name is empty" in it.message }, "got: $errors")
    }

    @Test
    fun `stray operator in ballot is an error`() {
        val text = """
            A = Alice
            B = Bob
            ---
            1: A > > B
        """.trimIndent()
        val errors = parseExpectingFailure(text)
        assertTrue(errors.any { "Empty rank group" in it.message }, "got: $errors")
    }

    @Test
    fun `same candidate twice in one ballot is an error`() {
        val text = """
            A = Alice
            B = Bob
            ---
            1: A > B > A
        """.trimIndent()
        val errors = parseExpectingFailure(text)
        assertTrue(errors.any { "more than once" in it.message }, "got: $errors")
    }

    @Test
    fun `declared but unused candidate produces a warning not an error`() {
        val text = """
            A = Alice
            B = Bob
            C = Carol
            ---
            1: A > B
        """.trimIndent()
        val success = parseOrFail(text)
        assertEquals(1, success.warnings.size)
        assertTrue("Carol" in success.warnings[0].message, "got: ${success.warnings}")
    }

    @Test
    fun `reserved character in abbreviation is an error`() {
        val text = """
            A>B = Alice
            ---
            1: A
        """.trimIndent()
        val errors = parseExpectingFailure(text)
        assertTrue(errors.any { "reserved character" in it.message }, "got: $errors")
    }

    @Test
    fun `toBallots expands grouped counts and applies rank tiers`() {
        val parsed = parseOrFail(
            """
            A = Alice
            B = Bob
            C = Carol
            ---
            2: A > B = C
            1: C > A
            """.trimIndent()
        )
        val ballots = PasteTallyFormat.toBallots(
            parsed,
            electionName = "(pasted)",
            whenCast = Instant.fromEpochMilliseconds(0),
        )
        assertEquals(3, ballots.size)
        assertEquals(
            listOf(
                Ranking("Alice", 1),
                Ranking("Bob", 2),
                Ranking("Carol", 2),
            ),
            ballots[0].rankings,
        )
        assertEquals(ballots[0].rankings, ballots[1].rankings)
        assertEquals(
            listOf(Ranking("Carol", 1), Ranking("Alice", 2)),
            ballots[2].rankings,
        )
        // Synthesized voter names should be unique
        assertEquals(ballots.size, ballots.map { it.voterName }.toSet().size)
    }

    @Test
    fun `documentation example 1 - Condorcet winner`() {
        val text = """
            M = minor-improvements
            S = status-quo
            R = radical-changes
            ---
            30: M > S > R
            30: S > M > R
            40: R > M > S
        """.trimIndent()
        val tally = runEndToEnd(text)
        assertEquals(
            listOf("minor-improvements", "status-quo", "radical-changes"),
            tally.places.map { it.candidateName },
        )
    }

    @Test
    fun `documentation example 2 - tactical voting produces the consensus winner`() {
        val text = """
            M = minor-improvements
            S = status-quo
            R = radical-changes
            ---
            3: M > S > R
            4: S > M > R
            2: R > M > S
        """.trimIndent()
        val tally = runEndToEnd(text)
        assertEquals(
            listOf("minor-improvements", "status-quo", "radical-changes"),
            tally.places.map { it.candidateName },
        )
    }

    @Test
    fun `documentation example 3 - IRV-eliminated candidate is the pairwise winner`() {
        val text = """
            N = niche
            S = satisfactory
            B = bought
            C = cult
            ---
            4: N > S > B > C
            3: B > S > N > C
            3: C > S > N > B
        """.trimIndent()
        val tally = runEndToEnd(text)
        assertEquals(
            listOf("satisfactory", "niche", "bought", "cult"),
            tally.places.map { it.candidateName },
        )
    }

    @Test
    fun `documentation example 4 - cycle resolved by Ranked Pairs`() {
        val text = """
            R = rock
            P = paper
            S = scissors
            ---
            3: R > S > P
            3: P > R > S
            3: S > P > R
            1: R > S > P
        """.trimIndent()
        val tally = runEndToEnd(text)
        assertEquals(
            listOf("rock", "scissors", "paper"),
            tally.places.map { it.candidateName },
        )
    }

    @Test
    fun `documentation example 5 - perfectly-tied cycle ties all candidates`() {
        val text = """
            A = A
            B = B
            C = C
            ---
            2: A > B > C
            2: B > C > A
            2: C > A > B
        """.trimIndent()
        val tally = runEndToEnd(text)
        // All three should land at the same rank (1).
        assertEquals(setOf(1), tally.places.map { it.rank }.toSet())
        assertEquals(setOf("A", "B", "C"), tally.places.map { it.candidateName }.toSet())
    }

    @Test
    fun `paste flow round-trips through the tally engine`() {
        // Pasted equivalent of the canonical Apple/Banana/Cherry test in TallyTest.
        // Demonstrates the format actually feeds the engine end-to-end.
        val parsed = parseOrFail(
            """
            A = Apple
            B = Banana
            C = Cherry
            ---
            1: A > B > C
            1: A > C > B
            1: B > A > C
            """.trimIndent()
        )
        val ballots = PasteTallyFormat.toBallots(
            parsed,
            electionName = "(pasted)",
            whenCast = Instant.fromEpochMilliseconds(0),
        )
        val tally = Tally.countBallots(
            electionName = "(pasted)",
            side = RankingSide.PUBLIC,
            candidates = parsed.candidates.map { it.fullName },
            tiers = emptyList(),
            ballots = ballots,
        )
        assertEquals(listOf("Apple", "Banana", "Cherry"), tally.places.map { it.candidateName })
    }

    @Test
    fun `generateAbbreviations uses initials of each word lowercased`() {
        val out = PasteTallyFormat.generateAbbreviations(listOf("Alice Johnson", "Bob Smith"))
        assertEquals(mapOf("Alice Johnson" to "aj", "Bob Smith" to "bs"), out)
    }

    @Test
    fun `generateAbbreviations strips leading The before taking initials`() {
        val out = PasteTallyFormat.generateAbbreviations(
            listOf(
                "The Great War of Separation 2",
                "The Horseman: Welcome To Florespark",
            )
        )
        assertEquals("gwos2", out["The Great War of Separation 2"])
        assertEquals("hwtf", out["The Horseman: Welcome To Florespark"])
    }

    @Test
    fun `generateAbbreviations single-word name gets one letter`() {
        val out = PasteTallyFormat.generateAbbreviations(listOf("Apple", "Banana"))
        assertEquals(mapOf("Apple" to "a", "Banana" to "b"), out)
    }

    @Test
    fun `generateAbbreviations The prefix strip is case-insensitive`() {
        val out = PasteTallyFormat.generateAbbreviations(listOf("THE Quick Fox", "the Lazy Dog"))
        assertEquals(mapOf("THE Quick Fox" to "qf", "the Lazy Dog" to "ld"), out)
    }

    @Test
    fun `generateAbbreviations resolves collisions with numeric suffix in input order`() {
        // Both have base "ab" after stripping non-alnums and taking initials.
        val out = PasteTallyFormat.generateAbbreviations(listOf("Alpha Bravo", "Apple Box"))
        assertEquals("ab", out["Alpha Bravo"])
        assertEquals("ab2", out["Apple Box"])
    }

    @Test
    fun `generateAbbreviations skips non-alphanumerics when taking initials`() {
        // The leading punctuation on "!!Welcome" is skipped; the first alnum (w) is used.
        val out = PasteTallyFormat.generateAbbreviations(listOf("!!Welcome 99-bottles"))
        assertEquals("w9", out["!!Welcome 99-bottles"])
    }

    @Test
    fun `generateAbbreviations empty name falls back to x`() {
        val out = PasteTallyFormat.generateAbbreviations(listOf("---", "!!!"))
        assertEquals(setOf("x", "x2"), out.values.toSet())
    }

    @Test
    fun `renderAsPasteText produces text that the parser accepts and round-trips`() {
        // Build a small tally end-to-end so the render path consumes the
        // engine's actual ballot shape.
        val originalText = """
            A = Alice
            B = Bob
            C = Carol
            ---
            5: A > B > C
            3: B = C > A
            2: C > A
        """.trimIndent()
        val tally = runEndToEnd(originalText)

        val exported = PasteTallyFormat.renderAsPasteText(
            candidateNames = tally.candidateNames,
            ballots = tally.ballots,
            electionName = "test",
        )

        // Re-parse and re-tally; the place order must match.
        val reTally = runEndToEnd(exported)
        assertEquals(tally.places.map { it.candidateName }, reTally.places.map { it.candidateName })
        assertEquals(tally.places.map { it.rank }, reTally.places.map { it.rank })
    }

    @Test
    fun `renderAsPasteText omits tier markers from the candidate index`() {
        // Synthesize an Identified ballot directly so we can include a tier-marker ranking.
        val ballots = listOf(
            Ballot.Identified(
                voterName = "v1",
                electionName = "e",
                confirmation = "c1",
                whenCast = Instant.fromEpochMilliseconds(0),
                rankings = listOf(
                    Ranking("Alice", 1),
                    Ranking("TopTier", 2, kind = RankingKind.TIER),
                    Ranking("Bob", 3),
                ),
            )
        )
        val text = PasteTallyFormat.renderAsPasteText(
            candidateNames = listOf("Alice", "Bob", "TopTier"),
            ballots = ballots,
            tiers = listOf("TopTier"),
        )
        assertTrue("Alice" in text)
        assertTrue("Bob" in text)
        assertTrue("TopTier" !in text, "tier marker should be excluded: $text")
    }

    private fun runEndToEnd(text: String): Tally {
        val parsed = parseOrFail(text)
        val ballots = PasteTallyFormat.toBallots(
            parsed = parsed,
            electionName = "(pasted)",
            whenCast = Instant.fromEpochMilliseconds(0),
        )
        return Tally.countBallots(
            electionName = "(pasted)",
            side = RankingSide.PUBLIC,
            candidates = parsed.candidates.map { it.fullName },
            tiers = emptyList(),
            ballots = ballots,
        )
    }

    private fun parseOrFail(text: String): ParseResult.Success {
        return when (val r = PasteTallyFormat.parse(text)) {
            is ParseResult.Success -> r
            is ParseResult.Failure -> fail("Expected success but got errors: ${r.errors}")
        }
    }

    private fun parseExpectingFailure(text: String): List<ParseMessage> {
        return when (val r = PasteTallyFormat.parse(text)) {
            is ParseResult.Failure -> r.errors
            is ParseResult.Success -> fail("Expected failure but got success: $r")
        }
    }
}
