package com.seanshubin.vote.backend.router

import com.seanshubin.vote.domain.CandidateNote
import com.seanshubin.vote.domain.HtmlEscape
import com.seanshubin.vote.domain.MarkdownLite
import com.seanshubin.vote.domain.WidthPreference

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
        // In <head>, and synchronous, so the width class is on the root
        // element before the first paint. Deferring it would widen the page
        // visibly after the reader already saw it narrow.
        //
        // This is the only script on the page. If a Content-Security-Policy
        // is ever added, it needs a hash or nonce for this block.
        appendLine("<script>")
        appendLine(SCRIPT)
        appendLine("</script>")
        appendLine("</head>")
        appendLine("<body>")
        append(body)
        appendLine(WIDTH_TOGGLE)
        appendLine("</body>")
        appendLine("</html>")
    }

    /**
     * Applies the saved width preference and exposes the toggle's handler.
     *
     * The interpolated values are the shared constants, so the key and class
     * names cannot drift from the ones the app uses — a mismatch here would
     * not fail to compile, it would just silently stop remembering. They are
     * all single-line, which is why trimIndent is safe despite the warning
     * in this file's header: that trap only springs on interpolated values
     * that carry their own newlines, the way a review body does.
     *
     * The handler only toggles a class and writes storage. It does not touch
     * the button's label, because the label is two spans and CSS picks which
     * one shows — that way the button reads correctly in the first paint
     * instead of being corrected by script afterwards.
     */
    private val SCRIPT = """
        (function () {
          var root = document.documentElement;
          root.classList.add('${WidthPreference.HAS_JS_CLASS}');
          try {
            if (localStorage.getItem('${WidthPreference.STORAGE_KEY}') === '${WidthPreference.WIDE}') {
              root.classList.add('${WidthPreference.WIDE_CLASS}');
            }
          } catch (ignored) {}
          window.voteToggleWidth = function () {
            var wide = root.classList.toggle('${WidthPreference.WIDE_CLASS}');
            try {
              localStorage.setItem(
                '${WidthPreference.STORAGE_KEY}',
                wide ? '${WidthPreference.WIDE}' : '${WidthPreference.FIXED}'
              );
            } catch (ignored) {}
          };
        })();
    """.trimIndent()

    /**
     * Both labels ship in the markup and CSS reveals one. The arrows carry
     * the verb — outward to expand, inward to contract — because a corner
     * button reading "Wide" could be taken for a status rather than an
     * action. The aria-label is phrased to stay true in both states, so no
     * script has to keep it in sync.
     *
     * Written as entities rather than literal arrows: the glyphs would be
     * correct either way given the charset, but entities survive any later
     * change to how this file or the response is encoded.
     */
    private val WIDTH_TOGGLE =
        """<button class="width-toggle" type="button" """ +
            """title="Switch between fixed and full-window width" """ +
            """aria-label="Toggle page width" onclick="voteToggleWidth()">""" +
            """<span class="width-toggle-to-wide">&#8676;&#8677; Wide</span>""" +
            """<span class="width-toggle-to-fixed">&#8677;&#8676; Fixed</span>""" +
            "</button>"

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
        /*
         * Hidden until the head script proves a script can run. This page is
         * readable with JavaScript off on purpose, and a button that cannot
         * do anything is worse than no button.
         */
        .width-toggle { display: none; }
        html.has-js .width-toggle {
          display: block;
          position: fixed;
          bottom: 6px;
          right: 8px;
          padding: 1px 6px;
          font: 10px/1.6 system-ui, -apple-system, "Segoe UI", sans-serif;
          background: #f4f7fb;
          border: 1px solid #c4d2e1;
          border-radius: 4px;
          color: #2c5282;
          opacity: 0.6;
          cursor: pointer;
        }
        .width-toggle:hover, .width-toggle:focus-visible { opacity: 1; }
        /* CSS picks the label, so the button is right in the first paint. */
        .width-toggle-to-fixed { display: none; }
        html.width-wide .width-toggle-to-wide { display: none; }
        html.width-wide .width-toggle-to-fixed { display: inline; }
        /* The feature itself. Same selector shape the app's stylesheet uses. */
        html.width-wide .review { max-width: none; }
        @media (prefers-color-scheme: dark) {
          body { background: #0f1722; color: #d6e2f0; }
          .review { background: #16202e; border-color: #2a3a4f; }
          h1, a { color: #8ab4e8; }
          .text h2, .text h3, .text h4, .text h5, .text h6 { color: #8ab4e8; }
          .election, .byline { color: #8da2bb; }
          html.has-js .width-toggle {
            background: #1b2838;
            border-color: #2a3a4f;
            color: #8ab4e8;
          }
        }
    """.trimIndent()
}
