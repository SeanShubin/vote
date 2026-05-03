package com.seanshubin.vote.backend.validation

import com.seanshubin.vote.contract.PasswordUtil
import com.seanshubin.vote.domain.User

/**
 * Convention for debug accounts in production. A user is a "test user" iff
 * their password is literally [MARKER_PASSWORD]. The convention is
 * intentionally public — anyone can mint a test user by registering with
 * that password — and the defense is the wipe endpoint at
 * DELETE /admin/test-users.
 *
 * The marker is checked via the user's stored salt+hash rather than against
 * the cleartext input, because by the time we need to know "is this a test
 * user?" the cleartext is gone. A user can transition between test and real
 * just by changing their password.
 *
 * Replaces the prior `.test` TLD convention: emails are now optional, so an
 * address-based marker no longer covers users without one.
 */
object TestUser {
    const val MARKER_PASSWORD = "test"

    fun isTestUser(user: User, passwordUtil: PasswordUtil): Boolean =
        passwordUtil.passwordMatches(MARKER_PASSWORD, user.salt, user.hash)
}
