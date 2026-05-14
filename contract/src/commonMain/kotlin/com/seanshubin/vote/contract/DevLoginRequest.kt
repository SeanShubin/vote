package com.seanshubin.vote.contract

import kotlinx.serialization.Serializable

/**
 * Body for the dev-only login endpoints (POST /auth/dev/login and
 * POST /auth/dev/create). Carries just the app username to log in as or
 * create. Dev-only — see [LoginConfig].
 */
@Serializable
data class DevLoginRequest(
    val userName: String,
)
