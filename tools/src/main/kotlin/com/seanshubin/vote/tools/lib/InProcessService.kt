package com.seanshubin.vote.tools.lib

import com.seanshubin.vote.backend.auth.DiscordConfigProvider
import com.seanshubin.vote.backend.auth.DiscordOAuthClient
import com.seanshubin.vote.backend.auth.JwtCipher
import com.seanshubin.vote.backend.auth.TokenEncoder
import com.seanshubin.vote.backend.repository.InMemoryCommandModel
import com.seanshubin.vote.backend.repository.InMemoryData
import com.seanshubin.vote.backend.repository.InMemoryEventLog
import com.seanshubin.vote.backend.repository.InMemoryQueryModel
import com.seanshubin.vote.backend.repository.InMemoryRawTableScanner
import com.seanshubin.vote.backend.service.DynamoToRelational
import com.seanshubin.vote.backend.service.EventApplier
import com.seanshubin.vote.backend.service.ServiceImpl
import com.seanshubin.vote.contract.Clock
import com.seanshubin.vote.contract.Integrations
import com.seanshubin.vote.contract.Notifications
import com.seanshubin.vote.contract.Service
import com.seanshubin.vote.contract.UniqueIdGenerator
import kotlinx.datetime.Instant
import java.net.http.HttpClient
import java.util.UUID

/**
 * Spins up an entirely in-memory [Service] backed by [InMemoryEventLog] /
 * [InMemoryCommandModel] / [InMemoryQueryModel]. Lets tools-side commands
 * exercise the real Discord login / election / ballot flows without standing
 * up a backend process, and then read out the resulting events for export.
 *
 * Discord OAuth is not configured here — the in-process service rejects
 * Discord login attempts with UNSUPPORTED. Tooling that needs users in
 * place seeds them by appending [com.seanshubin.vote.domain.DomainEvent.UserRegisteredViaDiscord]
 * events to the event log directly.
 */
class InProcessService {
    private val data = InMemoryData()
    val eventLog = InMemoryEventLog()
    private val commandModel = InMemoryCommandModel(data)
    private val queryModel = InMemoryQueryModel(data)
    private val rawTableScanner = InMemoryRawTableScanner()
    private val tokenEncoder = TokenEncoder(JwtCipher("dev-jwt-secret-DO-NOT-USE-IN-PROD"))
    private val integrations = ToolsIntegrations()

    val service: Service = ServiceImpl(
        integrations = integrations,
        eventLog = eventLog,
        commandModel = commandModel,
        queryModel = queryModel,
        rawTableScanner = rawTableScanner,
        tokenEncoder = tokenEncoder,
        discordConfigProvider = DiscordConfigProvider { null },
        discordOAuthClient = DiscordOAuthClient(httpClient = HttpClient.newHttpClient()),
        relationalProjection = DynamoToRelational(queryModel, eventLog),
        eventApplier = EventApplier(eventLog, commandModel, queryModel),
        devLoginEnabled = false,
    )
}

private class ToolsIntegrations : Integrations {
    override val commandLineArgs: Array<String> = emptyArray()
    override val emitLine: (String) -> Unit = { /* discard */ }
    override val clock: Clock = SystemClock
    override val uniqueIdGenerator: UniqueIdGenerator = UuidGenerator
    override val notifications: Notifications = NoopNotifications
    override val getEnv: (String) -> String? = { null }
}

private object SystemClock : Clock {
    override fun now(): Instant = kotlinx.datetime.Clock.System.now()
}

private object UuidGenerator : UniqueIdGenerator {
    override fun generate(): String = UUID.randomUUID().toString()
}

private object NoopNotifications : Notifications {
    override fun databaseEvent(name: String, statement: String) = Unit
    override fun httpRequestEvent(method: String, path: String) = Unit
    override fun httpResponseEvent(method: String, path: String, status: Int) = Unit
    override fun serviceRequestEvent(name: String, request: String) = Unit
    override fun serviceResponseEvent(name: String, request: String, response: String) = Unit
    override fun topLevelException(message: String, stackTrace: String) = Unit
    override fun sqlException(name: String, sqlCode: String, message: String) = Unit
    override fun sendMailEvent(to: String, subject: String) = Unit
    override fun unhandledHttpException(method: String, path: String, message: String, stackTrace: String) = Unit
    override fun clientErrorReported(
        message: String,
        url: String,
        userAgent: String,
        stackTrace: String?,
        timestamp: String,
    ) = Unit
}
