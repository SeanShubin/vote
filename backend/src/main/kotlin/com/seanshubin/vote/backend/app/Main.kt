package com.seanshubin.vote.backend.app

import com.seanshubin.vote.backend.dependencies.ApplicationDependencies
import com.seanshubin.vote.backend.dependencies.DatabaseConfig

fun main(args: Array<String>) {
    // Usage: Main [port] [db-type]
    // Examples:
    //   Main 8080 memory
    //   Main 8080 mysql
    //   Main 8080 dynamodb
    val port = args.getOrNull(0)?.toIntOrNull() ?: 8080
    val dbType = args.getOrNull(1) ?: "memory"

    val databaseConfig = when (dbType.lowercase()) {
        "memory" -> DatabaseConfig.InMemory
        "mysql" -> DatabaseConfig.MySql()
        "dynamodb" -> DatabaseConfig.DynamoDB()
        else -> {
            println("Unknown database type: $dbType. Using in-memory.")
            DatabaseConfig.InMemory
        }
    }

    println("Starting with database type: $dbType")
    val app = ApplicationDependencies(port, databaseConfig)
    app.start()
}
