package com.seanshubin.vote.integration.dsl

import com.seanshubin.vote.backend.auth.JwtCipher
import com.seanshubin.vote.backend.auth.TokenEncoder
import com.seanshubin.vote.backend.repository.InMemoryCommandModel
import com.seanshubin.vote.backend.repository.InMemoryData
import com.seanshubin.vote.backend.repository.InMemoryEventLog
import com.seanshubin.vote.backend.repository.InMemoryQueryModel
import com.seanshubin.vote.backend.repository.InMemoryRawTableScanner
import com.seanshubin.vote.backend.service.ServiceImpl
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

    // Backend abstraction - can be direct service calls or HTTP calls
    val backend: ScenarioBackend = backend ?: DirectServiceBackend(
        ServiceImpl(
            integrations,
            eventLog,
            commandModel,
            queryModel,
            InMemoryRawTableScanner(),
            tokenEncoder,
            "http://test.example.com",
        )
    )

    // Test helpers
    val events = EventInspector(eventLog)
    val database = DatabaseInspector(queryModel)

    // Owner token - first registered user is OWNER
    private var ownerToken: com.seanshubin.vote.contract.AccessToken? = null

    fun registerUser(
        name: String = "user${integrations.sequentialIdGenerator.generate()}",
        email: String = "$name@example.com",
        password: String = "password"
    ): UserContext {
        val token = backend.registerUser(name, email, password)
        if (ownerToken == null) {
            ownerToken = token
        }
        return UserContext(this, name, token)
    }

    fun registerUsers(vararg names: String): List<UserContext> =
        names.map { registerUser(it) }

    /**
     * Re-authenticate to pick up a role change. Existing access tokens bake
     * the role at issue time; after `setRole`, the user needs a fresh token
     * with the updated role claim.
     */
    fun authenticateAs(userName: String, password: String = "password"): UserContext {
        val tokens = backend.authenticate(userName, password)
        return UserContext(this, userName, tokens.accessToken)
    }

    // Admin query methods using owner token

    fun listUsers(): List<com.seanshubin.vote.domain.UserNameRole> {
        backend.synchronize()
        return backend.listUsers(ownerToken!!)
    }

    fun getUser(userName: String): com.seanshubin.vote.domain.UserNameEmail {
        backend.synchronize()
        return backend.getUser(ownerToken!!, userName)
    }

    fun userCount(): Int {
        backend.synchronize()
        return backend.userCount(ownerToken!!)
    }

    fun electionCount(): Int {
        backend.synchronize()
        return backend.electionCount(ownerToken!!)
    }

    fun eventCount(): Int {
        backend.synchronize()
        return backend.eventCount(ownerToken!!)
    }

    fun listTables(): List<String> {
        backend.synchronize()
        return backend.listTables(ownerToken!!)
    }

    fun tableCount(): Int {
        backend.synchronize()
        return backend.tableCount(ownerToken!!)
    }

    fun tableData(tableName: String): com.seanshubin.vote.domain.TableData {
        backend.synchronize()
        return backend.tableData(ownerToken!!, tableName)
    }

    fun permissionsForRole(role: com.seanshubin.vote.domain.Role): List<com.seanshubin.vote.domain.Permission> =
        backend.permissionsForRole(role)
}
