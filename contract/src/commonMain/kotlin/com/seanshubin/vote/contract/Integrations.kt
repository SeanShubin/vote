package com.seanshubin.vote.contract

interface Integrations {
    val commandLineArgs: Array<String>
    val emitLine: (String) -> Unit
    val clock: Clock
    val uniqueIdGenerator: UniqueIdGenerator
    val notifications: Notifications

    /**
     * Reads a process-level configuration value (typically an environment
     * variable). Routed through here rather than direct `System.getenv` calls
     * so the Bootstrap stage can be tested with a fake env, and so wiring
     * code never reaches into JVM globals.
     */
    val getEnv: (String) -> String?
}
