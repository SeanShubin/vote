package com.seanshubin.vote.contract

interface Integrations {
    val commandLineArgs: Array<String>
    val emitLine: (String) -> Unit
    val clock: Clock
    val uniqueIdGenerator: UniqueIdGenerator
    val notifications: Notifications
    val passwordUtil: PasswordUtil
}
