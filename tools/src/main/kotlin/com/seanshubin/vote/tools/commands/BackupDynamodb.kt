package com.seanshubin.vote.tools.commands

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.ScanRequest
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.domain.EventEnvelope
import com.seanshubin.vote.tools.lib.DynamoClient
import com.seanshubin.vote.tools.lib.Output
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists

class BackupDynamodb : CliktCommand(name = "backup-dynamodb") {
    private val outputPath by argument(name = "file", help = "Path to write the JSONL backup file.")
    private val prod by option("--prod", help = "Target real AWS DynamoDB instead of DynamoDB Local.").flag()
    private val force by option("--force", help = "Overwrite the output file if it already exists.").flag()

    override fun help(context: Context) =
        "Stream every event in vote_event_log to a local JSON Lines file. Use --prod to read from AWS."

    override fun run() = runBlocking {
        val file = Path.of(outputPath)
        if (file.exists() && !force) {
            Output.error("File already exists: $file (pass --force to overwrite)")
        }

        Output.banner("Backing up event log from ${DynamoClient.describe(prod)}")

        val json = Json { encodeDefaults = true }
        var count = 0L

        DynamoClient.createFor(prod).use { client ->
            Files.newBufferedWriter(
                file,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            ).use { writer ->
                var startKey: Map<String, AttributeValue>? = null
                do {
                    val response = client.scan(ScanRequest {
                        tableName = DynamoClient.TABLE_EVENT_LOG
                        exclusiveStartKey = startKey
                    })
                    response.items?.forEach { item ->
                        val envelope = parseEnvelope(item, json)
                        writer.write(json.encodeToString(envelope))
                        writer.newLine()
                        count++
                    }
                    startKey = response.lastEvaluatedKey
                } while (startKey != null)
            }
        }

        val bytes = Files.size(file)
        Output.success("Backed up $count event(s) to $file ($bytes bytes)")
    }

    companion object {
        fun parseEnvelope(item: Map<String, AttributeValue>, json: Json): EventEnvelope {
            val eventId = (item["event_id"] as? AttributeValue.N)?.value?.toLong()
                ?: error("event log row missing numeric event_id")
            val authority = (item["authority"] as? AttributeValue.S)?.value
                ?: error("event log row $eventId missing authority")
            val eventData = (item["event_data"] as? AttributeValue.S)?.value
                ?: error("event log row $eventId missing event_data")
            val createdAt = (item["created_at"] as? AttributeValue.N)?.value?.toLong()
                ?: error("event log row $eventId missing created_at")
            val event = json.decodeFromString<DomainEvent>(eventData)
            return EventEnvelope(
                eventId = eventId,
                whenHappened = Instant.fromEpochMilliseconds(createdAt),
                authority = authority,
                event = event,
            )
        }
    }
}
