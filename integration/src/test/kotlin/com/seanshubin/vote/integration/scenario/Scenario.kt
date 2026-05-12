package com.seanshubin.vote.integration.scenario

import com.seanshubin.vote.domain.Role
import com.seanshubin.vote.integration.dsl.ElectionContext
import com.seanshubin.vote.integration.dsl.TestContext
import com.seanshubin.vote.integration.dsl.UserContext

/**
 * Comprehensive scenario that exercises ALL Service API operations.
 * Every feature is something a user could potentially do, so every feature fits into the narrative.
 * Happy path only - no error conditions.
 */
object Scenario {
    fun comprehensive(context: TestContext) {
        // Section 1: Initial Setup
        val alice = setupOwner(context)
        val (bob, charlie, david) = setupUsers(context, alice)

        // Section 2: User Management
        demonstrateUserManagement(context, alice, bob, charlie, david)

        // Section 3: User Browsing
        demonstrateUserBrowsing(context, alice, bob)

        // Section 4: Election Creation & Configuration
        val langElection = createLanguageElection(alice)

        // Section 5: Voting & Editing
        demonstrateVoting(bob, charlie, langElection)

        // Section 6: Election Viewing
        demonstrateElectionViewing(context, alice, bob, langElection)

        // Section 7: Second Election
        createDbElection(alice, bob, charlie)

        // Section 8: Election Deletion
        demonstrateElectionDeletion(alice)

        // Section 9: Administrative Queries
        demonstrateAdminQueries(context, alice)
    }

    // ========== Section 1: Initial Setup ==========

    private fun setupOwner(context: TestContext): UserContext {
        // First user becomes OWNER
        val alice = context.registerUser("alice", "alice@example.com", "alicepass")

        // Alice views her own profile
        alice.getMyProfile()

        return alice
    }

    private fun setupUsers(context: TestContext, alice: UserContext): Triple<UserContext, UserContext, UserContext> {
        // New registrations default to VOTER. The OWNER promotes them to USER
        // so they can create elections, edit their own profile, etc. After
        // promotion each user re-authenticates to get a token carrying the
        // updated role claim (existing tokens bake the role at issue time).
        context.registerUser("bob", "bob@example.com", "bobpass")
        context.registerUser("charlie", "charlie@example.com", "charliepass")
        context.registerUser("david", "david@example.com", "davidpass")

        alice.setRole("bob", Role.USER)
        alice.setRole("charlie", Role.USER)
        alice.setRole("david", Role.USER)

        val bob = context.authenticateAs("bob", "bobpass")
        val charlie = context.authenticateAs("charlie", "charliepass")
        val david = context.authenticateAs("david", "davidpass")

        return Triple(bob, charlie, david)
    }

    // ========== Section 2: User Management ==========

    private fun demonstrateUserManagement(
        context: TestContext,
        alice: UserContext,
        bob: UserContext,
        charlie: UserContext,
        david: UserContext
    ) {
        alice.listUsers()
        context.permissionsForRole(Role.ADMIN)
        alice.setRole("bob", Role.ADMIN)
        charlie.updateUser(newEmail = "charlie.new@example.com")
        charlie.getMyProfile()
        david.updateUser(newName = "dave")
    }

    // ========== Section 3: User Browsing ==========

    private fun demonstrateUserBrowsing(
        context: TestContext,
        alice: UserContext,
        bob: UserContext
    ) {
        alice.listUsers()
        context.userCount()
        alice.getUser("bob")
    }

    // ========== Section 4: Election Creation & Configuration ==========

    private fun createLanguageElection(alice: UserContext): ElectionContext {
        val langElection = alice.createElection("Best Programming Language")
        langElection.setCandidates("Kotlin", "Rust", "Go", "Python", "TypeScript")
        langElection.listCandidates()
        langElection.getDetails()
        return langElection
    }

    // ========== Section 5: Voting & Editing ==========

    private fun demonstrateVoting(
        bob: UserContext,
        charlie: UserContext,
        langElection: ElectionContext
    ) {
        bob.castBallot(
            langElection,
            "Kotlin" to 1,
            "Rust" to 2,
            "Go" to 3,
            "Python" to 4,
            "TypeScript" to 5
        )

        bob.getBallot("Best Programming Language")
        bob.listRankings("Best Programming Language")

        charlie.castBallot(
            langElection,
            "Rust" to 1,
            "Kotlin" to 2,
            "TypeScript" to 3,
            "Python" to 4,
            "Go" to 5
        )

        // Bob updates his ballot — re-casting overwrites the prior ranking.
        bob.castBallot(
            langElection,
            "Rust" to 1,
            "Kotlin" to 2,
            "Go" to 3,
            "TypeScript" to 4,
            "Python" to 5
        )

        bob.listRankings("Best Programming Language")
    }

    // ========== Section 6: Election Viewing ==========

    private fun demonstrateElectionViewing(
        context: TestContext,
        alice: UserContext,
        bob: UserContext,
        langElection: ElectionContext
    ) {
        alice.listElections()
        context.electionCount()
        bob.listElections()
        langElection.tally()
    }

    // ========== Section 7: Second Election ==========

    private fun createDbElection(
        alice: UserContext,
        bob: UserContext,
        charlie: UserContext
    ): ElectionContext {
        val dbElection = alice.createElection("Best Database")

        dbElection.setCandidates("PostgreSQL", "MySQL", "MongoDB", "DynamoDB")

        bob.castBallot(
            dbElection,
            "PostgreSQL" to 1,
            "DynamoDB" to 2,
            "MySQL" to 3,
            "MongoDB" to 4
        )

        charlie.castBallot(
            dbElection,
            "PostgreSQL" to 1,
            "MySQL" to 2,
            "MongoDB" to 3,
            "DynamoDB" to 4
        )

        dbElection.getDetails()
        dbElection.tally()

        return dbElection
    }

    // ========== Section 8: Election Deletion ==========

    private fun demonstrateElectionDeletion(alice: UserContext) {
        val tempElection = alice.createElection("Temporary Election")
        tempElection.setCandidates("Option A", "Option B")

        tempElection.delete()

        alice.listElections()
    }

    // ========== Section 9: Administrative Queries ==========

    private fun demonstrateAdminQueries(context: TestContext, alice: UserContext) {
        context.listTables()
        context.tableCount()
        context.eventCount()
        context.tableData("user")
        context.permissionsForRole(Role.USER)
        context.permissionsForRole(Role.OWNER)
    }
}
