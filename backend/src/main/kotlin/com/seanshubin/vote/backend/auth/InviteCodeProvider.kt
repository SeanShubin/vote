package com.seanshubin.vote.backend.auth

/**
 * Returns the currently-configured invite code, or null/blank to disable the
 * registration gate. Implementations may read from a remote source (SSM,
 * Secrets Manager) and cache; callers should treat each call as cheap.
 */
fun interface InviteCodeProvider {
    fun current(): String?
}
