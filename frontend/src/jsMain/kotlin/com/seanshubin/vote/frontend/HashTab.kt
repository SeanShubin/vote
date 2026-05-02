package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import kotlinx.browser.window
import org.w3c.dom.events.Event

/**
 * Tab state mirrored to `window.location.hash`. The chosen tab survives a
 * refresh, deep-links cleanly, and the browser's back/forward buttons step
 * through tab changes (each click adds a history entry).
 *
 * Reads consult [validValues]; an unknown or empty hash falls back to
 * [default]. Use it like `var tab by rememberHashTab("setup", setOf(...))` —
 * assigning to the var updates state immediately and writes the hash, which
 * fires `hashchange`. The listener also catches external hash changes
 * (back/forward, manual edit) and pulls them back into state.
 *
 * Tab state lives in the URL fragment; the SPA's path-based [Router] only
 * reads pathname+search, so the two never interfere.
 */
@Composable
fun rememberHashTab(default: String, validValues: Set<String>): MutableState<String> {
    fun fromUrl(): String =
        window.location.hash.removePrefix("#").takeIf { it in validValues } ?: default

    val state = remember { mutableStateOf(fromUrl()) }

    DisposableEffect(Unit) {
        val listener: (Event) -> Unit = {
            val fresh = fromUrl()
            if (state.value != fresh) state.value = fresh
        }
        window.addEventListener("hashchange", listener)
        onDispose { window.removeEventListener("hashchange", listener) }
    }

    return remember(state) {
        object : MutableState<String> {
            override var value: String
                get() = state.value
                set(newValue) {
                    if (newValue !in validValues || newValue == state.value) return
                    state.value = newValue
                    if (window.location.hash.removePrefix("#") != newValue) {
                        window.location.hash = newValue
                    }
                }

            override fun component1(): String = value
            override fun component2(): (String) -> Unit = { value = it }
        }
    }
}
