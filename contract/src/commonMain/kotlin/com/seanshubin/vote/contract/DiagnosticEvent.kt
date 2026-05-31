package com.seanshubin.vote.contract

import kotlinx.serialization.Serializable

/**
 * One entry in the in-app diagnostics ring buffer. Flat shape (with a
 * [kind] discriminator and nullable per-kind fields) so the wire format
 * stays trivial across platforms — no polymorphic serializer registration.
 *
 * [isError] is precomputed so the UI can sort and filter without
 * re-deriving it from kind + status.
 */
@Serializable
data class DiagnosticEvent(
    val sequence: Long,
    val timestamp: String,
    val kind: DiagnosticKind,
    val isError: Boolean,
    val method: String? = null,
    val path: String? = null,
    val routePattern: String? = null,
    val status: Int? = null,
    val durationMs: Long? = null,
    val dbCalls: Int? = null,
    val message: String? = null,
    val stackTrace: String? = null,
    val exceptionSource: String? = null,
    val clientUrl: String? = null,
    val userAgent: String? = null,
    val clientTimestamp: String? = null,
)

@Serializable
enum class DiagnosticKind {
    HTTP_RESPONSE,
    SERVER_EXCEPTION,
    CLIENT_ERROR,
}
