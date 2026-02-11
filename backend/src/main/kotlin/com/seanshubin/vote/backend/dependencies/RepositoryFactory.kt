package com.seanshubin.vote.backend.dependencies

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import com.seanshubin.vote.backend.repository.*
import com.seanshubin.vote.contract.Integrations
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.sql.Connection

class RepositoryFactory(
    private val integrations: Integrations,
    private val configuration: Configuration,
    private val json: Json
) {
    fun createRepositories(
        sqlConnection: Connection?,
        dynamoDbClient: DynamoDbClient?
    ): RepositorySet {
        return when (configuration.databaseConfig) {
            is DatabaseConfig.InMemory -> {
                val eventLog = InMemoryEventLog()
                val sharedData = InMemoryData()
                val commandModel = InMemoryCommandModel(sharedData)
                val queryModel = InMemoryQueryModel(sharedData)
                RepositorySet(eventLog, commandModel, queryModel)
            }
            is DatabaseConfig.MySql -> {
                val queryLoader = QueryLoaderFromResource()
                val eventLog = MySqlEventLog(sqlConnection!!, queryLoader, json)
                val commandModel = MySqlCommandModel(sqlConnection, queryLoader, json)
                val queryModel = MySqlQueryModel(sqlConnection, queryLoader, json)
                RepositorySet(eventLog, commandModel, queryModel)
            }
            is DatabaseConfig.DynamoDB -> {
                runBlocking {
                    try {
                        DynamoDbSingleTableSchema.createTables(dynamoDbClient!!)
                        integrations.emitLine("DynamoDB single-table schema created/verified")
                    } catch (e: Exception) {
                        integrations.emitLine("DynamoDB tables may already exist: ${e.message}")
                    }
                }

                val eventLog = DynamoDbEventLog(dynamoDbClient!!, json)
                val commandModel = DynamoDbSingleTableCommandModel(dynamoDbClient, json)
                val queryModel = DynamoDbSingleTableQueryModel(dynamoDbClient, json)
                RepositorySet(eventLog, commandModel, queryModel)
            }
        }
    }
}
