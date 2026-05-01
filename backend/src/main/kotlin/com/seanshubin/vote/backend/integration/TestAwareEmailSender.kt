package com.seanshubin.vote.backend.integration

import com.seanshubin.vote.backend.validation.TestUser
import com.seanshubin.vote.contract.EmailSender

/**
 * Drops outbound email when the recipient is a test-domain address.
 * Wraps a real sender at integration wiring time so service code stays
 * unaware of the test-user concept.
 */
class TestAwareEmailSender(private val delegate: EmailSender) : EmailSender {
    override fun send(to: String, subject: String, body: String) {
        if (TestUser.isTestEmail(to)) return
        delegate.send(to, subject, body)
    }
}
