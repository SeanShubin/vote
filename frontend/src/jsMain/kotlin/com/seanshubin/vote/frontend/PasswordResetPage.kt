package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import org.jetbrains.compose.web.attributes.*
import org.jetbrains.compose.web.dom.*

/**
 * Target of the email reset link. The token comes from the URL's `?token=`
 * query string (parsed by Router.pathToPage). User enters a new password
 * twice; on success we send them to Login so they can sign in fresh.
 *
 * Two password inputs avoid the "I typed it wrong and got locked out"
 * scenario. autocomplete="new-password" tells password managers to
 * suggest a strong one.
 */
@Composable
fun PasswordResetPage(
    apiClient: ApiClient,
    resetToken: String,
    onResetComplete: () -> Unit,
    onNavigateToLogin: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val resetAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to reset password",
        onError = { errorMessage = it },
        action = {
            errorMessage = null
            apiClient.resetPassword(resetToken, password)
            onResetComplete()
        },
    )

    fun handleSubmit() {
        when {
            resetToken.isBlank() ->
                errorMessage = "Reset link is missing the token. Try requesting another email."
            password != confirmPassword ->
                errorMessage = "Passwords do not match"
            password.isBlank() ->
                errorMessage = "Password cannot be blank"
            else -> resetAction.invoke()
        }
    }

    Div({ classes("container") }) {
        H1 { Text("Set new password") }

        errorMessage?.let { msg ->
            Div({ classes("error") }) { Text(msg) }
        }

        Form(attrs = {
            classes("form")
            onSubmit { event ->
                event.preventDefault()
                handleSubmit()
            }
        }) {
            Input(InputType.Password) {
                attr("name", "password")
                attr("autocomplete", "new-password")
                placeholder("New password")
                value(password)
                onInput { password = it.value }
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
                if (resetAction.isLoading) attr("disabled", "")
            }) {
                Text(if (resetAction.isLoading) "Resetting…" else "Set password")
            }

            Button({ attr("type", "button"); onClick { onNavigateToLogin() } }) {
                Text("Back to Login")
            }
        }
    }
}
