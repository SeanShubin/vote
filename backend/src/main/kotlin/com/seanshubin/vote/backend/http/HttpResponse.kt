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
)
