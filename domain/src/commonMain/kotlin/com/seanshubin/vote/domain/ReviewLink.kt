package com.seanshubin.vote.domain

/**
 * The shareable address of one review: one voter's note on one candidate in
 * one election.
 *
 * ```
 * /review/favorite-rippaverse-book/zalen-what-kills-must-die/sean-a7f3k2
 * ```
 *
 * Readable slugs plus a checksum over the exact names. The slugs are for the
 * human reading the link; the checksum is what makes the address identify
 * exactly one (election, candidate, reviewer) triple, which is already unique
 * in storage — one note per triple.
 *
 * Nothing is stored to support this. The address is derived from the names,
 * which means it moves when a name does: renaming a candidate cascades to its
 * notes (the command models re-key them), so after a rename the old name
 * exists nowhere and the old link is correctly gone. A separately minted id
 * would outlive the names and make a link more durable than the keys beneath
 * it, which is not a promise this system makes anywhere else.
 *
 * Lives in :domain because both sides need it and must never disagree: the
 * backend resolves these addresses and the frontend hands them out. The
 * checksum is computed in common Kotlin so the JVM and JS results are
 * identical by construction rather than by two implementations agreeing.
 */
object ReviewLink {
    const val PATH_PREFIX = "/review"

    /** Width of the checksum. Fixed so an address is always the same shape. */
    private const val CHECKSUM_LENGTH = 6

    /** 36^6 — the number of distinct values [CHECKSUM_LENGTH] base-36 digits hold. */
    private const val CHECKSUM_MODULUS = 2_176_782_336L

    /**
     * The path for one review, rooted at [PATH_PREFIX]. Caller prepends the
     * origin; nothing here knows the host.
     */
    fun path(electionName: String, candidateName: String, reviewerName: String): String {
        val reviewerSegment = Slug.of(reviewerName) + "-" +
            checksum(electionName, candidateName, reviewerName)
        return "$PATH_PREFIX/${Slug.of(electionName)}/${Slug.of(candidateName)}/$reviewerSegment"
    }

    /**
     * Splits the reviewer segment into its slug and checksum halves. The
     * checksum is the final dash-delimited run, so a reviewer whose own slug
     * contains dashes ("bad-mr-frosty") still parses.
     *
     * Returns null when the segment carries no checksum at all, which is what
     * a hand-typed or truncated link looks like.
     */
    fun parseReviewerSegment(segment: String): ReviewerSegment? {
        val lastDash = segment.lastIndexOf('-')
        if (lastDash <= 0 || lastDash == segment.length - 1) return null
        return ReviewerSegment(
            slug = segment.substring(0, lastDash),
            checksum = segment.substring(lastDash + 1),
        )
    }

    /**
     * Whether [electionName]/[candidateName]/[reviewerName] is the triple the
     * address was built from. Resolution slug-matches candidates and authors
     * — which can match more than one, since slugs are lossy — and uses this
     * to pick the one the link actually meant.
     */
    fun matches(
        electionName: String,
        candidateName: String,
        reviewerName: String,
        checksum: String,
    ): Boolean = checksum(electionName, candidateName, reviewerName) == checksum.lowercase()

    /**
     * Checksum over the three exact names, case-folded because names are
     * unique case-insensitively ("Sean" and "sean" are one user), so a link
     * must not depend on which casing the sharer happened to have on screen.
     *
     * FNV-1a over UTF-16 code units: short, has no dependencies, and gives
     * the same answer on every Kotlin target. This is a disambiguator, not a
     * security boundary — these pages are world-readable by design, so there
     * is nothing for an unguessable value to protect.
     */
    fun checksum(electionName: String, candidateName: String, reviewerName: String): String {
        val key = listOf(electionName, candidateName, reviewerName)
            .joinToString("|") { it.trim().lowercase() }
        val unsigned = fnv1a(key).toLong() and 0xFFFFFFFFL
        return (unsigned % CHECKSUM_MODULUS).toString(36).padStart(CHECKSUM_LENGTH, '0')
    }

    private fun fnv1a(text: String): Int {
        var hash = FNV_OFFSET_BASIS
        text.forEach { ch ->
            hash = hash xor ch.code
            hash *= FNV_PRIME
        }
        return hash
    }

    // 0x811C9DC5 and 0x01000193, as Int so the multiply wraps identically on
    // every target rather than depending on a wider accumulator.
    private const val FNV_OFFSET_BASIS = -2128831035
    private const val FNV_PRIME = 16777619

    data class ReviewerSegment(val slug: String, val checksum: String)
}
