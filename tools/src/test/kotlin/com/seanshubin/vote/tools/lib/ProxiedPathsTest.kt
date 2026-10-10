package com.seanshubin.vote.tools.lib

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which paths the dev server hands to the backend instead of the SPA bundle.
 *
 * The predicate is the whole decision, and getting it wrong is quiet: too
 * narrow and a shared review is answered with index.html, which is the
 * original bug; too wide and ordinary SPA routes stop working locally while
 * continuing to work in production.
 */
class ProxiedPathsTest {

    @Test
    fun `shared review paths go to the backend`() {
        assertTrue(ProxiedPaths.isProxied("/review/favorite-rippaverse-book/zalen-what-kills-must-die/sean-ayaw2g"))
        assertTrue(ProxiedPaths.isProxied("/review/e/c/r-abc123"))
    }

    @Test
    fun `the bare prefix goes to the backend too`() {
        // So the backend answers its own 404 rather than the SPA fallback
        // quietly turning a dead link into the home page.
        assertTrue(ProxiedPaths.isProxied("/review"))
        assertTrue(ProxiedPaths.isProxied("/review/favorite-rippaverse-book"))
    }

    @Test
    fun `spa routes stay with the spa`() {
        assertFalse(ProxiedPaths.isProxied("/"))
        assertFalse(ProxiedPaths.isProxied("/elections"))
        assertFalse(ProxiedPaths.isProxied("/elections/Favorite%20Rippaverse%20Book"))
        assertFalse(ProxiedPaths.isProxied("/index.html"))
    }

    @Test
    fun `a path that merely starts with the same letters is not proxied`() {
        // Prefix matching has to respect the segment boundary, or a future
        // SPA route like /reviewers would silently be sent to the backend.
        assertFalse(ProxiedPaths.isProxied("/reviews"))
        assertFalse(ProxiedPaths.isProxied("/reviewers"))
        assertFalse(ProxiedPaths.isProxied("/reviewing/thing"))
    }

    @Test
    fun `api is not proxied locally`() {
        // In production CloudFront proxies /api so the browser sees a single
        // origin; locally the SPA is built with an absolute api.base.url and
        // calls the backend directly, so proxying it here would add a hop
        // that does not exist in either environment's design.
        assertFalse(ProxiedPaths.isProxied("/api/health"))
        assertFalse(ProxiedPaths.isProxied("/api"))
    }

    @Test
    fun `every proxied prefix is rooted and carries no trailing slash`() {
        // The predicate appends "/" itself, so a prefix stored with one
        // would never match a bare prefix.
        ProxiedPaths.PREFIXES.forEach { prefix ->
            assertTrue(prefix.startsWith("/"), "not rooted: $prefix")
            assertFalse(prefix.endsWith("/"), "trailing slash: $prefix")
        }
    }
}
