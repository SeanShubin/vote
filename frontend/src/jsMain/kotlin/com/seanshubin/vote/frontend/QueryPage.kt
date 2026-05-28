package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.domain.TableData
import org.jetbrains.compose.web.attributes.*
import org.jetbrains.compose.web.dom.*

/**
 * Admin debug page for ad-hoc, read-only queries in the active backend's
 * dialect (PartiQL for DynamoDB, etc.). The Home button that routes here is
 * hidden on backends with no query surface, so this page assumes a non-empty
 * dialect on arrival; if a user lands here via deep-link on an unsupported
 * backend, the first Run call surfaces the executor's "not supported" error
 * just like any other backend rejection.
 *
 * Writes are blocked at the executor — PartiQL INSERT/UPDATE/DELETE would
 * bypass the event log and corrupt event-sourced state. The page header
 * reflects this so the user doesn't waste a Run on a rejected statement.
 */
@Composable
fun QueryPage(
    apiClient: ApiClient,
    onBack: () -> Unit,
) {
    val dialectFetch = rememberCachedFetchState(
        apiClient = apiClient,
        cacheKey = "queryDialect",
        fallbackErrorMessage = "Failed to load query dialect",
    ) {
        apiClient.queryDialect()
    }
    val dialect = (dialectFetch.state as? FetchState.Success)?.value.orEmpty()

    var query by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<TableData?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val runAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Query failed",
        onError = {
            errorMessage = it
            result = null
        },
        action = {
            errorMessage = null
            result = apiClient.executeQuery(query)
        },
    )

    Div({ classes("admin-container") }) {
        H1 { Text(if (dialect.isEmpty()) "Query" else "Query ($dialect)") }
        P { Text("Read-only — only SELECT statements are accepted.") }

        TextArea(query) {
            classes("textarea")
            attr("rows", "8")
            placeholder("SELECT * FROM \"vote_data\" WHERE \"PK\" = 'USER#alice'")
            onInput { query = it.value }
        }

        Div({ classes("button-row") }) {
            Button({
                if (runAction.isLoading || query.isBlank()) attr("disabled", "")
                onClick { runAction.invoke() }
            }) {
                Text(if (runAction.isLoading) "Running…" else "Run")
            }
            Button({ onClick { onBack() } }) { Text("Back to Home") }
        }

        errorMessage?.let { msg ->
            Div({ classes("error") }) { Text(msg) }
        }

        Div({ classes("admin-table-scroll") }) {
            result?.let { renderTable(it) }
        }
    }
}
