package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import kotlinx.browser.document
import kotlinx.coroutines.delay

/**
 * Poll [ApiClient.isEventLogPaused] for the lifetime of the composition and
 * expose the latest value as a Compose [MutableState]. The owner toggles the
 * flag from the Home page; every other browser learns about the change on the
 * next poll and the maintenance banner appears or disappears accordingly.
 *
 * Returns the underlying [MutableState] (not a read-only [State]) so the owner
 * action can write the new value optimistically the instant it succeeds — no
 * one wants to click "Pause" and wait up to [intervalMillis] before the banner
 * appears as confirmation. The next poll reconciles, so an optimistic write
 * that races with reality self-corrects within one tick.
 *
 * Cadence mirrors [rememberVersionPolling] — one tiny request every
 * [intervalMillis], skipped while the tab is hidden so a backgrounded tab
 * doesn't generate traffic. The endpoint is unauthenticated, so a paused poll
 * never trips the token-refresh path.
 */
@Composable
fun rememberPauseState(
    apiClient: ApiClient,
    intervalMillis: Long = 10_000,
): MutableState<Boolean> {
    val paused = remember { mutableStateOf(false) }
    LaunchedEffect(intervalMillis) {
        while (true) {
            val tabHidden = document.asDynamic().hidden == true
            if (!tabHidden) {
                try {
                    paused.value = apiClient.isEventLogPaused()
                } catch (e: Exception) {
                    // One flaky poll must not stop every future poll. Log and
                    // keep the previous value displayed (so a transient network
                    // blip doesn't flip the banner off mid-maintenance).
                    apiClient.logErrorToServer(e)
                }
            }
            delay(intervalMillis)
        }
    }
    return paused
}
