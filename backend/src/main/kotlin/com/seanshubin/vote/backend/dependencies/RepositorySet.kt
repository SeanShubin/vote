package com.seanshubin.vote.backend.dependencies

import com.seanshubin.vote.contract.CommandModel
import com.seanshubin.vote.contract.EventLog
import com.seanshubin.vote.contract.QueryModel
import com.seanshubin.vote.contract.RawTableScanner

data class RepositorySet(
    val eventLog: EventLog,
    val commandModel: CommandModel,
    val queryModel: QueryModel,
    val rawTableScanner: RawTableScanner,
)
