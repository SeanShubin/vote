package com.seanshubin.vote.contract

import kotlinx.serialization.Serializable

/**
 * Snapshot returned by GET /admin/diagnostics.
 *
 * [containerId] identifies the process that served the snapshot. In prod
 * each Lambda container has its own buffer and the API gateway routes each
 * request to whatever container is warm, so two refreshes of the diagnostics
 * page can land on different containers and show different events. The
 * containerId lets the UI surface that fact honestly — when it changes
 * between refreshes, the operator knows they're now looking at a different
 * container's view, not at the same container with mysteriously lost data.
 *
 * On Lambda the id is the log stream name (which is per-container); locally
 * it's a UUID generated at process start.
 *
 * [events] is the current ring-buffer contents in newest-first order so the
 * UI can render directly. [capacity] is the configured ring size;
 * [droppedSinceStart] counts events evicted because the ring was full — a
 * non-zero value means the panel is missing the oldest activity.
 */
@Serializable
data class DiagnosticsSnapshot(
    val containerId: String,
    val events: List<DiagnosticEvent>,
    val capacity: Int,
    val droppedSinceStart: Long,
)
