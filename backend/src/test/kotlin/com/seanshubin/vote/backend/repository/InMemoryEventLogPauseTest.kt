package com.seanshubin.vote.backend.repository

import com.seanshubin.vote.contract.EventLogPausedException
import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.domain.Role
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InMemoryEventLogPauseTest {

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val anyEvent = DomainEvent.UserRegistered("alice", Role.VOTER)

    @Test
    fun `appendEvent succeeds when not paused`() {
        val log = InMemoryEventLog()
        log.appendEvent("system", now, anyEvent)
        assertEquals(1, log.eventCount())
    }

    @Test
    fun `appendEvent throws EventLogPausedException when paused`() {
        val log = InMemoryEventLog()
        log.setPaused(true)
        assertFailsWith<EventLogPausedException> {
            log.appendEvent("system", now, anyEvent)
        }
        assertEquals(0, log.eventCount())
    }

    @Test
    fun `appendEvent resumes succeeding after resume`() {
        val log = InMemoryEventLog()
        log.setPaused(true)
        log.setPaused(false)
        log.appendEvent("system", now, anyEvent)
        assertEquals(1, log.eventCount())
    }

    @Test
    fun `isPaused defaults to false then tracks setPaused`() {
        val log = InMemoryEventLog()
        assertFalse(log.isPaused())
        log.setPaused(true)
        assertTrue(log.isPaused())
        log.setPaused(false)
        assertFalse(log.isPaused())
    }
}
