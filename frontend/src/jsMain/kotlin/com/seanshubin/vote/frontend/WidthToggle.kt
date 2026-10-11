package com.seanshubin.vote.frontend

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.seanshubin.vote.domain.WidthPreference
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Text

/**
 * One button, bottom-right, that switches the app between a fixed-width
 * column and one that fills the window.
 *
 * Rendered at the composition root beside the build stamp rather than per
 * page: the preference applies to all fourteen `.container` pages, and a
 * sibling of `VoteApp` also survives the early returns inside it, so the
 * control is present on the login page and the awaiting-approval stub too.
 *
 * The label names where the button takes you, not where you are. A single
 * corner button saying "Wide" could be read as a status, so the arrows carry
 * the verb — outward to expand, inward to contract — and the title spells it
 * out for anyone who hovers.
 *
 * All this does is set a class on the root element. The widths themselves
 * live in CSS, which is the only thing that should know that admin pages
 * were already full-width and are therefore unaffected.
 */
@Composable
fun WidthToggle() {
    // The class is applied here, during composition, rather than only from
    // the effect below. Effects run after the composition that scheduled
    // them, which would leave a wide reader's first painted frame narrow.
    var wide by remember {
        val initial = WidthPreference.isWide(readStoredWidth())
        applyWidthClass(initial)
        mutableStateOf(initial)
    }
    LaunchedEffect(wide) {
        applyWidthClass(wide)
        // Guarded: localStorage throws, rather than no-opping, when site data
        // is blocked. A reader who cannot persist the choice should still get
        // to use it for the current page.
        runCatching {
            kotlinx.browser.window.localStorage.setItem(
                WidthPreference.STORAGE_KEY,
                WidthPreference.storedValue(wide),
            )
        }
    }
    Button({
        classes("width-toggle")
        attr("type", "button")
        val description = if (wide) "Switch to fixed width" else "Switch to full-window width"
        attr("title", description)
        attr("aria-label", description)
        onClick { wide = !wide }
    }) {
        Text(if (wide) "⇥⇤ Fixed" else "⇤⇥ Wide")
    }
}

private fun readStoredWidth(): String? =
    runCatching { kotlinx.browser.window.localStorage.getItem(WidthPreference.STORAGE_KEY) }.getOrNull()

private fun applyWidthClass(wide: Boolean) {
    val classList = kotlinx.browser.document.documentElement?.classList ?: return
    if (wide) classList.add(WidthPreference.WIDE_CLASS) else classList.remove(WidthPreference.WIDE_CLASS)
}
