package com.seanshubin.vote.integration.fake

import com.seanshubin.vote.contract.EmailSender

/**
 * Captures sent emails in-memory so tests can assert on them — e.g. that
 * a password reset request actually triggered a send to the right address,
 * and inspect the body for the reset token URL.
 */
class FakeEmailSender : EmailSender {
    data class Sent(val to: String, val subject: String, val body: String)

    private val captured = mutableListOf<Sent>()

    override fun send(to: String, subject: String, body: String) {
        captured.add(Sent(to, subject, body))
    }

    val sent: List<Sent> get() = captured.toList()

    fun lastSentTo(address: String): Sent? = captured.lastOrNull { it.to == address }

    fun reset() = captured.clear()
}
