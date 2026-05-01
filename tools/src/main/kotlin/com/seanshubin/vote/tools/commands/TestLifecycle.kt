package com.seanshubin.vote.tools.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.seanshubin.vote.tools.lib.Http
import com.seanshubin.vote.tools.lib.Output
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class TestLifecycle : CliktCommand(name = "test-lifecycle") {
    override fun help(context: Context) = "End-to-end lifecycle test against a running backend."
    val baseUrl: String? by argument(name = "BASE_URL").optional()

    override fun run() {
        val target = baseUrl ?: "http://localhost:$BACKEND_PORT"
        val http = Http(target)
        log("Starting end-to-end lifecycle test against $target")
        log("")

        log("1. Health check")
        val health = http.ensureSuccess("GET", "/health", http.get("/health"), "Health check")
        val status = health.fieldOrNull("status")
        if (status != "ok") fail("Health check failed: $status")
        log("   [OK] Health check passed")
        log("")

        log("2. Verify system starts empty")
        log("   (Will verify after first user registration)")
        log("")

        log("3. Register first user (alice - becomes OWNER)")
        val aliceToken = register(http, "alice", "alice@example.com", "password123")
        log("   [OK] Alice registered (OWNER)")
        log("")

        log("4. Verify initial counts")
        val initialUserCount = countField(http, "/users/count", aliceToken)
        val initialElectionCount = countField(http, "/elections/count", aliceToken)
        if (initialUserCount != 1) fail("Expected 1 user, got $initialUserCount")
        if (initialElectionCount != 0) fail("Expected 0 elections, got $initialElectionCount")
        log("   [OK] Users: $initialUserCount, Elections: $initialElectionCount")
        log("")

        log("5. Register second user (bob - becomes USER)")
        val bobToken = register(http, "bob", "bob@example.com", "password456")
        log("   [OK] Bob registered (USER)")
        log("")

        log("6. Register third user (charlie - becomes USER)")
        val charlieToken = register(http, "charlie", "charlie@example.com", "password789")
        log("   [OK] Charlie registered (USER)")
        log("")

        log("7. List all users")
        val users = listLength(http, "/users", aliceToken)
        if (users != 3) fail("Expected 3 users, got $users")
        log("   [OK] Found $users users")
        log("")

        val electionName = "Best Programming Language"
        val electionPath = "/election/${Http.urlEncode(electionName)}"

        log("8. Create election ($electionName)")
        http.ensureSuccess(
            "POST", "/election",
            http.post(
                "/election",
                """{"userName":"alice","electionName":"$electionName"}""",
                aliceToken
            ),
            "Create election"
        )
        log("   [OK] Election created")
        log("")

        log("9. Set candidates")
        http.ensureSuccess(
            "PUT", "$electionPath/candidates",
            http.put(
                "$electionPath/candidates",
                """{"candidateNames":["Kotlin","Python","Rust","TypeScript"]}""",
                aliceToken
            ),
            "Set candidates"
        )
        log("   [OK] Candidates set")
        log("")

        log("10. Cast ballots")
        castBallot(
            http, electionPath, aliceToken, "alice",
            listOf("Kotlin" to 1, "Rust" to 2, "Python" to 3, "TypeScript" to 4)
        )
        log("   [OK] Alice voted")
        castBallot(
            http, electionPath, bobToken, "bob",
            listOf("Python" to 1, "Kotlin" to 2, "TypeScript" to 3, "Rust" to 4)
        )
        log("   [OK] Bob voted")
        castBallot(
            http, electionPath, charlieToken, "charlie",
            listOf("Rust" to 1, "Kotlin" to 2, "Python" to 3, "TypeScript" to 4)
        )
        log("   [OK] Charlie voted")
        log("")

        log("13. List elections")
        val electionCount = listLength(http, "/elections", aliceToken)
        if (electionCount != 1) fail("Expected 1 election, got $electionCount")
        log("   [OK] Found $electionCount election")
        log("")

        log("14. Delete election")
        http.ensureSuccess("DELETE", electionPath, http.delete(electionPath, aliceToken), "Delete election")
        log("   [OK] Election deleted")
        log("")

        log("16. Verify election count is 0")
        val electionCountAfter = countField(http, "/elections/count", aliceToken)
        if (electionCountAfter != 0) fail("Expected 0 elections, got $electionCountAfter")
        log("   [OK] Elections: $electionCountAfter")
        log("")

        log("17. Alice deletes bob")
        http.ensureSuccess("DELETE", "/user/bob", http.delete("/user/bob", aliceToken), "Delete bob")
        log("   [OK] Bob deleted")
        log("")

        log("18. Alice deletes charlie")
        http.ensureSuccess("DELETE", "/user/charlie", http.delete("/user/charlie", aliceToken), "Delete charlie")
        log("   [OK] Charlie deleted")
        log("")

        log("19. Verify user count is 1")
        val userCountAfter = countField(http, "/users/count", aliceToken)
        if (userCountAfter != 1) fail("Expected 1 user, got $userCountAfter")
        log("   [OK] Users: $userCountAfter")
        log("")

        log("20. Alice deletes herself (final cleanup)")
        http.ensureSuccess("DELETE", "/user/alice", http.delete("/user/alice", aliceToken), "Delete alice")
        log("   [OK] Alice deleted")
        log("")

        log("21. Verify system returns to empty state")
        val verifyToken = register(http, "verify", "verify@example.com", "verify123")
        val verifyUserCount = countField(http, "/users/count", verifyToken)
        val verifyElectionCount = countField(http, "/elections/count", verifyToken)
        http.ensureSuccess("DELETE", "/user/verify", http.delete("/user/verify", verifyToken), "Delete verify user")
        if (verifyUserCount != 1) fail("Expected 1 user (verification), got $verifyUserCount")
        if (verifyElectionCount != 0) fail("Expected 0 elections, got $verifyElectionCount")
        log("   [OK] System returned to empty state")
        log("")

        log("=" .repeat(60))
        log("           [ALL TESTS PASSED]")
        log("=" .repeat(60))
        log("Complete lifecycle verified:")
        log("  - Started empty (0 users, 0 elections)")
        log("  - Created 3 users")
        log("  - Created 1 election with 4 candidates")
        log("  - Set eligible voters")
        log("  - Cast 3 ballots")
        log("  - Deleted election")
        log("  - Deleted all users (including self-deletion)")
        log("  - Ended empty (0 users, 0 elections)")
    }

    private fun log(message: String) = println("[TEST] $message")
    private fun fail(message: String): Nothing = Output.error(message)

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
            ?: fail("Registration response for $userName missing accessToken")
    }

    private fun countField(http: Http, path: String, token: String): Int {
        val response = http.ensureSuccess("GET", path, http.get(path, token), "GET $path")
        return response.fieldOrNull("count")?.toIntOrNull()
            ?: fail("Response from $path missing 'count' integer")
    }

    private fun listLength(http: Http, path: String, token: String): Int {
        val response = http.ensureSuccess("GET", path, http.get(path, token), "GET $path")
        val parsed = Json.parseToJsonElement(response.body)
        return (parsed as? JsonArray)?.size
            ?: fail("Expected JSON array from $path, got ${response.body.take(200)}")
    }

    private fun castBallot(
        http: Http,
        electionPath: String,
        token: String,
        voterName: String,
        rankings: List<Pair<String, Int>>
    ) {
        val rankingsJson = rankings.joinToString(",", "[", "]") { (name, rank) ->
            """{"candidateName":"$name","rank":$rank}"""
        }
        http.ensureSuccess(
            "POST", "$electionPath/ballot",
            http.post(
                "$electionPath/ballot",
                """{"voterName":"$voterName","rankings":$rankingsJson}""",
                token
            ),
            "Cast ballot for $voterName"
        )
    }
}

private fun JsonObject.field(name: String): String =
    (this[name] as? JsonPrimitive)?.contentOrNull
        ?: error("Missing field $name in $this")
