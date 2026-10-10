package com.seanshubin.vote.backend.router

import com.seanshubin.vote.domain.CandidateNote
import com.seanshubin.vote.domain.HtmlEscape
import com.seanshubin.vote.domain.MarkdownLite

/**
 * The HTML for a shared review page.
 *
 * A complete, self-contained document rather than the SPA. The reader has no
 * session and may have no JavaScript, and Discord's crawler has neither —
 * rendering server-side is what makes the link show a title and an excerpt
 * instead of whatever the SPA shell happens to say.
 *
 * Everything interpolated here is user-supplied: review text, candidate
 * names, voter names. All of it goes through [escape], which covers the five
 * characters that matter in both a text node and a quoted attribute value.
 * One function for both contexts on purpose — a second, narrower escaper
 * invites using the wrong one, and escaping quotes in a text node costs
 * nothing.
 *
 * Assembled line by line rather than from an indented raw string. A raw
 * string's `trimIndent` runs *after* interpolation, so splicing in a review
 * — which has its own newlines at no indentation — drops the common indent
 * to zero and leaves the whole document indented. Building it explicitly
 * keeps the output independent of how the source happens to be laid out.
 */
object ReviewPage {
    /**
     * Discord shows roughly the first few hundred characters of a
     * description. Truncating here keeps the preview from being cut
     * mid-sentence by the consumer and keeps the meta tag a sensible size.
     */
    private const val EXCERPT_LENGTH = 280

    fun found(
        note: CandidateNote,
        canonicalUrl: String,
        appUrl: String,
    ): String {
        val body = buildString {
            appendLine("""<article class="review">""")
            appendLine("""<p class="election">${escape(note.electionName)}</p>""")
            appendLine("<h1>${escape(note.candidateName)}</h1>")
            val date = note.lastUpdated.toString().take(10)
            appendLine("""<p class="byline">Reviewed by ${escape(note.voterName)} &middot; $date</p>""")
            // Rendered as the small markdown dialect in MarkdownLite,
            // which escapes every string it carries and emits only its own
            // fixed set of tags — a review can add formatting but never
            // markup or behavior.
            appendLine("""<div class="text">""")
            appendLine(MarkdownLite.toHtml(note.text))
            appendLine("</div>")
            appendLine("</article>")
            appendLine(
                """<p class="app-link"><a href="${escape(appUrl)}">""" +
                    "See every review of this election</a></p>",
            )
        }
        return document(
            title = "${note.voterName} on ${note.candidateName}",
            // Markdown stripped, not rendered: a description is an attribute
            // value, so a reviewer's ## and ** would otherwise show up in
            // everyone's link preview.
            description = excerpt(MarkdownLite.toPlainText(note.text)),
            canonicalUrl = canonicalUrl,
            body = body,
        )
    }

    fun notFound(appUrl: String): String {
        val body = buildString {
            appendLine("""<article class="review">""")
            appendLine("<h1>Review not found</h1>")
            appendLine(
                """<p class="text">This link no longer points to a review. It may have been """ +
                    "deleted, or the election or candidate may have been renamed — a review's " +
                    "address is built from those names, so it changes when they do.</p>",
            )
            appendLine("</article>")
            appendLine("""<p class="app-link"><a href="${escape(appUrl)}">Go to PairwiseVote</a></p>""")
        }
        return document(
            title = "Review not found",
            description = "This review is no longer available.",
            // Nothing lives at this address, so advertising a canonical url
            // for it would present a dead link as the real thing.
            canonicalUrl = null,
            body = body,
        )
    }

    /**
     * Collapses whitespace and truncates, because a description meta tag is a
     * single attribute value and a review has paragraph breaks.
     */
    fun excerpt(text: String): String {
        val collapsed = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        if (collapsed.length <= EXCERPT_LENGTH) return collapsed
        // Prefer a word boundary, but only when one is reasonably near the
        // end, so a run of very long tokens still gets cut.
        val hardCut = collapsed.take(EXCERPT_LENGTH)
        val lastSpace = hardCut.lastIndexOf(' ')
        val cut = if (lastSpace > EXCERPT_LENGTH / 2) hardCut.take(lastSpace) else hardCut
        return "$cut…"
    }

