package com.seanshubin.vote.backend.repository

import com.seanshubin.vote.contract.RawTableScanner
import com.seanshubin.vote.domain.TableData

/**
 * No-op raw scanner for the InMemory backend. There is no physical "vote_data"
 * to expose, so the Raw Tables admin view is empty in local dev. Local dev
 * still has the relational projection (debugTableData), which is the more
 * useful view anyway.
 */
class InMemoryRawTableScanner : RawTableScanner {
    override fun listRawTableNames(): List<String> = emptyList()

    override fun scanRawTable(tableName: String): TableData =
        TableData(tableName, emptyList(), emptyList())
}
