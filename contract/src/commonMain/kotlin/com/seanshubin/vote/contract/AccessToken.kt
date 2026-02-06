package com.seanshubin.vote.contract

import com.seanshubin.vote.domain.Role
import kotlinx.serialization.Serializable

@Serializable
data class AccessToken(val userName: String, val role: Role)
