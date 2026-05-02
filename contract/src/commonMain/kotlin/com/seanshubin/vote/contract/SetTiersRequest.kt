package com.seanshubin.vote.contract

import kotlinx.serialization.Serializable

@Serializable
data class SetTiersRequest(
    val tierNames: List<String>
)
