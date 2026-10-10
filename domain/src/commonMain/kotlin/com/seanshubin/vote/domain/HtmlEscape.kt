package com.seanshubin.vote.domain

/**
 * Escapes text for inclusion in HTML.
 *
 * One function for both text nodes and quoted attribute values. A second,
 * narrower escaper invites reaching for the wrong one at the wrong moment,
 * and escaping quotes inside a text node costs nothing.
 *
 * Lives in :domain because [MarkdownLite] needs it to render and the backend
 * needs it for page attributes, and the two must not drift.
 */
object HtmlEscape {
    /**
     * Ampersand is replaced first. Doing it later would re-escape the
     * entities introduced below and render the literal text "&lt;" to the
     * reader instead of a less-than sign.
     */
    fun text(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
