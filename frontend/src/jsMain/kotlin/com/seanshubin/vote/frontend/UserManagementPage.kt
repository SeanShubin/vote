package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.contract.AuthResponse
import com.seanshubin.vote.domain.Role
import com.seanshubin.vote.domain.UserNameRole
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.*
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
    currentRole: Role?,
    onSelfRoleChanged: (AuthResponse) -> Unit,
    // The self-row renders a "Change my password" link in the same column
    // slot that other rows use for the admin "Set password…" button. Self
    // can't use the admin path (the gate explicitly excludes self) so this
    // is a discoverability shortcut to ChangeMyPasswordPage rather than a
    // duplicated affordance.
    onChangeMyPassword: () -> Unit,
    onBack: () -> Unit,
) {
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var pendingTransfer by remember { mutableStateOf<UserNameRole?>(null) }
    var pendingRoleChange by remember { mutableStateOf<Pair<String, Role>?>(null) }
    // Tracks which user's set-password form is currently expanded — null
    // means no form open. Only one row at a time can be in the editing
    // state, mirroring how the role <select> works.
    var openSetPasswordFor by remember { mutableStateOf<String?>(null) }

    val usersFetch = rememberCachedFetchState(
        apiClient = apiClient,
        cacheKey = "users",
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
            // usersFetch.reload() invalidates the cache and refetches; the
            // staleWhileRevalidate path means the row re-renders without a
            // Loading flash even though the cache entry was dropped.
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
        if (successMessage != null) {
            Div({ classes("success") }) { Text(successMessage!!) }
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
                            // Mirror the backend gate exactly: caller can set
                            // another user's password when their role is
                            // strictly greater than the target's. Backend
                            // re-checks; this just hides the form for users
                            // the caller can't act on. Self uses the
                            // ChangeMyPassword page.
                            val canSetPassword = user.userName != currentUserName &&
                                currentRole != null && currentRole > user.role
                            UserRow(
                                user = user,
                                isSelf = user.userName == currentUserName,
                                canSetPassword = canSetPassword,
                                isSetPasswordOpen = openSetPasswordFor == user.userName,
                                onChangeMyPassword = onChangeMyPassword,
                                onRoleSelected = { newRole ->
                                    if (newRole == user.role) return@UserRow
                                    if (newRole == Role.OWNER) {
                                        pendingTransfer = user
                                    } else {
                                        submitRoleChange(user.userName, newRole)
                                    }
                                },
                                onToggleSetPassword = {
                                    openSetPasswordFor =
                                        if (openSetPasswordFor == user.userName) null
                                        else user.userName
                                },
                                onSubmitSetPassword = { newPassword ->
                                    successMessage = null
                                    errorMessage = null
                                    try {
                                        apiClient.adminSetPassword(user.userName, newPassword)
                                        successMessage = "Password set for ${user.userName}"
                                        openSetPasswordFor = null
                                    } catch (e: Exception) {
                                        apiClient.logErrorToServer(e)
                                        errorMessage = e.message ?: "Failed to set password"
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
    canSetPassword: Boolean,
    isSetPasswordOpen: Boolean,
    onChangeMyPassword: () -> Unit,
    onRoleSelected: (Role) -> Unit,
    onToggleSetPassword: () -> Unit,
    onSubmitSetPassword: suspend (String) -> Unit,
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
        // Column 3: admin Set-password for other users (when allowed), the
        // self-service Change-my-password shortcut on the self-row, or an
        // empty placeholder. The placeholder is required because .users-list
        // is a 3-column grid using display: contents on the row — without
        // it, the next user's name would land in this row's empty slot.
        when {
            canSetPassword -> Button({
                attr("data-set-password-toggle", user.userName)
                onClick { onToggleSetPassword() }
            }) {
                Text(if (isSetPasswordOpen) "Cancel" else "Set password…")
            }
            isSelf -> Button({
                attr("data-change-my-password", "")
                onClick { onChangeMyPassword() }
            }) {
                Text("Change my password")
            }
            else -> Span({ classes("user-row-spacer") })
        }
    }
    if (isSetPasswordOpen) {
        AdminSetPasswordForm(
            targetUserName = user.userName,
            onSubmitPassword = onSubmitSetPassword,
        )
    }
}

/**
 * Inline form for an admin to set another user's password. Lives directly
 * under the user's row when expanded. No old-password field — the whole
 * point of the admin path is recovery for users who don't know theirs.
 * The admin shares the new password out-of-band and the user is expected
 * to change it after logging in via [ChangeMyPasswordPage].
 */
@Composable
private fun AdminSetPasswordForm(
    targetUserName: String,
    onSubmitPassword: suspend (String) -> Unit,
) {
    var newPassword by remember(targetUserName) { mutableStateOf("") }
    var confirmPassword by remember(targetUserName) { mutableStateOf("") }
    var localError by remember(targetUserName) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var isSubmitting by remember(targetUserName) { mutableStateOf(false) }

    Form(attrs = {
        classes("form", "admin-set-password-form")
        onSubmit { event ->
            event.preventDefault()
            when {
                newPassword.isBlank() -> localError = "Password cannot be blank"
                newPassword != confirmPassword -> localError = "Passwords do not match"
                else -> {
                    localError = null
                    isSubmitting = true
                    scope.launch {
                        try {
                            onSubmitPassword(newPassword)
                        } finally {
                            isSubmitting = false
                        }
                    }
                }
            }
        }
    }) {
        localError?.let { msg ->
            Div({ classes("error") }) { Text(msg) }
        }
        Input(InputType.Password) {
            attr("name", "newPassword-$targetUserName")
            attr("autocomplete", "new-password")
            placeholder("New password for $targetUserName")
            value(newPassword)
            onInput { newPassword = it.value }
        }
        Input(InputType.Password) {
            attr("name", "confirmPassword-$targetUserName")
            attr("autocomplete", "new-password")
            placeholder("Confirm new password")
            value(confirmPassword)
            onInput { confirmPassword = it.value }
        }
        Button({
            attr("type", "submit")
            if (isSubmitting) attr("disabled", "")
        }) {
            Text(if (isSubmitting) "Setting…" else "Set password")
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
