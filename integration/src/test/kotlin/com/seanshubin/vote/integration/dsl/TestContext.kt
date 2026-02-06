package com.seanshubin.vote.integration.dsl

import com.seanshubin.vote.backend.repository.InMemoryCommandModel
import com.seanshubin.vote.backend.repository.InMemoryData
import com.seanshubin.vote.backend.repository.InMemoryEventLog
import com.seanshubin.vote.backend.repository.InMemoryQueryModel
import com.seanshubin.vote.backend.service.ServiceImpl
import com.seanshubin.vote.contract.Service
import com.seanshubin.vote.integration.fake.TestIntegrations

class TestContext {
    // Fake infrastructure
    val integrations = TestIntegrations()

    // Real components with fake infrastructure
    private val data = InMemoryData()
    private val eventLog = InMemoryEventLog()
    private val commandModel = InMemoryCommandModel(data)
    private val queryModel = InMemoryQueryModel(data)
    val service: Service = ServiceImpl(integrations, eventLog, commandModel, queryModel)

    // Test helpers
    val events = EventInspector(eventLog)
    val database = DatabaseInspector(queryModel)

    fun registerUser(
        name: String = "user${integrations.sequentialIdGenerator.generate()}",
        email: String = "$name@example.com",
        password: String = "password"
    ): UserContext {
        val tokens = service.register(name, email, password)
        return UserContext(this, name, tokens.accessToken)
    }

    fun registerUsers(vararg names: String): List<UserContext> =
        names.map { registerUser(it) }
}
