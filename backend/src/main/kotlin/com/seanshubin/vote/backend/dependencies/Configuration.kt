package com.seanshubin.vote.backend.dependencies

data class Configuration(
    val port: Int,
    val databaseConfig: DatabaseConfig
)
