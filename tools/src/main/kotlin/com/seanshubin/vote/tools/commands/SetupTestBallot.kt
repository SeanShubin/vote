package com.seanshubin.vote.tools.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.seanshubin.vote.tools.lib.Http
import com.seanshubin.vote.tools.lib.Output

class SetupTestBallot : CliktCommand(name = "setup-test-ballot") {
    override fun help(context: Context) =
        "Set up users, election, and ballots against a running backend for manual inspection."

    val database: String by argument(name = "DATABASE", help = "mysql or dynamodb")

    override fun run() {
        if (database !in setOf("mysql", "dynamodb")) {
            Output.error("Database must be 'mysql' or 'dynamodb', got '$database'.")
        }

        val http = Http("http://localhost:$BACKEND_PORT")
        Output.banner("Setting up test ballot for $database")

        println("1. Registering alice (OWNER)...")
        val aliceToken = register(http, "alice", "alice@example.com", "alicepass")
        Output.success("Alice registered")
        println()

        println("2. Registering bob (USER)...")
        val bobToken = register(http, "bob", "bob@example.com", "bobpass")
        Output.success("Bob registered")
        println()

        val electionName = "Favorite Language"
        val electionPath = "/election/${Http.urlEncode(electionName)}"

        println("3. Creating election '$electionName'...")
        http.ensureSuccess(
            "POST", "/election",
            http.post(
                "/election",
                """{"userName":"alice","electionName":"$electionName"}""",
                aliceToken
            ),
            "Create election"
        )
        Output.success("Election created")
        println()

        println("4. Setting candidates...")
        http.ensureSuccess(
            "PUT", "$electionPath/candidates",
            http.put(
                "$electionPath/candidates",
                """{"candidateNames":["Kotlin","Java","Python","Rust"]}""",
                aliceToken
            ),
            "Set candidates"
        )
        Output.success("Candidates set (4 candidates)")
        println()

        println("5. Adding eligible voters (alice and bob)...")
        http.ensureSuccess(
            "PUT", "$electionPath/eligibility",
            http.put(
                "$electionPath/eligibility",
                """{"voterNames":["alice","bob"]}""",
                aliceToken
            ),
            "Set eligibility"
        )
        Output.success("Eligible voters added")
        println()

        println("6. Launching election...")
        http.ensureSuccess(
            "POST", "$electionPath/launch",
            http.post("$electionPath/launch", "{}", aliceToken),
            "Launch election"
        )
        Output.success("Election launched")
        println()

        println("7. Alice casting ballot (Kotlin > Python > Rust > Java)...")
        val aliceBallotResp = http.ensureSuccess(
            "POST", "$electionPath/ballot",
            http.post(
                "$electionPath/ballot",
                """{"voterName":"alice","rankings":[
                    |{"candidateName":"Kotlin","rank":1},
                    |{"candidateName":"Python","rank":2},
                    |{"candidateName":"Rust","rank":3},
                    |{"candidateName":"Java","rank":4}]}""".trimMargin(),
                aliceToken
            ),
            "Cast Alice's ballot"
        )
        Output.success("Alice's ballot cast (confirmation: ${aliceBallotResp.fieldOrNull("confirmation") ?: ""})")
        println()

        println("8. Bob casting ballot (Python > Rust > Kotlin > Java)...")
        val bobBallotResp = http.ensureSuccess(
            "POST", "$electionPath/ballot",
            http.post(
                "$electionPath/ballot",
                """{"voterName":"bob","rankings":[
                    |{"candidateName":"Python","rank":1},
                    |{"candidateName":"Rust","rank":2},
                    |{"candidateName":"Kotlin","rank":3},
                    |{"candidateName":"Java","rank":4}]}""".trimMargin(),
                bobToken
            ),
            "Cast Bob's ballot"
        )
        Output.success("Bob's ballot cast (confirmation: ${bobBallotResp.fieldOrNull("confirmation") ?: ""})")
        println()

        Output.banner("Setup complete!")
        println("Test data created:")
        println("  - 2 users: alice (OWNER), bob (USER)")
        println("  - 1 election: '$electionName'")
        println("  - 4 candidates: Kotlin, Java, Python, Rust")
        println("  - 2 eligible voters: alice, bob")
        println("  - 2 ballots cast")
        println()

        if (database == "dynamodb") {
            println("To inspect: scripts/dev inspect-dynamodb-ballots")
        } else {
            println("To inspect: scripts/dev inspect-mysql-all")
            println("            scripts/dev inspect-mysql-raw-query \"SELECT * FROM ballots\"")
        }
    }

    private fun register(http: Http, userName: String, email: String, password: String): String {
        val response = http.ensureSuccess(
            "POST", "/register",
            http.post(
                "/register",
                """{"userName":"$userName","email":"$email","password":"$password"}"""
            ),
            "Register $userName"
        )
        return response.fieldOrNull("accessToken")
            ?: Output.error("Registration response missing accessToken")
    }
}
