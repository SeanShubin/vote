package com.seanshubin.vote.backend.dependencies

import com.seanshubin.vote.contract.Integrations

class Bootstrap(
    private val integrations: Integrations
) {
    fun parseConfiguration(): Configuration {
        val args = integrations.commandLineArgs
        val port = args.getOrNull(0)?.toIntOrNull() ?: 8080
        val dbType = args.getOrNull(1) ?: "memory"

        val databaseConfig = when (dbType.lowercase()) {
            "memory" -> DatabaseConfig.InMemory
            "mysql" -> DatabaseConfig.MySql()
            "dynamodb" -> DatabaseConfig.DynamoDB()
            else -> {
                integrations.emitLine("Unknown database type: $dbType. Using in-memory.")
                DatabaseConfig.InMemory
            }
        }

        integrations.emitLine("Starting with database type: $dbType")
        return Configuration(port, databaseConfig)
    }
}
