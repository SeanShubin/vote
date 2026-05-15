package com.seanshubin.vote.contract

interface Notifications {
    fun databaseEvent(name: String, statement: String)
    fun httpRequestEvent(method: String, path: String)
    fun httpResponseEvent(method: String, path: String, status: Int)
    fun serviceRequestEvent(name: String, request: String)
    fun serviceResponseEvent(name: String, request: String, response: String)
    fun topLevelException(message: String, stackTrace: String)
    fun sqlException(name: String, sqlCode: String, message: String)
    fun sendMailEvent(to: String, subject: String)

    /** Unrecoverable exception while routing an HTTP request — returns a 500. */
    fun unhandledHttpException(method: String, path: String, message: String, stackTrace: String)

    /** A frontend client reported an uncaught runtime error to /log-client-error. */
    fun clientErrorReported(
        message: String,
        url: String,
        userAgent: String,
        stackTrace: String?,
        timestamp: String,
    )
}
