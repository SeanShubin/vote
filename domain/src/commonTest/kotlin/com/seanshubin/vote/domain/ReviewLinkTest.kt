package com.seanshubin.vote.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs on both JVM and JS, which is the point: the backend resolves these
 * addresses and the frontend hands them out, so a checksum that disagreed
 * across targets would produce links that look right and resolve to nothing.
 */
class ReviewLinkTest {

    @Test
    fun `path for the real names it was designed around`() {
        // The actual election, candidate and reviewer this feature exists for
        // — spaces and a colon, the two characters that forced double
        // encoding in the first place.
        val path = ReviewLink.path(
            "Favorite Rippaverse Book",
            "Zalen: What Kills Must Die",
            "Sean",
        )
        assertTrue(
            path.startsWith("/review/favorite-rippaverse-book/zalen-what-kills-must-die/sean-"),
            "unexpected path: $path",
        )
        // No character in the address needs escaping anywhere.
        assertTrue(
            path.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '/' },
            "path is not already URL-safe: $path",
        )
    }

    @Test
    fun `checksum is stable for the same triple`() {
        // A link shared today has to resolve tomorrow, so the value must be a
        // pure function of the names and nothing else.
        val first = ReviewLink.checksum("Favorite Rippaverse Book", "Zalen: What Kills Must Die", "Sean")
        val second = ReviewLink.checksum("Favorite Rippaverse Book", "Zalen: What Kills Must Die", "Sean")
        assertEquals(first, second)
    }

    @Test
    fun `checksum is pinned to a literal so a change of algorithm cannot pass quietly`() {
        // Hard-coded on purpose. Changing the hash breaks every link already
        // shared, which is a decision to make deliberately, not something to
        // discover after the fact. If this fails, that is the question being
        // asked. The same literal also proves JVM and JS agree, since this
        // test body runs on both.
        assertEquals(
            "ayaw2g",
            ReviewLink.checksum("Favorite Rippaverse Book", "Zalen: What Kills Must Die", "Sean"),
        )
    }

    @Test
    fun `checksum is always six base36 digits`() {
        // Fixed width keeps the address one shape, and padStart is easy to
        // get wrong for small values, so check a spread of inputs.
        listOf(
            Triple("a", "b", "c"),
            Triple("Favorite Rippaverse Book", "Isom 1", "Sean"),
            Triple("x".repeat(500), "y".repeat(500), "z".repeat(500)),
            Triple("", "", ""),
        ).forEach { (e, c, r) ->
            val sum = ReviewLink.checksum(e, c, r)
            assertEquals(6, sum.length, "wrong width for $e/$c/$r: $sum")
            assertTrue(
                sum.all { it in '0'..'9' || it in 'a'..'z' },
                "not base36: $sum",
            )
        }
    }

    @Test
    fun `checksum ignores case and surrounding whitespace in the names`() {
        // Names are unique case-insensitively, so a link must not depend on
        // which casing the sharer had on screen.
        val canonical = ReviewLink.checksum("Favorite Rippaverse Book", "Isom 1", "Sean")
        assertEquals(canonical, ReviewLink.checksum("favorite rippaverse book", "ISOM 1", "sean"))
        assertEquals(canonical, ReviewLink.checksum("  Favorite Rippaverse Book  ", "Isom 1", " Sean "))
    }

    @Test
    fun `checksum distinguishes triples that slug alike`() {
        // The whole reason the checksum exists. These two candidates slug to
        // the same segment, so without it the address would be ambiguous.
        assertEquals(
            Slug.of("Isom: 1"),
            Slug.of("Isom 1"),
            "precondition: these names must collide as slugs",
        )
        assertTrue(
            ReviewLink.checksum("e", "Isom: 1", "sean") != ReviewLink.checksum("e", "Isom 1", "sean"),
            "colliding slugs must not also collide as checksums",
        )
    }

    @Test
    fun `checksum distinguishes which field a name sits in`() {
        // Joined with a separator rather than concatenated, so moving a name
        // between fields changes the result.
        assertTrue(
            ReviewLink.checksum("ab", "c", "d") != ReviewLink.checksum("a", "bc", "d"),
            "field boundaries must affect the checksum",
        )
    }

    @Test
    fun `matches accepts the triple the address was built from and rejects others`() {
        val sum = ReviewLink.checksum("Favorite Rippaverse Book", "Zalen: What Kills Must Die", "Sean")
        assertTrue(ReviewLink.matches("Favorite Rippaverse Book", "Zalen: What Kills Must Die", "Sean", sum))
        assertTrue(
            ReviewLink.matches("favorite rippaverse book", "zalen: what kills must die", "sean", sum),
            "resolution compares case-insensitively",
        )
        assertFalse(
            ReviewLink.matches("Favorite Rippaverse Book", "Isom 1", "Sean", sum),
            "a different candidate must not match",
        )
        assertFalse(
            ReviewLink.matches("Favorite Rippaverse Book", "Zalen: What Kills Must Die", "Bad Mr. Frosty", sum),
            "a different reviewer must not match",
        )
    }

    @Test
    fun `matches tolerates a checksum typed in upper case`() {
        // Someone retyping a link from a screenshot should not be defeated by
        // shift being held down.
        val sum = ReviewLink.checksum("e", "c", "r")
        assertTrue(ReviewLink.matches("e", "c", "r", sum.uppercase()))
    }

    @Test
    fun `reviewer segment splits on the final dash so dashed names survive`() {
        // "Bad Mr. Frosty" slugs to "bad-mr-frosty", so splitting on the
        // first dash would lose most of the name.
        val segment = "bad-mr-frosty-a7f3k2"
        val parsed = ReviewLink.parseReviewerSegment(segment)
        assertEquals("bad-mr-frosty", parsed?.slug)
        assertEquals("a7f3k2", parsed?.checksum)
    }

    @Test
    fun `reviewer segment round-trips through path and parse`() {
        val path = ReviewLink.path("Favorite Rippaverse Book", "Yaira 3", "Bad Mr. Frosty")
        val parsed = ReviewLink.parseReviewerSegment(path.substringAfterLast('/'))
        assertEquals(Slug.of("Bad Mr. Frosty"), parsed?.slug)
        assertTrue(
            ReviewLink.matches("Favorite Rippaverse Book", "Yaira 3", "Bad Mr. Frosty", parsed!!.checksum),
        )
    }

    @Test
    fun `malformed reviewer segments are rejected rather than guessed at`() {
        // A truncated or hand-typed link should fail to resolve, not resolve
        // to something plausible.
        assertNull(ReviewLink.parseReviewerSegment("sean"), "no checksum at all")
        assertNull(ReviewLink.parseReviewerSegment("sean-"), "empty checksum")
        assertNull(ReviewLink.parseReviewerSegment("-a7f3k2"), "empty slug")
        assertNull(ReviewLink.parseReviewerSegment(""), "empty segment")
    }
}
