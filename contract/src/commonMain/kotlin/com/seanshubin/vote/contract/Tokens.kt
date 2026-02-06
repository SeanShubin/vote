package com.seanshubin.vote.contract

import kotlinx.serialization.Serializable

@Serializable
data class Tokens(val accessToken: AccessToken, val refreshToken: RefreshToken)
