package com.seanshubin.vote.backend.integration

import com.seanshubin.vote.contract.Clock
import kotlinx.datetime.Instant

object SystemClock : Clock {
    override fun now(): Instant = kotlinx.datetime.Clock.System.now()
}
