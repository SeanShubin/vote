package com.seanshubin.vote.backend.dependencies

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import com.seanshubin.vote.backend.repository.DynamoDbSingleTableSchema
import com.seanshubin.vote.contract.Integrations
import kotlinx.coroutines.runBlocking

/**
 * Creates the DynamoDB tables on startup if they don't exist. Lifted out of
 * [RepositoryFactory] so wiring stays free of I/O — this runs explicitly
 * during the runner's bootstrap phase.
 */
class DynamoDbStartup(
    private val integrations: Integrations,
) {
    fun ensureTables(dynamoDbClient: DynamoDbClient) {
        runBlocking {
            try {
                DynamoDbSingleTableSchema.createTables(dynamoDbClient)
                integrations.emitLine("DynamoDB single-table schema created/verified")
            } catch (e: Exception) {
                integrations.emitLine("DynamoDB tables may already exist: ${e.message}")
            }
        }
    }
}
