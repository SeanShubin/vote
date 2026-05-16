package com.seanshubin.vote.backend.app

import com.seanshubin.vote.backend.dependencies.ApplicationDependencies
import com.seanshubin.vote.backend.dependencies.BootstrapDependencies
import com.seanshubin.vote.backend.integration.ProductionIntegrations
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // Usage: Main [port] [db-type]
    // Examples:
    //   Main 8080 memory
    //   Main 8080 mysql
    //   Main 8080 dynamodb

    // Stage 1: WIRING - Create integrations with args bundled
    val integrations = ProductionIntegrations(args)
    val notifications = integrations.notifications

    // Catches escapes from Jetty's accept/worker threads (and any future
    // background task), not just the main thread — without this they'd just
    // log to stderr and vanish.
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        try {
            notifications.topLevelException(
                message = "Uncaught in thread '${thread.name}': ${throwable.message ?: throwable::class.qualifiedName ?: "unknown"}",
                stackTrace = throwable.stackTraceToString(),
            )
        } catch (suppressed: Throwable) {
            System.err.println("Notification path failed: ${suppressed.message}")
            suppressed.printStackTrace(System.err)
        }
    }

    try {
        // Stage 2: WIRING - Create bootstrap composition root
        val bootstrapDeps = BootstrapDependencies(integrations)
        // Stage 2: WORK - Parse configuration
        val configuration = bootstrapDeps.bootstrap.parseDevConfiguration()

        // Stage 3: WIRING - Create application composition root
        val appDeps = ApplicationDependencies(integrations, configuration)
        // Stage 3: WORK - Run the application
        appDeps.runner.run()
    } catch (e: Throwable) {
        try {
            notifications.topLevelException(
                message = "backend Main: ${e.message ?: e::class.qualifiedName ?: "unknown"}",
                stackTrace = e.stackTraceToString(),
            )
        } catch (suppressed: Throwable) {
            System.err.println("Notification path failed: ${suppressed.message}")
            suppressed.printStackTrace(System.err)
        }
        exitProcess(1)
    }
}
