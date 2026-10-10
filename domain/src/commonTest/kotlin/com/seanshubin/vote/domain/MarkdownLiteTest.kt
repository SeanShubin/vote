package com.seanshubin.vote.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Most of this file is about one property: a review can never introduce
 * markup or behavior, only text and the small set of tags we choose to emit.
 * The formatting tests are the easy half.
 */
class MarkdownLiteTest {

    // ───────────────────────── safety ─────────────────────────

    @Test
    fun `a script tag is text, not a script`() {
        val html = MarkdownLite.toHtml("<script>alert(document.cookie)</script>")

        assertFalse(html.contains("<script"), "raw script tag survived")
        assertTrue(html.contains("&lt;script&gt;"))
    }

    @Test
    fun `an image with an error handler is text`() {
        // The classic no-JavaScript-needed vector.
        val html = MarkdownLite.toHtml("""<img src=x onerror="alert(1)">""")

        assertFalse(html.contains("<img"), "raw img tag survived")
        assertFalse(html.contains("onerror=\""), "an attribute reached the output")
        assertTrue(html.contains("&lt;img"))
    }

    @Test
    fun `link syntax stays literal because links are not supported`() {
        // No links means no URL to allowlist and no scheme to filter. The
        // text appears as written.
        val html = MarkdownLite.toHtml("[click me](javascript:alert(1))")

        assertFalse(html.contains("<a"), "an anchor was emitted")
        assertFalse(html.contains("href"), "an href was emitted")
        assertTrue(html.contains("[click me](javascript:alert(1))"))
    }

    @Test
    fun `the renderer never writes an attribute`() {
        // The structural guarantee: every tag in the output is a bare tag
        // from the allowed set. An attribute needs a space inside the tag,
        // so "no tag but these exact strings" means no attribute can exist
        // and there is nowhere for an event handler to live.
        //
        // Checked this way rather than by searching for "href" or
        // "javascript:", which legitimately appear as escaped, visible text
        // — text cannot execute, so its presence proves nothing either way.
        val bareTags = MarkdownLite.ALLOWED_TAGS.flatMap { listOf("<$it>", "</$it>") }.toSet()
        val hostile = listOf(
            """<a href="javascript:alert(1)">x</a>""",
            """<div onclick=alert(1)>x</div>""",
            """<svg/onload=alert(1)>""",
            """<iframe src="data:text/html,<script>alert(1)</script>">""",
            """**bold" onmouseover="alert(1)**""",
            """# heading" onload="alert(1)""",
            """- item" autofocus onfocus="alert(1)""",
            """<p class="x">already a tag we emit</p>""",
        )
        hostile.forEach { input ->
            val html = MarkdownLite.toHtml(input)
            val tags = Regex("<[^>]*>").findAll(html).map { it.value }.toSet()
            val unexpected = tags - bareTags
            assertTrue(unexpected.isEmpty(), "tags $unexpected emitted for input: $input -> $html")
            // No unescaped quote survives anywhere, so even an unquoted
            // attribute has no tag to sit inside.
            assertFalse(html.contains("\""), "a raw quote survived for: $input -> $html")
        }
    }

    @Test
    fun `only tags from the allowed set are ever emitted`() {
        // The structural guarantee, checked over input that exercises every
        // construct at once alongside attempted markup.
        val input = """
            # Verdict
            ## Detail
            Prose with **bold** and *italic* and _underscores_.
            - first
            - second
            1. one
            2. two
            <b>raw</b> <script>no</script> <img onerror=x>
        """.trimIndent()
        val html = MarkdownLite.toHtml(input)

        val emitted = Regex("<\\s*/?\\s*([A-Za-z][A-Za-z0-9]*)").findAll(html)
            .map { it.groupValues[1].lowercase() }
            .toSet()
        val unexpected = emitted - MarkdownLite.ALLOWED_TAGS
        assertTrue(unexpected.isEmpty(), "unexpected tags emitted: $unexpected in $html")
    }

    @Test
    fun `ampersands and quotes are escaped without double-escaping`() {
        val html = MarkdownLite.toHtml("""Tom & Jerry said "hi" and it's fine""")

        assertTrue(html.contains("&amp;"))
        assertTrue(html.contains("&quot;"))
        assertTrue(html.contains("&#39;"))
        assertFalse(html.contains("&amp;amp;"), "double-escaped")
        assertFalse(html.contains("&amp;quot;"), "double-escaped")
    }

    @Test
    fun `an entity typed by the reviewer is shown as typed`() {
        // "&lt;" in a review means the reviewer wrote those four characters.
        val html = MarkdownLite.toHtml("I typed &lt;b&gt; on purpose")

        assertTrue(html.contains("&amp;lt;b&amp;gt;"), "got: $html")
    }

    @Test
    fun `markup cannot be smuggled through bold or heading content`() {
        // Emphasis and heading text go through the same escaping as prose.
        assertTrue(MarkdownLite.toHtml("**<script>x</script>**").contains("&lt;script&gt;"))
        assertTrue(MarkdownLite.toHtml("# <script>x</script>").contains("&lt;script&gt;"))
        assertTrue(MarkdownLite.toHtml("- <script>x</script>").contains("&lt;script&gt;"))
    }

    // ───────────────────────── formatting ─────────────────────────

    @Test
    fun `headings start at h2 so a review cannot claim the page title`() {
        // The page's own h1 is the candidate name.
        assertEquals("<h2>Verdict</h2>", MarkdownLite.toHtml("# Verdict"))
        assertEquals("<h3>Detail</h3>", MarkdownLite.toHtml("## Detail"))
    }

