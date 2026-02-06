package com.seanshubin.vote.integration.fake

import com.seanshubin.vote.contract.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration

class FakeClock(private var currentTime: Instant = Instant.parse("2024-01-01T00:00:00Z")) : Clock {
    override fun now(): Instant = currentTime

    fun advance(duration: Duration) {
        currentTime = currentTime.plus(duration)
    }

    fun set(time: Instant) {
        currentTime = time
    }
}
