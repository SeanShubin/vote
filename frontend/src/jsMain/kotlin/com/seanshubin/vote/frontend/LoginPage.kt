package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.domain.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
                    val auth = apiClient.authenticate(userName, password)
                    onLoginSuccess(auth.accessToken, auth.userName, auth.role)
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

        // Wrapping in <form> + giving each field a name + autocomplete value is
        // what browsers and password managers (1Password, Bitwarden, browser
        // built-ins) need to offer fill-saved-credentials and save-on-success.
        // current-password tells managers "fill the saved one" — distinct from
        // RegisterPage's new-password ("the user is creating a credential").
        Form(attrs = {
            classes("form")
            attr("autocomplete", "on")
            onSubmit { event ->
                event.preventDefault()
                handleLogin()
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
                        handleLogin()
                    }
                }
            }

            Input(InputType.Password) {
                attr("name", "password")
                attr("autocomplete", "current-password")
                placeholder("Password")
                value(password)
                onInput { password = it.value }
                onKeyDown { event ->
                    if (event.key == "Enter") {
                        handleLogin()
                    }
                }
            }

            // type=submit so Enter / button-click triggers the form's onSubmit,
            // which is the path password managers watch for "save credential?".
            Button({
                attr("type", "submit")
            }) {
                Text(if (isLoading) "Logging in..." else "Login")
            }

            // type=button so this doesn't accidentally submit the form.
            Button({
                attr("type", "button")
                onClick { onNavigateToRegister() }
            }) {
                Text("Register")
            }
        }
    }
}
