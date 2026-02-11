package com.seanshubin.vote.backend.integration

import com.seanshubin.vote.backend.crypto.RealPasswordUtil
import com.seanshubin.vote.contract.*

class ProductionIntegrations(
    override val commandLineArgs: Array<String>
) : Integrations {
    override val emitLine: (String) -> Unit = { line -> println(line) }
    override val clock: Clock = SystemClock
    override val uniqueIdGenerator: UniqueIdGenerator = UUIDGenerator
    override val notifications: Notifications = ConsoleNotifications
    override val passwordUtil: PasswordUtil = RealPasswordUtil
}