    @Test
    fun `heading levels are clamped rather than overflowing`() {
        assertEquals("<h6>Deep</h6>", MarkdownLite.toHtml("###### Deep"))
    }

    @Test
    fun `a hash without a space is prose`() {
        // "#1 pick" is a common way to write a ranking, not a heading.
        val html = MarkdownLite.toHtml("#1 pick of the year")

        assertTrue(html.startsWith("<p>"), "got: $html")
        assertTrue(html.contains("#1 pick"))
    }

    @Test
    fun `bullets become an unordered list`() {
        val html = MarkdownLite.toHtml("- first\n- second")

        assertEquals("<ul>\n<li>first</li>\n<li>second</li>\n</ul>", html)
    }

    @Test
    fun `all three bullet markers work`() {
        listOf("-", "*", "+").forEach { marker ->
            assertEquals("<ul>\n<li>item</li>\n</ul>", MarkdownLite.toHtml("$marker item"), "marker $marker")
        }
    }

    @Test
    fun `numbered lines become an ordered list`() {
        // The shape the Zalen review already used.
        val html = MarkdownLite.toHtml("1. The confrontation\n2. The ship\n3. The creature")

        assertEquals("<ol>\n<li>The confrontation</li>\n<li>The ship</li>\n<li>The creature</li>\n</ol>", html)
    }

    @Test
    fun `a list directly after prose is still a list`() {
        val html = MarkdownLite.toHtml("Consider these:\n- one\n- two")

        assertTrue(html.contains("<p>Consider these:</p>"), "got: $html")
        assertTrue(html.contains("<ul>"), "got: $html")
    }

    @Test
    fun `blank lines separate paragraphs`() {
        val html = MarkdownLite.toHtml("First thought.\n\nSecond thought.")

        assertEquals("<p>First thought.</p>\n<p>Second thought.</p>", html)
    }

    @Test
    fun `a single newline inside a paragraph is a line break`() {
        // Reviewers break lines without meaning a new paragraph; reflowing
        // their text would change what they wrote.
        val html = MarkdownLite.toHtml("line one\nline two")

        assertEquals("<p>line one<br>line two</p>", html)
    }

    @Test
    fun `runs of blank lines collapse rather than making empty paragraphs`() {
        val html = MarkdownLite.toHtml("one\n\n\n\ntwo")

        assertEquals("<p>one</p>\n<p>two</p>", html)
    }

    @Test
    fun `bold and italic render as strong and em`() {
        assertEquals("<p><strong>very</strong></p>", MarkdownLite.toHtml("**very**"))
        assertEquals("<p><em>sort of</em></p>", MarkdownLite.toHtml("*sort of*"))
        assertEquals("<p><em>sort of</em></p>", MarkdownLite.toHtml("_sort of_"))
    }

    @Test
    fun `bold wins over italic so double asterisks are not misread`() {
        assertEquals("<p>a <strong>b</strong> c</p>", MarkdownLite.toHtml("a **b** c"))
    }

    @Test
    fun `an unclosed delimiter stays literal`() {
        // Guessing would let one stray asterisk italicise the rest of a
        // review. Leaving it alone is predictable.
        assertEquals("<p>2 * 3 = 6</p>", MarkdownLite.toHtml("2 * 3 = 6"))
        assertEquals("<p>a lone * asterisk</p>", MarkdownLite.toHtml("a lone * asterisk"))
        assertEquals("<p>**unclosed</p>", MarkdownLite.toHtml("**unclosed"))
    }

    @Test
    fun `arithmetic and snake_case survive`() {
        // The everyday false positives of naive emphasis parsing.
        assertEquals("<p>2 * 3 * 4</p>", MarkdownLite.toHtml("2 * 3 * 4"))
        assertTrue(MarkdownLite.toHtml("call set_candidate_note now").contains("set_candidate_note"))
    }

    @Test
    fun `emphasis does not span lines`() {
        val html = MarkdownLite.toHtml("start *not\nemphasis* end")

        assertTrue(html.contains("*not"), "got: $html")
        assertTrue(html.contains("emphasis*"), "got: $html")
        assertFalse(html.contains("<em>"), "emphasis leaked across a line break")
    }

    @Test
    fun `empty and blank input produce nothing`() {
        assertEquals("", MarkdownLite.toHtml(""))
        assertEquals("", MarkdownLite.toHtml("   \n\n  \n"))
    }

    @Test
    fun `plain prose is just a paragraph`() {
        val opening = "Another exciting book with lots of action and easy-to-understand characters."

        assertEquals("<p>$opening</p>", MarkdownLite.toHtml(opening))
    }

    // ───────────────────────── plain text ─────────────────────────

    @Test
    fun `toPlainText strips markdown for link previews`() {
        // An og:description is an attribute value, so a reviewer's ## and **
        // must not show up in everyone's Discord preview.
        val plain = MarkdownLite.toPlainText("# Verdict\n\nIt was **very** good.\n- one\n- two")

        assertEquals("Verdict It was very good. one two", plain)
        assertFalse(plain.contains("#"))
        assertFalse(plain.contains("*"))
        assertFalse(plain.contains("<"))
    }

    @Test
    fun `toPlainText turns line breaks into spaces`() {
        assertEquals("line one line two", MarkdownLite.toPlainText("line one\nline two"))
    }
}
