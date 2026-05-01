package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable

/**
 * Snapshot of a user's footprint in the system. Used by the SPA to drive the
 * Home page's role-and-activity indicator and to populate the delete-account
 * confirmation ("you'll be deleting N elections you own and M ballots you've
 * cast"). Counts come from the projection at request time, so they reflect
 * current state regardless of any role changes since the access token was
 * issued.
 */
@Serializable
data class UserActivity(
    val userName: String,
    val role: Role,
    val electionsOwnedCount: Int,
    val ballotsCastCount: Int,
)
