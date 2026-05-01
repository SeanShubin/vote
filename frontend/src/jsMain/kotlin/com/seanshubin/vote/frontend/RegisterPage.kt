package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.domain.Role
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.compose.web.attributes.*
import org.jetbrains.compose.web.dom.*

@Composable
fun RegisterPage(
    apiClient: ApiClient,
    onLoginSuccess: (userName: String, role: Role) -> Unit,
    onNavigateToLogin: () -> Unit,
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
) {
    var userName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val registerAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Registration failed",
        onError = { errorMessage = it },
        coroutineScope = coroutineScope,
        action = {
            errorMessage = null
            val auth = apiClient.register(userName, email, password)
            onLoginSuccess(auth.userName, auth.role)
        },
    )

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
                registerAction.invoke()
            }
        }) {
            Input(InputType.Text) {
                attr("name", "username")
                attr("autocomplete", "username")
                placeholder("Username")
                value(userName)
                onInput { userName = it.value }
            }

            Input(InputType.Email) {
                attr("name", "email")
                attr("autocomplete", "email")
                placeholder("Email")
                value(email)
                onInput { email = it.value }
            }

            Input(InputType.Password) {
                attr("name", "password")
                attr("autocomplete", "new-password")
                placeholder("Password")
                value(password)
                onInput { password = it.value }
            }

            Button({
                attr("type", "submit")
                if (registerAction.isLoading) attr("disabled", "")
            }) {
                Text(if (registerAction.isLoading) "Registering…" else "Register")
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
