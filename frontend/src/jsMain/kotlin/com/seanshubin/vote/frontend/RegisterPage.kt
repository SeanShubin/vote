package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.*
import org.jetbrains.compose.web.dom.*

@Composable
fun RegisterPage(
    onLoginSuccess: (String, String) -> Unit,
    onNavigateToLogin: () -> Unit
) {
    var userName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val handleRegister = {
        if (!isLoading) {
            isLoading = true
            errorMessage = null
            scope.launch {
                try {
                    val tokens = ApiClient.register(userName, email, password)
                    val tokenJson = """{"userName":"$userName","role":"USER"}"""
                    onLoginSuccess(tokenJson, userName)
                } catch (e: Exception) {
                    ApiClient.logErrorToServer(e)
                    errorMessage = e.message ?: "Registration failed"
                } finally {
                    isLoading = false
                }
            }
        }
    }

    Div({ classes("container") }) {
        H1 { Text("Vote - Register") }

        if (errorMessage != null) {
            Div({ classes("error") }) {
                Text(errorMessage!!)
            }
        }

        Div({ classes("form") }) {
            Input(InputType.Text) {
                placeholder("Username")
                value(userName)
                onInput { userName = it.value }
                onKeyDown { event ->
                    if (event.key == "Enter") {
                        handleRegister()
                    }
                }
            }

            Input(InputType.Email) {
                placeholder("Email")
                value(email)
                onInput { email = it.value }
                onKeyDown { event ->
                    if (event.key == "Enter") {
                        handleRegister()
                    }
                }
            }

            Input(InputType.Password) {
                placeholder("Password")
                value(password)
                onInput { password = it.value }
                onKeyDown { event ->
                    if (event.key == "Enter") {
                        handleRegister()
                    }
                }
            }

            Button({
                onClick { handleRegister() }
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
