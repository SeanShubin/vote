package com.seanshubin.vote.backend.integration

import com.seanshubin.vote.backend.crypto.RealPasswordUtil
import com.seanshubin.vote.contract.*

class ProductionIntegrations(
    override val commandLineArgs: Array<String>,
    rawEmailSender: EmailSender = ConsoleEmailSender,
) : Integrations {
    // Outbound-mail suppression for test users now lives in the service
    // layer (ServiceImpl.requestPasswordReset checks TestUser.isTestUser),
    // so the integration layer no longer needs a wrapping sender.
    override val emailSender: EmailSender = rawEmailSender
    override val emitLine: (String) -> Unit = { line -> println(line) }
    override val clock: Clock = SystemClock
    override val uniqueIdGenerator: UniqueIdGenerator = UUIDGenerator
    override val notifications: Notifications = ConsoleNotifications
    override val passwordUtil: PasswordUtil = RealPasswordUtil
    override val getEnv: (String) -> String? = System::getenv
}
