package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import kotlinx.browser.window
import org.w3c.dom.events.Event

/**
 * Logical pages a user can be on. Each variant maps to exactly one URL path
 * via [pageToPath] / [pathToPage]. Loading is intentionally NOT here — it's
 * a transient bootstrap state, not a bookmarkable URL. See [VoteApp].
 *
 * Adding a new page: add a sealed variant, then handle it in both
 * [pageToPath] and [pathToPage]. The unit test suite enforces round-trip.
 */
sealed class Page {
    object Login : Page()
    object Register : Page()
    object Home : Page()
    object CreateElection : Page()
    object Elections : Page()
    object RawTables : Page()
    object DebugTables : Page()
    object UserManagement : Page()
    object MyAccount : Page()
    object PasswordResetRequest : Page()
    /**
     * Target of the email reset link. Carries the reset token that came back
     * in the URL's `?token=` query string — the page presents new-password
     * input and submits both to the backend.
     */
    data class PasswordReset(val resetToken: String) : Page()
    data class ElectionDetail(val electionName: String) : Page()
    data class ElectionPreferences(val electionName: String) : Page()
    data class ElectionStrongestPaths(val electionName: String) : Page()
}

/**
 * Render a [Page] to its URL path. Inverse of [pathToPage].
 *
 * Election names can contain spaces and other characters that must be
 * percent-encoded — we delegate to the browser's encodeURIComponent so the
 * encoding matches what the user sees when they copy the URL.
 */
fun pageToPath(page: Page): String = when (page) {
    is Page.Login -> "/login"
    is Page.Register -> "/register"
    is Page.Home -> "/"
    is Page.Elections -> "/elections"
    is Page.CreateElection -> "/elections/new"
    is Page.ElectionDetail -> "/elections/${encodeUriComponent(page.electionName)}"
    is Page.ElectionPreferences -> "/elections/${encodeUriComponent(page.electionName)}/preferences"
    is Page.ElectionStrongestPaths -> "/elections/${encodeUriComponent(page.electionName)}/strongest-paths"
    is Page.RawTables -> "/admin/raw-tables"
    is Page.DebugTables -> "/admin/debug-tables"
    is Page.UserManagement -> "/admin/users"
    is Page.MyAccount -> "/me/account"
    is Page.PasswordResetRequest -> "/password-reset-request"
    // Token belongs in the path's query string — that's the standard place
    // for one-time link parameters and matches what email clients render.
    is Page.PasswordReset -> "/reset-password?token=${encodeUriComponent(page.resetToken)}"
}

/**
 * Parse a URL path to a [Page]. Inverse of [pageToPath].
 *
 * Unknown paths fall back to [Page.Home] — kinder than 404ing inside the SPA,
 * and the user can navigate from there. Trailing slashes are tolerated.
 *
 * `/elections/new` is reserved (CreateElection) before the catch-all
 * `/elections/{name}` so creating-a-new-election doesn't get misread as
 * "view election named 'new'".
 */
fun pathToPage(pathWithQuery: String): Page {
    // Split path from query so the path matchers don't have to know about ?token=...
    val queryStart = pathWithQuery.indexOf('?')
    val path = if (queryStart >= 0) pathWithQuery.substring(0, queryStart) else pathWithQuery
    val query = if (queryStart >= 0) pathWithQuery.substring(queryStart + 1) else ""
    val normalized = path.trimEnd('/').ifEmpty { "/" }
    return when {
        normalized == "/" -> Page.Home
        normalized == "/login" -> Page.Login
        normalized == "/register" -> Page.Register
        normalized == "/elections" -> Page.Elections
        normalized == "/elections/new" -> Page.CreateElection
        normalized.startsWith("/elections/") -> {
            val raw = normalized.removePrefix("/elections/")
            // Sub-routes (preferences, strongest-paths) are detail-style child
            // pages of an election. Election names are percent-encoded so any
            // literal "/" in the rest of the path is a route separator, not
            // part of the name.
            val parts = raw.split("/", limit = 2)
            when {
                parts.size == 2 && parts[1] == "preferences" ->
                    Page.ElectionPreferences(decodeUriComponent(parts[0]))
                parts.size == 2 && parts[1] == "strongest-paths" ->
                    Page.ElectionStrongestPaths(decodeUriComponent(parts[0]))
                else -> Page.ElectionDetail(decodeUriComponent(raw))
            }
        }
        normalized == "/admin/raw-tables" -> Page.RawTables
        normalized == "/admin/debug-tables" -> Page.DebugTables
        normalized == "/admin/users" -> Page.UserManagement
        normalized == "/me/account" -> Page.MyAccount
        normalized == "/password-reset-request" -> Page.PasswordResetRequest
        // Reset link from the email; missing/empty token shouldn't crash —
        // the reset page will surface a clear error to the user.
        normalized == "/reset-password" -> Page.PasswordReset(parseQueryParam(query, "token") ?: "")
        else -> Page.Home
    }
}

