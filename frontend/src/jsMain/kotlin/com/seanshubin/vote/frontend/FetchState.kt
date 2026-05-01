package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient

/**
 * State of an async fetch tied to a composable's lifetime. Three branches:
 * [Loading] before the first response, [Success] with the value once
 * resolved, [Error] with a human-readable message on failure.
 *
 * Pages render with a single `when` over a `FetchState<T>` instead of
 * juggling separate `data: T?`, `errorMessage: String?`, and
 * `isLoading: Boolean` state fields.
 */
sealed class FetchState<out T> {
    object Loading : FetchState<Nothing>()
    data class Success<T>(val value: T) : FetchState<T>()
    data class Error(val message: String) : FetchState<Nothing>()
}

/**
 * Result of [rememberFetchState] — pairs the current [state] with a
 * [reload] action that re-runs the fetcher (e.g., after a successful
 * submit). Calling [reload] resets [state] to [FetchState.Loading] and
 * re-invokes the fetcher.
 */
class Fetched<T>(val state: FetchState<T>, val reload: () -> Unit)

/**
 * Run [fetcher] when this composable enters the composition (or [key]
 * changes), and expose the result as [FetchState]. On failure, the
 * exception is forwarded to [ApiClient.logErrorToServer] for server-side
 * observability — and CancellationException is rethrown by that method,
 * so navigating away mid-fetch produces no spurious error state.
 *
 * The returned [Fetched.reload] lets the caller force a refetch (typical
 * use: a SubmitButton's onSuccess callback triggers a reload to refresh
 * the page after a write).
 */
@Composable
fun <T> rememberFetchState(
    apiClient: ApiClient,
    key: Any? = Unit,
    fallbackErrorMessage: String = "Failed to load",
    fetcher: suspend () -> T,
): Fetched<T> {
    var generation by remember { mutableStateOf(0) }
    val state by produceState<FetchState<T>>(
        initialValue = FetchState.Loading,
        key1 = key,
        key2 = generation,
    ) {
        value = FetchState.Loading
        value = try {
            FetchState.Success(fetcher())
        } catch (e: Exception) {
            apiClient.logErrorToServer(e)
            FetchState.Error(e.message ?: fallbackErrorMessage)
        }
    }
    return Fetched(state, reload = { generation++ })
}
