package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable

@Serializable
data class UserNameEmail(val name: String, val email: String) {
    companion object {
        fun User.toUserNameEmail(): UserNameEmail = UserNameEmail(name, email)
    }
}
