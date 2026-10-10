package com.seanshubin.vote.domain

/**
 * Turns a natural-key name into a URL path segment.
 *
 * Names in this system are free text — "Favorite Rippaverse Book",
 * "Zalen: What Kills Must Die" — and putting them in a path raw means
 * double-encoding, because API Gateway HTTP API v2 decodes the path once and
 * re-parses it for query separators. The result is unreadable and leans on
 * the exact `%25` behavior that has already caused one bug. A slug sidesteps
 * the encoding question entirely: what comes out is already safe in a path,
 * in a Discord message, and in a browser's address bar.
 *
 * Lossy on purpose. Two different names can slug alike ("Isom: 1" and
 * "Isom 1"), so a slug alone never identifies anything — [ReviewLink] pairs
 * it with a checksum over the exact names, and resolution tests candidates
 * whose slug matches until the checksum agrees.
 */
object Slug {
    /**
     * Returned when a name slugs to nothing — a name made entirely of
     * punctuation, which validation permits. An empty segment would put
     * "//" in the path and break parsing, so the segment has to be
     * non-empty even when it carries no information. The checksum is what
     * actually resolves such a name.
     */
    const val EMPTY_PLACEHOLDER = "unnamed"

    fun of(name: String): String {
        val slug = buildString {
            var pendingSeparator = false
            name.forEach { ch ->
                // Deliberately ASCII alphanumerics only. A name in another
                // script would slug to the placeholder rather than emit
                // bytes whose encoding varies by hop, and the checksum still
                // resolves it.
                val kept = when {
                    ch in 'a'..'z' -> ch
                    ch in 'A'..'Z' -> ch + ('a' - 'A')
                    ch in '0'..'9' -> ch
                    else -> null
                }
                if (kept == null) {
                    // Collapse any run of separators, and never lead with
                    // one: the separator is only emitted once something
                    // follows it.
                    if (isNotEmpty()) pendingSeparator = true
                } else {
                    if (pendingSeparator) append('-')
                    pendingSeparator = false
                    append(kept)
                }
            }
        }
        return slug.ifEmpty { EMPTY_PLACEHOLDER }
    }
}
