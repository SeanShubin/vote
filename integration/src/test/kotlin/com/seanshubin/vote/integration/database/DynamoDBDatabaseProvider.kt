package com.seanshubin.vote.integration.database

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*
import aws.smithy.kotlin.runtime.net.url.Url
import com.seanshubin.vote.backend.repository.DynamoDbEventLog
import com.seanshubin.vote.backend.repository.DynamoDbSingleTableCommandModel
import com.seanshubin.vote.backend.repository.DynamoDbSingleTableQueryModel
import com.seanshubin.vote.backend.repository.DynamoDbSingleTableSchema
import com.seanshubin.vote.contract.CommandModel
import com.seanshubin.vote.contract.EventLog
import com.seanshubin.vote.contract.QueryModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

class DynamoDBDatabaseProvider : DatabaseProvider {
    override val name = "DynamoDB"

    private val container: LocalStackContainer = LocalStackContainer(
        DockerImageName.parse("localstack/localstack:latest")
    ).withServices(LocalStackContainer.Service.DYNAMODB)
        .apply {
            start()
            // Wait a bit for DynamoDB to be fully ready
            Thread.sleep(2000)
        }

    val dynamoDbClient: DynamoDbClient = runBlocking {
        DynamoDbClient {
            region = "us-east-1"
            endpointUrl = Url.parse(container.getEndpointOverride(LocalStackContainer.Service.DYNAMODB).toString())
            credentialsProvider = StaticCredentialsProvider {
                accessKeyId = container.accessKey
                secretAccessKey = container.secretKey
            }
        }
    }

    init {
        runBlocking {
            // Create tables using the schema utility
            try {
                DynamoDbSingleTableSchema.createTables(dynamoDbClient)
            } catch (e: Exception) {
                // Tables might already exist
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    override val eventLog: EventLog = DynamoDbEventLog(dynamoDbClient, json)
    override val commandModel: CommandModel = DynamoDbSingleTableCommandModel(dynamoDbClient, json)
    override val queryModel: QueryModel = DynamoDbSingleTableQueryModel(dynamoDbClient, json)

    override fun close() {
        runBlocking {
            dynamoDbClient.close()
        }
        container.stop()
    }

    override fun toString() = name
}
