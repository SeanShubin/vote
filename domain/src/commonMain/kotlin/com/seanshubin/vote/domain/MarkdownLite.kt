package com.seanshubin.vote.domain

/**
 * A deliberately small markdown dialect for reviews: headings, bullet and
 * numbered lists, bold, italic, paragraphs and line breaks. Nothing else.
 *
 * **What it cannot do, on purpose.** There are no links, no images, and no
 * raw HTML passthrough. Those are the ways markdown renderers execute
 * someone else's code — `[x](javascript:...)`, `<img onerror=...>`, an
 * inline `<script>` — and the safest treatment of a vector is not to have
 * it. Omitting links means there is no URL to allowlist and no scheme to
 * filter, so there is nothing to get subtly wrong later.
 *
 * **The safety invariant.** The parser produces an AST of plain strings; it
 * never produces markup. The renderer escapes every one of those strings
 * through [HtmlEscape] and emits only tags from a fixed set — h2..h6, p, ul,
 * ol, li, strong, em, br. No attribute is ever written, so there is nowhere
 * for an event handler to live. A reviewer who types `<script>` gets the
 * text `<script>` on screen, which is what they asked for.
 *
 * **Why not a markdown library.** The same text renders server-side for the
 * shared page and client-side in the app, so one implementation in common
 * Kotlin keeps them identical; the mature JVM parsers are JVM-only. And a
 * general parser has to be configured into safety, while a closed tag set
 * is safe by construction.
 *
 * **Deliberate limits.** No nesting: a list item cannot contain a list, and
 * bold cannot contain italic. Emphasis does not span lines. Anything that
 * does not parse is left as literal text rather than guessed at — an
 * unclosed `*` is an asterisk, not the start of something.
 */
object MarkdownLite {
    /** Tags the renderer is allowed to emit. Nothing else may appear. */
    val ALLOWED_TAGS = setOf("h2", "h3", "h4", "h5", "h6", "p", "ul", "ol", "li", "strong", "em", "br")

    private const val MAX_HEADING_LEVEL = 6

    sealed interface Block {
        data class Heading(val level: Int, val spans: List<Span>) : Block
        data class Paragraph(val spans: List<Span>) : Block
        data class Bullets(val items: List<List<Span>>) : Block
        data class Numbers(val items: List<List<Span>>) : Block
    }

    sealed interface Span {
        /** Literal text. Holds no markup; the renderer escapes it. */
        data class Text(val value: String) : Span
        data class Bold(val value: String) : Span
        data class Italic(val value: String) : Span

        /**
         * A single newline inside a paragraph. Reviewers use one to break a
         * line without starting a new thought, and losing that would reflow
         * their text in ways they did not write.
         */
        data object LineBreak : Span
    }

    /**
     * Render [text] as HTML.
     *
     * [headingOffset] shifts markdown heading levels down. The default of 1
     * maps `#` to `<h2>`, because the page already has an `<h1>` of its own
     * — the candidate's name — and a review should not be able to introduce
     * a second document title.
     */
    fun toHtml(text: String, headingOffset: Int = 1): String =
        parse(text).joinToString("\n") { block -> renderBlock(block, headingOffset) }

    /**
     * The text with its markdown removed, for places that take no markup:
     * the `og:description` of a shared review, which is an attribute value
     * and would otherwise show a reviewer's `##` and `**` to everyone
     * reading a link preview.
     */
    fun toPlainText(text: String): String =
        parse(text).joinToString(" ") { block ->
            when (block) {
                is Block.Heading -> plain(block.spans)
                is Block.Paragraph -> plain(block.spans)
                is Block.Bullets -> block.items.joinToString(" ") { plain(it) }
                is Block.Numbers -> block.items.joinToString(" ") { plain(it) }
            }
        }

