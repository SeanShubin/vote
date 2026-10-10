package com.seanshubin.vote.tools.lib

/**
 * Which request paths the local static server hands to the backend instead of
 * answering from the SPA bundle, one entry per CloudFront behavior that
 * targets the API origin in production.
 *
 * Its own file rather than a member of StaticServer. The proxy filter needs
 * the predicate and the server needs to install the filter, so putting the
 * predicate on the server made the two depend on each other — a dependency
 * cycle, which :analyzeCodeStructure fails the build over. A shared
 * definition that depends on neither leaves one direction of dependency:
 * server to filter to here.
 *
 * /api is deliberately absent. Production routes it through CloudFront so the
 * browser sees a single origin and cookies travel as same-origin, but locally
 * the SPA is built with an absolute api.base.url and calls the backend
 * directly, so there is nothing to proxy and a hop here would invent a
 * difference from production rather than remove one.
 */
object ProxiedPaths {
    val PREFIXES = listOf("/review")

    /**
     * Matches a prefix only at a segment boundary, so a future SPA route
     * like /reviewers keeps being served by the SPA rather than being
     * swallowed by a bare string prefix match.
     */
    fun isProxied(path: String): Boolean = PREFIXES.any { prefix ->
        path == prefix || path.startsWith("$prefix/")
    }
}
