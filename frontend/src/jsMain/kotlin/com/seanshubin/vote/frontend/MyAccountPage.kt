package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import org.jetbrains.compose.web.attributes.*
import org.jetbrains.compose.web.dom.*

/**
 * Self-service account page. Two sections:
 *
 * - Email — current value loaded from the server. Save submits whatever's
 *   in the field; an empty field clears the email on file. The user with
 *   no email loses self-service password reset, so an admin set-password
 *   is the only recovery path for them.
 * - Password — same shape as the prior dedicated page: old, new, confirm.
 *   The backend verifies the old password before accepting the new one.
 */
@Composable
fun MyAccountPage(
    apiClient: ApiClient,
    onCancel: () -> Unit,
) {
    Div({ classes("container") }) {
        H1 { Text("My Account") }
        Div({ classes("my-account-sections") }) {
            EmailSection(apiClient = apiClient)
            PasswordSection(apiClient = apiClient)
        }
        Button({
            attr("type", "button")
            onClick { onCancel() }
        }) {
            Text("Back")
        }
    }
}

@Composable
private fun EmailSection(apiClient: ApiClient) {
    val userFetch = rememberFetchState(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to load email",
    ) {
        apiClient.getMyUser()
    }

    Div({ classes("my-account-section") }) {
        H2 { Text("Email") }
        when (val state = userFetch.state) {
            FetchState.Loading -> P { Text("Loading…") }
            is FetchState.Error -> Div({ classes("error") }) { Text(state.message) }
            is FetchState.Success -> EmailEditor(
                apiClient = apiClient,
                initialEmail = state.value.email,
                onSaved = { userFetch.reload() },
            )
        }
    }
}

@Composable
private fun EmailEditor(
    apiClient: ApiClient,
    initialEmail: String,
    onSaved: () -> Unit,
) {
    var email by remember(initialEmail) { mutableStateOf(initialEmail) }
    var errorMessage by remember(initialEmail) { mutableStateOf<String?>(null) }
    var statusMessage by remember(initialEmail) { mutableStateOf<String?>(null) }

    val saveAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to save email",
        onError = { errorMessage = it; statusMessage = null },
        action = {
            errorMessage = null
            statusMessage = null
            apiClient.updateMyEmail(email)
            statusMessage = if (email.isEmpty()) "Email cleared" else "Email saved"
            onSaved()
        },
    )

    errorMessage?.let { Div({ classes("error") }) { Text(it) } }
    statusMessage?.let { Div({ classes("success") }) { Text(it) } }

    Form(attrs = {
        classes("form")
        onSubmit { event ->
            event.preventDefault()
            saveAction.invoke()
        }
    }) {
        Input(InputType.Email) {
            attr("name", "email")
            attr("autocomplete", "email")
            placeholder("Email (leave blank to clear)")
            value(email)
            onInput { email = it.value }
        }
        Button({
            attr("type", "submit")
            if (saveAction.isLoading) attr("disabled", "")
        }) {
            Text(if (saveAction.isLoading) "Saving…" else "Save email")
        }
    }
}

@Composable
private fun PasswordSection(apiClient: ApiClient) {
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val changeAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to change password",
        onError = { errorMessage = it; statusMessage = null },
        action = {
            errorMessage = null
            statusMessage = null
            apiClient.changeMyPassword(oldPassword, newPassword)
            statusMessage = "Password changed"
            oldPassword = ""
            newPassword = ""
            confirmPassword = ""
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

    Div({ classes("my-account-section") }) {
        H2 { Text("Password") }
        errorMessage?.let { Div({ classes("error") }) { Text(it) } }
        statusMessage?.let { Div({ classes("success") }) { Text(it) } }

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
        }
    }
}
