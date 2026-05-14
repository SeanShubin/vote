package com.seanshubin.vote.contract

import kotlinx.serialization.Serializable

/**
 * Which login methods this environment offers. Fetched unauthenticated by the
 * login page so it can decide what to render.
 *
 * [devLoginEnabled] is true only on local dev runs (the Jetty entry point's
 * dev configuration). Production — which always boots through the Lambda
 * configuration — reports false, so the dev-login UI never appears there and
 * the dev-login endpoints reject every request with UNSUPPORTED.
 */
@Serializable
data class LoginConfig(
    val devLoginEnabled: Boolean,
)
