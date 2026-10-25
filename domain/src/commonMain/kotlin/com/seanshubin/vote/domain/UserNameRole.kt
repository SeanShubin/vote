package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable

/**
 * Row shape returned by `listUsers` to admin UIs. Carries credential
 * info so the UI can show the Discord display name next to the username.
 * [discordDisplayName] empty means "no Discord credential on this user".
 * [discordDisplayName] alone (without id) would never happen — both come
 * from the same Discord callback and write atomically.
 *
 * [canRemove] and the two counts drive the delete-account affordance.
 * Like [allowedRoles], [canRemove] is the backend's verdict on the caller's
 * authority over this row, so the UI renders what the server says is legal
 * instead of re-deriving the rule. The counts describe what deleting this
 * user destroys — their own ballots, plus everything inside the elections
 * they own — so the confirmation can state the blast radius.
 */
@Serializable
data class UserNameRole(
    val userName: String,
    val role: Role,
    val allowedRoles: List<Role>,
    val discordId: String = "",
    val discordDisplayName: String = "",
    val canRemove: Boolean = false,
    val electionsOwnedCount: Int = 0,
    val ballotsCastCount: Int = 0,
)
