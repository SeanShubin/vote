package com.seanshubin.vote.backend.app

import com.seanshubin.vote.backend.dependencies.ApplicationDependencies
import com.seanshubin.vote.backend.dependencies.BootstrapDependencies
import com.seanshubin.vote.backend.integration.ProductionIntegrations

fun main(args: Array<String>) {
    // Usage: Main [port] [db-type]
    // Examples:
    //   Main 8080 memory
    //   Main 8080 mysql
    //   Main 8080 dynamodb

    // Stage 1: WIRING - Create integrations with args bundled
    val integrations = ProductionIntegrations(args)

    // Stage 2: WIRING - Create bootstrap composition root
    val bootstrapDeps = BootstrapDependencies(integrations)
    // Stage 2: WORK - Parse configuration
    val configuration = bootstrapDeps.bootstrap.parseConfiguration()

    // Stage 3: WIRING - Create application composition root
    val appDeps = ApplicationDependencies(integrations, configuration)
    // Stage 3: WORK - Run the application
    appDeps.runner.run()
}
