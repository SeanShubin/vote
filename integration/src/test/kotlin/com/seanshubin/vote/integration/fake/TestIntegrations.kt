package com.seanshubin.vote.integration.fake

import com.seanshubin.vote.contract.*

class TestIntegrations(
    override val commandLineArgs: Array<String> = emptyArray(),
    private val emitLineCapture: MutableList<String> = mutableListOf(),
    override val clock: Clock = FakeClock(),
    override val uniqueIdGenerator: UniqueIdGenerator = SequentialIdGenerator(),
    override val notifications: Notifications = FakeNotifications(),
) : Integrations {
    override val emitLine: (String) -> Unit = { line -> emitLineCapture.add(line) }
    override val getEnv: (String) -> String? = { null }

    val fakeClock: FakeClock
        get() = clock as FakeClock

    val sequentialIdGenerator: SequentialIdGenerator
        get() = uniqueIdGenerator as SequentialIdGenerator

    val fakeNotifications: FakeNotifications
        get() = notifications as FakeNotifications

    val emittedLines: List<String>
        get() = emitLineCapture
}
