package com.seanshubin.vote.integration

import com.seanshubin.vote.backend.service.ServiceImpl
import com.seanshubin.vote.integration.database.DatabaseProvider
import com.seanshubin.vote.integration.database.DynamoDBDatabaseProvider
import com.seanshubin.vote.integration.database.InMemoryDatabaseProvider
import com.seanshubin.vote.integration.database.MySQLDatabaseProvider
import com.seanshubin.vote.integration.dsl.DirectServiceBackend
import com.seanshubin.vote.integration.dsl.TestContext
import com.seanshubin.vote.integration.fake.TestIntegrations
import com.seanshubin.vote.integration.scenario.Scenario
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class ScenarioCompatibilityTest {
    companion object {
        @JvmStatic
        fun providerNames(): Stream<String> = Stream.of(
            "InMemory",
            "MySQL",
            "DynamoDB"
        )
    }

    private var currentProvider: DatabaseProvider? = null

    @AfterEach
    fun cleanup() {
        currentProvider?.close()
        currentProvider = null
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providerNames")
    fun `comprehensive scenario works identically across all backends`(providerName: String) {
        val provider = createProvider(providerName)
        currentProvider = provider

        val integrations = TestIntegrations()
        val service = ServiceImpl(
            integrations,
            provider.eventLog,
            provider.commandModel,
            provider.queryModel
        )

        val backend = DirectServiceBackend(service)
        val context = TestContext(provider, backend)

        Scenario.comprehensive(context)

        verifyFinalState(provider)

        println("✓ Comprehensive scenario passed for ${provider.name}")
    }

    private fun createProvider(name: String): DatabaseProvider = when (name) {
        "InMemory" -> InMemoryDatabaseProvider()
        "MySQL" -> MySQLDatabaseProvider()
        "DynamoDB" -> DynamoDBDatabaseProvider()
        else -> throw IllegalArgumentException("Unknown provider: $name")
    }

    private fun verifyFinalState(provider: DatabaseProvider) {
        val queryModel = provider.queryModel

        assertEquals(4, queryModel.userCount(), "Expected 4 users")
        assertEquals(2, queryModel.electionCount(), "Expected 2 active elections")

        val lastSynced = queryModel.lastSynced()
        assertNotNull(lastSynced, "Sync state should exist")
        assertTrue(lastSynced!! > 0, "Should have synced events")

        val eventCount = provider.eventLog.eventCount()
        assertTrue(eventCount > 20, "Should have many events logged (actual: $eventCount)")
    }
}
