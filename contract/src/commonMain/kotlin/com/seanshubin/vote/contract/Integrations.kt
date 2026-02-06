package com.seanshubin.vote.contract

interface Integrations {
    val clock: Clock
    val uniqueIdGenerator: UniqueIdGenerator
    val notifications: Notifications
}
