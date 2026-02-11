package com.seanshubin.vote.integration.fake

import com.seanshubin.vote.contract.*
import kotlinx.datetime.Instant

class TestIntegrations(
    override val commandLineArgs: Array<String> = emptyArray(),
    private val emitLineCapture: MutableList<String> = mutableListOf(),
    override val clock: Clock = FakeClock(),
    override val uniqueIdGenerator: UniqueIdGenerator = SequentialIdGenerator(),
    override val notifications: Notifications = FakeNotifications(),
    override val passwordUtil: PasswordUtil = FakePasswordUtil()
) : Integrations {
    override val emitLine: (String) -> Unit = { line -> emitLineCapture.add(line) }

    // Convenience accessors with proper types for test assertions
    val fakeClock: FakeClock
        get() = clock as FakeClock

    val sequentialIdGenerator: SequentialIdGenerator
        get() = uniqueIdGenerator as SequentialIdGenerator

    val fakeNotifications: FakeNotifications
        get() = notifications as FakeNotifications

    val fakePasswordUtil: FakePasswordUtil
        get() = passwordUtil as FakePasswordUtil

    val emittedLines: List<String>
        get() = emitLineCapture
}
