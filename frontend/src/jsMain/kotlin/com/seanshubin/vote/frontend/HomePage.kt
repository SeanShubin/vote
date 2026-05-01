package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.domain.Role
import com.seanshubin.vote.domain.UserActivity
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.dom.*

@Composable
fun HomePage(
    apiClient: ApiClient,
    userName: String,
    role: Role?,
    onNavigateToCreateElection: () -> Unit,
    onNavigateToElections: () -> Unit,
    onNavigateToRawTables: () -> Unit,
    onNavigateToDebugTables: () -> Unit,
    onNavigateToUserManagement: () -> Unit,
    onLogout: () -> Unit,
    onAccountDeleted: () -> Unit,
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
) {
    var activity by remember { mutableStateOf<UserActivity?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    suspend fun reloadActivity() {
        try {
            activity = apiClient.getUserActivity()
        } catch (e: Exception) {
            apiClient.logErrorToServer(e)
        }
    }

    LaunchedEffect(userName) {
        reloadActivity()
    }

    Div({ classes("container") }) {
        H1 { Text("Vote - Home") }

        P { Text("Welcome, $userName!") }

        // Indicator: role + footprint. The role here comes from the activity
        // response, which re-reads the projection — so it stays accurate even
        // if the access token's role claim is stale (e.g. just after an
        // ownership transfer).
        activity?.let { a ->
            P {
                Text(
                    "Role: ${a.role.name} — ${a.role.description} • " +
                        "Owns ${a.electionsOwnedCount} election${if (a.electionsOwnedCount == 1) "" else "s"} • " +
                        "Cast ${a.ballotsCastCount} ballot${if (a.ballotsCastCount == 1) "" else "s"}"
                )
            }
        } ?: role?.let {
            // Fallback while activity is loading: token-claim role only.
            P { Text("Role: ${it.name} — ${it.description}") }
        }

        if (errorMessage != null) {
            Div({ classes("error") }) {
                Text(errorMessage!!)
            }
        }

        Div({ classes("menu") }) {
            Button({
                onClick { onNavigateToCreateElection() }
            }) {
                Text("Create Election")
            }

            Button({
                onClick { onNavigateToElections() }
            }) {
                Text("View Elections")
            }

            // ADMIN+ get user management. UX shortcut — the backend re-checks
            // MANAGE_USERS on every request.
            if (role != null && role >= Role.ADMIN) {
                Button({
                    onClick { onNavigateToUserManagement() }
                }) {
                    Text("Manage Users")
                }
            }

            // AUDITOR+ get the admin data browser. The role gate here is a UX
            // shortcut — the backend re-checks VIEW_SECRETS on every request.
            if (role != null && role >= Role.AUDITOR) {
                Button({
                    onClick { onNavigateToRawTables() }
                }) {
                    Text("Raw Tables")
                }

                Button({
                    onClick { onNavigateToDebugTables() }
                }) {
                    Text("Debug Tables")
                }
            }

            Button({
                onClick { onLogout() }
            }) {
                Text("Logout")
            }

            // Destructive — confirm with the cascade impact spelled out.
            // Before fetching activity we fall back to a generic warning.
            // After it's loaded we show "you'll be deleting N elections + M
            // ballots" so the user sees what cascade-deletes with them.
            Button({
                onClick {
                    val a = activity
                    val message = if (a == null) {
                        "Delete your account? This cannot be undone."
                    } else {
                        val parts = buildList {
                            if (a.electionsOwnedCount > 0) {
                                add("${a.electionsOwnedCount} election${if (a.electionsOwnedCount == 1) " you own" else "s you own"}")
                            }
                            if (a.ballotsCastCount > 0) {
                                add("${a.ballotsCastCount} ballot${if (a.ballotsCastCount == 1) " you've cast" else "s you've cast"}")
                            }
                        }
                        if (parts.isEmpty()) {
                            "Delete your account? This cannot be undone."
                        } else {
                            "Delete your account? This will also delete " +
                                parts.joinToString(" and ") +
                                ". This cannot be undone."
                        }
                    }
                    val confirmed = window.confirm(message)
                    if (!confirmed) return@onClick
                    coroutineScope.launch {
                        try {
                            apiClient.removeUser(userName)
                            onAccountDeleted()
                        } catch (e: Exception) {
                            apiClient.logErrorToServer(e)
                            errorMessage = e.message ?: "Failed to delete account"
                        }
                    }
                }
            }) {
                Text("Delete Account")
            }
        }
    }
}
