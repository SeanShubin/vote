package com.seanshubin.vote.tools.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.seanshubin.vote.tools.lib.Logs
import com.seanshubin.vote.tools.lib.Output
import com.seanshubin.vote.tools.lib.Procs
import com.seanshubin.vote.tools.lib.ProjectPaths
import java.nio.file.Path

internal enum class Database(val argLabel: String, val displayName: String) {
    Mysql("mysql", "MySQL"),
    Dynamodb("dynamodb", "DynamoDB")
}

internal class LaunchPlan(
    private val database: Database,
    private val freshStart: Boolean,
    private val openBrowser: Boolean = true,
    private val bannerSuffix: String? = null,
    private val postPurge: (() -> Unit)? = null,
) {
    private var stepCounter = 0
    private fun step(label: String) {
        stepCounter++
        println()
        println("$stepCounter. $label")
    }

    fun run() {
        Logs.pidFile("backend") // ensure logs/ exists implicitly via writePidFile later
        val title = if (bannerSuffix != null) {
            "Launch $bannerSuffix (${database.displayName})"
        } else {
            "Launch ${if (freshStart) "Fresh" else "Keep"} (${database.displayName})"
        }
        Output.banner(title)
        // first step doesn't need a leading blank line — strip it inline
        stepCounter = 1
        println("1. Terminating existing processes...")
        TerminateAll().run()
        Thread.sleep(1000)

        step("Rolling logs...")
        RollLogs().run()

        if (freshStart) {
            step("Purging ${database.displayName} database...")
            when (database) {
                Database.Mysql -> PurgeMysql().run()
                Database.Dynamodb -> PurgeDynamodb().run()
            }
            postPurge?.let {
                step("Loading post-purge data...")
                it()
            }
        }

        step("Building frontend (incremental)...")
        Procs.runOrFail(
            ProjectPaths.gradlew.toString(),
            ":frontend:build",
            "--no-daemon",
            workingDir = ProjectPaths.projectRoot,
            description = "Build frontend"
        )

        step("Starting backend (${database.displayName})...")
        val backend = startBackend(database)
        Procs.writePidFile(Logs.pidFile("backend"), backend.pid())
        println("   Backend started (PID: ${backend.pid()}, log: logs/backend.log)")

        step("Starting frontend server...")
        val frontend = startFrontend()
        Procs.writePidFile(Logs.pidFile("frontend"), frontend.pid())
        println("   Frontend started (PID: ${frontend.pid()}, log: logs/frontend.log)")

        step("Waiting for backend to be ready...")
        val backendReady = Procs.waitUntil(timeoutSeconds = 60) {
            runCatching {
                val client = java.net.http.HttpClient.newHttpClient()
                val req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:$BACKEND_PORT/health")).build()
                client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode() == 200
            }.getOrDefault(false)
        }
        if (!backendReady) Output.error("Backend did not start within 60 seconds. Check logs/backend.log.")
        Output.success("Backend ready!")

        step("Waiting for frontend to be ready...")
        val frontendReady = Procs.waitUntil(timeoutSeconds = 30) { Procs.isPortOpen(FRONTEND_PORT) }
        if (!frontendReady) Output.error("Frontend did not start within 30 seconds. Check logs/frontend.log.")
        Output.success("Frontend ready!")

        if (openBrowser) {
            step("Opening browser...")
            Procs.openBrowser("http://localhost:$FRONTEND_PORT")
        }

        println()
        Output.banner("Development Environment Ready")
        println("Backend:  http://localhost:$BACKEND_PORT (${database.displayName})")
        println("Frontend: http://localhost:$FRONTEND_PORT")
        println("Logs:     logs/backend.log, logs/frontend.log")
        println()
        println("To stop: scripts/dev terminate-all")
    }

    private fun startBackend(database: Database): Process {
        val command = listOf(
            ProjectPaths.gradlew.toString(),
            ":backend:run",
            "--args=$BACKEND_PORT ${database.argLabel}",
            "--console=plain"
        )
        return Procs.spawnBackground(
            command = command,
            workingDir = ProjectPaths.projectRoot,
            stdoutLog = Logs.logFile("backend")
        )
    }

    private fun startFrontend(): Process {
        val launcher = launcherPath()
        val command = listOf(launcher.toString(), "serve-frontend")
        return Procs.spawnBackground(
            command = command,
            workingDir = ProjectPaths.projectRoot,
            stdoutLog = Logs.logFile("frontend")
        )
    }

    private fun launcherPath(): Path {
        val name = if (ProjectPaths.isWindows) "vote-dev.bat" else "vote-dev"
        return ProjectPaths.toolsDir.resolve("build/install/vote-dev/bin/$name")
    }
}

class LaunchFreshMysql : CliktCommand(name = "launch-fresh-mysql") {
    override fun help(context: Context) = "Fresh dev launch with MySQL (purge + start everything)."
    override fun run() = LaunchPlan(Database.Mysql, freshStart = true).run()
}

class LaunchFreshDynamodb : CliktCommand(name = "launch-fresh-dynamodb") {
    override fun help(context: Context) = "Fresh dev launch with DynamoDB (purge + start everything)."
    override fun run() = LaunchPlan(Database.Dynamodb, freshStart = true).run()
}

class LaunchKeepMysql : CliktCommand(name = "launch-keep-mysql") {
    override fun help(context: Context) = "Dev launch with MySQL, preserving existing data."
    override fun run() = LaunchPlan(Database.Mysql, freshStart = false).run()
}

class LaunchKeepDynamodb : CliktCommand(name = "launch-keep-dynamodb") {
    override fun help(context: Context) = "Dev launch with DynamoDB, preserving existing data."
    override fun run() = LaunchPlan(Database.Dynamodb, freshStart = false).run()
}

class RunLocal : CliktCommand(name = "run-local") {
    override fun help(context: Context) = "Convenience: launch-fresh-dynamodb."

    override fun run() {
        println("Launching fresh development environment (DynamoDB)...")
        println("For other options, run: scripts/dev --help")
        println()
        LaunchFreshDynamodb().run()
    }
}
