package com.seanshubin.vote.backend.dependencies

import com.seanshubin.vote.contract.Integrations
import com.seanshubin.vote.domain.Ballot
import com.seanshubin.vote.domain.SecretBallot
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

class ApplicationDependencies(
    integrations: Integrations,
    configuration: Configuration
) {
    private val json = Json {
        prettyPrint = true
        serializersModule = SerializersModule {
            polymorphic(Ballot::class) {
                subclass(SecretBallot::class)
            }
        }
    }

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