    /**
     * Escapes for both text nodes and quoted attribute values. Delegates to
     * the shared implementation in :domain, which MarkdownLite also uses, so
     * the page and the review body cannot escape differently.
     */
    fun escape(raw: String): String = HtmlEscape.text(raw)

    private fun document(
        title: String,
        description: String,
        canonicalUrl: String?,
        body: String,
    ): String = buildString {
        appendLine("<!doctype html>")
        appendLine("""<html lang="en">""")
        appendLine("<head>")
        appendLine("""<meta charset="utf-8">""")
        appendLine("""<meta name="viewport" content="width=device-width, initial-scale=1">""")
        appendLine("<title>${escape(title)}</title>")
        // Readable by anyone with the link, but deliberately kept out of
        // search results: sharing a review is not the same as publishing a
        // reviewer's name to a search index. Link previews do not consult
        // robots directives, so this costs nothing there.
        appendLine("""<meta name="robots" content="noindex, nofollow">""")
        appendLine("""<meta name="description" content="${escape(description)}">""")
        appendLine("""<meta property="og:type" content="article">""")
        appendLine("""<meta property="og:site_name" content="PairwiseVote">""")
        appendLine("""<meta property="og:title" content="${escape(title)}">""")
        appendLine("""<meta property="og:description" content="${escape(description)}">""")
        if (canonicalUrl != null) {
            appendLine("""<link rel="canonical" href="${escape(canonicalUrl)}">""")
            appendLine("""<meta property="og:url" content="${escape(canonicalUrl)}">""")
        }
        appendLine("""<meta name="twitter:card" content="summary">""")
        appendLine("<style>")
        appendLine(STYLE)
        appendLine("</style>")
        appendLine("</head>")
        appendLine("<body>")
        append(body)
        appendLine("</body>")
        appendLine("</html>")
    }

    // No interpolation in here, so trimIndent behaves predictably.
    private val STYLE = """
        :root { color-scheme: light dark; }
        body {
          margin: 0;
          padding: 2rem 1rem;
          display: flex;
          flex-direction: column;
          align-items: center;
          gap: 1rem;
          background: #f7f9fc;
          color: #1f3956;
          font: 1rem/1.5 system-ui, -apple-system, "Segoe UI", sans-serif;
        }
        .review {
          display: flex;
          flex-direction: column;
          gap: 0.5rem;
          width: 100%;
          max-width: 42rem;
          box-sizing: border-box;
          padding: 1.5rem;
          border: 1px solid #c4d2e1;
          border-radius: 6px;
          background: #ffffff;
        }
        .election { margin: 0; color: #768aa3; font-size: 0.9rem; }
        h1 { margin: 0; font-size: 1.4rem; color: #2c5282; }
        .byline { margin: 0; color: #768aa3; font-size: 0.9rem; }
        .text > :first-child { margin-top: 0; }
        .text > :last-child { margin-bottom: 0; }
        .text p { margin: 0 0 0.75rem; }
        .text h2, .text h3, .text h4, .text h5, .text h6 {
          margin: 1.25rem 0 0.5rem;
          font-size: 1.05rem;
          color: #2c5282;
        }
        .text ul, .text ol { margin: 0 0 0.75rem; padding-left: 1.5rem; }
        .text li { margin: 0.15rem 0; }
        .app-link { margin: 0; font-size: 0.9rem; }
        a { color: #2c5282; }
        @media (prefers-color-scheme: dark) {
          body { background: #0f1722; color: #d6e2f0; }
          .review { background: #16202e; border-color: #2a3a4f; }
          h1, a { color: #8ab4e8; }
          .text h2, .text h3, .text h4, .text h5, .text h6 { color: #8ab4e8; }
          .election, .byline { color: #8da2bb; }
        }
    """.trimIndent()
}
