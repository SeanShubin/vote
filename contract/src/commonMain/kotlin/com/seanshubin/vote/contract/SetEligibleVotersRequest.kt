package com.seanshubin.vote.contract

import kotlinx.serialization.Serializable

@Serializable
data class SetEligibleVotersRequest(
    val voterNames: List<String>
)
