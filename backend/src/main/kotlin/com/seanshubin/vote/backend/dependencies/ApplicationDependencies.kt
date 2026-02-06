package com.seanshubin.vote.backend.dependencies

import com.seanshubin.vote.backend.http.SimpleHttpHandler
import com.seanshubin.vote.backend.integration.ProductionIntegrations
import com.seanshubin.vote.backend.repository.InMemoryCommandModel
import com.seanshubin.vote.backend.repository.InMemoryEventLog
import com.seanshubin.vote.backend.repository.InMemoryQueryModel
import com.seanshubin.vote.backend.service.ServiceImpl
import com.seanshubin.vote.contract.Integrations
import com.seanshubin.vote.contract.Service
import kotlinx.serialization.json.Json
import org.eclipse.jetty.server.Server

class ApplicationDependencies(
    private val port: Int,
    private val integrations: Integrations = ProductionIntegrations
) {
    private val json = Json { prettyPrint = true }
    private val eventLog = InMemoryEventLog()
    private val commandModel = InMemoryCommandModel()
    private val queryModel = InMemoryQueryModel()

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

    fun stop() {
        server.stop()
    }
}
