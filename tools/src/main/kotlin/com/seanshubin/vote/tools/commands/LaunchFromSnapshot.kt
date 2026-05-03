package com.seanshubin.vote.tools.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.seanshubin.vote.tools.lib.Output
import com.seanshubin.vote.tools.lib.ProjectPaths
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.exists

class LaunchFromProd : CliktCommand(name = "launch-from-prod") {
    private val snapshotOption: String? by option(
        "--snapshot",
        help = "Use an existing snapshot file instead of downloading from prod. Skips the download step."
    )

    override fun help(context: Context) =
        "Download prod event log to a snapshot file, replay into local DynamoDB, then launch the dev environment. " +
            "Snapshots are saved under .local/prod-snapshots/ (gitignored). Requires AWS credentials."

    override fun run() {
        val snapshot = resolveSnapshot()

        LaunchPlan(
            database = Database.Dynamodb,
            freshStart = true,
            bannerSuffix = "From Prod Snapshot",
            postPurge = {
                runBlocking {
                    RestoreDynamodb.restoreEventLog(
                        prod = false,
                        file = snapshot,
                        skipConfirmation = true,
                    )
                }
            },
        ).run()
    }

    private fun resolveSnapshot(): Path {
        val explicit = snapshotOption
        if (explicit != null) {
            val file = Path.of(explicit)
            if (!file.exists()) Output.error("Snapshot file not found: $file")
            println("Using existing snapshot: $file")
            return file
        }

        Files.createDirectories(ProjectPaths.prodSnapshotDir)
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val target = ProjectPaths.prodSnapshotDir.resolve("prod-snapshot-$timestamp.jsonl")

        runBlocking {
            BackupDynamodb.backupEventLog(prod = true, file = target)
        }
        return target
    }
}
