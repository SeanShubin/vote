package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.contract.DiagnosticEvent
import com.seanshubin.vote.contract.DiagnosticKind
import org.jetbrains.compose.web.dom.*

/**
 * Full-page diagnostics view. Errors are listed first as expandable cards
 * (so the operator can spot trouble at a glance); the activity table fills
 * the remaining vertical space inside an `admin-container` flex shell, so
 * the table itself gets the headroom — same layout pattern as Raw Tables
 * and Debug Tables.
 *
 * Per-process buffer: the snapshot only reflects activity served by the
 * backend container that handled this read. A backend restart wipes it.
 *
 * Non-owners who land here (deep link, role downgrade mid-session) are
 * bounced out; the backend also re-checks Role.OWNER on every read.
 */
@Composable
fun DiagnosticsPage(
    apiClient: ApiClient,
    role: com.seanshubin.vote.domain.Role?,
    onBack: () -> Unit,
) {
    LaunchedEffect(role) {
        if (role != com.seanshubin.vote.domain.Role.OWNER) onBack()
    }
    if (role != com.seanshubin.vote.domain.Role.OWNER) {
        Div({ classes("container") }) { P { Text("Redirecting…") } }
        return
    }

    val diagnosticsFetch = rememberFetchState(
        apiClient = apiClient,
        key = "diagnostics",
        fallbackErrorMessage = "Failed to load diagnostics",
    ) {
        apiClient.diagnostics()
    }

    Div({ classes("admin-container") }) {
        H1 { Text("Diagnostics") }

        when (val state = diagnosticsFetch.state) {
            FetchState.Loading -> P { Text("Loading…") }
            is FetchState.Error -> Div({ classes("error") }) { Text(state.message) }
            is FetchState.Success -> {
                val snapshot = state.value
                val allEvents = snapshot.events
                val errors = allEvents.filter { it.isError }

                // Lambda routes each request to whatever container is warm,
                // and the buffer is per-container. Calling this out up front
                // so an operator who sees a tiny count doesn't conclude the
                // recorder is broken — they're just looking at one container.
                Div({ classes("diag-callout") }) {
                    P {
                        B { Text("Per-container view. ") }
                        Text(
                            "In prod each Lambda container has its own buffer. " +
                                "Refreshing this page may land on a different container " +
                                "and show different events — when the Container ID below " +
                                "changes between refreshes, that's why. Errors are also " +
                                "emailed (durable, cross-container)."
                        )
                    }
                }

                P({ classes("diag-meta") }) {
                    val dropped = if (snapshot.droppedSinceStart > 0) {
                        " • ${snapshot.droppedSinceStart} evicted since start"
                    } else ""
                    Text(
                        "Container: ${snapshot.containerId} • " +
                            "Buffer: ${allEvents.size} / ${snapshot.capacity} events$dropped" +
                            " • newest first • backend restart clears this buffer"
                    )
                }

                if (errors.isEmpty()) {
                    Div({ classes("diag-no-errors") }) {
                        Text("No recent errors.")
                    }
                } else {
                    H2 { Text("Recent errors (${errors.size})") }
                    errors.forEach { event ->
                        DiagnosticErrorCard(event)
                    }
                }

                H2 { Text("Recent activity (${allEvents.size})") }
                if (allEvents.isEmpty()) {
                    P { Text("No activity recorded yet.") }
                } else {
                    // admin-table-scroll grows to fill the flex parent so
                    // the table gets the vertical space, with sticky thead
                    // pinned while rows scroll past.
                    Div({ classes("admin-table-scroll") }) {
                        DiagnosticActivityTable(allEvents)
                    }
                }
            }
        }

        Div({ classes("button-row") }) {
            Button({
                onClick { diagnosticsFetch.reload() }
            }) { Text("Refresh") }
            Button({ onClick { onBack() } }) { Text("Back to Admin") }
        }
    }
}

@Composable
private fun DiagnosticErrorCard(event: DiagnosticEvent) {
    var stackOpen by remember(event.sequence) { mutableStateOf(false) }
    Div({ classes("diag-error-card") }) {
        Div({ classes("diag-error-header") }) {
            Span({ classes("diag-badge", "diag-badge-error") }) {
                Text(badgeLabel(event))
            }
            Span({ classes("diag-timestamp") }) { Text(event.timestamp) }
        }
        if (event.method != null && event.path != null) {
            P({ classes("diag-route") }) {
                Text("${event.method} ${event.path}")
            }
        }
        if (event.clientUrl != null) {
            P({ classes("diag-route") }) { Text("Page: ${event.clientUrl}") }
        }
        event.message?.let { msg ->
            P({ classes("diag-message") }) { Text(msg) }
        }
        val trace = event.stackTrace?.takeIf { it.isNotBlank() }
        if (trace != null) {
            Button({
                classes("diag-stack-toggle")
                onClick { stackOpen = !stackOpen }
            }) {
                Text(if (stackOpen) "Hide stack trace" else "Show stack trace")
            }
            if (stackOpen) {
                Pre({ classes("diag-stack") }) { Text(trace) }
            }
        }
    }
}

@Composable
private fun DiagnosticActivityTable(events: List<DiagnosticEvent>) {
    Table({ classes("data-table", "diag-activity-table") }) {
        Thead {
            Tr {
                Th { Text("Time") }
                Th { Text("Kind") }
                Th { Text("Summary") }
            }
        }
        Tbody {
            events.forEach { event ->
                Tr({ if (event.isError) classes("diag-row-error") }) {
                    Td({ classes("diag-cell-time") }) { Text(event.timestamp) }
                    Td { Text(kindLabel(event.kind)) }
                    Td { Text(summaryLine(event)) }
                }
            }
        }
    }
}

private fun badgeLabel(event: DiagnosticEvent): String = when (event.kind) {
    DiagnosticKind.CLIENT_ERROR -> "CLIENT ERROR"
    DiagnosticKind.SERVER_EXCEPTION -> "SERVER ${event.exceptionSource?.uppercase() ?: "EXCEPTION"}"
    DiagnosticKind.HTTP_RESPONSE -> "HTTP ${event.status ?: ""}"
}

private fun kindLabel(kind: DiagnosticKind): String = when (kind) {
    DiagnosticKind.HTTP_RESPONSE -> "http"
    DiagnosticKind.SERVER_EXCEPTION -> "server-error"
    DiagnosticKind.CLIENT_ERROR -> "client-error"
}

private fun summaryLine(event: DiagnosticEvent): String = when (event.kind) {
    DiagnosticKind.HTTP_RESPONSE -> buildString {
        append(event.method ?: "?")
        append(' ')
        append(event.path ?: "?")
        append(" -> ")
        append(event.status ?: "?")
        event.durationMs?.let { append(" [${it}ms") }
        event.dbCalls?.let { append(", db=$it]") }
            ?: if (event.durationMs != null) append("]") else Unit
    }
    DiagnosticKind.SERVER_EXCEPTION -> {
        val prefix = event.method?.let { m -> event.path?.let { p -> "$m $p — " } } ?: ""
        prefix + (event.message ?: "(no message)")
    }
    DiagnosticKind.CLIENT_ERROR -> {
        val where = event.clientUrl?.let { " @ $it" } ?: ""
        (event.message ?: "(no message)") + where
    }
}
