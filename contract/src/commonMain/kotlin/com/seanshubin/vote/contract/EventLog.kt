package com.seanshubin.vote.contract

import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.domain.EventEnvelope
import kotlinx.datetime.Instant

interface EventLog {
    /**
     * Append [event] under [authority] at [whenHappened]. Throws
     * [EventLogPausedException] if the log is currently paused — the owner
     * pauses the log before a data migration so no new events can land in the
     * window between migration and deploy.
     */
    fun appendEvent(authority: String, whenHappened: Instant, event: DomainEvent)
    fun eventsToSync(lastEventSynced: Long): List<EventEnvelope>
    fun eventCount(): Int

    /**
     * Toggle the pause flag. State is persisted so it survives Lambda cold
     * starts and is visible to every concurrent instance — the owner pauses
     * before kicking off a migration+deploy and resumes once it's safe.
     */
    fun setPaused(paused: Boolean)

    /** Current pause state. Cheap — used by the frontend to render the banner. */
    fun isPaused(): Boolean
}

/**
 * Raised by [EventLog.appendEvent] when the owner has paused the log for a
 * maintenance window. Routed to HTTP 503 by the request router so the frontend
 * can show a "maintenance in progress" message rather than a generic failure.
 */
class EventLogPausedException : RuntimeException(
    "Event log is paused for maintenance — new writes are temporarily disabled",
)
