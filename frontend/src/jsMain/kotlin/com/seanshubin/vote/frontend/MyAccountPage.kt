package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import org.jetbrains.compose.web.dom.*

/**
 * Self-service account page. Currently a read-only view of the current
 * user. Password and email management were retired alongside password
 * login; the page is retained as the surface for any future profile
 * settings.
 */
@Composable
fun MyAccountPage(
    apiClient: ApiClient,
    onCancel: () -> Unit,
) {
    val userFetch = rememberFetchState(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to load account",
    ) {
        apiClient.getMyUser()
    }

    Div({ classes("container") }) {
        H1 { Text("My Account") }
        when (val state = userFetch.state) {
            FetchState.Loading -> P { Text("Loading…") }
            is FetchState.Error -> Div({ classes("error") }) { Text(state.message) }
            is FetchState.Success -> Div({ classes("my-account-sections") }) {
                P { Text("Signed in as ${state.value.name}.") }
                P { Text("Sign-in is handled by Discord — there are no account settings to change here.") }
            }
        }
        Button({
            attr("type", "button")
            onClick { onCancel() }
        }) {
            Text("Back")
        }
    }
}
