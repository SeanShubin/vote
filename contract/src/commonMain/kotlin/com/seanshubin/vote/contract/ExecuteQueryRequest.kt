package com.seanshubin.vote.contract

import kotlinx.serialization.Serializable

@Serializable
data class ExecuteQueryRequest(val query: String)
