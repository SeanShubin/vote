package com.seanshubin.vote.frontend

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the pure path/Page mapping functions. The Router class itself
 * (history.pushState, popstate listener) is exercised by manual testing —
 * a stable jsdom-style harness for window.history is more setup than the
 * mapping functions warrant for V1.
 */
class RouterTest {

    @Test
    fun `pageToPath covers every routable variant`() {
        assertEquals("/", pageToPath(Page.Home))
        assertEquals("/login", pageToPath(Page.Login))
        assertEquals("/register", pageToPath(Page.Register))
        assertEquals("/elections", pageToPath(Page.Elections))
        assertEquals("/elections/new", pageToPath(Page.CreateElection))
        assertEquals("/elections/Lang", pageToPath(Page.ElectionDetail("Lang")))
        assertEquals("/elections/Lang/preferences", pageToPath(Page.ElectionPreferences("Lang")))
        assertEquals("/elections/Lang/strongest-paths", pageToPath(Page.ElectionStrongestPaths("Lang")))
        assertEquals("/admin/raw-tables", pageToPath(Page.RawTables))
        assertEquals("/admin/debug-tables", pageToPath(Page.DebugTables))
        assertEquals("/admin/users", pageToPath(Page.UserManagement))
    }

    @Test
    fun `pathToPage covers every routable variant`() {
        assertEquals(Page.Home, pathToPage("/"))
        assertEquals(Page.Login, pathToPage("/login"))
        assertEquals(Page.Register, pathToPage("/register"))
        assertEquals(Page.Elections, pathToPage("/elections"))
        assertEquals(Page.CreateElection, pathToPage("/elections/new"))
        assertEquals(Page.ElectionDetail("Lang"), pathToPage("/elections/Lang"))
        assertEquals(Page.ElectionPreferences("Lang"), pathToPage("/elections/Lang/preferences"))
        assertEquals(Page.ElectionStrongestPaths("Lang"), pathToPage("/elections/Lang/strongest-paths"))
        assertEquals(Page.RawTables, pathToPage("/admin/raw-tables"))
        assertEquals(Page.DebugTables, pathToPage("/admin/debug-tables"))
        assertEquals(Page.UserManagement, pathToPage("/admin/users"))
    }

    @Test
    fun `election sub-routes round-trip with percent-encoded names`() {
        val name = "Best Programming Language"
        val prefs = pageToPath(Page.ElectionPreferences(name))
        assertEquals("/elections/Best%20Programming%20Language/preferences", prefs)
        assertEquals(Page.ElectionPreferences(name), pathToPage(prefs))

        val paths = pageToPath(Page.ElectionStrongestPaths(name))
        assertEquals("/elections/Best%20Programming%20Language/strongest-paths", paths)
        assertEquals(Page.ElectionStrongestPaths(name), pathToPage(paths))
    }

    @Test
    fun `pathToPage trims trailing slashes`() {
        assertEquals(Page.Elections, pathToPage("/elections/"))
        assertEquals(Page.Login, pathToPage("/login/"))
    }

    @Test
    fun `pathToPage returns Home for unknown paths`() {
        assertEquals(Page.Home, pathToPage("/totally-bogus"))
        assertEquals(Page.Home, pathToPage("/admin/something-else"))
    }

    @Test
    fun `election names with spaces and other special chars round-trip via percent-encoding`() {
        val name = "Best Programming Language"
        val path = pageToPath(Page.ElectionDetail(name))
        assertEquals("/elections/Best%20Programming%20Language", path)
        // Round-trip — the path we generate must parse back to the same name.
        assertEquals(Page.ElectionDetail(name), pathToPage(path))
    }

    @Test
    fun `election name 'new' is reserved for CreateElection — cannot be created via URL`() {
        // /elections/new is the create page, not "view election named 'new'".
        // This is by design: putting CreateElection's match before the catch-all
        // means a literal election named "new" is unreachable via URL. We accept
        // this — election names are user-supplied and can be picked elsewhere.
        assertEquals(Page.CreateElection, pathToPage("/elections/new"))
    }

    @Test
    fun `isPublicPage matches Login and Register only`() {
        assertEquals(true, isPublicPage(Page.Login))
        assertEquals(true, isPublicPage(Page.Register))
        assertEquals(false, isPublicPage(Page.Home))
        assertEquals(false, isPublicPage(Page.Elections))
        assertEquals(false, isPublicPage(Page.ElectionDetail("Lang")))
        assertEquals(false, isPublicPage(Page.ElectionPreferences("Lang")))
        assertEquals(false, isPublicPage(Page.ElectionStrongestPaths("Lang")))
        assertEquals(false, isPublicPage(Page.CreateElection))
        assertEquals(false, isPublicPage(Page.RawTables))
        assertEquals(false, isPublicPage(Page.DebugTables))
        assertEquals(false, isPublicPage(Page.UserManagement))
    }
}
