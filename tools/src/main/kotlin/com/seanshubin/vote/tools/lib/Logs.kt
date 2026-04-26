package com.seanshubin.vote.tools.lib

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.exists

object Logs {
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")

    /**
     * Move existing logs into the archive directory with a timestamp suffix.
     * Returns the names of files that were rolled (e.g. "backend.log").
     */
    fun roll(): List<String> {
        val logDir = ProjectPaths.logsDir
        val archiveDir = ProjectPaths.logsArchiveDir
        Files.createDirectories(logDir)
        Files.createDirectories(archiveDir)

        val timestamp = LocalDateTime.now().format(timestampFormat)
        val rolled = mutableListOf<String>()

        listOf("backend.log", "frontend.log").forEach { name ->
            val source = logDir.resolve(name)
            if (source.exists()) {
                val basename = name.removeSuffix(".log")
                val target = archiveDir.resolve("$basename-$timestamp.log")
                Files.move(source, target)
                rolled.add(name)
            }
        }
        return rolled
    }

    fun pidFile(name: String): Path = ProjectPaths.logsDir.resolve("$name.pid")
    fun logFile(name: String): Path = ProjectPaths.logsDir.resolve("$name.log")
}
