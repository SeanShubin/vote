package com.seanshubin.vote.backend.integration

import com.seanshubin.vote.contract.Clock
import com.seanshubin.vote.contract.Integrations
import com.seanshubin.vote.contract.Notifications
import com.seanshubin.vote.contract.UniqueIdGenerator

object ProductionIntegrations : Integrations {
    override val clock: Clock = SystemClock
    override val uniqueIdGenerator: UniqueIdGenerator = UUIDGenerator
    override val notifications: Notifications = ConsoleNotifications
}
