package com.seanshubin.vote.frontend

import androidx.compose.runtime.Composable
import com.seanshubin.vote.domain.Role
import org.jetbrains.compose.web.dom.*

@Composable
fun HomePage(
    userName: String,
    role: Role?,
    authToken: String,
    onNavigateToCreateElection: () -> Unit,
    onNavigateToElections: () -> Unit,
    onNavigateToRawTables: () -> Unit,
    onNavigateToDebugTables: () -> Unit,
    onLogout: () -> Unit
) {
    Div({ classes("container") }) {
        H1 { Text("Vote - Home") }

        P { Text("Welcome, $userName!") }

        if (role != null) {
            P { Text("Role: ${role.name} — ${role.description}") }
        }

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

            // AUDITOR+ get the admin data browser. The role gate here is a UX
            // shortcut — the backend re-checks VIEW_SECRETS on every request.
            if (role != null && role >= Role.AUDITOR) {
                Button({
                    onClick { onNavigateToRawTables() }
                }) {
                    Text("Raw Tables")
                }

                Button({
                    onClick { onNavigateToDebugTables() }
                }) {
                    Text("Debug Tables")
                }
            }

            Button({
                onClick { onLogout() }
            }) {
                Text("Logout")
            }
        }
    }
}
