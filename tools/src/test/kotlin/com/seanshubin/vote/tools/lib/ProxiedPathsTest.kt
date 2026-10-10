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
class StaticServerProxyTest {

    @Test
    fun `shared review paths go to the backend`() {
        assertTrue(StaticServer.isProxied("/review/favorite-rippaverse-book/zalen-what-kills-must-die/sean-ayaw2g"))
        assertTrue(StaticServer.isProxied("/review/e/c/r-abc123"))
    }

    @Test
    fun `the bare prefix goes to the backend too`() {
        // So the backend answers its own 404 rather than the SPA fallback
        // quietly turning a dead link into the home page.
        assertTrue(StaticServer.isProxied("/review"))
        assertTrue(StaticServer.isProxied("/review/favorite-rippaverse-book"))
    }

    @Test
    fun `spa routes stay with the spa`() {
        assertFalse(StaticServer.isProxied("/"))
        assertFalse(StaticServer.isProxied("/elections"))
        assertFalse(StaticServer.isProxied("/elections/Favorite%20Rippaverse%20Book"))
        assertFalse(StaticServer.isProxied("/index.html"))
    }

    @Test
    fun `a path that merely starts with the same letters is not proxied`() {
        // Prefix matching has to respect the segment boundary, or a future
        // SPA route like /reviewers would silently be sent to the backend.
        assertFalse(StaticServer.isProxied("/reviews"))
        assertFalse(StaticServer.isProxied("/reviewers"))
        assertFalse(StaticServer.isProxied("/reviewing/thing"))
    }

    @Test
    fun `api is not proxied locally`() {
        // In production CloudFront proxies /api so the browser sees a single
        // origin; locally the SPA is built with an absolute api.base.url and
        // calls the backend directly, so proxying it here would add a hop
        // that does not exist in either environment's design.
        assertFalse(StaticServer.isProxied("/api/health"))
        assertFalse(StaticServer.isProxied("/api"))
    }

    @Test
    fun `every proxied prefix is rooted and carries no trailing slash`() {
        // The predicate appends "/" itself, so a prefix stored with one
        // would never match a bare prefix.
        StaticServer.PROXIED_PREFIXES.forEach { prefix ->
            assertTrue(prefix.startsWith("/"), "not rooted: $prefix")
            assertFalse(prefix.endsWith("/"), "trailing slash: $prefix")
        }
    }
}
