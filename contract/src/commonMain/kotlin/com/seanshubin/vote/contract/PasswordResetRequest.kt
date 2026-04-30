package com.seanshubin.vote.contract

import kotlinx.serialization.Serializable

/** Body for POST /password-reset-request — kicks off the email flow. */
@Serializable
data class PasswordResetRequestRequest(val nameOrEmail: String)

/** Body for POST /password-reset — completes the flow with the token from the email. */
@Serializable
data class PasswordResetRequest(val resetToken: String, val newPassword: String)
