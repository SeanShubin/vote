package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable

@Serializable
data class UserRole(val userName: String, val role: Role)
