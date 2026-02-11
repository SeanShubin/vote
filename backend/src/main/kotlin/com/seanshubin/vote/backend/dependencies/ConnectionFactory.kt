package com.seanshubin.vote.backend.dependencies

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.net.url.Url
import java.sql.Connection
import java.sql.DriverManager

class ConnectionFactory(
    private val configuration: Configuration
) {
    fun createSqlConnection(): Connection? {
        return when (val config = configuration.databaseConfig) {
            is DatabaseConfig.InMemory -> null
            is DatabaseConfig.MySql -> {
                Class.forName("com.mysql.cj.jdbc.Driver")
                DriverManager.getConnection(
                    config.url,
                    config.user,
                    config.password
                )
            }
            is DatabaseConfig.DynamoDB -> null
        }
    }

    fun createDynamoDbClient(): DynamoDbClient? {
        return when (val config = configuration.databaseConfig) {
            is DatabaseConfig.DynamoDB -> {
                DynamoDbClient {
                    region = config.region
                    endpointUrl = Url.parse(config.endpoint)
                    credentialsProvider = StaticCredentialsProvider(
                        Credentials(
                            accessKeyId = "dummy",
                            secretAccessKey = "dummy"
                        )
                    )
                }
            }
            else -> null
        }
    }
}
