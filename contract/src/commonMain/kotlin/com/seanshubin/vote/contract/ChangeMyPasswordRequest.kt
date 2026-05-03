package com.seanshubin.vote.contract

import kotlinx.serialization.Serializable

/**
 * Body for PUT /user/me/password — the authenticated user changes their
 * own password. The old password is verified server-side as a guard
 * against an attacker walking up to an unattended browser session.
 */
@Serializable
data class ChangeMyPasswordRequest(val oldPassword: String, val newPassword: String)

/**
 * Body for PUT /admin/user/{userName}/password — an admin (role > target.role,
 * with MANAGE_USERS) sets another user's password directly. There's no
 * old-password field by design: the whole point is recovery for a user
 * who has forgotten theirs. The admin tells them the new password
 * out-of-band; the user is expected to change it after logging in.
 */
@Serializable
data class AdminSetPasswordRequest(val newPassword: String)
