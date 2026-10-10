package com.seanshubin.vote.backend.router

import com.seanshubin.vote.domain.CandidateNote
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The page is assembled by string interpolation from entirely user-supplied
 * values — review text, candidate names, voter names — and is served to
 * readers with no session. Escaping is the only thing standing between a
 * review and script execution in a stranger's browser, so most of these
 * tests are about that.
 */
class ReviewPageTest {

    @Test
    fun `escape covers the characters that matter in text and in attributes`() {
        assertEquals("&amp;", ReviewPage.escape("&"))
        assertEquals("&lt;", ReviewPage.escape("<"))
        assertEquals("&gt;", ReviewPage.escape(">"))
        assertEquals("&quot;", ReviewPage.escape("\""))
        assertEquals("&#39;", ReviewPage.escape("'"))
    }

    @Test
    fun `escape does not double-escape the entities it introduces`() {
        // Ampersand has to be replaced first. Replacing "<" first would turn
        // it into "&lt;" and the ampersand pass would then produce
        // "&amp;lt;", rendering the literal text "&lt;" to the reader.
        assertEquals("&amp;lt;", ReviewPage.escape("&lt;"))
        assertEquals("&lt;b&gt;", ReviewPage.escape("<b>"))
    }

    @Test
    fun `a review that tries to close the document cannot`() {
        val hostile = "</div></article><script>alert(document.cookie)</script>"
        val html = ReviewPage.found(noteWith(text = hostile), CANONICAL, APP)

        assertFalse(html.contains("<script>alert"), "raw script tag reached the page")
        assertFalse(html.contains("</article><script"), "raw markup reached the page")
        assertTrue(html.contains("&lt;script&gt;alert(document.cookie)&lt;/script&gt;"))
    }

