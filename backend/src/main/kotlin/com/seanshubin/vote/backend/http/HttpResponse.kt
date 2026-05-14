package com.seanshubin.vote.backend.http

/**
 * Runtime-agnostic representation of an outbound HTTP response. Returned by
 * [RequestRouter]; written back to the wire by adapters (Jetty servlet,
 * Lambda APIGW response).
 */
data class HttpResponse(
    val status: Int,
    val body: String,
    val contentType: String = "application/json",
    val setCookies: List<SetCookie> = emptyList(),
    /**
     * Extra response headers beyond Content-Type and Set-Cookie. The Discord
     * OAuth callback uses this for `Location` on a 302 back to the frontend
     * after stamping the refresh cookie.
     */
    val headers: Map<String, String> = emptyMap(),
)
