package com.seanshubin.vote.backend.integration

import com.seanshubin.vote.contract.Clock
import com.seanshubin.vote.contract.DiagnosticEvent
import com.seanshubin.vote.contract.DiagnosticKind
import com.seanshubin.vote.contract.DiagnosticsSnapshot
import com.seanshubin.vote.contract.DiagnosticsSource
import com.seanshubin.vote.contract.Notifications

/**
 * Decorator that records every error-shaped event and every HTTP response
 * into a bounded in-memory ring buffer, then forwards to [delegate]. The
 * outermost layer in the notifications chain, so the buffer captures
 * everything even when an inner decorator (SES, Console) is misbehaving.
 *
 * Buffered kinds:
 *  - HTTP responses (every request, including 2xx — used for timing/route view)
 *  - Server exceptions (top-level, unhandled-http, sql)
 *  - Client errors reported via /log-client-error
 *
 * Not buffered (would drown signal in volume):
 *  - databaseEvent, httpRequestEvent, serviceRequestEvent, serviceResponseEvent,
 *    sendMailEvent, deployedVersionsReported
 *
 * Per-request db-call count is tracked locally (own ThreadLocal) so we
 * don't reach into the delegate's private state — both this layer and
 * ConsoleNotifications maintain their own counter and stay in sync via
 * the call order that Notifications guarantees.
 *
 * Process-local: each JVM (and on Lambda, each container) has its own
 * buffer. A restart wipes it.
 */
class BufferedNotifications(
    private val delegate: Notifications,
    private val clock: Clock,
    private val capacity: Int = DEFAULT_CAPACITY,
) : Notifications by delegate, DiagnosticsSource {

    private val lock = Any()
    private val buffer = ArrayDeque<DiagnosticEvent>(capacity)
    private var nextSequence: Long = 0
    private var dropped: Long = 0
    private val dbCallCount = ThreadLocal.withInitial { 0 }

    override fun databaseEvent(name: String, statement: String) {
        dbCallCount.set(dbCallCount.get() + 1)
        delegate.databaseEvent(name, statement)
    }

    override fun httpRequestEvent(method: String, path: String) {
        dbCallCount.set(0)
        delegate.httpRequestEvent(method, path)
    }

    override fun httpResponseEvent(
        method: String,
        path: String,
        routePattern: String,
        status: Int,
        durationMs: Long,
    ) {
        record(
            DiagnosticEvent(
                sequence = 0,
                timestamp = "",
                kind = DiagnosticKind.HTTP_RESPONSE,
                isError = status >= 500,
                method = method,
                path = path,
                routePattern = routePattern,
                status = status,
                durationMs = durationMs,
                dbCalls = dbCallCount.get(),
            )
        )
        delegate.httpResponseEvent(method, path, routePattern, status, durationMs)
    }

    override fun topLevelException(message: String, stackTrace: String) {
        record(
            DiagnosticEvent(
                sequence = 0,
                timestamp = "",
                kind = DiagnosticKind.SERVER_EXCEPTION,
                isError = true,
                message = message,
                stackTrace = stackTrace,
                exceptionSource = "top-level",
            )
        )
        delegate.topLevelException(message, stackTrace)
    }

    override fun unhandledHttpException(method: String, path: String, message: String, stackTrace: String) {
        record(
            DiagnosticEvent(
                sequence = 0,
                timestamp = "",
                kind = DiagnosticKind.SERVER_EXCEPTION,
                isError = true,
                method = method,
                path = path,
                message = message,
                stackTrace = stackTrace,
                exceptionSource = "unhandled-http",
            )
        )
        delegate.unhandledHttpException(method, path, message, stackTrace)
    }

    override fun sqlException(name: String, sqlCode: String, message: String) {
        record(
            DiagnosticEvent(
                sequence = 0,
                timestamp = "",
                kind = DiagnosticKind.SERVER_EXCEPTION,
                isError = true,
                message = "[$name] code=$sqlCode: $message",
                exceptionSource = "sql",
            )
        )
        delegate.sqlException(name, sqlCode, message)
    }

    override fun clientErrorReported(
        message: String,
        url: String,
        userAgent: String,
        stackTrace: String?,
        timestamp: String,
    ) {
        record(
            DiagnosticEvent(
                sequence = 0,
                timestamp = "",
                kind = DiagnosticKind.CLIENT_ERROR,
                isError = true,
                message = message,
                stackTrace = stackTrace,
                clientUrl = url,
                userAgent = userAgent,
                clientTimestamp = timestamp,
            )
        )
        delegate.clientErrorReported(message, url, userAgent, stackTrace, timestamp)
    }

    override fun snapshot(): DiagnosticsSnapshot = synchronized(lock) {
        // Reverse so newest is first — matches how the admin UI wants to render.
        DiagnosticsSnapshot(
            events = buffer.reversed(),
            capacity = capacity,
            droppedSinceStart = dropped,
        )
    }

    private fun record(skeleton: DiagnosticEvent) {
        // Stamp sequence + timestamp inside the lock so concurrent producers
        // can't interleave a higher sequence with an earlier timestamp.
        synchronized(lock) {
            val stamped = skeleton.copy(
                sequence = nextSequence++,
                timestamp = clock.now().toString(),
            )
            if (buffer.size >= capacity) {
                buffer.removeFirst()
                dropped++
            }
            buffer.addLast(stamped)
        }
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 1000
    }
}
