package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable

@Serializable
data class TableData(val name: String, val columnNames: List<String>, val rows: List<List<String?>>)
