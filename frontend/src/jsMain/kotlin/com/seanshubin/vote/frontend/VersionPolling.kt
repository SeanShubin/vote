package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import kotlinx.browser.document
import kotlinx.coroutines.delay

/**
 * Background poll of [ApiClient.version] — the server's monotonic read-model
 * version. While this composable is in the composition it checks the version
 * every [intervalMillis] and invokes [onChanged] whenever the value moves,
 * which is the signal that some write landed and the page's real data is now
 * worth refetching.
 *
 * The cost when nothing has changed is one tiny request per interval; the
 * expensive endpoints (tally, ballot lists) are only refetched on an actual
 * version bump — that is the whole point of polling the cheap counter instead
 * of the heavy data.
 *
 * The first poll only seeds the baseline (no [onChanged] call) — it runs
 * roughly concurrently with the page's own initial data fetch, so the
 * baseline is accurate to within milliseconds of what the page already
 * displays. Polling is skipped while the tab is hidden; when the tab comes
 * back the next tick catches up (a version bump that happened while hidden
 * still differs from the baseline and fires [onChanged]).
 */
@Composable
fun rememberVersionPolling(
    apiClient: ApiClient,
    intervalMillis: Long = 10_000,
    onChanged: () -> Unit,
) {
    // The loop outlives individual recompositions, so capture the latest
    // onChanged rather than the instance from the composition that started it.
    val currentOnChanged by rememberUpdatedState(onChanged)
    LaunchedEffect(intervalMillis) {
        var lastSeen: Long? = null
        while (true) {
            // Page Visibility API. Accessed dynamically because document.hidden
            // isn't in this Kotlin/JS version's typed DOM bindings.
            val tabHidden = document.asDynamic().hidden == true
            if (!tabHidden) {
                try {
                    val current = apiClient.version()
                    if (lastSeen != null && current != lastSeen) {
                        currentOnChanged()
                    }
                    lastSeen = current
                } catch (e: Exception) {
                    // logErrorToServer rethrows CancellationException, so a
                    // disposed composable cancels the loop cleanly. Any other
                    // failure is logged and the loop continues — one flaky
                    // poll must not stop every future poll.
                    apiClient.logErrorToServer(e)
                }
            }
            delay(intervalMillis)
        }
    }
}
