package com.seanshubin.vote.contract

import com.seanshubin.vote.domain.TableData

/**
 * Backend-agnostic admin entry point for ad-hoc text queries.
 *
 * [dialect] names the query language this backend speaks (e.g. "PartiQL",
 * "SQL"). An empty string means the backend does not expose a text query
 * surface — the frontend hides the Query nav button in that case rather than
 * routing the user to a page that can only throw.
 *
 * Gated by VIEW_SECRETS at the service layer; keep the interface itself lean.
 */
interface QueryExecutor {
    fun dialect(): String
    fun execute(query: String): TableData
}
