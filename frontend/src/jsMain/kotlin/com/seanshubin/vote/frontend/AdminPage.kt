package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.domain.FeatureFlag
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.*

/**
 * Owner-only system console. Houses the event-log pause/resume toggle and
 * the feature-flag map — both are runtime operator state that doesn't
 * belong in the user-facing Home page. Non-owners who land here (deep
 * link, role downgrade mid-session) are bounced to Home.
 *
 * State propagation: [isEventLogPaused] and [featureFlags] come from the
 * root pollers (rememberPauseState / rememberFeatureFlags). The owner's
 * actions write through optimistically — the polls reconcile within one
 * tick — so the buttons here feel instant.
 */
@Composable
fun AdminPage(
    apiClient: ApiClient,
    role: com.seanshubin.vote.domain.Role?,
    isEventLogPaused: Boolean,
    onEventLogPauseToggled: (Boolean) -> Unit,
    featureFlags: Map<FeatureFlag, Boolean>,
    onFeatureFlagToggled: (FeatureFlag, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Non-owners bounce to Home. The backend re-checks Role.OWNER on every
    // mutating call anyway; this is just UX so a stale token holder doesn't
    // see a useless page full of disabled buttons.
    LaunchedEffect(role) {
        if (role != com.seanshubin.vote.domain.Role.OWNER) onBack()
    }
    if (role != com.seanshubin.vote.domain.Role.OWNER) {
        Div({ classes("container") }) { P { Text("Redirecting…") } }
        return
    }

    val pauseToggleAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = if (isEventLogPaused) "Failed to resume" else "Failed to pause",
        onError = { errorMessage = it },
        action = {
            if (isEventLogPaused) {
                apiClient.resumeEventLog()
                onEventLogPauseToggled(false)
            } else {
                apiClient.pauseEventLog()
                onEventLogPauseToggled(true)
            }
        },
    )

    Div({ classes("container") }) {
        H1 { Text("System") }
        P {
            Text(
                "Owner-only runtime controls. These are operator state, not " +
                    "domain events — no audit trail is kept, only the current value."
            )
        }

        if (errorMessage != null) {
            Div({ classes("error") }) { Text(errorMessage!!) }
        }

        Div({ classes("admin-section") }) {
            H2 { Text("Event Log") }
            P {
                Text(
                    if (isEventLogPaused) "Paused — voting and editing are temporarily disabled across the app."
                    else "Active — voting and editing work normally."
                )
            }
            Button({
                if (pauseToggleAction.isLoading) attr("disabled", "")
                onClick {
                    val message = if (isEventLogPaused) {
                        "Resume the event log? Voting and editing will start working again."
                    } else {
                        "Pause the event log? Voting and editing will be temporarily disabled for everyone."
                    }
                    if (window.confirm(message)) pauseToggleAction.invoke()
                }
            }) {
                Text(
                    when {
                        pauseToggleAction.isLoading && isEventLogPaused -> "Resuming…"
                        pauseToggleAction.isLoading -> "Pausing…"
                        isEventLogPaused -> "Resume Event Log"
                        else -> "Pause Event Log"
                    }
                )
            }
        }

        Div({ classes("admin-section") }) {
            H2 { Text("Feature Flags") }
            P {
                Text(
                    "Toggle gated features on or off without a redeploy. Each flag's " +
                        "behavior is reversible — turning it off hides the surface from " +
                        "the UI while preserving any data already produced under it."
                )
            }

            // One card per flag, driven by the FeatureFlag enum so a new
            // flag added to the enum surfaces here automatically.
            FeatureFlag.entries.forEach { flag ->
                val enabled = featureFlags[flag] ?: flag.defaultEnabled
                FeatureFlagCard(
                    apiClient = apiClient,
                    flag = flag,
                    enabled = enabled,
                    onToggled = { newEnabled -> onFeatureFlagToggled(flag, newEnabled) },
                    onError = { errorMessage = it },
                )
            }
        }

        Button({ onClick { onBack() } }) { Text("Back to Home") }
    }
}

@Composable
private fun FeatureFlagCard(
    apiClient: ApiClient,
    flag: FeatureFlag,
    enabled: Boolean,
    onToggled: (Boolean) -> Unit,
    onError: (String) -> Unit,
) {
    val toggleAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to update ${flag.name}",
        onError = onError,
        action = {
            val next = !enabled
            apiClient.setFeatureEnabled(flag, next)
            onToggled(next)
        },
    )

    Div({ classes("admin-flag-card") }) {
        Div({ classes("admin-flag-header") }) {
            Span({ classes("admin-flag-name") }) { Text(flag.name) }
            Span({
                classes("admin-flag-state")
                if (enabled) classes("admin-flag-state-on") else classes("admin-flag-state-off")
            }) { Text(if (enabled) "ON" else "OFF") }
        }
        P({ classes("admin-flag-description") }) { Text(flag.description) }
        Button({
            if (toggleAction.isLoading) attr("disabled", "")
            onClick { toggleAction.invoke() }
        }) {
            Text(
                when {
                    toggleAction.isLoading && enabled -> "Turning off…"
                    toggleAction.isLoading -> "Turning on…"
                    enabled -> "Turn off"
                    else -> "Turn on"
                }
            )
        }
    }
}
