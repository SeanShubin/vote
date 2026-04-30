package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.domain.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.compose.web.attributes.*
import org.jetbrains.compose.web.dom.*

@Composable
fun RegisterPage(
    apiClient: ApiClient,
    onLoginSuccess: (authToken: String, userName: String, role: Role) -> Unit,
    onNavigateToLogin: () -> Unit,
    coroutineScope: CoroutineScope = rememberCoroutineScope()
) {
    var userName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val handleRegister = {
        if (!isLoading) {
            isLoading = true
            errorMessage = null
            coroutineScope.launch {
                try {
                    val tokens = apiClient.register(userName, email, password)
                    // Use the real access token. New users default to USER, but the very
                    // first user becomes OWNER — this captures whatever the server actually assigned.
                    val tokenJson = Json.encodeToString(tokens.accessToken)
                    onLoginSuccess(tokenJson, tokens.accessToken.userName, tokens.accessToken.role)
                } catch (e: Exception) {
                    apiClient.logErrorToServer(e)
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
