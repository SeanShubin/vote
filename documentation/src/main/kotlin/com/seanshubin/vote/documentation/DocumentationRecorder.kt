package com.seanshubin.vote.documentation

import com.seanshubin.vote.domain.EventEnvelope

/**
 * Records everything that happens during scenario execution in chronological order:
 * - Section markers (narrative structure)
 * - HTTP exchanges (API calls)
 * - Events (domain changes)
 */
class DocumentationRecorder {
    private val entries = mutableListOf<DocumentationEntry>()

    fun markSection(title: String, description: String) {
        entries.add(DocumentationEntry.SectionMarker(title, description))
    }

    fun recordHttp(exchange: HttpExchange) {
        entries.add(DocumentationEntry.HttpCall(exchange))
    }

    fun recordEvent(envelope: EventEnvelope) {
        entries.add(DocumentationEntry.Event(envelope))
    }

    fun getEntries(): List<DocumentationEntry> = entries.toList()
}

sealed class DocumentationEntry {
    data class SectionMarker(val title: String, val description: String) : DocumentationEntry()
    data class HttpCall(val exchange: HttpExchange) : DocumentationEntry()
    data class Event(val envelope: EventEnvelope) : DocumentationEntry()
}
