package com.seanshubin.vote.documentation

import com.seanshubin.vote.domain.Role
import com.seanshubin.vote.integration.dsl.TestContext

/**
 * Comprehensive scenario that touches every feature in the system.
 * Happy path only - no error conditions.
 */
object Scenario {
    fun comprehensive(context: TestContext) {
        // ========== User Registration ==========
        // First user becomes OWNER
        val alice = context.registerUser("alice", "alice@example.com", "alicepass")

        // Additional users (USER role)
        val bob = context.registerUser("bob", "bob@example.com", "bobpass")
        val charlie = context.registerUser("charlie", "charlie@example.com", "charliepass")
        val david = context.registerUser("david", "david@example.com", "davidpass")

        // ========== User Management ==========
        // Change user role
        alice.setRole("bob", Role.ADMIN)

        // Change password
        bob.changePassword("newbobpass")

        // Update email
        charlie.updateUser(newEmail = "charlie.new@example.com")

        // Update name (do this before setting election eligibility)
        david.updateUser(newName = "dave")

        // ========== Election 1: Programming Languages ==========
        val langElection = alice.createElection("Best Programming Language")

        // Add candidates
        langElection.setCandidates("Kotlin", "Rust", "Go", "Python", "TypeScript")

        // Set eligible voters (note: david is now "dave", but won't vote in this election)
        langElection.setEligibleVoters("bob", "charlie", "dave")

        // Launch election (allow editing ballots)
        langElection.launch(allowEdit = true)

        // Cast ballots (dave is eligible but doesn't vote - demonstrates partial turnout)
        bob.castBallot(
            langElection,
            "Kotlin" to 1,
            "Rust" to 2,
            "Go" to 3,
            "Python" to 4,
            "TypeScript" to 5
        )

        charlie.castBallot(
            langElection,
            "Rust" to 1,
            "Kotlin" to 2,
            "TypeScript" to 3,
            "Python" to 4,
            "Go" to 5
        )

        // Bob updates his ballot (testing edit capability)
        langElection.updateRankings(
            "bob",
            "Rust" to 1,
            "Kotlin" to 2,
            "Go" to 3,
            "TypeScript" to 4,
            "Python" to 5
        )

        // ========== Election 2: Databases (finalized) ==========
        val dbElection = alice.createElection("Best Database")

        // Add candidates (note: removal not exposed through Service API)
        dbElection.setCandidates("PostgreSQL", "MySQL", "MongoDB", "DynamoDB")

        // Set eligible voters
        dbElection.setEligibleVoters("bob", "charlie", "dave")

        // Launch without edit capability
        dbElection.launch(allowEdit = false)

        // Cast ballots
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

        // ========== Election 3: Deleted Election ==========
        val tempElection = alice.createElection("Temporary Election")
        tempElection.setCandidates("Option A", "Option B")
        tempElection.setEligibleVoters("bob")

        // Delete election before launch
        tempElection.delete()

        // ========== User Removal ==========
        // Remove dave user (demonstrates user deletion)
        // Note: In real scenario, this would cascade and affect elections
        // For documentation purposes, we'll skip this to keep election data intact
        // alice.removeUser("dave")
    }
}
