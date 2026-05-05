package com.seanshubin.vote.tools.lib

import com.seanshubin.vote.backend.auth.JwtCipher
import com.seanshubin.vote.backend.auth.TokenEncoder
import com.seanshubin.vote.backend.crypto.RealPasswordUtil
import com.seanshubin.vote.backend.repository.InMemoryCommandModel
import com.seanshubin.vote.backend.repository.InMemoryData
import com.seanshubin.vote.backend.repository.InMemoryEventLog
import com.seanshubin.vote.backend.repository.InMemoryQueryModel
import com.seanshubin.vote.backend.repository.InMemoryRawTableScanner
import com.seanshubin.vote.backend.service.ServiceImpl
import com.seanshubin.vote.contract.Clock
import com.seanshubin.vote.contract.EmailSender
import com.seanshubin.vote.contract.Integrations
import com.seanshubin.vote.contract.Notifications
import com.seanshubin.vote.contract.PasswordUtil
import com.seanshubin.vote.contract.Service
import com.seanshubin.vote.contract.UniqueIdGenerator
import kotlinx.datetime.Instant
import java.util.UUID

/**
 * Spins up an entirely in-memory [Service] backed by [InMemoryEventLog] /
 * [InMemoryCommandModel] / [InMemoryQueryModel]. Lets tools-side commands
 * exercise the real registration / election / ballot flows without standing
 * up a backend process, and then read out the resulting events for export.
 *
 * This mirrors the wiring used by integration tests (see TestContext) but
 * uses real password hashing and a system clock so the produced event log
 * is byte-compatible with one captured from a live backend.
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
        frontendBaseUrl = "http://localhost:3000",
    )
}

private class ToolsIntegrations : Integrations {
    override val commandLineArgs: Array<String> = emptyArray()
    override val emitLine: (String) -> Unit = { /* discard */ }
    override val clock: Clock = SystemClock
    override val uniqueIdGenerator: UniqueIdGenerator = UuidGenerator
    override val notifications: Notifications = NoopNotifications
    override val passwordUtil: PasswordUtil = RealPasswordUtil
    override val emailSender: EmailSender = NoopEmailSender
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
}

private object NoopEmailSender : EmailSender {
    override fun send(to: String, subject: String, body: String) = Unit
}
