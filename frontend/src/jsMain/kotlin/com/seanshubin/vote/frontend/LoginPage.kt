package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.*
import org.jetbrains.compose.web.dom.*

@Composable
fun LoginPage(
    onLoginSuccess: (String, String) -> Unit,
    onNavigateToRegister: () -> Unit
) {
    var userName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Div({ classes("container") }) {
        H1 { Text("Vote - Login") }

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
                        scope.launch {
                            try {
                                val tokens = ApiClient.authenticate(userName, password)
                                val tokenJson = """{"userName":"$userName","role":"USER"}"""
                                onLoginSuccess(tokenJson, userName)
                            } catch (e: Exception) {
                                errorMessage = e.message ?: "Login failed"
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                }
            }) {
                Text(if (isLoading) "Logging in..." else "Login")
            }

            Button({
                onClick { onNavigateToRegister() }
            }) {
                Text("Register")
            }
        }
    }
}
