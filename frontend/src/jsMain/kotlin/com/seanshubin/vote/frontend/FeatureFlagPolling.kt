package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.domain.FeatureFlag
import kotlinx.browser.document
import kotlinx.coroutines.delay

/**
 * Poll [ApiClient.listFeatureFlags] for the lifetime of the composition and
 * expose the latest values as a Compose [MutableState]. Owner toggles a flag
 * from the admin page; every other browser learns about the change on the
 * next poll and any gated UI surfaces flip accordingly.
 *
 * Same shape as [rememberPauseState]: returns the [MutableState] (not a
 * read-only [State]) so the owner action can write the new value
 * optimistically without waiting up to [intervalMillis] for the next poll to
 * confirm. Defaults fill the initial state so callers never see a partial map.
 *
 * Skipped while the tab is hidden so a backgrounded tab generates no traffic.
 * Endpoint is unauthenticated, so a paused poll never trips token refresh.
 */
@Composable
fun rememberFeatureFlags(
    apiClient: ApiClient,
    intervalMillis: Long = 10_000,
): MutableState<Map<FeatureFlag, Boolean>> {
    val flags = remember {
        mutableStateOf(FeatureFlag.entries.associateWith { it.defaultEnabled })
    }
    LaunchedEffect(intervalMillis) {
        while (true) {
            val tabHidden = document.asDynamic().hidden == true
            if (!tabHidden) {
                try {
                    flags.value = apiClient.listFeatureFlags()
                } catch (e: Exception) {
                    apiClient.logErrorToServer(e)
                }
            }
            delay(intervalMillis)
        }
    }
    return flags
}
