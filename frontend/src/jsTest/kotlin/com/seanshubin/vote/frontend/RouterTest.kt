package com.seanshubin.vote.frontend

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Round-trip tests for the SPA URL layer. The interesting case is names that
 * contain characters AWS API Gateway HTTP API v2 mishandles when single-
 * encoded — `?` is the canonical example: API Gateway decodes %3F then
 * re-parses the path for a query separator, truncating everything after.
 *
 * The fix is to double-encode path segments. These tests pin that behavior
 * so a future change to the encoder helpers can't silently regress to
 * single-encoding (which works locally against Jetty but breaks in prod).
 */
class RouterTest {

    @Test
    fun `election detail roundtrips through a name with a question mark`() {
        val original = Page.ElectionDetail("What should the Rippaverse focus on improving?")
        val path = pageToPath(original)
        val parsed = pathToPage(path)
        assertEquals(original, parsed)
    }

    @Test
    fun `election detail path encodes ? as %253F (double-encoded)`() {
        // The specific wire format matters: API Gateway HTTPv2 will decode
        // %253F once to %3F, leaving a valid path segment that the backend's
        // URLDecoder.decode collapses to ?. Single-encoded (%3F) would be
        // decoded to ? and treated as a query separator.
        val path = pageToPath(Page.ElectionDetail("a?b"))
        assertEquals("/elections/a%253Fb", path)
    }

    @Test
    fun `election detail roundtrips through a name with spaces`() {
        val original = Page.ElectionDetail("Favorite Rippaverse Book")
        val parsed = pathToPage(pageToPath(original))
        assertEquals(original, parsed)
    }

    @Test
    fun `election head-to-head roundtrips with a question mark in the name`() {
        val original = Page.ElectionHeadToHead("Why?")
        val parsed = pathToPage(pageToPath(original))
        assertEquals(original, parsed)
    }

    @Test
    fun `election process roundtrips with a question mark in the name`() {
        val original = Page.ElectionProcess("Why?")
        val parsed = pathToPage(pageToPath(original))
        assertEquals(original, parsed)
    }

    @Test
    fun `name containing literal percent survives the encode-decode roundtrip`() {
        // A name with `%` is a stress test for double-encoding — the literal
        // `%` becomes `%25`, then `%2525` after the second pass. Verifies the
        // encoder is symmetric with the decoder for any character.
        val original = Page.ElectionDetail("100% sure")
        val parsed = pathToPage(pageToPath(original))
        assertEquals(original, parsed)
    }
}
