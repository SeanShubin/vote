package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val handleReset = {
        when {
            isLoading -> Unit
            resetToken.isBlank() -> errorMessage = "Reset link is missing the token. Try requesting another email."
            password != confirmPassword -> errorMessage = "Passwords do not match"
            password.isBlank() -> errorMessage = "Password cannot be blank"
            else -> {
                isLoading = true
                errorMessage = null
                coroutineScope.launch {
                    try {
                        apiClient.resetPassword(resetToken, password)
                        onResetComplete()
                    } catch (e: Exception) {
                        apiClient.logErrorToServer(e)
                        errorMessage = e.message ?: "Failed to reset password"
                    } finally {
                        isLoading = false
                    }
                }
            }
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
                handleReset()
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
                onKeyDown { event ->
                    if (event.key == "Enter") {
                        handleReset()
                    }
                }
            }

            Button({ attr("type", "submit") }) {
                Text(if (isLoading) "Resetting..." else "Set password")
            }

            Button({ attr("type", "button"); onClick { onNavigateToLogin() } }) {
                Text("Back to Login")
            }
        }
    }
}
