package com.seanshubin.vote.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * EventEnvelope wraps a DomainEvent with metadata for storage in the event log.
 *
 * This is the structure stored in DynamoDB:
 * - PK = eventId (sequential)
 * - SK = whenHappened (timestamp for ordering)
 * - payload = JSON serialized EventEnvelope
 *
 * The event log is append-only. All state changes are expressed as events.
 * Both projections (MySQL + DynamoDB) rebuild state by replaying events.
 */
@Serializable
data class EventEnvelope(
    val eventId: Long,
    val whenHappened: Instant,
    val authority: String,
    val event: DomainEvent
)
