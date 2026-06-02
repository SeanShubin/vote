package com.seanshubin.vote.tools.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.seanshubin.vote.backend.repository.DynamoDbSingleTableSchema
import com.seanshubin.vote.tools.lib.DynamoClient
import com.seanshubin.vote.tools.lib.Output
import kotlinx.coroutines.runBlocking

/**
 * Idempotent reconciliation of the vote_data projection table against the
 * shape declared in [DynamoDbSingleTableSchema.expectedMainTableShape].
 *
 * If the live shape already matches, the command exits without touching
 * production. If it differs (added/removed/changed GSIs, key changes, new
 * attribute definitions), the command:
 *
 *   1. Pauses the event log so no writes land mid-rebuild.
 *   2. Drops vote_data.
 *   3. Recreates it via CreateTable with all GSIs declared up front —
 *      sidestepping DynamoDB's 1-GSI-per-UpdateTable limit.
 *   4. Initializes the sync cursor to 0.
 *   5. Replays the entire event log into the new (empty) projection;
 *      the new GSIs populate as the projection writes happen, so there
 *      is no separate backfill phase.
 *   6. Resumes the event log.
 *
 * Run as the post-deploy step in CI on every push — when nothing changed
 * it is a fast no-op (one DescribeTable call); when the projection shape
 * changed it is the canonical reconciliation. Same code path is reachable
 * by hand for local recovery.
 */
class RebuildProjection : CliktCommand(name = "rebuild-projection") {
    private val prod by option("--prod", help = "Target real AWS DynamoDB instead of DynamoDB Local.").flag()
    private val yes by option("--yes", help = "Skip the confirmation prompt (scripted use).").flag()
    private val checkOnly by option(
        "--check",
        help = "Exit 0 if the live table matches the expected shape, exit 1 if it differs. Does not modify anything.",
    ).flag()

    override fun help(context: Context) =
        "Rebuild vote_data from vote_event_log when an invariant is violated. " +
            "Triggers on shape mismatch OR cursor-ahead-of-log " +
            "(last_synced > max(event_id), which happens after delete-event removed a tail event " +
            "without rewinding the cursor). Idempotent: no-op when both invariants hold."

    override fun run() {
        val target = DynamoClient.describe(prod)
        runBlocking {
            DynamoClient.createFor(prod).use { client ->
                val live = DynamoDbSingleTableSchema.readLiveMainTableShape(client)
                val shapeDiff = DynamoDbSingleTableSchema.expectedMainTableShape.diffFrom(live)
                val lastSynced = DynamoDbSingleTableSchema.readLastSynced(client)
                val maxEventId = DynamoDbSingleTableSchema.readMaxEventId(client)
                val cursorAhead = lastSynced > maxEventId

                if (shapeDiff == null && !cursorAhead) {
                    Output.success(
                        "vote_data shape matches expected and cursor invariant holds " +
                            "(last_synced=$lastSynced <= max(event_id)=$maxEventId); " +
                            "no rebuild needed on $target."
                    )
                    return@runBlocking
                }

                Output.banner("Projection rebuild required on $target")
                if (shapeDiff != null) println("Shape diff: $shapeDiff")
                if (cursorAhead) {
                    println(
                        "Cursor invariant violated: last_synced=$lastSynced > max(event_id)=$maxEventId. " +
                            "Projection references events that no longer exist in the log."
                    )
                }

                if (checkOnly) {
                    Output.error("Reconciliation required (--check). Run without --check to rebuild.")
                }

                if (!yes) requireConfirmation(prod)

                ProjectionRebuilder.rebuild(client)
            }
        }
    }

    private fun requireConfirmation(prod: Boolean) {
        if (prod) {
            print("Type 'rebuild production' to continue: ")
            val typed = readlnOrNull()?.trim()
            if (typed != "rebuild production") Output.error("Aborted (got: ${typed ?: "<eof>"}).")
        } else {
            print("Rebuild local DynamoDB projection? Type 'y' to continue: ")
            val typed = readlnOrNull()?.trim()
            if (typed != "y" && typed != "yes") Output.error("Aborted.")
        }
    }
}
