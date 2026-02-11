package com.seanshubin.vote.backend.dependencies

sealed class DatabaseConfig {
    data object InMemory : DatabaseConfig()
    data class MySql(
        val url: String = "jdbc:mysql://localhost:3306/vote",
        val user: String = "vote",
        val password: String = "vote"
    ) : DatabaseConfig()
    data class DynamoDB(
        val endpoint: String = "http://localhost:8000",
        val region: String = "us-east-1"
    ) : DatabaseConfig()
}
