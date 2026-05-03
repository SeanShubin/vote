package com.seanshubin.vote.tools.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.seanshubin.vote.tools.lib.Output
import com.seanshubin.vote.tools.lib.ProjectPaths
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.exists

class LaunchFromSnapshot : CliktCommand(name = "launch-from-snapshot") {
    private val snapshotOption: String? by option(
        "--snapshot",
        help = "Path to an existing JSONL snapshot file (e.g. one produced by backup-dynamodb or generate-scenario-event-log)."
    )
    private val prod by option(
        "--prod",
        help = "Download a fresh snapshot from AWS DynamoDB before launching. Saved under .local/prod-snapshots/."
    ).flag()

    override fun help(context: Context) =
        "Replay a JSONL event-log snapshot into local DynamoDB and launch the dev environment. " +
            "Source the snapshot via --prod (download fresh from AWS) or --snapshot <file> (use an existing file). " +
            "Exactly one of the two is required."

    override fun run() {
        val snapshot = resolveSnapshot()

        LaunchPlan(
            database = Database.Dynamodb,
            freshStart = true,
            bannerSuffix = "From Snapshot",
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
        if (explicit != null && prod) {
            Output.error("Pass either --snapshot <file> or --prod, not both.")
        }
        if (explicit == null && !prod) {
            Output.error("Specify --snapshot <file> or --prod.")
        }

        if (explicit != null) {
            val file = Path.of(explicit)
            if (!file.exists()) Output.error("Snapshot file not found: $file")
            println("Using existing snapshot: $file")
            return file
        }

        // --prod: download fresh from AWS into a timestamped file under .local/prod-snapshots/
        Files.createDirectories(ProjectPaths.prodSnapshotDir)
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val target = ProjectPaths.prodSnapshotDir.resolve("prod-snapshot-$timestamp.jsonl")

        runBlocking {
            BackupDynamodb.backupEventLog(prod = true, file = target)
        }
        return target
    }
}
