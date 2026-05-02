package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.domain.TableData
import org.jetbrains.compose.web.dom.*

/**
 * Tabbed admin browser. The page loads a list of table names on mount and,
 * on tab click, loads the rows for that table. Used by both the Raw Tables
 * view (physical DynamoDB tables) and the Debug Tables view (relational
 * projection of those items).
 *
 * The two callers differ only in the title and the two suspend lambdas.
 */
@Composable
fun TablesPage(
    apiClient: ApiClient,
    title: String,
    emptyMessage: String,
    loadNames: suspend () -> List<String>,
    loadData: suspend (String) -> TableData,
    onBack: () -> Unit,
) {
    var selectedTable by remember { mutableStateOf<String?>(null) }

    val namesFetch = rememberFetchState(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to load table names",
    ) {
        loadNames()
    }

    // Auto-select the first table once names have loaded.
    LaunchedEffect(namesFetch.state) {
        val state = namesFetch.state
        if (state is FetchState.Success && selectedTable == null) {
            selectedTable = state.value.firstOrNull()
        }
    }

    val dataFetch = rememberFetchState(
        apiClient = apiClient,
        key = selectedTable,
        fallbackErrorMessage = "Failed to load table",
    ) {
        val name = selectedTable
        if (name == null) null else loadData(name)
    }

    Div({ classes("admin-container") }) {
        H1 { Text(title) }

        when (val state = namesFetch.state) {
            FetchState.Loading -> P { Text("Loading tables…") }
            is FetchState.Error -> Div({ classes("error") }) { Text(state.message) }
            is FetchState.Success -> {
                if (state.value.isEmpty()) {
                    P { Text(emptyMessage) }
                } else {
                    Div({ classes("tab-strip") }) {
                        state.value.forEach { name ->
                            Button({
                                classes(if (name == selectedTable) "tab-active" else "tab")
                                onClick { selectedTable = name }
                            }) {
                                Text(name)
                            }
                        }
                    }

                    Div({ classes("admin-table-scroll") }) {
                        when (val dataState = dataFetch.state) {
                            FetchState.Loading -> P { Text("Loading rows…") }
                            is FetchState.Error ->
                                Div({ classes("error") }) { Text(dataState.message) }
                            is FetchState.Success -> dataState.value?.let { renderTable(it) }
                        }
                    }
                }
            }
        }

        Button({ onClick { onBack() } }) { Text("Back to Home") }
    }
}

@Composable
private fun renderTable(data: TableData) {
    if (data.columnNames.isEmpty()) {
        P { Text("(no columns)") }
        return
    }
    Table({ classes("data-table") }) {
        Thead {
            Tr {
                data.columnNames.forEach { col ->
                    Th { Text(col) }
                }
            }
        }
        Tbody {
            if (data.rows.isEmpty()) {
                Tr {
                    Td({ attr("colspan", data.columnNames.size.toString()) }) {
                        Text("(no rows)")
                    }
                }
            } else {
                data.rows.forEach { row ->
                    Tr {
                        row.forEach { cell ->
                            Td { Text(cell ?: "null") }
                        }
                    }
                }
            }
        }
    }
}
