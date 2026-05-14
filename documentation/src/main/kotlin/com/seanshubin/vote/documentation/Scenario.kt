package com.seanshubin.vote.documentation

import com.seanshubin.vote.domain.Role
import com.seanshubin.vote.integration.dsl.TestContext

/**
 * Documentation fixture: a small comprehensive scenario that exercises the
 * remaining domain surface (Discord-stub users, an election with candidates,
 * a handful of ballots) so the generated HTML has meaningful rows in each
 * projection.
 *
 * Replaces the old integration-tests Scenario class that bootstrapped users
 * through the now-retired password-registration path.
 */
object Scenario {
    fun comprehensive(ctx: TestContext) {
        val owner = ctx.registerUser("owner", role = Role.PRIMARY_ROLE)
        val alice = ctx.registerUser("alice", role = Role.VOTER)
        val bob = ctx.registerUser("bob", role = Role.VOTER)
        val carol = ctx.registerUser("carol", role = Role.VOTER)

        val election = owner.createElection(
            name = "Favorite Programming Language",
            description = "Pick your favorite language for backend work.",
        )
        election.setCandidates("Kotlin", "Python", "Rust", "Java")

        alice.castBallot(election, "Kotlin" to 1, "Python" to 2, "Rust" to 3, "Java" to 4)
        bob.castBallot(election, "Python" to 1, "Rust" to 2, "Kotlin" to 3, "Java" to 4)
        carol.castBallot(election, "Rust" to 1, "Kotlin" to 2, "Python" to 3, "Java" to 4)
    }
}
