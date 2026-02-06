package com.seanshubin.vote.frontend

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.*

@Composable
fun HomePage(
    userName: String,
    authToken: String,
    onNavigateToCreateElection: () -> Unit,
    onNavigateToElections: () -> Unit,
    onLogout: () -> Unit
) {
    Div({ classes("container") }) {
        H1 { Text("Vote - Home") }

        P { Text("Welcome, $userName!") }

        Div({ classes("menu") }) {
            Button({
                onClick { onNavigateToCreateElection() }
            }) {
                Text("Create Election")
            }

            Button({
                onClick { onNavigateToElections() }
            }) {
                Text("View Elections")
            }

            Button({
                onClick { onLogout() }
            }) {
                Text("Logout")
            }
        }
    }
}
