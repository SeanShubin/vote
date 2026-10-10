package com.seanshubin.vote.frontend

import androidx.compose.runtime.Composable
import com.seanshubin.vote.domain.MarkdownLite
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Br
import org.jetbrains.compose.web.dom.Em
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.H4
import org.jetbrains.compose.web.dom.H5
import org.jetbrains.compose.web.dom.H6
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.Ol
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul

/**
 * Renders a review's markdown as Compose nodes.
 *
 * Builds real elements from [MarkdownLite.parse] rather than handing an HTML
 * string to innerHTML. The backend renders the same AST to HTML for the
 * shared page, so both surfaces show identical structure from one parser —
 * but this side never has to trust a string, because it never produces one.
 * Every piece of reviewer text reaches the DOM through [Text], which the
 * browser treats as text by construction, so there is no escaping step here
 * to get wrong.
 *
 * [headingOffset] defaults to 2, one more than the shared page uses. This
 * page nests reviews under an h2 ("Candidate reviews"), where that page puts
 * them under its h1 (the candidate's name), so the same review is an h3 here
 * and an h2 there. Each is correct for the document it sits in; CSS gives
 * them the same size either way.
 */
@Composable
fun MarkdownText(text: String, headingOffset: Int = 2) {
    MarkdownLite.parse(text).forEach { block ->
        when (block) {
            is MarkdownLite.Block.Heading -> {
                when (minOf(block.level + headingOffset, 6)) {
                    3 -> H3 { Spans(block.spans) }
                    4 -> H4 { Spans(block.spans) }
                    5 -> H5 { Spans(block.spans) }
                    else -> H6 { Spans(block.spans) }
                }
            }
            is MarkdownLite.Block.Paragraph -> P { Spans(block.spans) }
            is MarkdownLite.Block.Bullets -> Ul {
                block.items.forEach { item -> Li { Spans(item) } }
            }
            is MarkdownLite.Block.Numbers -> Ol {
                block.items.forEach { item -> Li { Spans(item) } }
            }
        }
    }
}

@Composable
private fun Spans(spans: List<MarkdownLite.Span>) {
    spans.forEach { span ->
        when (span) {
            is MarkdownLite.Span.Text -> Text(span.value)
            // <b> rather than <strong>: compose-html 1.9.0 offers no
            // Strong builder. The shared page emits <strong>, so the tag
            // name differs between the two surfaces while the rendering and
            // the structure do not.
            is MarkdownLite.Span.Bold -> B { Text(span.value) }
            is MarkdownLite.Span.Italic -> Em { Text(span.value) }
            MarkdownLite.Span.LineBreak -> Br()
        }
    }
}
