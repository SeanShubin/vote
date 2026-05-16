package com.seanshubin.vote.frontend

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Sticky banner shown at the top of every page when the owner has paused the
 * event log. Phrased as "intentional and temporary" so a visiting voter reads
 * "maintenance window" rather than "the site is broken." Calm blue palette
 * (not warning yellow/red) for the same reason — the system isn't in trouble,
 * it's just busy.
 */
@Composable
fun MaintenanceBanner() {
    Div({ classes("maintenance-banner") }) {
        Span({ classes("maintenance-banner-title") }) {
            Text("Scheduled maintenance in progress.")
        }
        Text(
            " Voting and editing are temporarily paused while we ship an" +
                " update. Reads still work; new writes will resume in a few" +
                " minutes."
        )
    }
}
