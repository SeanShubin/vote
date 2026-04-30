package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.domain.TableData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
    title: String,
    emptyMessage: String,
    loadNames: suspend () -> List<String>,
    loadData: suspend (String) -> TableData,
    onError: (Throwable) -> Unit,
    onBack: () -> Unit,
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
) {
    var tableNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedTable by remember { mutableStateOf<String?>(null) }
    var tableData by remember { mutableStateOf<TableData?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoadingNames by remember { mutableStateOf(true) }
    var isLoadingData by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            tableNames = loadNames()
            selectedTable = tableNames.firstOrNull()
        } catch (e: Exception) {
            onError(e)
            errorMessage = e.message ?: "Failed to load table names"
        } finally {
            isLoadingNames = false
        }
    }

    LaunchedEffect(selectedTable) {
        val name = selectedTable ?: return@LaunchedEffect
        isLoadingData = true
        try {
            tableData = loadData(name)
        } catch (e: Exception) {
            onError(e)
            errorMessage = e.message ?: "Failed to load table $name"
        } finally {
            isLoadingData = false
        }
    }

    Div({ classes("container") }) {
        H1 { Text(title) }

        errorMessage?.let { msg ->
            Div({ classes("error") }) { Text(msg) }
        }

        when {
            isLoadingNames -> P { Text("Loading tables...") }
            tableNames.isEmpty() -> P { Text(emptyMessage) }
            else -> {
                // Tab strip
                Div({ classes("tab-strip") }) {
                    tableNames.forEach { name ->
                        Button({
                            classes(if (name == selectedTable) "tab-active" else "tab")
                            onClick { selectedTable = name }
                        }) {
                            Text(name)
                        }
                    }
                }

                if (isLoadingData) {
                    P { Text("Loading rows...") }
                } else {
                    tableData?.let { renderTable(it) }
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
