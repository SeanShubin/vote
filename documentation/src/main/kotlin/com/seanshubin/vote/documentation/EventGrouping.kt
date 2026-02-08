package com.seanshubin.vote.documentation

import com.seanshubin.vote.domain.EventEnvelope

/**
 * Groups HTTP calls and events from DocumentationRecorder by events.
 * Each event becomes a section containing the HTTP calls that led to it plus the event itself.
 */
object EventGrouping {
    data class EventSection(
        val event: EventEnvelope,
        val httpCalls: List<HttpExchange>
    )

    fun groupByEvents(entries: List<DocumentationEntry>): List<EventSection> {
        val sections = mutableListOf<EventSection>()
        var currentHttpCalls = mutableListOf<HttpExchange>()

        for (entry in entries) {
            when (entry) {
                is DocumentationEntry.HttpCall -> {
                    currentHttpCalls.add(entry.exchange)
                }
                is DocumentationEntry.Event -> {
                    // Event completes a section
                    sections.add(EventSection(entry.envelope, currentHttpCalls.toList()))
                    currentHttpCalls = mutableListOf()
                }
                is DocumentationEntry.SectionMarker -> {
                    // Ignore - we derive sections from events
                }
            }
        }

        return sections
    }
}
