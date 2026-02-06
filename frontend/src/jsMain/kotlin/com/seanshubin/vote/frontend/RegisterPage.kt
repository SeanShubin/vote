package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.*
import org.jetbrains.compose.web.dom.*

@Composable
fun RegisterPage(
    onRegisterSuccess: () -> Unit,
    onNavigateToLogin: () -> Unit
) {
    var userName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Div({ classes("container") }) {
        H1 { Text("Vote - Register") }

        if (errorMessage != null) {
            Div({ classes("error") }) {
                Text(errorMessage!!)
            }
        }

        if (successMessage != null) {
            Div({ classes("success") }) {
                Text(successMessage!!)
            }
        }

        Div({ classes("form") }) {
            Input(InputType.Text) {
                placeholder("Username")
                value(userName)
                onInput { userName = it.value }
            }

            Input(InputType.Email) {
                placeholder("Email")
                value(email)
                onInput { email = it.value }
            }

            Input(InputType.Password) {
                placeholder("Password")
                value(password)
                onInput { password = it.value }
            }

            Button({
                onClick {
                    if (!isLoading) {
                        isLoading = true
                        errorMessage = null
                        successMessage = null
                        scope.launch {
                            try {
                                ApiClient.register(userName, email, password)
                                successMessage = "Registration successful! Please login."
                                userName = ""
                                email = ""
                                password = ""
                                onRegisterSuccess()
                            } catch (e: Exception) {
                                ApiClient.logErrorToServer(e)
                                errorMessage = e.message ?: "Registration failed"
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                }
            }) {
                Text(if (isLoading) "Registering..." else "Register")
            }

            Button({
                onClick { onNavigateToLogin() }
            }) {
                Text("Back to Login")
            }
        }
    }
}
