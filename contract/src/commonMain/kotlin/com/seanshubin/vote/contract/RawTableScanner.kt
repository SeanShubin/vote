package com.seanshubin.vote.contract

import com.seanshubin.vote.domain.TableData

/**
 * Backend-agnostic admin scan over physical storage.
 *
 * Only meaningful for backends that have a "physical table" notion (DynamoDB).
 * The InMemory backend reports an empty list and rejects scans — local dev uses
 * the relational projection (debugTableData) instead.
 *
 * Gated by VIEW_SECRETS at the service layer; keep the interface itself lean.
 */
interface RawTableScanner {
    fun listRawTableNames(): List<String>
    fun scanRawTable(tableName: String): TableData
}
