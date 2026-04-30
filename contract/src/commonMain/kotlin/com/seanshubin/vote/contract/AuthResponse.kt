package com.seanshubin.vote.contract

import com.seanshubin.vote.domain.Role
import kotlinx.serialization.Serializable

/**
 * Wire-level auth response for register / authenticate / refresh.
 * The refresh token never appears in the body — it's set as an
 * HttpOnly cookie by the server and never observed by the SPA.
 *
 * @property accessToken signed JWT for the Authorization header
 * @property userName logged-in user (avoids the SPA having to parse the JWT)
 * @property role current role (likewise — surfaced for UI without parsing JWT)
 */
@Serializable
data class AuthResponse(
    val accessToken: String,
    val userName: String,
    val role: Role,
)
