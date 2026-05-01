package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.domain.Role
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.compose.web.attributes.*
import org.jetbrains.compose.web.dom.*

@Composable
fun LoginPage(
    apiClient: ApiClient,
    // The ApiClient stores the token internally on success — we just relay
    // identity (user, role) up so the SPA can drive UI display.
    onLoginSuccess: (userName: String, role: Role) -> Unit,
    onNavigateToRegister: () -> Unit,
    onNavigateToForgotPassword: () -> Unit = {},
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
) {
    var nameOrEmail by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val loginAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Login failed",
        onError = { errorMessage = it },
        coroutineScope = coroutineScope,
        action = {
            errorMessage = null
            val auth = apiClient.authenticate(nameOrEmail, password)
            onLoginSuccess(auth.userName, auth.role)
        },
    )

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
                loginAction.invoke()
            }
        }) {
            // Backend's authenticate() tries the value as a username first, then
            // falls back to email — so users can log in either way. The HTML name
            // and autocomplete tokens stay "username" so the browser/password
            // manager keys saved credentials by that stable identifier even if
            // the UI label changes.
            Input(InputType.Text) {
                attr("name", "username")
                attr("autocomplete", "username")
                placeholder("Username or email")
                value(nameOrEmail)
                onInput { nameOrEmail = it.value }
            }

            Input(InputType.Password) {
                attr("name", "password")
                attr("autocomplete", "current-password")
                placeholder("Password")
                value(password)
                onInput { password = it.value }
            }

            // type=submit so Enter / button-click triggers the form's onSubmit,
            // which is the path password managers watch for "save credential?".
            Button({
                attr("type", "submit")
                if (loginAction.isLoading) attr("disabled", "")
            }) {
                Text(if (loginAction.isLoading) "Logging in…" else "Login")
            }

            // type=button so these don't accidentally submit the form.
            Button({
                attr("type", "button")
                onClick { onNavigateToRegister() }
            }) {
                Text("Register")
            }

            Button({
                attr("type", "button")
                onClick { onNavigateToForgotPassword() }
            }) {
                Text("Forgot password?")
            }
        }
    }
}
