package com.seanshubin.vote.backend.dependencies

sealed class DatabaseConfig {
    data object InMemory : DatabaseConfig()
    data class MySql(
        val url: String = "jdbc:mysql://localhost:3306/vote",
        val user: String = "vote",
        val password: String = "vote"
    ) : DatabaseConfig()
    /**
     * @param endpoint If non-null, points the SDK at a local DynamoDB Local
     *   container with dummy credentials. If null, the SDK uses the AWS
     *   default endpoint and the default credentials chain (env vars from
     *   Lambda's IAM role, or whatever the host process provides).
     */
    data class DynamoDB(
        val endpoint: String? = "http://localhost:8000",
        val region: String = "us-east-1"
    ) : DatabaseConfig()
}
