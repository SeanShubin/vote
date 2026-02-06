package com.seanshubin.vote.integration.database

import com.seanshubin.vote.backend.repository.InMemoryCommandModel
import com.seanshubin.vote.backend.repository.InMemoryData
import com.seanshubin.vote.backend.repository.InMemoryEventLog
import com.seanshubin.vote.backend.repository.InMemoryQueryModel
import com.seanshubin.vote.contract.CommandModel
import com.seanshubin.vote.contract.EventLog
import com.seanshubin.vote.contract.QueryModel

class InMemoryDatabaseProvider : DatabaseProvider {
    override val name = "InMemory"

    private val data = InMemoryData()
    override val eventLog: EventLog = InMemoryEventLog()
    override val commandModel: CommandModel = InMemoryCommandModel(data)
    override val queryModel: QueryModel = InMemoryQueryModel(data)

    override fun close() {
        // Nothing to clean up for in-memory
    }

    override fun toString() = name
}
