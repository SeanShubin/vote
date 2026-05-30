package com.seanshubin.vote.integration

import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.integration.database.DynamoDBDatabaseProvider
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Append-only guarantees of [com.seanshubin.vote.backend.repository.DynamoDbEventLog]
 * against a real (LocalStack) DynamoDB.
 *
 * These are DynamoDB-specific because the id is derived from the log itself
 * (`max(event_id) + 1`) and the write is guarded by `attribute_not_exists` —
 * behavior the in-memory and SQL logs don't share. The properties under test:
 *   1. ids are a contiguous 1..N (the log allocates them; no separate counter),
 *   2. concurrent appends never overwrite — every event survives, ids stay
 *      distinct and gap-free, which is the append-only guarantee under
 *      contention (a lost race retries rather than clobbering).
 */
class DynamoDbEventLogAppendOnlyTest {

    private fun electionCreated(i: Int): DomainEvent =
        DomainEvent.ElectionCreated(ownerName = "tester", electionName = "E$i", description = "")

    @Test
    fun `sequential appends receive a contiguous 1 to N id sequence`() {
        DynamoDBDatabaseProvider().use { provider ->
            val log = provider.eventLog
            repeat(5) { i -> log.appendEvent("tester", Clock.System.now(), electionCreated(i)) }

            val ids = log.eventsToSync(0).map { it.eventId }
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ids, "the log allocates ids itself, contiguously from 1")
            assertEquals(5, log.eventCount())
        }
    }

    @Test
    fun `concurrent appends never overwrite — every event survives with a distinct id`() {
        DynamoDBDatabaseProvider().use { provider ->
            val log = provider.eventLog
            val count = 16

            // Fire all appends at once. Two appenders can read the same max and
            // target the same id; the conditional write lets one win and the
            // loser retries with a fresh max. None may be lost or overwritten.
            val threads = (1..count).map { i ->
                Thread { log.appendEvent("tester", Clock.System.now(), electionCreated(i)) }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }

            val ids = log.eventsToSync(0).map { it.eventId }
            assertEquals(count, ids.size, "every concurrent append must persist — none overwritten")
            assertEquals(count, ids.toSet().size, "ids must be distinct — no reuse")
            assertEquals(
                (1L..count.toLong()).toList(),
                ids.sorted(),
                "ids must be a gap-free 1..N — a lost race retries, it doesn't burn or skip an id",
            )
        }
    }
}
