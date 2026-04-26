package com.seanshubin.vote.tools.lib

object Docker {

    /**
     * Whether the docker command is available on the PATH.
     */
    fun isAvailable(): Boolean {
        return runCatching {
            Procs.run("docker", "version", "--format", "{{.Server.Version}}").success
        }.getOrDefault(false)
    }

    fun requireAvailable() {
        if (!isAvailable()) {
            Output.error("docker is not available. Install Docker Desktop and ensure the engine is running.")
        }
    }

    /**
     * Whether a container with the given name exists (running or stopped).
     */
    fun containerExists(name: String): Boolean {
        val result = Procs.run("docker", "ps", "-a", "--format", "{{.Names}}")
        if (!result.success) return false
        return result.stdout.lineSequence().any { it.trim() == name }
    }

    /**
     * Whether a container with the given name is currently running.
     */
    fun containerRunning(name: String): Boolean {
        val result = Procs.run("docker", "ps", "--format", "{{.Names}}")
        if (!result.success) return false
        return result.stdout.lineSequence().any { it.trim() == name }
    }

    fun start(name: String) {
        Procs.runOrFail("docker", "start", name, description = "docker start $name")
    }

    fun stop(name: String) {
        // Best-effort; do not fail if not running.
        Procs.run("docker", "stop", name)
    }

    fun remove(name: String) {
        Procs.run("docker", "rm", name)
    }

    /**
     * Run a docker container. Args after the image are appended verbatim.
     */
    fun run(
        name: String,
        image: String,
        ports: Map<Int, Int> = emptyMap(),
        env: Map<String, String> = emptyMap(),
        extraArgs: List<String> = emptyList(),
        imageArgs: List<String> = emptyList()
    ) {
        val command = buildList {
            add("docker")
            add("run")
            add("-d")
            add("--name"); add(name)
            ports.forEach { (host, container) -> add("-p"); add("$host:$container") }
            env.forEach { (k, v) -> add("-e"); add("$k=$v") }
            addAll(extraArgs)
            add(image)
            addAll(imageArgs)
        }
        Procs.runOrFail(*command.toTypedArray(), description = "docker run $name")
    }

    /**
     * Exec a command inside a running container. Optionally pipe stdin.
     */
    fun exec(
        container: String,
        command: List<String>,
        stdin: String? = null,
        interactive: Boolean = stdin != null
    ): Procs.ExecResult {
        val full = buildList {
            add("docker")
            add("exec")
            if (interactive) add("-i")
            add(container)
            addAll(command)
        }
        return Procs.run(*full.toTypedArray(), stdin = stdin)
    }

    /**
     * Exec; fail loudly on non-zero. Returns stdout.
     */
    fun execOrFail(
        container: String,
        command: List<String>,
        stdin: String? = null,
        description: String? = null
    ): String {
        val result = exec(container, command, stdin)
        if (!result.success) {
            val label = description ?: "docker exec ${command.joinToString(" ")}"
            System.err.println("ERROR: $label failed (exit ${result.exitCode})")
            if (result.stdout.isNotBlank()) System.err.println(result.stdout.trim())
            if (result.stderr.isNotBlank()) System.err.println(result.stderr.trim())
            kotlin.system.exitProcess(result.exitCode)
        }
        return result.stdout
    }
}
