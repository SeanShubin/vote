package com.seanshubin.vote.backend.dependencies

import com.seanshubin.vote.contract.Integrations
import kotlinx.serialization.json.Json

class ApplicationDependencies(
    integrations: Integrations,
    configuration: Configuration
) {
    private val json = Json { prettyPrint = true }

    private val connectionFactory = ConnectionFactory(configuration)
    private val repositoryFactory = RepositoryFactory(integrations, configuration, json)

    val runner: ApplicationRunner = ApplicationRunner(
        integrations,
        configuration,
        connectionFactory,
        repositoryFactory,
        json
    )
}
