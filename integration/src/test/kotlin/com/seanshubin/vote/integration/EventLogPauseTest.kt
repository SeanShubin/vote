package com.seanshubin.vote.integration

import com.seanshubin.vote.backend.service.ServiceException
import com.seanshubin.vote.contract.EventLogPausedException
import com.seanshubin.vote.domain.Role
import com.seanshubin.vote.integration.dsl.TestContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventLogPauseTest {

    @Test
    fun `owner can pause and resume the event log`() {
        val testContext = TestContext()
        val owner = testContext.registerUser("alice")  // First registered user becomes OWNER

        assertFalse(testContext.backend.isEventLogPaused())

        testContext.backend.pauseEventLog(owner.accessToken)
        assertTrue(testContext.backend.isEventLogPaused())

        testContext.backend.resumeEventLog(owner.accessToken)
        assertFalse(testContext.backend.isEventLogPaused())
    }

    @Test
    fun `non-owner cannot pause the event log`() {
        val testContext = TestContext()
        testContext.registerUser("alice")  // First user → OWNER, but we'll act as bob
        val bob = testContext.registerUser("bob")  // VOTER

        val ex = assertFailsWith<ServiceException> {
            testContext.backend.pauseEventLog(bob.accessToken)
        }
        assertEquals(ServiceException.Category.UNAUTHORIZED, ex.category)
        assertFalse(testContext.backend.isEventLogPaused())
    }

    @Test
    fun `ADMIN cannot pause the event log — owner-only is strictly OWNER`() {
        val testContext = TestContext()
        val owner = testContext.registerUser("alice")
        testContext.registerUser("bob")
        // Promote bob to ADMIN. The pause guard is strict OWNER, not "OWNER or
        // higher", because there is no role higher than OWNER and ADMIN
        // shouldn't be able to interfere with a maintenance window.
        owner.setRole("bob", Role.ADMIN)
        val bobAsAdmin = testContext.reissueToken("bob")

        val ex = assertFailsWith<ServiceException> {
            testContext.backend.pauseEventLog(bobAsAdmin.accessToken)
        }
        assertEquals(ServiceException.Category.UNAUTHORIZED, ex.category)
    }

    @Test
    fun `event-producing service calls fail with EventLogPausedException while paused`() {
        val testContext = TestContext()
        val owner = testContext.registerUser("alice")
        testContext.backend.pauseEventLog(owner.accessToken)

        // createElection appends an ElectionCreated event — must fail.
        assertFailsWith<EventLogPausedException> {
            owner.createElection("Favorite Color")
        }
    }

    @Test
    fun `read endpoints still work while paused`() {
        val testContext = TestContext()
        val owner = testContext.registerUser("alice")
        owner.createElection("Favorite Color")

        testContext.backend.pauseEventLog(owner.accessToken)

        // Pure-read endpoints append nothing — they must keep working so the
        // app stays browsable during the maintenance window.
        assertEquals(1, testContext.electionCount())
        assertEquals(1, testContext.userCount())
        assertEquals(listOf("Favorite Color"), owner.listElections().map { it.electionName })
    }

    @Test
    fun `resume restores write capability`() {
        val testContext = TestContext()
        val owner = testContext.registerUser("alice")

        testContext.backend.pauseEventLog(owner.accessToken)
        assertFailsWith<EventLogPausedException> { owner.createElection("E1") }

        testContext.backend.resumeEventLog(owner.accessToken)
        // Same operation must succeed once the owner resumes.
        owner.createElection("E2")
        assertEquals(1, testContext.electionCount())
    }
}
