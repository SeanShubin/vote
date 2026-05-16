package com.seanshubin.vote.backend.repository

import com.seanshubin.vote.contract.EventLog
import com.seanshubin.vote.contract.EventLogPausedException
import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.domain.EventEnvelope
import kotlinx.datetime.Instant

class InMemoryEventLog : EventLog {
    private val events = mutableListOf<EventEnvelope>()
    private var nextId: Long = 1
    private var paused: Boolean = false

    override fun appendEvent(authority: String, whenHappened: Instant, event: DomainEvent) {
        if (paused) throw EventLogPausedException()
        val envelope = EventEnvelope(
            eventId = nextId++,
            whenHappened = whenHappened,
            authority = authority,
            event = event
        )
        events.add(envelope)
    }

    override fun eventsToSync(lastEventSynced: Long): List<EventEnvelope> {
        return events.filter { it.eventId > lastEventSynced }
    }

    override fun eventCount(): Int {
        return events.size
    }

    override fun setPaused(paused: Boolean) {
        this.paused = paused
    }

    override fun isPaused(): Boolean = paused
}
