package com.seanshubin.vote.backend.dependencies

import com.seanshubin.vote.contract.Integrations
import kotlinx.serialization.json.Json

class ApplicationDependencies(
    integrations: Integrations,
    configuration: Configuration
) {
    private val json = Json { prettyPrint = true }

    private val connectionFactory = ConnectionFactory(configuration)
    private val repositoryFactory = RepositoryFactory(configuration, json)
    private val dynamoDbStartup = DynamoDbStartup(integrations)

    val runner: ApplicationRunner = ApplicationRunner(
        integrations = integrations,
        configuration = configuration,
        connectionFactory = connectionFactory,
        repositoryFactory = repositoryFactory,
        dynamoDbStartup = dynamoDbStartup,
        json = json,
    )
}
