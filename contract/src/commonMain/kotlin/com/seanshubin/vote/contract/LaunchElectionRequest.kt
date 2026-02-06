package com.seanshubin.vote.contract

import kotlinx.serialization.Serializable

@Serializable
data class LaunchElectionRequest(
    val allowEdit: Boolean
)
