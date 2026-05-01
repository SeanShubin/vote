package com.seanshubin.vote.contract

import kotlinx.serialization.Serializable

@Serializable
data class WipeTestUsersResult(
    val usersDeleted: Int,
    val electionsDeleted: Int,
)
