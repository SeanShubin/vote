package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
) {
    var nameOrEmail by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var emailSent by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }

    val handleRequest = {
        if (!isLoading && !emailSent) {
            isLoading = true
            errorMessage = null
            coroutineScope.launch {
                try {
                    apiClient.requestPasswordReset(nameOrEmail)
                    emailSent = true
                } catch (e: Exception) {
                    apiClient.logErrorToServer(e)
                    errorMessage = e.message ?: "Failed to send reset email"
                } finally {
                    isLoading = false
                }
            }
        }
    }

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
                handleRequest()
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
                onKeyDown { event ->
                    if (event.key == "Enter") {
                        handleRequest()
                    }
                }
            }

            Button({ attr("type", "submit") }) {
                Text(if (isLoading) "Sending..." else "Send reset email")
            }

            Button({ attr("type", "button"); onClick { onNavigateToLogin() } }) {
                Text("Back to Login")
            }
        }
    }
}