    fun parse(text: String): List<Block> {
        val lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val blocks = mutableListOf<Block>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank()) {
                index++
                continue
            }
            val heading = headingOf(line)
            if (heading != null) {
                blocks.add(Block.Heading(heading.first, parseSpans(heading.second)))
                index++
                continue
            }
            if (bulletContent(line) != null) {
                val items = mutableListOf<List<Span>>()
                while (index < lines.size) {
                    val content = bulletContent(lines[index]) ?: break
                    items.add(parseSpans(content))
                    index++
                }
                blocks.add(Block.Bullets(items))
                continue
            }
            if (numberContent(line) != null) {
                val items = mutableListOf<List<Span>>()
                while (index < lines.size) {
                    val content = numberContent(lines[index]) ?: break
                    items.add(parseSpans(content))
                    index++
                }
                blocks.add(Block.Numbers(items))
                continue
            }
            // A paragraph runs until a blank line or the start of another
            // kind of block, so a list directly after prose still becomes a
            // list rather than being swallowed as more prose.
            val paragraph = mutableListOf<Span>()
            while (index < lines.size) {
                val current = lines[index]
                if (current.isBlank()) break
                if (headingOf(current) != null) break
                if (bulletContent(current) != null) break
                if (numberContent(current) != null) break
                if (paragraph.isNotEmpty()) paragraph.add(Span.LineBreak)
                paragraph.addAll(parseSpans(current))
                index++
            }
            blocks.add(Block.Paragraph(paragraph))
        }
        return blocks
    }

    /** Level and content of a heading line, or null when it is not one. */
    private fun headingOf(line: String): Pair<Int, String>? {
        val trimmed = line.trimStart()
        var hashes = 0
        while (hashes < trimmed.length && trimmed[hashes] == '#') hashes++
        if (hashes == 0 || hashes > MAX_HEADING_LEVEL) return null
        // A space is required, so "#1 pick" stays prose. Without this, any
        // sentence opening with a hash would silently become a heading.
        if (hashes >= trimmed.length || trimmed[hashes] != ' ') return null
        val content = trimmed.substring(hashes + 1).trim()
        if (content.isEmpty()) return null
        return hashes to content
    }

    private fun bulletContent(line: String): String? {
        val trimmed = line.trimStart()
        if (trimmed.length < 2) return null
        if (trimmed[0] !in "-*+" || trimmed[1] != ' ') return null
        return trimmed.substring(2).trim().ifEmpty { null }
    }

    private fun numberContent(line: String): String? {
        val trimmed = line.trimStart()
        var digits = 0
        while (digits < trimmed.length && trimmed[digits].isDigit()) digits++
        if (digits == 0) return null
        if (digits + 1 >= trimmed.length) return null
        if (trimmed[digits] !in ".)") return null
        if (trimmed[digits + 1] != ' ') return null
        return trimmed.substring(digits + 2).trim().ifEmpty { null }
    }

    /**
     * Bold before italic, so `**x**` is not read as an empty italic followed
     * by stray asterisks.
     */
    private fun parseSpans(line: String): List<Span> {
        val spans = mutableListOf<Span>()
        val literal = StringBuilder()
        fun flush() {
            if (literal.isNotEmpty()) {
                spans.add(Span.Text(literal.toString()))
                literal.clear()
            }
        }
        var index = 0
        while (index < line.length) {
            val bold = delimited(line, index, "**")
            if (bold != null) {
                flush()
                spans.add(Span.Bold(bold.first))
                index = bold.second
                continue
            }
            val star = delimited(line, index, "*")
            if (star != null) {
                flush()
                spans.add(Span.Italic(star.first))
                index = star.second
                continue
            }
            val underscore = delimited(line, index, "_", wordBoundaryRequired = true)
            if (underscore != null) {
                flush()
                spans.add(Span.Italic(underscore.first))
                index = underscore.second
                continue
            }
            literal.append(line[index])
            index++
        }
        flush()
        return spans
    }

    /**
     * Content between a matched pair of [delimiter] starting at [start], with
     * the index just past the closing delimiter.
     *
     * Rejects a pair whose content is empty or begins or ends with a space,
     * which is what keeps arithmetic and prose intact: "2 * 3 * 4" has no
     * emphasis in it, and a lone asterisk never swallows the rest of a line.
     */
    private fun delimited(
        line: String,
        start: Int,
        delimiter: String,
        wordBoundaryRequired: Boolean = false,
    ): Pair<String, Int>? {
        if (!line.startsWith(delimiter, start)) return null
        // Underscores have to sit at a word boundary, which is CommonMark's
        // rule and exists for a reason: without it set_candidate_note renders
        // as set<em>candidate</em>note. Identifiers appear in reviews of a
        // voting app often enough that this is not hypothetical.
        if (wordBoundaryRequired && start > 0 && isWordCharacter(line[start - 1])) return null
        val contentStart = start + delimiter.length
        val closeAt = line.indexOf(delimiter, contentStart)
        if (closeAt < 0) return null
        val content = line.substring(contentStart, closeAt)
        if (content.isEmpty()) return null
        if (content.first() == ' ' || content.last() == ' ') return null
        // Would otherwise treat the second half of "**x**" as italic content.
        if (content.contains(delimiter)) return null
        val end = closeAt + delimiter.length
        if (wordBoundaryRequired && end < line.length && isWordCharacter(line[end])) return null
        return content to end
    }

    private fun isWordCharacter(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'

    private fun renderBlock(block: Block, headingOffset: Int): String = when (block) {
        is Block.Heading -> {
            val level = minOf(block.level + headingOffset, MAX_HEADING_LEVEL)
            "<h$level>${render(block.spans)}</h$level>"
        }
        is Block.Paragraph -> "<p>${render(block.spans)}</p>"
        is Block.Bullets -> block.items.joinToString("\n", "<ul>\n", "\n</ul>") { "<li>${render(it)}</li>" }
        is Block.Numbers -> block.items.joinToString("\n", "<ol>\n", "\n</ol>") { "<li>${render(it)}</li>" }
    }

    private fun render(spans: List<Span>): String = spans.joinToString("") { span ->
        when (span) {
            is Span.Text -> HtmlEscape.text(span.value)
            is Span.Bold -> "<strong>${HtmlEscape.text(span.value)}</strong>"
            is Span.Italic -> "<em>${HtmlEscape.text(span.value)}</em>"
            Span.LineBreak -> "<br>"
        }
    }

    private fun plain(spans: List<Span>): String = spans.joinToString("") { span ->
        when (span) {
            is Span.Text -> span.value
            is Span.Bold -> span.value
            is Span.Italic -> span.value
            Span.LineBreak -> " "
        }
    }
}
