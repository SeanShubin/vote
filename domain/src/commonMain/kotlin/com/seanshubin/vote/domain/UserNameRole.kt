package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable

@Serializable
data class UserNameRole(val userName: String, val role: Role, val allowedRoles: List<Role>)
