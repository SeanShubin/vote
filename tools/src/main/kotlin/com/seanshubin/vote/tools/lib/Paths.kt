package com.seanshubin.vote.tools.lib

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute

object ProjectPaths {
    val projectRoot: Path = findProjectRoot()
    val toolsDir: Path get() = projectRoot.resolve("tools")
    val scriptsDir: Path get() = projectRoot.resolve("scripts")
    val logsDir: Path get() = projectRoot.resolve("logs")
    val logsArchiveDir: Path get() = logsDir.resolve("archive")
    val scenarioDataDir: Path get() = projectRoot.resolve("scenario-data")
    val prodSnapshotDir: Path get() = projectRoot.resolve(".local/prod-snapshots")
    val frontendDistDir: Path get() = projectRoot.resolve("frontend/build/dist/js/productionExecutable")
    val backendSchemaSql: Path get() = projectRoot.resolve("backend/src/main/resources/database/schema.sql")
    val gradlew: Path get() = projectRoot.resolve(if (isWindows) "gradlew.bat" else "gradlew")

    val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("windows")

    private fun findProjectRoot(): Path {
        var current: Path? = Path.of("").absolute()
        while (current != null) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) {
                return current
            }
            current = current.parent
        }
        return Path.of("").absolute()
    }
}
