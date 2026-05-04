package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient

/**
 * Tab-lifetime cache keyed by a stable string per endpoint. Lets list pages
 * (Elections, Users, Home activity) render instantly from the previous
 * navigation's data while the fresh response is fetched in the background —
 * a "stale-while-revalidate" pattern. The cache is process-scoped: it lives
 * for as long as the JS module is loaded, which means until the tab is
 * closed or [clear] is called (e.g. on logout, where the next user must not
 * see the previous user's data).
 *
 * Memory bound is the number of distinct cache keys, which is a small
 * constant (one per list endpoint). No per-key TTL — staleness is bounded
 * to one round-trip because every visit kicks off a refetch that overwrites
 * the entry. The cache is purely a paint-fast latch, never a source of
 * truth.
 */
object PageCache {
    private val entries = mutableMapOf<String, Any?>()

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): T? = entries[key] as T?

    fun put(key: String, value: Any?) {
        entries[key] = value
    }

    fun invalidate(key: String) {
        entries.remove(key)
    }

    /** Drop everything. Called on logout / account deletion to avoid leaking data across sessions. */
    fun clear() {
        entries.clear()
    }
}

/**
 * Variant of [rememberFetchState] that consults [PageCache] before issuing
 * the request. On a cache hit the composable mounts straight into
 * [FetchState.Success] (no Loading flash) and the fetcher still runs to
 * refresh the cached value — so the user sees the prior data immediately
 * and the fresh data swaps in transparently when it arrives.
 *
 * On a cache miss the behavior matches [rememberFetchState] exactly:
 * Loading → Success/Error.
 *
 * On error after a cache hit the prior cached value stays on screen — the
 * error is logged server-side via [ApiClient.logErrorToServer] but not
 * surfaced to the user, who is better off seeing slightly stale data than
 * an error message replacing useful content.
 */
@Composable
fun <T> rememberCachedFetchState(
    apiClient: ApiClient,
    cacheKey: String,
    key: Any? = Unit,
    fallbackErrorMessage: String = "Failed to load",
    fetcher: suspend () -> T,
): Fetched<T> {
    var generation by remember { mutableStateOf(0) }
    val state by produceState<FetchState<T>>(
        initialValue = PageCache.get<T>(cacheKey)?.let { FetchState.Success(it) } ?: FetchState.Loading,
        key1 = key,
        key2 = generation,
    ) {
        val cached = PageCache.get<T>(cacheKey)
        value = if (cached != null) FetchState.Success(cached) else FetchState.Loading
        value = try {
            val fresh = fetcher()
            PageCache.put(cacheKey, fresh)
            FetchState.Success(fresh)
        } catch (e: SessionLostException) {
            FetchState.Loading
        } catch (e: Exception) {
            apiClient.logErrorToServer(e)
            // Prefer keeping the stale value on screen over flashing an error
            // banner — the server log already captured the failure.
            cached?.let { FetchState.Success(it) }
                ?: FetchState.Error(e.message ?: fallbackErrorMessage)
        }
    }
    // reload() deliberately does NOT invalidate the cache entry — keeping the
    // prior value present means the producer body shows it immediately
    // (Success rather than Loading) while the fresh fetch is in flight, so
    // an explicit reload still feels instant. The fetcher's Success branch
    // overwrites the cache with the fresh value on completion.
    return Fetched(state, reload = { generation++ })
}
