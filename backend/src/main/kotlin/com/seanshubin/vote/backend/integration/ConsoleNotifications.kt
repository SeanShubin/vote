package com.seanshubin.vote.backend.integration

import com.seanshubin.vote.contract.Notifications

object ConsoleNotifications : Notifications {
    override fun databaseEvent(name: String, statement: String) {
        println("[$name] $statement")
    }

    override fun httpRequestEvent(method: String, path: String) {
        println("HTTP Request: $method $path")
    }

    override fun httpResponseEvent(method: String, path: String, status: Int) {
        println("HTTP Response: $method $path -> $status")
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
}
