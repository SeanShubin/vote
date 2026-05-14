package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable

/**
 * Self-view (or admin-view) of a user. Currently only carries the username;
 * the type name is retained for wire compatibility with existing callers
 * even though the email field has been retired alongside password login.
 */
@Serializable
data class UserNameEmail(
    val name: String,
) {
    companion object {
        fun User.toUserNameEmail(): UserNameEmail = UserNameEmail(name)
    }
}
