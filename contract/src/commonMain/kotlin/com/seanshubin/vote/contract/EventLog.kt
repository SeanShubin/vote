package com.seanshubin.vote.contract

import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.domain.EventEnvelope
import kotlinx.datetime.Instant

interface EventLog {
    fun appendEvent(authority: String, whenHappened: Instant, event: DomainEvent)
    fun eventsToSync(lastEventSynced: Long): List<EventEnvelope>
    fun eventCount(): Int
}
