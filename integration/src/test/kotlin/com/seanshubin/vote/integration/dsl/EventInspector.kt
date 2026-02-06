package com.seanshubin.vote.integration.dsl

import com.seanshubin.vote.contract.EventLog
import com.seanshubin.vote.domain.DomainEvent

class EventInspector(private val eventLog: EventLog) {
    inline fun <reified T : DomainEvent> ofType(): List<T> =
        all().filterIsInstance<T>()

    fun last(): DomainEvent =
        eventLog.eventsToSync(0).last().event

    fun count(): Int =
        eventLog.eventCount()

    fun all(): List<DomainEvent> =
        eventLog.eventsToSync(0).map { it.event }
}
