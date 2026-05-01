package com.seanshubin.vote.tools.commands

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.PutItemRequest
import aws.sdk.kotlin.services.dynamodb.model.ReturnValue
import aws.sdk.kotlin.services.dynamodb.model.UpdateItemRequest
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.seanshubin.vote.backend.repository.DynamoDbEventLog
import com.seanshubin.vote.backend.repository.DynamoDbSingleTableCommandModel
import com.seanshubin.vote.backend.repository.DynamoDbSingleTableQueryModel
import com.seanshubin.vote.backend.repository.DynamoDbSingleTableSchema
import com.seanshubin.vote.backend.service.EventApplier
import com.seanshubin.vote.domain.EventEnvelope
import com.seanshubin.vote.tools.lib.DynamoClient
import com.seanshubin.vote.tools.lib.Output
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

class RestoreDynamodb : CliktCommand(name = "restore-dynamodb") {
    private val inputPath by argument(name = "file", help = "JSONL backup file produced by backup-dynamodb.")
    private val prod by option("--prod", help = "Target real AWS DynamoDB instead of DynamoDB Local.").flag()
    private val yes by option("--yes", help = "Skip the confirmation prompt (scripted use).").flag()

    override fun help(context: Context) =
        "Replay events from a backup file into vote_event_log, then rebuild vote_data. Refuses if event log is non-empty."

    override fun run() = runBlocking {
        val file = Path.of(inputPath)
        if (!file.exists()) Output.error("File not found: $file")

        val target = DynamoClient.describe(prod)
        Output.banner("Restoring event log to $target")

        val json = Json { ignoreUnknownKeys = true }
        val envelopes = readEnvelopes(file, json)
        if (envelopes.isEmpty()) Output.error("Backup file contains no events: $file")

        val sorted = envelopes.sortedBy { it.eventId }
        val maxEventId = sorted.last().eventId
        println("Read ${sorted.size} event(s); event_id range ${sorted.first().eventId}..$maxEventId")

        if (!yes) {
            requireConfirmation(prod)
        }

        DynamoClient.createFor(prod).use { client ->
            val existingEvents = countTable(client, DynamoClient.TABLE_EVENT_LOG)
            if (existingEvents > 0) {
                Output.error(
                    "Refusing to restore: ${DynamoClient.TABLE_EVENT_LOG} already contains $existingEvents item(s). " +
                        "Run nuke-dynamodb first to start from a clean state."
                )
            }

            putEvents(client, sorted, json)
            println("Wrote ${sorted.size} event(s) into ${DynamoClient.TABLE_EVENT_LOG}.")

            setEventCounter(client, maxEventId)
            println("Set EVENT_COUNTER = $maxEventId.")

            val applier = EventApplier(
                eventLog = DynamoDbEventLog(client, json),
                commandModel = DynamoDbSingleTableCommandModel(client, json),
                queryModel = DynamoDbSingleTableQueryModel(client, json),
            )
            applier.synchronize()
            println("Rebuilt projection in ${DynamoClient.TABLE_DATA}.")
        }

        Output.success("Restore complete.")
    }

    private fun requireConfirmation(prod: Boolean) {
        if (prod) {
            print("Type 'restore production' to continue: ")
            val typed = readlnOrNull()?.trim()
            if (typed != "restore production") Output.error("Aborted (got: ${typed ?: "<eof>"}).")
        } else {
            print("Restore into local DynamoDB? Type 'y' to continue: ")
            val typed = readlnOrNull()?.trim()
            if (typed != "y" && typed != "yes") Output.error("Aborted.")
        }
    }

    private fun readEnvelopes(file: Path, json: Json): List<EventEnvelope> {
        val result = mutableListOf<EventEnvelope>()
        Files.newBufferedReader(file).useLines { lines ->
            lines.forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEachIndexed
                try {
                    result.add(json.decodeFromString<EventEnvelope>(line))
                } catch (e: Exception) {
                    Output.error("Failed to parse line ${index + 1} of $file: ${e.message}")
                }
            }
        }
        return result
    }

    private suspend fun putEvents(client: DynamoDbClient, envelopes: List<EventEnvelope>, json: Json) {
        envelopes.forEach { envelope ->
            val eventType = envelope.event::class.simpleName ?: "Unknown"
            client.putItem(PutItemRequest {
                tableName = DynamoClient.TABLE_EVENT_LOG
                item = mapOf(
                    "event_id" to AttributeValue.N(envelope.eventId.toString()),
                    "authority" to AttributeValue.S(envelope.authority),
                    "event_type" to AttributeValue.S(eventType),
                    "event_data" to AttributeValue.S(json.encodeToString(envelope.event)),
                    "created_at" to AttributeValue.N(envelope.whenHappened.toEpochMilliseconds().toString()),
                )
            })
        }
    }

    private suspend fun setEventCounter(client: DynamoDbClient, value: Long) {
        client.updateItem(UpdateItemRequest {
            tableName = DynamoDbSingleTableSchema.MAIN_TABLE
            key = mapOf(
                "PK" to AttributeValue.S("METADATA"),
                "SK" to AttributeValue.S("EVENT_COUNTER"),
            )
            updateExpression = "SET next_event_id = :v"
            expressionAttributeValues = mapOf(":v" to AttributeValue.N(value.toString()))
            returnValues = ReturnValue.None
        })
    }
}
