package com.seanshubin.vote.tools.commands

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.DeleteItemRequest
import aws.sdk.kotlin.services.dynamodb.model.GetItemRequest
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.seanshubin.vote.tools.lib.DynamoClient
import com.seanshubin.vote.tools.lib.Output
import kotlinx.coroutines.runBlocking

/**
 * Delete one or more rows from vote_event_log by event_id.
 *
 * The event log is the source of truth and the projection is replayed from
 * it, so this is surgery — use it only to drop a poison event that makes
 * synchronize() throw (which freezes the projection for every event after
 * it). Once the bad event is gone, synchronize() can advance past it again.
 * Deleting an event that later events depend on will corrupt the replay.
 */
class DeleteEvent : CliktCommand(name = "delete-event") {
    private val eventIds: List<String> by argument(
        name = "EVENT_ID",
        help = "One or more event_id values to delete.",
    ).multiple(required = true)
    private val prod by option("--prod", help = "Target real AWS DynamoDB instead of DynamoDB Local.").flag()
    private val yes by option("--yes", help = "Skip the confirmation prompt (scripted use).").flag()

    override fun help(context: Context) =
        "Delete event(s) from vote_event_log by event_id. Source-of-truth surgery — use with care."

    override fun run() = runBlocking {
        val ids = eventIds
            .map { raw -> raw.trim().toLongOrNull() ?: Output.error("Not a valid event_id: $raw") }
            .distinct()
            .sorted()

        Output.banner("Delete event(s) ${ids.joinToString(", ")} from ${DynamoClient.describe(prod)}")

        DynamoClient.createFor(prod).use { client ->
            // Show what each id points at before touching anything, so the
            // operator sees exactly what they are about to remove.
            val present = mutableListOf<Long>()
            for (id in ids) {
                val item = client.getItem(GetItemRequest {
                    tableName = DynamoClient.TABLE_EVENT_LOG
                    key = mapOf("event_id" to AttributeValue.N(id.toString()))
                }).item
                if (item == null) {
                    println("  event_id $id — not found, skipping")
                } else {
                    val type = DynamoClient.s(item, "event_type") ?: "?"
                    val data = (DynamoClient.s(item, "event_data") ?: "")
                        .replace(Regex("\\s+"), " ").trim().take(140)
                    println("  event_id $id — $type: $data")
                    present.add(id)
                }
            }

            if (present.isEmpty()) {
                Output.success("Nothing to delete.")
                return@use
            }

            if (!yes) requireConfirmation(prod, present)

            for (id in present) {
                client.deleteItem(DeleteItemRequest {
                    tableName = DynamoClient.TABLE_EVENT_LOG
                    key = mapOf("event_id" to AttributeValue.N(id.toString()))
                })
                println("Deleted event_id $id.")
            }
            Output.success("Deleted ${present.size} event(s).")
        }
    }

    private fun requireConfirmation(prod: Boolean, ids: List<Long>) {
        val idList = ids.joinToString(", ")
        if (prod) {
            print("Type 'delete from production' to delete event(s) $idList: ")
            val typed = readlnOrNull()?.trim()
            if (typed != "delete from production") Output.error("Aborted (got: ${typed ?: "<eof>"}).")
        } else {
            print("Delete event(s) $idList from local DynamoDB? Type 'y' to continue: ")
            val typed = readlnOrNull()?.trim()
            if (typed != "y" && typed != "yes") Output.error("Aborted.")
        }
    }
}
