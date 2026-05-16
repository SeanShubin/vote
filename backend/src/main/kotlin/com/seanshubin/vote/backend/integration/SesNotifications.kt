package com.seanshubin.vote.backend.integration

import aws.sdk.kotlin.services.ses.SesClient
import aws.sdk.kotlin.services.ses.model.Body
import aws.sdk.kotlin.services.ses.model.Content
import aws.sdk.kotlin.services.ses.model.Destination
import aws.sdk.kotlin.services.ses.model.Message
import aws.sdk.kotlin.services.ses.model.SendEmailRequest
import com.seanshubin.vote.contract.Notifications
import kotlinx.coroutines.runBlocking
import java.time.Instant

/**
 * Decorator that wraps a delegate [Notifications] and additionally emails
 * the four error-shaped events ([topLevelException], [unhandledHttpException],
 * [clientErrorReported], [sqlException]) so the recipient sees the full
 * context — message, stack trace, request info — directly in the email
 * rather than having to dig into CloudWatch.
 *
 * Email failures never propagate: if SES is down, IAM is misconfigured, or
 * the From identity isn't verified, the original failure still reaches the
 * delegate's stderr path unscathed. A broken alerter must not shadow the
 * thing it's trying to alert about.
 *
 * The non-error events ([databaseEvent], [httpRequestEvent], etc.) and the
 * outbound [sendMailEvent] hook are delegate-only — those produce volume,
 * not signal, and would drown the inbox.
 */
class SesNotifications(
    private val delegate: Notifications,
    private val fromAddress: String,
    private val toAddress: String,
    private val region: String,
    private val nowUtc: () -> Instant = Instant::now,
    private val sendEmail: (String, String) -> Unit = { subject, body ->
        defaultSendEmail(fromAddress, toAddress, region, subject, body)
    },
) : Notifications by delegate {

    override fun topLevelException(message: String, stackTrace: String) {
        delegate.topLevelException(message, stackTrace)
        safeEmail(
            subject = "Top-level exception",
            body = formatBody(
                kind = "TOP-LEVEL EXCEPTION",
                message = message,
                extras = emptyList(),
                stackTrace = stackTrace,
            ),
        )
    }

    override fun unhandledHttpException(method: String, path: String, message: String, stackTrace: String) {
        delegate.unhandledHttpException(method, path, message, stackTrace)
        safeEmail(
            subject = "Unhandled HTTP exception ($method $path)",
            body = formatBody(
                kind = "UNHANDLED HTTP EXCEPTION",
                message = message,
                extras = listOf("Method" to method, "Path" to path),
                stackTrace = stackTrace,
            ),
        )
    }

    override fun clientErrorReported(
        message: String,
        url: String,
        userAgent: String,
        stackTrace: String?,
        timestamp: String,
    ) {
        delegate.clientErrorReported(message, url, userAgent, stackTrace, timestamp)
        safeEmail(
            subject = "Frontend client error",
            body = formatBody(
                kind = "CLIENT ERROR",
                message = message,
                extras = listOf(
                    "URL" to url,
                    "User-Agent" to userAgent,
                    "Client timestamp" to timestamp,
                ),
                stackTrace = stackTrace ?: "(none)",
            ),
        )
    }

    override fun sqlException(name: String, sqlCode: String, message: String) {
        delegate.sqlException(name, sqlCode, message)
        safeEmail(
            subject = "SQL exception ($name)",
            body = formatBody(
                kind = "SQL EXCEPTION",
                message = message,
                extras = listOf("Statement name" to name, "SQL code" to sqlCode),
                stackTrace = "(not captured)",
            ),
        )
    }

    private fun safeEmail(subject: String, body: String) {
        try {
            sendEmail("[pairwisevote] ${subject.take(120)}", body)
        } catch (suppressed: Throwable) {
            System.err.println("SesNotifications: failed to send alert email (${suppressed.message}); original event already logged via delegate")
            suppressed.printStackTrace(System.err)
        }
    }

    private fun formatBody(
        kind: String,
        message: String,
        extras: List<Pair<String, String>>,
        stackTrace: String,
    ): String = buildString {
        appendLine(kind)
        appendLine("=".repeat(kind.length))
        appendLine()
        appendLine("Server time (UTC): ${nowUtc()}")
        appendLine("Message: $message")
        for ((label, value) in extras) {
            appendLine("$label: $value")
        }
        appendLine()
        appendLine("Stack trace:")
        appendLine(stackTrace)
    }

    companion object {
        private fun defaultSendEmail(
            from: String,
            to: String,
            region: String,
            subject: String,
            body: String,
        ) {
            runBlocking {
                SesClient { this.region = region }.use { ses ->
                    ses.sendEmail(
                        SendEmailRequest {
                            source = from
                            destination = Destination { toAddresses = listOf(to) }
                            message = Message {
                                this.subject = Content { data = subject }
                                this.body = Body { text = Content { data = body } }
                            }
                        }
                    )
                }
            }
        }
    }
}
