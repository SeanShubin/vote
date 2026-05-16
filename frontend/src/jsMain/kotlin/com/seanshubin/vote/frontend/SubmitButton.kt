package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.AttrsScope
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLButtonElement

/**
 * Handle for an async action: tracks whether it is currently in flight and
 * provides an idempotent [invoke] to start it. Calling [invoke] while
 * [isLoading] is `true` is a no-op.
 */
class AsyncAction(val isLoading: Boolean, val invoke: () -> Unit)

/**
 * Hook-style helper for any user-initiated suspend action that follows the
 * "spinner + log on failure + show error message" pattern. Pages that drive
 * the action from a `<form>` onSubmit (so Enter key submits the form) use
 * this directly; pages with a standalone button can use [SubmitButton].
 *
 * The action's exception is forwarded to [ApiClient.logErrorToServer], which
 * rethrows CancellationException so the launch coroutine cancels cleanly
 * when the composable disposes mid-action.
 */
@Composable
fun rememberAsyncAction(
    apiClient: ApiClient,
    fallbackErrorMessage: String = "Operation failed",
    onSuccess: () -> Unit = {},
    onError: (String) -> Unit = {},
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
    action: suspend () -> Unit,
): AsyncAction {
    var isLoading by remember { mutableStateOf(false) }
    val invoke: () -> Unit = {
        if (!isLoading) {
            isLoading = true
            coroutineScope.launch {
                try {
                    action()
                    onSuccess()
                } catch (e: SessionLostException) {
                    // Session ended mid-action — ApiClient.onSessionLost has
                    // already routed to /login. Don't log or surface an error
                    // to a page that's about to be unmounted.
                } catch (e: MaintenancePausedException) {
                    // Owner has paused the event log for a maintenance window.
                    // The global banner already tells the user — don't log a
                    // false-positive frontend error, don't double up with a
                    // per-page red box. The action simply doesn't happen;
                    // the voter can retry once the banner clears.
                } catch (e: Exception) {
                    apiClient.logErrorToServer(e)
                    onError(e.message ?: fallbackErrorMessage)
                } finally {
                    isLoading = false
                }
            }
        }
    }
    return AsyncAction(isLoading, invoke)
}

/**
 * Single-action button with built-in spinner and error handling. Use for
 * standalone action buttons (Submit Ballot, Save Candidates, Delete Election,
 * Delete Account). For form-style submission where Enter should also trigger
 * the action, use a `<Form>` with onSubmit + [rememberAsyncAction] instead.
 *
 * @param text label shown when idle
 * @param apiClient used by the underlying [rememberAsyncAction] for error logging
 * @param action the suspend body that performs the work
 * @param loadingText label shown while [action] is running (default: "$text…")
 * @param enabled extra gating beyond the loading state
 * @param fallbackErrorMessage shown if the thrown exception has no message
 * @param onSuccess called after [action] returns successfully
 * @param onError called with the error's message (or [fallbackErrorMessage])
 * @param attrs extra raw button attributes (rare — class names, etc.)
 */
@Composable
fun SubmitButton(
    text: String,
    apiClient: ApiClient,
    action: suspend () -> Unit,
    loadingText: String = "$text…",
    enabled: Boolean = true,
    fallbackErrorMessage: String = "Operation failed",
    onSuccess: () -> Unit = {},
    onError: (String) -> Unit = {},
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
    attrs: AttrsScope<HTMLButtonElement>.() -> Unit = {},
) {
    val asyncAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = fallbackErrorMessage,
        onSuccess = onSuccess,
        onError = onError,
        coroutineScope = coroutineScope,
        action = action,
    )
    Button({
        attrs()
        if (!enabled || asyncAction.isLoading) attr("disabled", "")
        onClick { asyncAction.invoke() }
    }) {
        Text(if (asyncAction.isLoading) loadingText else text)
    }
}
