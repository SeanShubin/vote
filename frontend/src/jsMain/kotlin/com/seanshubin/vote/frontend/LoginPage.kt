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
fun LoginPage(
    apiClient: ApiClient,
    onLoginSuccess: (authToken: String, userName: String, role: Role) -> Unit,
    onNavigateToRegister: () -> Unit,
    coroutineScope: CoroutineScope = rememberCoroutineScope()
) {
    var userName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val handleLogin = {
        if (!isLoading) {
            isLoading = true
            errorMessage = null
            coroutineScope.launch {
                try {
                    val tokens = apiClient.authenticate(userName, password)
                    // Use the real access token (with its actual role) — not a hand-crafted USER stub.
                    val tokenJson = Json.encodeToString(tokens.accessToken)
                    onLoginSuccess(tokenJson, tokens.accessToken.userName, tokens.accessToken.role)
                } catch (e: Exception) {
                    apiClient.logErrorToServer(e)
                    errorMessage = e.message ?: "Login failed"
                } finally {
                    isLoading = false
                }
            }
        }
    }

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
                onKeyDown { event ->
                    if (event.key == "Enter") {
                        handleLogin()
                    }
                }
            }

            Input(InputType.Password) {
                placeholder("Password")
                value(password)
                onInput { password = it.value }
                onKeyDown { event ->
                    if (event.key == "Enter") {
                        handleLogin()
                    }
                }
            }

            Button({
                onClick { handleLogin() }
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
