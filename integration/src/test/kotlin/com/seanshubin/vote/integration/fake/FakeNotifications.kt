package com.seanshubin.vote.integration.fake

import com.seanshubin.vote.contract.Notifications

class FakeNotifications : Notifications {
    val events = mutableListOf<String>()
    val sentMails = mutableListOf<Pair<String, String>>()

    override fun databaseEvent(name: String, statement: String) {
        events.add("database: $name - $statement")
    }

    override fun httpRequestEvent(method: String, path: String) {
        events.add("http-request: $method $path")
    }

    override fun httpResponseEvent(method: String, path: String, status: Int) {
        events.add("http-response: $method $path - $status")
    }

    override fun serviceRequestEvent(name: String, request: String) {
        events.add("service-request: $name - $request")
    }

    override fun serviceResponseEvent(name: String, request: String, response: String) {
        events.add("service-response: $name - $request -> $response")
    }

    override fun topLevelException(message: String, stackTrace: String) {
        events.add("exception: $message")
    }

    override fun sqlException(name: String, sqlCode: String, message: String) {
        events.add("sql-exception: $name [$sqlCode] - $message")
    }

    override fun sendMailEvent(to: String, subject: String) {
        events.add("mail: to=$to, subject=$subject")
        sentMails.add(to to subject)
    }
}
