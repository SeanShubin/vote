package com.seanshubin.vote.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * EventEnvelope wraps a DomainEvent with metadata for the append-only event
 * log. All state changes are expressed as events; projections rebuild state
 * by replaying them.
 */
@Serializable
data class EventEnvelope(
    val eventId: Long,
    val whenHappened: Instant,
    val authority: String,
    val event: DomainEvent
)
