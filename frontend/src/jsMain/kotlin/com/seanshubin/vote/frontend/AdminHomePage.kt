package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.domain.Role
import org.jetbrains.compose.web.dom.*

/**
 * Admin landing page — a fan-out menu of role-gated sub-pages. Keeps the
 * user-facing Home page uncluttered: instead of every admin tool getting
 * its own button on Home, there's a single "Admin" button there, and the
 * actual choices live here.
 *
 * Role gating is the same as the buttons used to be on Home. The backend
 * re-checks the relevant permission on every request, so a stale token
 * holder seeing extra buttons here would just hit 401s — these are UX
 * shortcuts, not the security boundary.
 *
 * Anyone reaching this page is at least ADMIN (the floor for any admin
 * tool — ADMIN gets Manage Users; AUDITOR adds the data browsers; OWNER
 * adds System and Diagnostics). USER and below get bounced back to Home.
 */
@Composable
fun AdminHomePage(
    apiClient: ApiClient,
    role: Role?,
    onNavigateToSystem: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
    onNavigateToUserManagement: () -> Unit,
    onNavigateToRawTables: () -> Unit,
    onNavigateToDebugTables: () -> Unit,
    onNavigateToQuery: () -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(role) {
        if (role == null || role < Role.ADMIN) onBack()
    }
    if (role == null || role < Role.ADMIN) {
        Div({ classes("container") }) { P { Text("Redirecting…") } }
        return
    }

    // Deployment-static label ("PartiQL"/"SQL"/""); empty means the active
    // backend has no text-query surface, so the Query button is omitted.
    val queryDialectFetch = rememberCachedFetchState(
        apiClient = apiClient,
        cacheKey = "queryDialect",
        fallbackErrorMessage = "Failed to load query dialect",
    ) {
        apiClient.queryDialect()
    }
    val queryDialect = (queryDialectFetch.state as? FetchState.Success)?.value.orEmpty()

    Div({ classes("container") }) {
        H1 { Text("Admin") }
        P {
            Text(
                "Tools available to your role. The backend re-checks " +
                    "permission on every request — these buttons are just " +
                    "the entry points."
            )
        }

        Div({ classes("menu") }) {
            // OWNER-only: runtime operator state and runtime diagnostics.
            if (role == Role.OWNER) {
                Button({ onClick { onNavigateToSystem() } }) {
                    Text("System")
                }
                Button({ onClick { onNavigateToDiagnostics() } }) {
                    Text("Diagnostics")
                }
            }

            // ADMIN+: user role management.
            if (role >= Role.ADMIN) {
                Button({ onClick { onNavigateToUserManagement() } }) {
                    Text("Manage Users")
                }
            }

            // AUDITOR+: data browsers.
            if (role >= Role.AUDITOR) {
                Button({ onClick { onNavigateToRawTables() } }) {
                    Text("Raw Tables")
                }
                Button({ onClick { onNavigateToDebugTables() } }) {
                    Text("Debug Tables")
                }
                if (queryDialect.isNotEmpty()) {
                    Button({ onClick { onNavigateToQuery() } }) {
                        Text("Query ($queryDialect)")
                    }
                }
            }

            Button({ onClick { onBack() } }) { Text("Back to Home") }
        }
    }
}
