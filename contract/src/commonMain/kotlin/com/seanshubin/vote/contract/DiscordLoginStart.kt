package com.seanshubin.vote.contract

import kotlinx.serialization.Serializable

/**
 * URL the browser should be redirected to in order to start Discord OAuth,
 * paired with the random [state] string. The state must be persisted (HTTP
 * layer sets it as an HttpOnly cookie) and verified on the callback to
 * defend against CSRF / login-fixation.
 */
@Serializable
data class DiscordLoginStart(
    val authorizeUrl: String,
    val state: String,
)
