package com.seanshubin.vote.backend.repository

import com.seanshubin.vote.contract.QueryExecutor
import com.seanshubin.vote.domain.TableData

/**
 * No-op executor for backends without a text-query surface. Dialect is empty,
 * so the frontend hides the Query nav button entirely — [execute] should never
 * be called and throws if it is.
 *
 * Used by both InMemory and MySQL (which lacks a runtime query surface in this
 * iteration); a future MysqlQueryExecutor can replace it.
 */
class InMemoryQueryExecutor : QueryExecutor {
    override fun dialect(): String = ""

    override fun execute(query: String): TableData =
        error("Query is not supported on this backend")
}
