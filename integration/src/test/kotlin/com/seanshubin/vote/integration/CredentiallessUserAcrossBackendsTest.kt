package com.seanshubin.vote.integration

import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.domain.Role
import com.seanshubin.vote.integration.database.DatabaseProvider
import com.seanshubin.vote.integration.database.DynamoDBDatabaseProvider
import com.seanshubin.vote.integration.database.MySQLDatabaseProvider
import com.seanshubin.vote.integration.dsl.TestContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A user with no Discord credential — [DomainEvent.UserRegistered], emitted
 * only by the dev-login bypass — must project cleanly into every store.
 *
 * This is DynamoDB-shaped in origin: `discord_id` is the hash key of the
 * sparse `discord-id-index`, and DynamoDB rejects an empty string for a key
 * attribute, so writing `discord_id = ""` failed the whole put with
 * "The AttributeValue for a key attribute cannot contain an empty string
 * value". The fix is to omit the attribute entirely, which also keeps the
 * index sparse. MySQL is covered alongside it to pin the parity: a
 * credential-less user reads back the same either way.
 */
class CredentiallessUserAcrossBackendsTest {

    @Test
    fun `MySQL — a user with no Discord credential registers and reads back`() {
        MySQLDatabaseProvider().use { assertCredentiallessUserWorks(it) }
    }

    @Test
    fun `DynamoDB — a user with no Discord credential registers and reads back`() {
        DynamoDBDatabaseProvider().use { assertCredentiallessUserWorks(it) }
    }

    private fun assertCredentiallessUserWorks(provider: DatabaseProvider) {
        val testContext = TestContext(provider = provider)

        // The dev-login path: no Discord identity at all.
        testContext.backend.seedEvent(
            "system",
            testContext.integrations.clock.now(),
            DomainEvent.UserRegistered(name = "devuser", role = Role.PRIMARY_ROLE),
        )
        testContext.backend.synchronize()

        val user = assertNotNull(
            testContext.database.searchUser("devuser"),
            "a credential-less registration must still produce a user row",
        )
        assertEquals("devuser", user.name)
        assertEquals(Role.PRIMARY_ROLE, user.role)
        assertEquals("", user.discordId, "no credential reads back as the empty id")
        assertEquals("", user.discordDisplayName)

        // A Discord-registered user alongside them is still reachable by id,
        // so omitting the attribute didn't break the index for everyone else.
        val discordUser = testContext.registerUser("discorduser", Role.VOTER)
        assertEquals(
            "discorduser",
            testContext.database.findUser(discordUser.userName).name,
        )

        // And the credential-less user is unreachable by Discord id, which is
        // the whole point of leaving them out of the sparse index.
        assertNull(
            provider.queryModel.searchUserByDiscordId(""),
            "an empty Discord id matches nobody",
        )

        // The rename path copies the stored item forward; an absent
        // discord_id attribute must survive that copy rather than reappear
        // as an illegal empty key value.
        devuserRenamedTo(testContext, "devuser", "renameduser")
        assertNull(testContext.database.searchUser("devuser"))
        val renamed = assertNotNull(testContext.database.searchUser("renameduser"))
        assertEquals("", renamed.discordId)
        assertEquals(Role.PRIMARY_ROLE, renamed.role)
    }

    private fun devuserRenamedTo(testContext: TestContext, oldName: String, newName: String) {
        testContext.backend.seedEvent(
            "system",
            testContext.integrations.clock.now(),
            DomainEvent.UserNameChanged(oldUserName = oldName, newUserName = newName),
        )
        testContext.backend.synchronize()
    }
}
