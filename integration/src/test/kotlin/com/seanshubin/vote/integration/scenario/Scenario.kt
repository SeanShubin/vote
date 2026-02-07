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
        val (bob, charlie, david) = setupUsers(context)

        // Section 2: User Management
        demonstrateUserManagement(context, alice, bob, charlie, david)

        // Section 3: User Browsing
        demonstrateUserBrowsing(context, alice, bob)

        // Section 4: Election Creation & Configuration
        val langElection = createLanguageElection(alice, bob, charlie, david)

        // Section 5: Voting & Editing
        demonstrateVoting(bob, charlie, langElection)

        // Section 6: Election Viewing
        demonstrateElectionViewing(context, alice, bob, langElection)

        // Section 7: Finalized Election
        val dbElection = createAndFinalizeDbElection(alice, bob, charlie)

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
        val profile = alice.getMyProfile()
        // Returns: UserNameEmail(name="alice", email="alice@example.com")

        return alice
    }

    private fun setupUsers(context: TestContext): Triple<UserContext, UserContext, UserContext> {
        // Additional users (USER role by default)
        val bob = context.registerUser("bob", "bob@example.com", "bobpass")
        val charlie = context.registerUser("charlie", "charlie@example.com", "charliepass")
        val david = context.registerUser("david", "david@example.com", "davidpass")

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
        // Alice lists all users
        val allUsers = alice.listUsers()
        // Returns: List<UserNameRole> with 4 users

        // Alice checks permissions for admin role
        val adminPerms = context.permissionsForRole(Role.ADMIN)
        // Returns: List<Permission> for ADMIN role

        // Alice promotes Bob to ADMIN
        alice.setRole("bob", Role.ADMIN)

        // Bob changes his password
        bob.changePassword("newbobpass")

        // Charlie updates email
        charlie.updateUser(newEmail = "charlie.new@example.com")

        // Charlie views his updated profile
        val charlieProfile = charlie.getMyProfile()
        // Returns: UserNameEmail(name="charlie", email="charlie.new@example.com")

        // David updates name (do this before setting election eligibility)
        david.updateUser(newName = "dave")
    }

    // ========== Section 3: User Browsing ==========

    private fun demonstrateUserBrowsing(
        context: TestContext,
        alice: UserContext,
        bob: UserContext
    ) {
        // Alice lists all users
        val users = alice.listUsers()
        // Returns: List<UserNameRole>

        // Check total user count
        val totalUsers = context.userCount()
        // Returns: 4

        // Alice looks up Bob's profile
        val bobProfile = alice.getUser("bob")
        // Returns: UserNameEmail(name="bob", email="bob@example.com")
    }

    // ========== Section 4: Election Creation & Configuration ==========

    private fun createLanguageElection(
        alice: UserContext,
        bob: UserContext,
        charlie: UserContext,
        dave: UserContext
    ): ElectionContext {
        // Alice creates election
        val langElection = alice.createElection("Best Programming Language")

        // Alice configures candidates
        langElection.setCandidates("Kotlin", "Rust", "Go", "Python", "TypeScript")

        // Alice views candidates she just added
        val candidates = langElection.listCandidates()
        // Returns: List<String> with 5 candidates

        // Alice sets eligible voters (note: david is now "dave")
        langElection.setEligibleVoters("bob", "charlie", "dave")

        // Alice views eligibility list
        val eligibility = langElection.listEligibility()
        // Returns: List<VoterEligibility> with 3 voters

        // Bob checks if he's eligible
        val bobEligible = bob.isEligible("Best Programming Language")
        // Returns: true

        // Alice launches election (allow editing ballots)
        langElection.launch(allowEdit = true)

        // Alice views election details after launch
        val details = langElection.getDetails()
        // Returns: ElectionDetail with allowVote=true, allowEdit=true

        return langElection
    }

    // ========== Section 5: Voting & Editing ==========

    private fun demonstrateVoting(
        bob: UserContext,
        charlie: UserContext,
        langElection: ElectionContext
    ) {
        // Bob casts ballot
        bob.castBallot(
            langElection,
            "Kotlin" to 1,
            "Rust" to 2,
            "Go" to 3,
            "Python" to 4,
            "TypeScript" to 5
        )

        // Bob retrieves his ballot
        val bobBallot = bob.getBallot("Best Programming Language")
        // Returns: BallotSummary with confirmation and whenCast

        // Bob views his rankings
        val bobRankings = bob.listRankings("Best Programming Language")
        // Returns: List<Ranking> with 5 rankings

        // Charlie casts ballot
        charlie.castBallot(
            langElection,
            "Rust" to 1,
            "Kotlin" to 2,
            "TypeScript" to 3,
            "Python" to 4,
            "Go" to 5
        )

        // Bob edits his ballot (allowed because allowEdit=true)
        langElection.updateRankings(
            "bob",
            "Rust" to 1,
            "Kotlin" to 2,
            "Go" to 3,
            "TypeScript" to 4,
            "Python" to 5
        )

        // Bob views his updated rankings
        val bobUpdatedRankings = bob.listRankings("Best Programming Language")
        // Returns: List<Ranking> with updated order
    }

    // ========== Section 6: Election Viewing ==========

    private fun demonstrateElectionViewing(
        context: TestContext,
        alice: UserContext,
        bob: UserContext,
        langElection: ElectionContext
    ) {
        // Alice lists all elections
        val allElections = alice.listElections()
        // Returns: List<ElectionSummary> with 1 election

        // Check election count
        val electionCount = context.electionCount()
        // Returns: 1

        // Bob also lists elections from his perspective
        val bobElections = bob.listElections()
        // Returns: List<ElectionSummary> with 1 election

        // Alice views tally before finalization
        val tally = langElection.tally()
        // Returns: Tally with current results (2 ballots)
    }

    // ========== Section 7: Finalized Election ==========

    private fun createAndFinalizeDbElection(
        alice: UserContext,
        bob: UserContext,
        charlie: UserContext
    ): ElectionContext {
        val dbElection = alice.createElection("Best Database")

        dbElection.setCandidates("PostgreSQL", "MySQL", "MongoDB", "DynamoDB")
        dbElection.setEligibleVoters("bob", "charlie", "dave")

        // Demonstrates election without edit capability
        dbElection.launch(allowEdit = false)

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

        // Finalize election
        dbElection.finalize()

        // View details of finalized election
        val details = dbElection.getDetails()
        // Shows allowVote=false after finalization

        // View final tally
        val finalTally = dbElection.tally()
        // Returns: Tally with final results

        return dbElection
    }

    // ========== Section 8: Election Deletion ==========

    private fun demonstrateElectionDeletion(alice: UserContext) {
        val tempElection = alice.createElection("Temporary Election")
        tempElection.setCandidates("Option A", "Option B")
        tempElection.setEligibleVoters("bob")

        // Delete before launch
        tempElection.delete()

        // Election no longer appears in list
        val afterDelete = alice.listElections()
        // Returns only the 2 non-deleted elections
    }

    // ========== Section 9: Administrative Queries ==========

    private fun demonstrateAdminQueries(context: TestContext, alice: UserContext) {
        // List database tables
        val tables = context.listTables()
        // Returns: List<String> with table names

        // Check table count
        val tableCount = context.tableCount()
        // Returns: number of tables

        // Check event count
        val eventCount = context.eventCount()
        // Returns: count of all domain events appended

        // View data for users table
        val userData = context.tableData("user")
        // Returns: TableData with columns and rows

        // Alice checks permissions for USER role
        val userPerms = context.permissionsForRole(Role.USER)
        // Returns: List<Permission> for USER role

        // Alice checks permissions for OWNER role
        val ownerPerms = context.permissionsForRole(Role.OWNER)
        // Returns: List<Permission> for OWNER role
    }
}
