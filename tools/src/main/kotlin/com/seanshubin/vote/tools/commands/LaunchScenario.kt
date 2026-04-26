package com.seanshubin.vote.tools.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.seanshubin.vote.tools.lib.Http
import com.seanshubin.vote.tools.lib.Logs
import com.seanshubin.vote.tools.lib.Output
import com.seanshubin.vote.tools.lib.Procs
import com.seanshubin.vote.tools.lib.ProjectPaths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText

internal class ScenarioLauncher(
    private val database: Database
) {
    private val baseUrl = "http://localhost:$BACKEND_PORT"
    private val json = Json { ignoreUnknownKeys = true }

    fun run(scenarioPattern: String) {
        val scenarioFile = findScenarioFile(scenarioPattern)
            ?: errorListing("No scenario found matching '$scenarioPattern'")

        val scenarioJson = json.parseToJsonElement(scenarioFile.readText()).jsonObject
        val displayName = (scenarioJson["displayName"] as? JsonPrimitive)?.contentOrNull
            ?: scenarioFile.name.removeSuffix(".json")

        Output.banner("Launch Scenario (${database.displayName}): $displayName")

        // 1-6: terminate, roll, purge, build, start backend, wait
        println("1. Terminating existing processes...")
        TerminateAll().run()
        Thread.sleep(1000)

        println()
        println("2. Rolling logs...")
        RollLogs().run()

        println()
        println("3. Purging ${database.displayName} database...")
        when (database) {
            Database.Mysql -> PurgeMysql().run()
            Database.Dynamodb -> PurgeDynamodb().run()
        }

        println()
        println("4. Building frontend (incremental)...")
        Procs.runOrFail(
            ProjectPaths.gradlew.toString(),
            ":frontend:build",
            "--no-daemon",
            workingDir = ProjectPaths.projectRoot,
            description = "Build frontend"
        )

        println()
        println("5. Starting backend (${database.displayName})...")
        val backend = Procs.spawnBackground(
            command = listOf(
                ProjectPaths.gradlew.toString(),
                ":backend:run",
                "--args=$BACKEND_PORT ${database.argLabel}",
                "--console=plain"
            ),
            workingDir = ProjectPaths.projectRoot,
            stdoutLog = Logs.logFile("backend")
        )
        Procs.writePidFile(Logs.pidFile("backend"), backend.pid())
        println("   Backend started (PID: ${backend.pid()}, log: logs/backend.log)")

        println()
        println("6. Waiting for backend to be ready...")
        val http = Http(baseUrl)
        val ready = Procs.waitUntil(timeoutSeconds = 60) {
            runCatching { http.get("/health").ok }.getOrDefault(false)
        }
        if (!ready) Output.error("Backend did not start in 60 seconds; see logs/backend.log")
        Output.success("Backend ready!")

        // 7: load scenario data
        println()
        println("7. Loading scenario data: $displayName...")
        loadScenario(http, scenarioJson)

        // 8-9: frontend
        println()
        println("8. Starting frontend server...")
        val launcherName = if (ProjectPaths.isWindows) "vote-dev.bat" else "vote-dev"
        val launcher = ProjectPaths.toolsDir.resolve("build/install/vote-dev/bin/$launcherName")
        val frontend = Procs.spawnBackground(
            command = listOf(launcher.toString(), "serve-frontend"),
            workingDir = ProjectPaths.projectRoot,
            stdoutLog = Logs.logFile("frontend")
        )
        Procs.writePidFile(Logs.pidFile("frontend"), frontend.pid())
        println("   Frontend started (PID: ${frontend.pid()}, log: logs/frontend.log)")

        println()
        println("9. Waiting for frontend to be ready...")
        if (!Procs.waitUntil(timeoutSeconds = 30) { Procs.isPortOpen(FRONTEND_PORT) }) {
            Output.error("Frontend did not start in 30 seconds; see logs/frontend.log")
        }
        Output.success("Frontend ready!")

        println()
        println("10. Opening browser...")
        Procs.openBrowser("http://localhost:$FRONTEND_PORT")

        println()
        Output.banner("Scenario Loaded: $displayName")
        val candidateCount = (scenarioJson["candidates"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0
        val voterCount = (scenarioJson["voters"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0
        val ballotCount = (scenarioJson["ballots"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0
        val ownerName = (scenarioJson["ownerName"] as? JsonPrimitive)?.contentOrNull ?: "owner"
        val electionName = (scenarioJson["electionName"] as? JsonPrimitive)?.contentOrNull ?: ""
        println("Backend:    http://localhost:$BACKEND_PORT (${database.displayName})")
        println("Frontend:   http://localhost:$FRONTEND_PORT")
        println("Election:   $electionName")
        println("Candidates: $candidateCount")
        println("Voters:     $voterCount")
        println("Ballots:    $ballotCount")
        println()
        println("Login credentials:")
        println("  Owner: $ownerName / password")
        println("  All voters: <voter-name> / password")
        println()
        println("To stop: scripts/dev terminate-all")
    }

    private fun loadScenario(http: Http, scenario: JsonObject) {
        val ownerName = (scenario["ownerName"] as? JsonPrimitive)?.contentOrNull ?: "owner"
        val electionName = (scenario["electionName"] as? JsonPrimitive)?.contentOrNull
            ?: error("Scenario missing electionName")
        val candidates = (scenario["candidates"] as? kotlinx.serialization.json.JsonArray) ?: error("missing candidates")
        val voters = (scenario["voters"] as? kotlinx.serialization.json.JsonArray) ?: error("missing voters")
        val ballots = (scenario["ballots"] as? kotlinx.serialization.json.JsonArray) ?: error("missing ballots")

        println("   Registering owner ($ownerName)...")
        val ownerToken = register(http, ownerName, "owner@example.com")
        Output.success("Owner registered")

        println("   Registering ${voters.size} voters...")
        val voterTokens = mutableMapOf<String, String>()
        voters.forEach { voterEl ->
            val voter = voterEl.jsonObject
            val voterName = (voter["displayName"] as JsonPrimitive).content
            val voterEmail = (voter["email"] as JsonPrimitive).content
            voterTokens[voterName] = register(http, voterName, voterEmail)
        }
        Output.success("Registered ${voters.size} voters")

        val electionPath = "/election/${Http.urlEncode(electionName)}"

        println("   Creating election...")
        http.ensureSuccess(
            "POST", "/election",
            http.post(
                "/election",
                """{"userName":"$ownerName","electionName":${escape(electionName)}}""",
                ownerToken
            ),
            "Create election"
        )
        Output.success("Election created")

        val candidateNames = candidates.map { (it.jsonObject["displayName"] as JsonPrimitive).content }
        http.ensureSuccess(
            "PUT", "$electionPath/candidates",
            http.put(
                "$electionPath/candidates",
                """{"candidateNames":${jsonArrayOfStrings(candidateNames)}}""",
                ownerToken
            ),
            "Set candidates"
        )
        Output.success("Added ${candidateNames.size} candidates")

        val voterNames = voters.map { (it.jsonObject["displayName"] as JsonPrimitive).content }
        http.ensureSuccess(
            "PUT", "$electionPath/eligibility",
            http.put(
                "$electionPath/eligibility",
                """{"voterNames":${jsonArrayOfStrings(voterNames)}}""",
                ownerToken
            ),
            "Set eligibility"
        )
        Output.success("Added ${voterNames.size} eligible voters")

        http.ensureSuccess(
            "POST", "$electionPath/launch",
            http.post("$electionPath/launch", """{"allowEdit":false}""", ownerToken),
            "Launch election"
        )
        Output.success("Election launched (revealed ballots)")

        println("   Casting ${ballots.size} ballots...")
        ballots.forEach { ballotEl ->
            val ballot = ballotEl.jsonObject
            val voterName = (ballot["voterDisplayName"] as JsonPrimitive).content
            val token = voterTokens[voterName]
                ?: error("No token for voter $voterName")
            val rankings = (ballot["rankings"] as kotlinx.serialization.json.JsonArray)
                .joinToString(",", "[", "]") { rEl ->
                    val r = rEl.jsonObject
                    val cName = (r["candidateDisplayName"] as JsonPrimitive).content
                    val rank = (r["rank"] as JsonPrimitive).content
                    """{"candidateName":${escape(cName)},"rank":$rank}"""
                }
            http.ensureSuccess(
                "POST", "$electionPath/ballot",
                http.post(
                    "$electionPath/ballot",
                    """{"voterName":${escape(voterName)},"rankings":$rankings}""",
                    token
                ),
                "Cast ballot for $voterName"
            )
        }
        Output.success("Cast ${ballots.size} ballots")
    }

    private fun register(http: Http, userName: String, email: String): String {
        val body = """{"userName":${escape(userName)},"email":${escape(email)},"password":"password"}"""
        val response = http.ensureSuccess(
            "POST", "/register",
            http.post("/register", body),
            "Register $userName"
        )
        return response.fieldOrNull("accessToken")
            ?: Output.error("Registration response for $userName missing accessToken: ${response.body}")
    }

    private fun findScenarioFile(pattern: String): Path? {
        val dir = ProjectPaths.scenarioDataDir
        if (!dir.exists()) return null
        return Files.list(dir).use { stream ->
            stream
                .filter { it.fileName.toString().endsWith(".json") }
                .filter { it.fileName.toString().startsWith(pattern) }
                .findFirst()
                .orElse(null)
        }
    }

    private fun errorListing(message: String): Nothing {
        System.err.println("ERROR: $message")
        System.err.println()
        System.err.println("Available scenarios:")
        val dir = ProjectPaths.scenarioDataDir
        if (dir.exists()) {
            Files.list(dir).use { stream ->
                stream
                    .filter { it.fileName.toString().endsWith(".json") }
                    .sorted()
                    .forEach { System.err.println("  ${it.fileName.toString().removeSuffix(".json")}") }
            }
        } else {
            System.err.println("  (none — run scripts/dev convert-scenarios first)")
        }
        kotlin.system.exitProcess(1)
    }
}

private fun escape(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun jsonArrayOfStrings(list: List<String>): String =
    list.joinToString(",", "[", "]") { escape(it) }

class LaunchScenarioMysql : CliktCommand(name = "launch-scenario-mysql") {
    override fun help(context: Context) = "Launch with MySQL preloaded with a named scenario."
    val scenario: String by argument(name = "SCENARIO")
    override fun run() = ScenarioLauncher(Database.Mysql).run(scenario)
}

class LaunchScenarioDynamodb : CliktCommand(name = "launch-scenario-dynamodb") {
    override fun help(context: Context) = "Launch with DynamoDB preloaded with a named scenario."
    val scenario: String by argument(name = "SCENARIO")
    override fun run() = ScenarioLauncher(Database.Dynamodb).run(scenario)
}