private fun parseQueryParam(query: String, name: String): String? {
    if (query.isEmpty()) return null
    return query.split("&")
        .map { it.split("=", limit = 2) }
        .firstOrNull { it.size == 2 && it[0] == name }
        ?.let { decodeUriComponent(it[1]) }
}

/**
 * Routing controller. Wraps `history.pushState` for in-app navigation and
 * listens for `popstate` so the browser's back/forward buttons drive
 * recomposition just like a click would.
 *
 * Use via [rememberRouter] inside a composition — that handles the
 * popstate listener lifecycle.
 */
class Router internal constructor(initialPath: String) {
    var currentPage: Page by mutableStateOf(pathToPage(initialPath))
        private set

    /**
     * Navigate to [page]. Pushes a new entry onto the history stack so
     * back/forward works. No-ops if the destination URL would equal the
     * current one (same page AND same [hash]), avoiding duplicate history
     * entries when the same nav is triggered twice.
     *
     * [hash] is the URL fragment to write (without leading `#`). The fragment
     * is used by [rememberHashTab] to pick the active tab on the destination
     * page, so passing e.g. `"tally"` to navigate back to ElectionDetail
     * lands the user on the Results tab they likely came from.
     */
    fun navigate(page: Page, hash: String = "") {
        val currentHash = window.location.hash.removePrefix("#")
        if (page == currentPage && hash == currentHash) return
        val path = pageToPath(page)
        val fullPath = if (hash.isNotEmpty()) "$path#$hash" else path
        window.history.pushState(null, "", fullPath)
        currentPage = page
    }

    /**
     * Like [navigate] but uses replaceState — the current history entry is
     * overwritten instead of a new one being pushed. Used for the auth
     * bootstrap redirect (e.g. failed refresh on a protected URL → /login)
     * so the user's back button doesn't take them back to a page they
     * weren't authorized to see.
     */
    fun replace(page: Page) {
        val path = pageToPath(page)
        window.history.replaceState(null, "", path)
        currentPage = page
    }

    /** Called by the popstate listener when the user hits Back/Forward. */
    internal fun handlePopState() {
        currentPage = pathToPage(window.location.pathname + window.location.search)
    }
}

/**
 * Compose entry point for routing. Parses the initial URL, installs a
 * popstate listener for the lifetime of the composition, and returns the
 * [Router].
 */
@Composable
fun rememberRouter(): Router {
    val router = remember {
        Router(initialPath = window.location.pathname + window.location.search)
    }
    DisposableEffect(router) {
        val listener: (Event) -> Unit = { router.handlePopState() }
        window.addEventListener("popstate", listener)
        onDispose { window.removeEventListener("popstate", listener) }
    }
    return router
}

/** Pages that don't require authentication. Anything else bounces to /login on auth fail. */
fun isPublicPage(page: Page): Boolean =
    page is Page.Login ||
        page is Page.Register ||
        page is Page.PasswordResetRequest ||
        page is Page.PasswordReset

private fun encodeUriComponent(s: String): String =
    js("encodeURIComponent")(s) as String

private fun decodeUriComponent(s: String): String =
    js("decodeURIComponent")(s) as String
