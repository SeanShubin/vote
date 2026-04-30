package com.seanshubin.vote.contract

/**
 * Outbound email. Implementations are configured per environment:
 *
 *  - Local dev / tests: log to stdout or capture in-memory.
 *  - Production: AWS SES via the Lambda execution role's `ses:SendEmail`
 *    permission — no SMTP credentials needed in code or config.
 *
 * Plain text only for now. Add an HTML overload if/when we need richer
 * formatting; password reset and login links are fine as plain text.
 */
interface EmailSender {
    fun send(to: String, subject: String, body: String)
}
