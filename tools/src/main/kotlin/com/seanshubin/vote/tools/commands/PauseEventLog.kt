package com.seanshubin.vote.tools.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.seanshubin.vote.backend.repository.DynamoDbEventLog
import com.seanshubin.vote.tools.lib.DynamoClient
import com.seanshubin.vote.tools.lib.Output
import kotlinx.serialization.json.Json

/**
 * Flip the EVENT_LOG_PAUSED flag in vote_data so the backend rejects new
 * writes. Bypasses HTTP — talks straight to DynamoDB — so the datafix
 * script doesn't need an owner-token round-trip or the web server to be
 * up. The flag is read with strongly-consistent reads on every append,
 * so all Lambdas honor the pause within a single request cycle.
 */
class PauseEventLog : CliktCommand(name = "pause-event-log") {
    private val prod by option("--prod", help = "Target real AWS DynamoDB instead of DynamoDB Local.").flag()
    private val yes by option("--yes", help = "Skip the confirmation prompt (scripted use).").flag()

    override fun help(context: Context) =
        "Pause the event log by setting the EVENT_LOG_PAUSED flag in vote_data. New writes will be rejected until resumed."

    override fun run() {
        val target = DynamoClient.describe(prod)
        Output.banner("Pausing event log on $target")
        if (!yes) requireConfirmation(prod, "pause")
        flipPause(prod, paused = true)
        Output.success("Event log paused. Resume with: vote-dev resume-event-log${if (prod) " --prod" else ""}")
    }
}

/**
 * Counterpart to [PauseEventLog]. Run after the deploy step lands so the
 * application starts accepting writes again.
 */
class ResumeEventLog : CliktCommand(name = "resume-event-log") {
    private val prod by option("--prod", help = "Target real AWS DynamoDB instead of DynamoDB Local.").flag()
    private val yes by option("--yes", help = "Skip the confirmation prompt (scripted use).").flag()

    override fun help(context: Context) =
        "Resume the event log by clearing the EVENT_LOG_PAUSED flag in vote_data."

    override fun run() {
        val target = DynamoClient.describe(prod)
        Output.banner("Resuming event log on $target")
        if (!yes) requireConfirmation(prod, "resume")
        flipPause(prod, paused = false)
        Output.success("Event log resumed.")
    }
}

private fun flipPause(prod: Boolean, paused: Boolean) {
    kotlinx.coroutines.runBlocking {
        DynamoClient.createFor(prod).use { client ->
            val eventLog = DynamoDbEventLog(client, Json { ignoreUnknownKeys = true })
            eventLog.setPaused(paused)
        }
    }
}

private fun requireConfirmation(prod: Boolean, verb: String) {
    if (prod) {
        print("Type '$verb production' to continue: ")
        val typed = readlnOrNull()?.trim()
        if (typed != "$verb production") Output.error("Aborted (got: ${typed ?: "<eof>"}).")
    } else {
        print("$verb local DynamoDB? Type 'y' to continue: ")
        val typed = readlnOrNull()?.trim()
        if (typed != "y" && typed != "yes") Output.error("Aborted.")
    }
}
