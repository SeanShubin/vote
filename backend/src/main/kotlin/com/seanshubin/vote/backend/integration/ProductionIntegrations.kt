package com.seanshubin.vote.backend.integration

import com.seanshubin.vote.backend.dependencies.EnvVars
import com.seanshubin.vote.contract.*

class ProductionIntegrations(
    override val commandLineArgs: Array<String>,
) : Integrations {
    override val emitLine: (String) -> Unit = { line -> println(line) }
    override val clock: Clock = SystemClock
    override val uniqueIdGenerator: UniqueIdGenerator = UUIDGenerator
    override val getEnv: (String) -> String? = System::getenv

    // SES alert emails are opt-in via env: a deploy that doesn't set both
    // EMAIL_FROM_ADDRESS and EMAIL_TO_ADDRESS gets ConsoleNotifications-only
    // behavior (stderr logs, no email). Keeps local dev quiet and lets a
    // misconfigured prod deploy keep serving requests while just losing alerts.
    override val notifications: Notifications = run {
        val from = getEnv(EnvVars.EMAIL_FROM_ADDRESS)?.takeIf { it.isNotBlank() }
        val to = getEnv(EnvVars.EMAIL_TO_ADDRESS)?.takeIf { it.isNotBlank() }
        val region = getEnv(EnvVars.AWS_REGION)?.takeIf { it.isNotBlank() } ?: "us-east-1"
        if (from != null && to != null) {
            SesNotifications(
                delegate = ConsoleNotifications,
                fromAddress = from,
                toAddress = to,
                region = region,
            )
        } else {
            ConsoleNotifications
        }
    }
}
