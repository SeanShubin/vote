package com.seanshubin.vote.domain

/**
 * The reader's choice between a fixed-width column and one that fills the
 * window. Visual only, and per browser: nothing about it reaches the server.
 *
 * That is a deliberate limit rather than a shortcut. The store is event
 * sourced, so a preference held server-side would mean a new event type, a
 * schema change, and the pause/rewrite/deploy/unpause ritual every time it
 * changed shape — permanent cost for something no other user and no replay
 * will ever care about.
 *
 * The constants live here, in common code, because two surfaces apply them:
 * the Kotlin/JS app through [com.seanshubin.vote.frontend.WidthToggle], and
 * the server-rendered shared review page, which emits them into an inline
 * script from the JVM. Those are different languages compiled from different
 * modules; a key spelled differently in one would not fail to compile, it
 * would just quietly stop remembering. Same reasoning as
 * [CandidateNote.MAX_TEXT_LENGTH].
 *
 * Both surfaces are served from one origin — the app from CloudFront, the
 * review page from the API behind the same host — so they share one
 * localStorage. Setting the preference anywhere sets it everywhere, which is
 * why it must be one key and not two.
 */
object WidthPreference {
    /** localStorage key. Namespaced because the origin is shared with the app. */
    const val STORAGE_KEY = "vote.width"

    const val FIXED = "fixed"
    const val WIDE = "wide"

    /**
     * Applied to the *root* element, not the body.
     *
     * The review page sets this from a script in `<head>`, where
     * `document.body` does not exist yet. Using `documentElement` is what
     * makes the width correct in the first paint instead of flashing narrow
     * and then widening on every load. The app follows suit so the CSS
     * selector is identical in both stylesheets.
     */
    const val WIDE_CLASS = "width-wide"

    /**
     * Also applied to the root element, by the same script. The shared review
     * page renders without JavaScript on purpose, so its toggle is hidden
     * unless this class says a script ran — a reader with JS off gets the
     * default width and no button that cannot work.
     */
    const val HAS_JS_CLASS = "has-js"

    /**
     * Anything other than [WIDE] means fixed, so a missing key, a stale value
     * from an older build, or a blocked localStorage all land on the current
     * behavior rather than on a surprise.
     */
    fun isWide(stored: String?): Boolean = stored == WIDE

    fun storedValue(wide: Boolean): String = if (wide) WIDE else FIXED
}
