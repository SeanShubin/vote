package com.seanshubin.vote.backend.dependencies

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import com.seanshubin.vote.backend.repository.*
import kotlinx.serialization.json.Json
import java.sql.Connection

/**
 * Pure wiring: produces a [RepositorySet] for the configured backend.
 * No I/O side effects beyond what's intrinsic to repository construction
 * (e.g., AWS SDK client creation lives upstream in [ConnectionFactory]).
 * Schema creation and other startup work runs separately in
 * [DynamoDbStartup] so the wiring stage stays free of network calls.
 */
class RepositoryFactory(
    private val configuration: Configuration,
    private val json: Json,
) {
    fun createRepositories(
        sqlConnection: Connection?,
        dynamoDbClient: DynamoDbClient?
    ): RepositorySet = when (configuration.databaseConfig) {
        is DatabaseConfig.InMemory -> {
            val sharedData = InMemoryData()
            RepositorySet(
                eventLog = InMemoryEventLog(),
                commandModel = InMemoryCommandModel(sharedData),
                queryModel = InMemoryQueryModel(sharedData),
                rawTableScanner = InMemoryRawTableScanner(),
            )
        }
        is DatabaseConfig.MySql -> {
            val queryLoader = QueryLoaderFromResource()
            RepositorySet(
                eventLog = MySqlEventLog(sqlConnection!!, queryLoader, json),
                commandModel = MySqlCommandModel(sqlConnection, queryLoader, json),
                queryModel = MySqlQueryModel(sqlConnection, queryLoader, json),
                // MySQL backend has no admin raw view in this iteration.
                rawTableScanner = InMemoryRawTableScanner(),
            )
        }
        is DatabaseConfig.DynamoDB -> {
            val client = dynamoDbClient!!
            RepositorySet(
                eventLog = DynamoDbEventLog(client, json),
                commandModel = DynamoDbSingleTableCommandModel(client, json),
                queryModel = DynamoDbSingleTableQueryModel(client, json),
                rawTableScanner = DynamoDbRawTableScanner(client),
            )
        }
    }
}
