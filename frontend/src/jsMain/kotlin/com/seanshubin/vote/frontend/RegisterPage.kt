package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.domain.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
                    val auth = apiClient.register(userName, email, password)
                    onLoginSuccess(auth.accessToken, auth.userName, auth.role)
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

        // new-password (vs LoginPage's current-password) tells password managers
        // the user is creating a credential — triggers strong-password
        // suggestions and the "save new credential?" prompt on success.
        Form(attrs = {
            classes("form")
            attr("autocomplete", "on")
            onSubmit { event ->
                event.preventDefault()
                handleRegister()
            }
        }) {
            Input(InputType.Text) {
                attr("name", "username")
                attr("autocomplete", "username")
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
                attr("name", "email")
                attr("autocomplete", "email")
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
                attr("name", "password")
                attr("autocomplete", "new-password")
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
                attr("type", "submit")
            }) {
                Text(if (isLoading) "Registering..." else "Register")
            }

            Button({
                attr("type", "button")
                onClick { onNavigateToLogin() }
            }) {
                Text("Back to Login")
            }
        }
    }
}
