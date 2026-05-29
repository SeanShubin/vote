package com.seanshubin.vote.backend.integration

import com.seanshubin.vote.contract.Notifications

object ConsoleNotifications : Notifications {
    // Per-request DB-call counter. ThreadLocal because Jetty serves requests
    // on a per-request thread and Lambda runs one invocation per container
    // thread — either way, no cross-request leakage. Reset at the start of
    // each request, drained into the response log line at the end.
    private val dbCallCount = ThreadLocal.withInitial { 0 }

    override fun databaseEvent(name: String, statement: String) {
        dbCallCount.set(dbCallCount.get() + 1)
        println("[$name] $statement")
    }

    override fun httpRequestEvent(method: String, path: String) {
        dbCallCount.set(0)
        println("HTTP Request: $method $path")
    }

    override fun httpResponseEvent(
        method: String,
        path: String,
        routePattern: String,
        status: Int,
        durationMs: Long,
    ) {
        val dbCalls = dbCallCount.get()
        println("HTTP Response: $method $path -> $status [route=$routePattern dur=${durationMs}ms db=$dbCalls]")
    }

    override fun serviceRequestEvent(name: String, request: String) {
        println("Service Request: $name")
        println("  Request: $request")
    }

    override fun serviceResponseEvent(name: String, request: String, response: String) {
        println("Service Response: $name")
        println("  Request: $request")
        println("  Response: $response")
    }

    override fun topLevelException(message: String, stackTrace: String) {
        System.err.println("Exception: $message")
        System.err.println(stackTrace)
    }

    override fun sqlException(name: String, sqlCode: String, message: String) {
        System.err.println("SQL Exception [$name] code=$sqlCode: $message")
    }

    override fun sendMailEvent(to: String, subject: String) {
        println("Sending email to $to: $subject")
    }

    override fun unhandledHttpException(method: String, path: String, message: String, stackTrace: String) {
        System.err.println("Unhandled exception: $method $path - $message")
        System.err.println(stackTrace)
    }

    override fun clientErrorReported(
        message: String,
        url: String,
        userAgent: String,
        stackTrace: String?,
        timestamp: String,
    ) {
        System.err.println(
            "CLIENT ERROR: $message\n" +
                "  URL: $url\n" +
                "  User-Agent: $userAgent\n" +
                "  Timestamp: $timestamp\n" +
                "  Stack trace: ${stackTrace ?: "none"}"
        )
    }

    override fun deployedVersionsReported(report: String) {
        println(report)
    }
}
