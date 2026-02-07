package com.seanshubin.vote.backend.dependencies

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.net.url.Url
import com.seanshubin.vote.backend.http.SimpleHttpHandler
import com.seanshubin.vote.backend.integration.ProductionIntegrations
import com.seanshubin.vote.backend.repository.*
import com.seanshubin.vote.backend.service.ServiceImpl
import com.seanshubin.vote.contract.CommandModel
import com.seanshubin.vote.contract.EventLog
import com.seanshubin.vote.contract.Integrations
import com.seanshubin.vote.contract.QueryModel
import com.seanshubin.vote.contract.Service
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.eclipse.jetty.server.Server
import java.sql.Connection
import java.sql.DriverManager

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

class ApplicationDependencies(
    private val port: Int,
    private val databaseConfig: DatabaseConfig = DatabaseConfig.InMemory,
    private val integrations: Integrations = ProductionIntegrations
) {
    private val json = Json { prettyPrint = true }

    private val connection: Connection? = when (databaseConfig) {
        is DatabaseConfig.InMemory -> null
        is DatabaseConfig.MySql -> {
            Class.forName("com.mysql.cj.jdbc.Driver")
            DriverManager.getConnection(
                databaseConfig.url,
                databaseConfig.user,
                databaseConfig.password
            )
        }
        is DatabaseConfig.DynamoDB -> null
    }

    private data class RepositorySet(
        val eventLog: EventLog,
        val commandModel: CommandModel,
        val queryModel: QueryModel
    )

    private val dynamoDbClient: DynamoDbClient? = when (databaseConfig) {
        is DatabaseConfig.DynamoDB -> {
            DynamoDbClient {
                region = databaseConfig.region
                endpointUrl = Url.parse(databaseConfig.endpoint)
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

    private val repositories: RepositorySet = when (databaseConfig) {
        is DatabaseConfig.InMemory -> {
            val eventLog = InMemoryEventLog()
            val sharedData = InMemoryData()
            val commandModel = InMemoryCommandModel(sharedData)
            val queryModel = InMemoryQueryModel(sharedData)
            RepositorySet(eventLog, commandModel, queryModel)
        }
        is DatabaseConfig.MySql -> {
            val queryLoader = QueryLoaderFromResource()
            val eventLog = MySqlEventLog(connection!!, queryLoader, json)
            val commandModel = MySqlCommandModel(connection, queryLoader, json)
            val queryModel = MySqlQueryModel(connection, queryLoader, json)
            RepositorySet(eventLog, commandModel, queryModel)
        }
        is DatabaseConfig.DynamoDB -> {
            // Initialize DynamoDB single-table schema
            runBlocking {
                try {
                    DynamoDbSingleTableSchema.createTables(dynamoDbClient!!)
                    println("DynamoDB single-table schema created/verified")
                } catch (e: Exception) {
                    println("DynamoDB tables may already exist: ${e.message}")
                }
            }

            val eventLog = DynamoDbEventLog(dynamoDbClient!!, json)
            val commandModel = DynamoDbSingleTableCommandModel(dynamoDbClient, json)
            val queryModel = DynamoDbSingleTableQueryModel(dynamoDbClient, json)
            RepositorySet(eventLog, commandModel, queryModel)
        }
    }

    private val eventLog: EventLog = repositories.eventLog
    private val commandModel: CommandModel = repositories.commandModel
    private val queryModel: QueryModel = repositories.queryModel

    private val service: Service = ServiceImpl(
        integrations = integrations,
        eventLog = eventLog,
        commandModel = commandModel,
        queryModel = queryModel
    )

    private val httpHandler = SimpleHttpHandler(service, json)
    private val server = Server(port)

    init {
        server.handler = httpHandler
    }

    fun start() {
        println("Starting server on port $port...")
        server.start()
        println("Server started. Visit http://localhost:$port/health")
        server.join()
    }

    fun startNonBlocking() {
        println("Starting server on port $port...")
        server.start()
        println("Server started. Visit http://localhost:$port/health")
    }

    fun stop() {
        server.stop()
        connection?.close()
        dynamoDbClient?.close()
    }
}
