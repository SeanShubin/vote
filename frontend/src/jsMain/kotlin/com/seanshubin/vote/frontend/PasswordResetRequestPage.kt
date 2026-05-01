package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import org.jetbrains.compose.web.attributes.*
import org.jetbrains.compose.web.dom.*

/**
 * "I forgot my password" — first step. User submits their username or email,
 * the backend looks them up and emails a reset link. We surface the success
 * confirmation in-page rather than navigating away so the user knows the
 * email request actually went out.
 */
@Composable
fun PasswordResetRequestPage(
    apiClient: ApiClient,
    onNavigateToLogin: () -> Unit,
) {
    var nameOrEmail by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var emailSent by remember { mutableStateOf(false) }

    val sendAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to send reset email",
        onError = { errorMessage = it },
        action = {
            errorMessage = null
            apiClient.requestPasswordReset(nameOrEmail)
            emailSent = true
        },
    )

    Div({ classes("container") }) {
        H1 { Text("Reset password") }

        if (emailSent) {
            Div({ classes("success") }) {
                Text("If an account matches, an email has been sent with a reset link. The link expires in 1 hour.")
            }
            Button({ attr("type", "button"); onClick { onNavigateToLogin() } }) {
                Text("Back to Login")
            }
            return@Div
        }

        errorMessage?.let { msg ->
            Div({ classes("error") }) { Text(msg) }
        }

        Form(attrs = {
            classes("form")
            onSubmit { event ->
                event.preventDefault()
                if (!emailSent) sendAction.invoke()
            }
        }) {
            // The HTML name stays "username" so password-manager affordances
            // (autofill the saved login) work even on the recovery flow.
            Input(InputType.Text) {
                attr("name", "username")
                attr("autocomplete", "username")
                placeholder("Username or email")
                value(nameOrEmail)
                onInput { nameOrEmail = it.value }
            }

            Button({
                attr("type", "submit")
                if (sendAction.isLoading) attr("disabled", "")
            }) {
                Text(if (sendAction.isLoading) "Sending…" else "Send reset email")
            }

            Button({ attr("type", "button"); onClick { onNavigateToLogin() } }) {
                Text("Back to Login")
            }
        }
    }
}
