package com.seanshubin.vote.integration.dsl

import com.seanshubin.vote.contract.EventLog
import com.seanshubin.vote.domain.DomainEvent

class EventInspector(private val eventLog: EventLog) {
    inline fun <reified T : DomainEvent> ofType(): List<T> =
        all().filterIsInstance<T>()

    /**
     * Authority strings (the recorded actor) for every event of type [T] in
     * the order they were appended. Lets tests assert "alice triggered this
     * password change" without re-deriving it from the envelopes.
     */
    inline fun <reified T : DomainEvent> authoritiesOf(): List<String> =
        allEnvelopes().filter { it.event is T }.map { it.authority }

    fun last(): DomainEvent =
        eventLog.eventsToSync(0).last().event

    fun count(): Int =
        eventLog.eventCount()

    fun all(): List<DomainEvent> =
        eventLog.eventsToSync(0).map { it.event }

    fun allEnvelopes(): List<com.seanshubin.vote.domain.EventEnvelope> =
        eventLog.eventsToSync(0)
}
