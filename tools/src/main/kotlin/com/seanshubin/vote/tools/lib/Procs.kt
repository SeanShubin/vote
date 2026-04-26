package com.seanshubin.vote.tools.lib

import java.awt.Desktop
import java.io.File
import java.lang.ProcessBuilder.Redirect
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

object Procs {

    data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String) {
        val success: Boolean get() = exitCode == 0
    }

    /**
     * Run a command and capture both stdout and stderr. Throws if the executable cannot start.
     */
    fun run(
        vararg command: String,
        workingDir: Path? = null,
        env: Map<String, String> = emptyMap(),
        stdin: String? = null
    ): ExecResult {
        val pb = ProcessBuilder(*command)
        if (workingDir != null) pb.directory(workingDir.toFile())
        if (env.isNotEmpty()) pb.environment().putAll(env)
        pb.redirectErrorStream(false)

        val process = pb.start()
        if (stdin != null) {
            process.outputStream.use { it.write(stdin.toByteArray()) }
        } else {
            process.outputStream.close()
        }
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        val code = process.waitFor()
        return ExecResult(code, out, err)
    }

    /**
     * Run a command, fail loudly if non-zero. Returns stdout.
     */
    fun runOrFail(
        vararg command: String,
        workingDir: Path? = null,
        env: Map<String, String> = emptyMap(),
        stdin: String? = null,
        description: String? = null
    ): String {
        val result = run(*command, workingDir = workingDir, env = env, stdin = stdin)
        if (!result.success) {
            val label = description ?: command.joinToString(" ")
            System.err.println("ERROR: $label failed (exit ${result.exitCode})")
            if (result.stdout.isNotBlank()) System.err.println(result.stdout.trim())
            if (result.stderr.isNotBlank()) System.err.println(result.stderr.trim())
            kotlin.system.exitProcess(result.exitCode)
        }
        return result.stdout
    }

    /**
     * Spawn a long-running child process detached from stdin and pointed at log files.
     * Returns the Process so the caller can record its PID.
     */
    fun spawnBackground(
        command: List<String>,
        workingDir: Path,
        stdoutLog: Path,
        stderrLog: Path = stdoutLog,
        env: Map<String, String> = emptyMap()
    ): Process {
        Files.createDirectories(stdoutLog.parent)
        val pb = ProcessBuilder(command)
        pb.directory(workingDir.toFile())
        if (env.isNotEmpty()) pb.environment().putAll(env)
        pb.redirectInput(Redirect.from(File(if (ProjectPaths.isWindows) "NUL" else "/dev/null")))
        if (stdoutLog == stderrLog) {
            pb.redirectErrorStream(true)
            pb.redirectOutput(Redirect.appendTo(stdoutLog.toFile()))
        } else {
            pb.redirectOutput(Redirect.appendTo(stdoutLog.toFile()))
            pb.redirectError(Redirect.appendTo(stderrLog.toFile()))
        }
        return pb.start()
    }

    /**
     * Open a URL in the system's default browser.
     */
    fun openBrowser(url: String) {
        val uri = URI.create(url)
        val desktopOk = runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri)
                true
            } else false
        }.getOrDefault(false)
        if (desktopOk) return

        // Fallback: shell out to OS-specific opener
        val command = when {
            ProjectPaths.isWindows -> arrayOf("rundll32", "url.dll,FileProtocolHandler", url)
            System.getProperty("os.name").lowercase().contains("mac") -> arrayOf("open", url)
            else -> arrayOf("xdg-open", url)
        }
        runCatching { ProcessBuilder(*command).start() }
    }

    /**
     * Wait until [check] returns true or timeout expires. Returns true if successful.
     */
    fun waitUntil(timeoutSeconds: Int, intervalMs: Long = 1000, check: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(check).getOrDefault(false)) return true
            Thread.sleep(intervalMs)
        }
        return false
    }

    /**
     * Test whether something is listening on localhost:port.
     */
    fun isPortOpen(port: Int, timeoutMs: Int = 500): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("127.0.0.1", port), timeoutMs)
                true
            }
        }.getOrDefault(false)
    }

    /**
     * Best-effort kill of any process with the given PID.
     */
    fun killPid(pid: Long, force: Boolean = true): Boolean {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return false
        return if (force) handle.destroyForcibly() else handle.destroy()
    }

    /**
     * Find the PID listening on a TCP port. Returns null if nothing found or the lookup fails.
     * Uses native shells: netstat on Windows, lsof on Unix.
     */
    fun findPidByPort(port: Int): Long? {
        return if (ProjectPaths.isWindows) {
            val result = run("netstat", "-ano", "-p", "tcp")
            if (!result.success) return null
            result.stdout.lineSequence()
                .filter { it.contains(":$port ") || it.contains(":$port\t") }
                .filter { it.contains("LISTENING", ignoreCase = true) }
                .mapNotNull { line ->
                    line.trim().split(Regex("\\s+")).lastOrNull()?.toLongOrNull()
                }
                .firstOrNull()
        } else {
            val result = run("lsof", "-ti", ":$port")
            if (!result.success) return null
            result.stdout.lineSequence().mapNotNull { it.trim().toLongOrNull() }.firstOrNull()
        }
    }

    /**
     * Stop a process on the given port. First tries the PID file (under logs/),
     * then falls back to port-based discovery. Returns true if anything was killed.
     */
    fun killByPort(port: Int, pidFile: Path? = null): Boolean {
        val killed = mutableListOf<Long>()

        if (pidFile != null && pidFile.exists()) {
            val recorded = pidFile.readText().trim().toLongOrNull()
            if (recorded != null && killPid(recorded)) killed.add(recorded)
            runCatching { Files.deleteIfExists(pidFile) }
        }

        val pidFromPort = findPidByPort(port)
        if (pidFromPort != null && pidFromPort !in killed) {
            if (killPid(pidFromPort)) killed.add(pidFromPort)
        }
        return killed.isNotEmpty()
    }

    fun writePidFile(path: Path, pid: Long) {
        Files.createDirectories(path.parent)
        path.writeText(pid.toString())
    }
}
