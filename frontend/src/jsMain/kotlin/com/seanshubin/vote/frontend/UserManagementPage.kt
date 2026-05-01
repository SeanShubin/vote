package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.contract.AuthResponse
import com.seanshubin.vote.domain.Role
import com.seanshubin.vote.domain.UserNameRole
import org.jetbrains.compose.web.attributes.selected
import org.jetbrains.compose.web.dom.*
import org.w3c.dom.HTMLSelectElement

/**
 * Admin page for listing users and changing their roles. The role <select>
 * for each row is bound to that row's [UserNameRole.allowedRoles] — the
 * backend has already narrowed those based on the caller's authority, so
 * the UI just renders what the server says is legal.
 *
 * Promoting another user to OWNER is an ownership transfer: the caller is
 * atomically demoted to AUDITOR. The dropdown commits on change, but if the
 * picked role is OWNER we first show a confirmation explaining the demotion.
 *
 * After any successful role change we reload the user list (the caller's
 * own allowedRoles may have changed — most obviously after handing off OWNER).
 */
@Composable
fun UserManagementPage(
    apiClient: ApiClient,
    currentUserName: String,
    onSelfRoleChanged: (AuthResponse) -> Unit,
    onBack: () -> Unit,
) {
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pendingTransfer by remember { mutableStateOf<UserNameRole?>(null) }
    var pendingRoleChange by remember { mutableStateOf<Pair<String, Role>?>(null) }

    val usersFetch = rememberFetchState(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to load users",
    ) {
        apiClient.listUsers()
    }

    val roleChangeAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to update role",
        onError = { errorMessage = it },
        action = {
            errorMessage = null
            val (userName, newRole) = pendingRoleChange ?: return@rememberAsyncAction
            apiClient.setRole(userName, newRole)
            // The caller's access token may now be stale (most obviously after
            // an ownership transfer demoted them to AUDITOR). Refresh so subsequent
            // listUsers carries the caller's true current role, and surface the
            // new identity to the rest of the app via [onSelfRoleChanged].
            val refreshed = apiClient.refresh()
            if (refreshed != null) onSelfRoleChanged(refreshed)
            usersFetch.reload()
        },
    )

    fun submitRoleChange(userName: String, newRole: Role) {
        pendingRoleChange = userName to newRole
        roleChangeAction.invoke()
    }

    Div({ classes("container") }) {
        H1 { Text("User Management") }

        if (errorMessage != null) {
            Div({ classes("error") }) { Text(errorMessage!!) }
        }

        when (val state = usersFetch.state) {
            FetchState.Loading -> P { Text("Loading users…") }
            is FetchState.Error -> Div({ classes("error") }) { Text(state.message) }
            is FetchState.Success -> {
                if (state.value.isEmpty()) {
                    P { Text("No users found.") }
                } else {
                    Div({ classes("users-list") }) {
                        state.value.forEach { user ->
                            UserRow(
                                user = user,
                                isSelf = user.userName == currentUserName,
                                onRoleSelected = { newRole ->
                                    if (newRole == user.role) return@UserRow
                                    if (newRole == Role.OWNER) {
                                        pendingTransfer = user
                                    } else {
                                        submitRoleChange(user.userName, newRole)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        Button({ onClick { onBack() } }) { Text("Back to Home") }
    }

    pendingTransfer?.let { target ->
        OwnershipTransferDialog(
            targetUserName = target.userName,
            onConfirm = {
                pendingTransfer = null
                submitRoleChange(target.userName, Role.OWNER)
            },
            onCancel = {
                pendingTransfer = null
                // Force a reload so the dropdown snaps back to the user's actual role
                // (the <select> currently shows OWNER because the user picked it).
                usersFetch.reload()
            },
        )
    }
}

@Composable
private fun UserRow(
    user: UserNameRole,
    isSelf: Boolean,
    onRoleSelected: (Role) -> Unit,
) {
    Div({ classes("user-row") }) {
        Span({ classes("user-name") }) {
            Text(user.userName + if (isSelf) " (you)" else "")
        }
        // A user with only their own role in allowedRoles can't be changed —
        // either the caller lacks authority, or it's the caller themselves.
        // Render a static label rather than a dropdown that does nothing.
        if (user.allowedRoles.size <= 1) {
            Span({ classes("user-role") }) { Text(user.role.name) }
        } else {
            Select(attrs = {
                attr("data-user", user.userName)
                onChange { event ->
                    val selected = (event.target as HTMLSelectElement).value
                    val role = Role.entries.firstOrNull { it.name == selected }
                    if (role != null) onRoleSelected(role)
                }
            }) {
                user.allowedRoles.forEach { role ->
                    Option(
                        value = role.name,
                        attrs = { if (role == user.role) selected() },
                    ) {
                        Text(role.name)
                    }
                }
            }
        }
    }
}

@Composable
private fun OwnershipTransferDialog(
    targetUserName: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Div({ classes("dialog-backdrop") }) {
        Div({ classes("dialog") }) {
            H2 { Text("Transfer ownership?") }
            P {
                Text(
                    "Promoting $targetUserName to OWNER will demote you to AUDITOR. " +
                        "There can only be one OWNER at a time.",
                )
            }
            Div({ classes("button-row") }) {
                Button({ onClick { onConfirm() } }) { Text("Transfer ownership") }
                Button({ onClick { onCancel() } }) { Text("Cancel") }
            }
        }
    }
}
