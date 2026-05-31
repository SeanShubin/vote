package com.seanshubin.vote.contract

/**
 * Read side of the diagnostics ring buffer. The recorder ([Notifications]
 * decorator) and the reader (admin HTTP handler) are split so the router
 * receives only what it needs — a snapshot, no mutation surface.
 */
interface DiagnosticsSource {
    fun snapshot(): DiagnosticsSnapshot
}
