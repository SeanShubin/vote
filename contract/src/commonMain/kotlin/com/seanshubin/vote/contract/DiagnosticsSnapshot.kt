package com.seanshubin.vote.contract

import kotlinx.serialization.Serializable

/**
 * Snapshot returned by GET /admin/diagnostics.
 *
 * [events] is the current ring-buffer contents in newest-first order so the
 * UI can render directly. [capacity] is the configured ring size;
 * [droppedSinceStart] counts events evicted because the ring was full — a
 * non-zero value means the panel is missing the oldest activity.
 */
@Serializable
data class DiagnosticsSnapshot(
    val events: List<DiagnosticEvent>,
    val capacity: Int,
    val droppedSinceStart: Long,
)
