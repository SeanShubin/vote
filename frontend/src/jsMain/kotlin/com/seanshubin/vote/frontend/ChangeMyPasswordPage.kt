package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import org.jetbrains.compose.web.attributes.*
import org.jetbrains.compose.web.dom.*

/**
 * Self-service password change for an authenticated user. The backend
 * verifies [oldPassword] against the stored hash before accepting the
 * new value, so an attacker who walks up to an unlocked browser session
 * can't lock the legitimate user out by setting a password they don't
 * know. The new password is asked for twice to catch typos.
 *
 * Distinct from [PasswordResetPage] (which consumes a token from an
 * email link and requires no old password) and from the admin
 * set-password form on the user-management page (which requires no
 * old password but is gated by role).
 */
@Composable
fun ChangeMyPasswordPage(
    apiClient: ApiClient,
    onSuccess: () -> Unit,
    onCancel: () -> Unit,
) {
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val changeAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to change password",
        onError = { errorMessage = it },
        action = {
            errorMessage = null
            apiClient.changeMyPassword(oldPassword, newPassword)
            onSuccess()
        },
    )

    fun handleSubmit() {
        when {
            oldPassword.isBlank() -> errorMessage = "Old password cannot be blank"
            newPassword.isBlank() -> errorMessage = "New password cannot be blank"
            newPassword != confirmPassword -> errorMessage = "Passwords do not match"
            else -> changeAction.invoke()
        }
    }

    Div({ classes("container") }) {
        H1 { Text("Change Password") }

        errorMessage?.let { msg ->
            Div({ classes("error") }) { Text(msg) }
        }

        // current-password / new-password autocomplete hints let password
        // managers fill the existing entry and offer to save the new one.
        Form(attrs = {
            classes("form")
            onSubmit { event ->
                event.preventDefault()
                handleSubmit()
            }
        }) {
            Input(InputType.Password) {
                attr("name", "oldPassword")
                attr("autocomplete", "current-password")
                placeholder("Old password")
                value(oldPassword)
                onInput { oldPassword = it.value }
            }

            Input(InputType.Password) {
                attr("name", "newPassword")
                attr("autocomplete", "new-password")
                placeholder("New password")
                value(newPassword)
                onInput { newPassword = it.value }
            }

            Input(InputType.Password) {
                attr("name", "confirmPassword")
                attr("autocomplete", "new-password")
                placeholder("Confirm new password")
                value(confirmPassword)
                onInput { confirmPassword = it.value }
            }

            Button({
                attr("type", "submit")
                if (changeAction.isLoading) attr("disabled", "")
            }) {
                Text(if (changeAction.isLoading) "Changing…" else "Change password")
            }

            Button({
                attr("type", "button")
                onClick { onCancel() }
            }) {
                Text("Cancel")
            }
        }
    }
}
