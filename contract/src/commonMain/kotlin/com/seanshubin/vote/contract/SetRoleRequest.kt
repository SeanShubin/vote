package com.seanshubin.vote.contract

import com.seanshubin.vote.domain.Role
import kotlinx.serialization.Serializable

@Serializable
data class SetRoleRequest(
    val role: Role
)
