package com.seanshubin.vote.integration.dsl

import com.seanshubin.vote.backend.auth.JwtCipher
import com.seanshubin.vote.backend.auth.TokenEncoder
import com.seanshubin.vote.backend.repository.InMemoryCommandModel
import com.seanshubin.vote.backend.repository.InMemoryData
import com.seanshubin.vote.backend.repository.InMemoryEventLog
import com.seanshubin.vote.backend.repository.InMemoryQueryModel
import com.seanshubin.vote.backend.repository.InMemoryRawTableScanner
import com.seanshubin.vote.backend.service.ServiceImpl
import com.seanshubin.vote.contract.AccessToken
import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.domain.Role
import com.seanshubin.vote.integration.database.DatabaseProvider
import com.seanshubin.vote.integration.fake.TestIntegrations

class TestContext(
    provider: DatabaseProvider? = null,
    backend: ScenarioBackend? = null
) {
    // Fake infrastructure
    val integrations = TestIntegrations()

    // Real components with fake infrastructure
    private val data = InMemoryData()
    private val defaultEventLog = InMemoryEventLog()
    private val defaultCommandModel = InMemoryCommandModel(data)
    private val defaultQueryModel = InMemoryQueryModel(data)

    // Use provider if provided, otherwise use in-memory defaults
    private val eventLog = provider?.eventLog ?: defaultEventLog
    private val commandModel = provider?.commandModel ?: defaultCommandModel
    private val queryModel = provider?.queryModel ?: defaultQueryModel

    // Match the dev secret used by ApplicationRunner so tokens issued in tests
    // round-trip via the same TokenEncoder the production wiring uses.
    private val tokenEncoder = TokenEncoder(JwtCipher("dev-jwt-secret-DO-NOT-USE-IN-PROD"))

    private val service = ServiceImpl(
        integrations,
        eventLog,
        commandModel,
        queryModel,
        InMemoryRawTableScanner(),
        tokenEncoder,
    )

    // Backend abstraction - can be direct service calls or HTTP calls
    val backend: ScenarioBackend = backend ?: DirectServiceBackend(service)

    // Test helpers
    val events = EventInspector(eventLog)
    val database = DatabaseInspector(queryModel)

    /**
     * Seed a user via the event log directly. With Discord-only login there
     * is no password-registration HTTP path; tests bootstrap their fixtures
     * by appending a [DomainEvent.UserRegisteredViaDiscord] event. Each
     * synthetic `discord_id` just needs to be unique per test user — it
     * never leaves the in-memory event log.
     *
     * The first user seeded in a TestContext is promoted to PRIMARY_ROLE so
     * subsequent admin operations have a caller. Override [role] to seed a
     * specific role explicitly.
     */
    fun registerUser(
        name: String = "user${integrations.sequentialIdGenerator.generate()}",
        role: Role = if (queryModel.userCount() == 0) Role.PRIMARY_ROLE else Role.DEFAULT_ROLE,
    ): UserContext {
        eventLog.appendEvent(
            "system",
            integrations.clock.now(),
            DomainEvent.UserRegisteredViaDiscord(
                name = name,
                discordId = "test-discord-$name",
                discordDisplayName = name,
                role = role,
            ),
        )
        backend.synchronize()
        return UserContext(this, name, AccessToken(name, role))
    }

    fun registerUsers(vararg names: String): List<UserContext> =
        names.map { registerUser(it) }

    /**
     * Re-issue an access token after a role change. Existing tokens bake the
     * role at issue time; after `setRole`, the caller needs a fresh token
     * carrying the updated role claim.
     */
    fun reissueToken(userName: String): UserContext {
        backend.synchronize()
        val user = queryModel.searchUserByName(userName)
            ?: error("User not found: $userName")
        return UserContext(this, user.name, AccessToken(user.name, user.role))
    }

    // Admin query methods using the first seeded user's token
    private val ownerToken: AccessToken
        get() {
            backend.synchronize()
            val owners = queryModel.listUsers().sortedBy { it.role }
            val owner = owners.lastOrNull()
                ?: error("No users seeded — call registerUser first")
            return AccessToken(owner.name, owner.role)
        }

    fun listUsers(): List<com.seanshubin.vote.domain.UserNameRole> {
        backend.synchronize()
        return backend.listUsers(ownerToken)
    }

    fun getUser(userName: String): com.seanshubin.vote.domain.UserNameEmail {
        backend.synchronize()
        return backend.getUser(ownerToken, userName)
    }

    fun userCount(): Int {
        backend.synchronize()
        return backend.userCount(ownerToken)
    }

    fun electionCount(): Int {
        backend.synchronize()
        return backend.electionCount(ownerToken)
    }

    fun eventCount(): Int {
        backend.synchronize()
        return backend.eventCount(ownerToken)
    }

    fun listTables(): List<String> {
        backend.synchronize()
        return backend.listTables(ownerToken)
    }

    fun tableCount(): Int {
        backend.synchronize()
        return backend.tableCount(ownerToken)
    }

    fun tableData(tableName: String): com.seanshubin.vote.domain.TableData {
        backend.synchronize()
        return backend.tableData(ownerToken, tableName)
    }

    fun permissionsForRole(role: com.seanshubin.vote.domain.Role): List<com.seanshubin.vote.domain.Permission> =
        backend.permissionsForRole(role)
}
