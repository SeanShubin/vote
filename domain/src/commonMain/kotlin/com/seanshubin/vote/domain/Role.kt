package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable

@Serializable
enum class Role(val description: String) {
    NO_ACCESS("Waiting for ADMIN to promote"),
    OBSERVER("Can navigate the application"),
    VOTER("Can vote, can do anything an OBSERVER can do"),
    USER("Can create elections, can do anything a VOTER can do"),
    ADMIN("Can manage users, can do anything a USER can do"),
    AUDITOR("Can see secrets, can do anything a ADMIN can do"),
    OWNER("Only 1 owner, can transfer OWNER to another user, can do anything AUDITOR can do");

    companion object {
        val PRIMARY_ROLE = OWNER
        val SECONDARY_ROLE = AUDITOR
        val DEFAULT_ROLE = USER
    }
}
