package com.seanshubin.vote.backend.http

/**
 * Runtime-agnostic representation of an inbound HTTP request. Built by
 * adapters (Jetty servlet, Lambda APIGW event) and routed by [RequestRouter].
 */
class HttpRequest(
    val method: String,
    val target: String,
    private val rawHeaders: Map<String, String>,
    val body: String,
) {
    /** Case-insensitive header lookup. */
    fun header(name: String): String? =
        rawHeaders.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /** Copy with a different target (used when the router strips a path prefix). */
    fun withTarget(newTarget: String): HttpRequest =
        HttpRequest(method, newTarget, rawHeaders, body)
}
