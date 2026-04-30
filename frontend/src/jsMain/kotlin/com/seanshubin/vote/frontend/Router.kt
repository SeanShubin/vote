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
    data class ElectionDetail(val electionName: String) : Page()
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
    is Page.RawTables -> "/admin/raw-tables"
    is Page.DebugTables -> "/admin/debug-tables"
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
fun pathToPage(path: String): Page {
    val normalized = path.trimEnd('/').ifEmpty { "/" }
    return when {
        normalized == "/" -> Page.Home
        normalized == "/login" -> Page.Login
        normalized == "/register" -> Page.Register
        normalized == "/elections" -> Page.Elections
        normalized == "/elections/new" -> Page.CreateElection
        normalized.startsWith("/elections/") -> {
            val raw = normalized.removePrefix("/elections/")
            Page.ElectionDetail(decodeUriComponent(raw))
        }
        normalized == "/admin/raw-tables" -> Page.RawTables
        normalized == "/admin/debug-tables" -> Page.DebugTables
        else -> Page.Home
    }
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
     * back/forward works. No-ops if already on [page] to avoid duplicate
     * history entries when the same nav is triggered twice.
     */
    fun navigate(page: Page) {
        if (page == currentPage) return
        val path = pageToPath(page)
        window.history.pushState(null, "", path)
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
        currentPage = pathToPage(window.location.pathname)
    }
}

/**
 * Compose entry point for routing. Parses the initial URL, installs a
 * popstate listener for the lifetime of the composition, and returns the
 * [Router].
 */
@Composable
fun rememberRouter(): Router {
    val router = remember { Router(initialPath = window.location.pathname) }
    DisposableEffect(router) {
        val listener: (Event) -> Unit = { router.handlePopState() }
        window.addEventListener("popstate", listener)
        onDispose { window.removeEventListener("popstate", listener) }
    }
    return router
}

/** Pages that don't require authentication. Anything else bounces to /login on auth fail. */
fun isPublicPage(page: Page): Boolean = page is Page.Login || page is Page.Register

private fun encodeUriComponent(s: String): String =
    js("encodeURIComponent")(s) as String

private fun decodeUriComponent(s: String): String =
    js("decodeURIComponent")(s) as String
