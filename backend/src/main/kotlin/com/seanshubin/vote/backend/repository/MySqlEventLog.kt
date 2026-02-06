package com.seanshubin.vote.backend.repository

import com.seanshubin.vote.contract.EventLog
import com.seanshubin.vote.contract.QueryLoader
import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.domain.EventEnvelope
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp

class MySqlEventLog(
    private val connection: Connection,
    private val queryLoader: QueryLoader,
    private val json: Json
) : EventLog {
    override fun appendEvent(authority: String, whenHappened: Instant, event: DomainEvent) {
        val eventType = event::class.simpleName ?: "Unknown"
        val eventData = json.encodeToString(event)
        val sql = queryLoader.load("event-log-insert")

        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, authority)
            stmt.setString(2, eventType)
            stmt.setString(3, eventData)
            stmt.setTimestamp(4, Timestamp(whenHappened.toEpochMilliseconds()))
            stmt.executeUpdate()
        }
    }

    override fun eventsToSync(lastEventSynced: Long): List<EventEnvelope> {
        val sql = queryLoader.load("event-log-select-to-sync")

        return connection.prepareStatement(sql).use { stmt ->
            stmt.setLong(1, lastEventSynced)
            val resultSet = stmt.executeQuery()
            buildList {
                while (resultSet.next()) {
                    add(resultSet.toEventEnvelope())
                }
            }
        }
    }

    override fun eventCount(): Int {
        val sql = queryLoader.load("event-log-count")
        return connection.prepareStatement(sql).use { stmt ->
            val rs = stmt.executeQuery()
            if (rs.next()) {
                rs.getInt("count")
            } else {
                0
            }
        }
    }

    private fun ResultSet.toEventEnvelope(): EventEnvelope {
        val eventId = getLong("event_id")
        val whenHappened = Instant.fromEpochMilliseconds(getTimestamp("created_at").time)
        val authority = getString("authority")
        val eventData = getString("event_data")
        val event = json.decodeFromString<DomainEvent>(eventData)

        return EventEnvelope(
            eventId = eventId,
            whenHappened = whenHappened,
            authority = authority,
            event = event
        )
    }
}
