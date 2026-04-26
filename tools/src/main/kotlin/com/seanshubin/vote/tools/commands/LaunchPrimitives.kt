package com.seanshubin.vote.tools.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.seanshubin.vote.tools.lib.Logs
import com.seanshubin.vote.tools.lib.Output
import com.seanshubin.vote.tools.lib.Procs
import com.seanshubin.vote.tools.lib.ProjectPaths
import com.seanshubin.vote.tools.lib.StaticServer
import kotlin.io.path.exists

const val BACKEND_PORT = 8080
const val FRONTEND_PORT = 3000

class TerminateAll : CliktCommand(name = "terminate-all") {
    override fun help(context: Context) = "Kill backend (8080) and frontend (3000) processes."

    override fun run() {
        println("Terminating all backend and frontend processes...")

        val backendKilled = Procs.killByPort(BACKEND_PORT, Logs.pidFile("backend"))
        if (backendKilled) {
            println("Killed backend on port $BACKEND_PORT.")
        } else {
            println("No backend process found on port $BACKEND_PORT.")
        }

        val frontendKilled = Procs.killByPort(FRONTEND_PORT, Logs.pidFile("frontend"))
        if (frontendKilled) {
            println("Killed frontend on port $FRONTEND_PORT.")
        } else {
            println("No frontend process found on port $FRONTEND_PORT.")
        }

        Output.success("All processes terminated")
    }
}

class RollLogs : CliktCommand(name = "roll-logs") {
    override fun help(context: Context) = "Move current logs into the archive directory."

    override fun run() {
        val rolled = Logs.roll()
        if (rolled.isEmpty()) {
            println("No logs to roll.")
        } else {
            rolled.forEach { name ->
                println("Rolled $name -> archive/")
            }
        }
    }
}

class ServeFrontend : CliktCommand(name = "serve-frontend") {
    override fun help(context: Context) = "Serve the built frontend on http://localhost:$FRONTEND_PORT (blocks)."

    override fun run() {
        val dist = ProjectPaths.frontendDistDir
        if (!dist.exists()) {
            println("Frontend not built at $dist.")
            println("Building now (./gradlew :frontend:build)...")
            Procs.runOrFail(
                ProjectPaths.gradlew.toString(),
                ":frontend:build",
                "--no-daemon",
                workingDir = ProjectPaths.projectRoot,
                description = "Build frontend"
            )
        }

        println("Serving frontend from $dist on http://localhost:$FRONTEND_PORT")
        val server = StaticServer.start(dist, FRONTEND_PORT)
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { server.stop() } })
        server.join()
    }
}
