package com.seanshubin.vote.integration.database

import com.seanshubin.vote.contract.CommandModel
import com.seanshubin.vote.contract.EventLog
import com.seanshubin.vote.contract.QueryModel

interface DatabaseProvider : AutoCloseable {
    val name: String
    val eventLog: EventLog
    val commandModel: CommandModel
    val queryModel: QueryModel
}
