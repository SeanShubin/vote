package com.seanshubin.vote.backend.validation

/**
 * Convention for debug accounts in production. Any registration whose email
 * lands in the RFC-2606 reserved `.test` TLD is a "test user": registration
 * requires the shared password below, and outbound email is silently dropped
 * (TestAwareEmailSender). The convention is intentionally public — the
 * defense is the wipe endpoint at DELETE /admin/test-users.
 */
object TestUser {
    const val SHARED_PASSWORD = "test"

    fun isTestEmail(email: String): Boolean =
        email.substringAfterLast('@', "").lowercase().endsWith(".test")
}