    @Test
    fun `a hostile name cannot break out of an attribute value`() {
        // The candidate name lands in og:title, inside double quotes. A name
        // carrying a quote would otherwise end the attribute and let the rest
        // become markup.
        val html = ReviewPage.found(
            noteWith(candidateName = """Zalen" onload="alert(1)"""),
            CANONICAL,
            APP,
        )
        assertFalse(html.contains("""onload="alert(1)""""), "attribute break-out reached the page")
        assertTrue(html.contains("&quot;"), "the quote should have been escaped")
    }

    @Test
    fun `the page is kept out of search indexes`() {
        // Confirmed as a product decision: readable by anyone with the link,
        // but not published to a search index under a reviewer's name.
        val html = ReviewPage.found(noteWith(), CANONICAL, APP)
        assertTrue(html.contains("""<meta name="robots" content="noindex, nofollow">"""))
    }

    @Test
    fun `preview tags carry the reviewer, the candidate and an excerpt`() {
        // The reason the page is server-rendered at all: a link pasted into
        // Discord should say who reviewed what.
        val html = ReviewPage.found(
            noteWith(
                voterName = "Sean",
                candidateName = "Zalen: What Kills Must Die",
                text = "Another exciting book with lots of action.",
            ),
            CANONICAL,
            APP,
        )
        assertTrue(html.contains("""<meta property="og:title" content="Sean on Zalen: What Kills Must Die">"""))
        assertTrue(html.contains("""content="Another exciting book with lots of action.""""))
        assertTrue(html.contains("""<meta property="og:url" content="$CANONICAL">"""))
        assertTrue(html.contains("""<title>Sean on Zalen: What Kills Must Die</title>"""))
    }

    @Test
    fun `excerpt collapses the paragraph breaks a review is full of`() {
        // A description is one attribute value, and reviews have blank lines
        // between paragraphs.
        assertEquals(
            "first para second para",
            ReviewPage.excerpt("first para\n\n   second   para\n"),
        )
    }

    @Test
    fun `excerpt truncates long reviews at a word boundary`() {
        val words = List(200) { "word$it" }.joinToString(" ")
        val excerpt = ReviewPage.excerpt(words)

        assertTrue(excerpt.length <= 281, "excerpt too long: ${excerpt.length}")
        assertTrue(excerpt.endsWith("…"), "no ellipsis: $excerpt")
        assertFalse(excerpt.dropLast(1).endsWith(" "), "trailing space before ellipsis")
        assertTrue(words.startsWith(excerpt.dropLast(1)), "excerpt is not a prefix of the text")
    }

    @Test
    fun `excerpt still truncates text with no spaces to break on`() {
        // Guards the word-boundary search: with no space to find, it must
        // fall back to a hard cut rather than return the whole thing.
        val excerpt = ReviewPage.excerpt("x".repeat(1000))
        assertTrue(excerpt.length <= 281, "excerpt too long: ${excerpt.length}")
        assertTrue(excerpt.endsWith("…"))
    }

    @Test
    fun `short reviews are not given an ellipsis`() {
        assertEquals("brief", ReviewPage.excerpt("brief"))
    }

    @Test
    fun `the review body is rendered as markdown`() {
        // Paragraph structure is now carried by real elements rather than
        // white-space: pre-wrap, so the reviewer shape survives and a
        // heading or list reads as one.
        val html = ReviewPage.found(
            noteWith(text = "one\n\ntwo\n\n# Verdict\n- a\n- b"),
            CANONICAL,
            APP,
        )
        assertTrue(html.contains("<p>one</p>"), "got: $html")
        assertTrue(html.contains("<p>two</p>"), "got: $html")
        assertTrue(html.contains("<h2>Verdict</h2>"), "got: $html")
        assertTrue(html.contains("<li>a</li>"), "got: $html")
    }

    @Test
    fun `markdown in a review cannot introduce markup`() {
        // The reason this page can render markdown at all: MarkdownLite
        // escapes every string it carries and emits only its own tags.
        val html = ReviewPage.found(
            noteWith(text = "# Head\n\n<script>alert(1)</script>\n\n[x](javascript:alert(1))"),
            CANONICAL,
            APP,
        )
        assertFalse(html.contains("<script>alert"), "script survived")
        // The page has one anchor of its own (the link back to the app),
        // so the check is that the review link syntax stayed literal
        // rather than becoming a second one.
        assertTrue(html.contains("[x](javascript:alert(1))"), "link syntax was consumed")
        assertTrue(html.contains("&lt;script&gt;"), "got: $html")
    }

    @Test
    fun `the link preview shows no markdown syntax`() {
        // An og:description is an attribute value. A heading marker and
        // bold markers must not appear in everyone preview card.
        val html = ReviewPage.found(
            noteWith(text = "# Verdict\n\nIt was **very** good."),
            CANONICAL,
            APP,
        )
        assertTrue(html.contains("""content="Verdict It was very good.""""), "got: $html")
    }

    @Test
    fun `the not-found page explains the two ways a link dies`() {
        // Deletion and rename. Confirmed as acceptable precisely because it
        // is no mystery why the review would be gone, so the page should say.
        val html = ReviewPage.notFound(APP)
        assertTrue(html.contains("deleted"))
        assertTrue(html.contains("renamed"))
        assertTrue(html.contains("""<meta name="robots" content="noindex, nofollow">"""))
    }

    @Test
    fun `the not-found page claims no canonical url`() {
        // Nothing lives at that address, so pointing a canonical or og:url at
        // it would advertise a dead link as the real thing.
        val html = ReviewPage.notFound(APP)
        assertFalse(html.contains("og:url"))
        assertFalse(html.contains("rel=\"canonical\""))
    }

    @Test
    fun `both pages are complete documents`() {
        listOf(ReviewPage.found(noteWith(), CANONICAL, APP), ReviewPage.notFound(APP)).forEach { html ->
            assertTrue(html.startsWith("<!doctype html>"), "missing doctype")
            assertTrue(html.contains("""<meta charset="utf-8">"""), "missing charset")
            assertTrue(html.contains("name=\"viewport\""), "missing viewport")
            assertTrue(html.trimEnd().endsWith("</html>"), "document not closed")
        }
    }

    private fun noteWith(
        electionName: String = "Favorite Rippaverse Book",
        candidateName: String = "Zalen: What Kills Must Die",
        voterName: String = "Sean",
        text: String = "a review",
    ) = CandidateNote(
        electionName = electionName,
        candidateName = candidateName,
        voterName = voterName,
        text = text,
        lastUpdated = Instant.parse("2026-10-09T17:12:00Z"),
    )

    private companion object {
        const val CANONICAL = "https://pairwisevote.com/review/e/c/r-ayaw2g"
        const val APP = "https://pairwisevote.com/elections/Favorite%20Rippaverse%20Book#reviews"
    }
}
