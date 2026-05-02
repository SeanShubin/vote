package com.seanshubin.vote.tools.commands

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.ScanRequest
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.tools.lib.DynamoClient
import com.seanshubin.vote.tools.lib.NarrativeEvent
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
        "Stream every event in vote_event_log to a JSONL narrative (no event_id; line position is the id on restore). Use --prod to read from AWS."

    override fun run() {
        val file = Path.of(outputPath)
        if (file.exists() && !force) {
            Output.error("File already exists: $file (pass --force to overwrite)")
        }
        runBlocking { backupEventLog(prod, file) }
    }

    companion object {
        suspend fun backupEventLog(prod: Boolean, file: Path): Long {
            Output.banner("Backing up event log from ${DynamoClient.describe(prod)}")

            val json = Json { encodeDefaults = true }
            var count = 0L

            DynamoClient.createFor(prod).use { client ->
                // Collect, sort by event_id ascending, then write — so the on-disk
                // narrative starts as a faithful chronology even though restore
                // ignores any ordering hint other than line position.
                val rows = mutableListOf<Pair<Long, NarrativeEvent>>()
                var startKey: Map<String, AttributeValue>? = null
                do {
                    val response = client.scan(ScanRequest {
                        tableName = DynamoClient.TABLE_EVENT_LOG
                        exclusiveStartKey = startKey
                    })
                    response.items?.forEach { item ->
                        rows.add(parseRow(item, json))
                    }
                    startKey = response.lastEvaluatedKey
                } while (startKey != null)

                rows.sortBy { it.first }

                Files.newBufferedWriter(
                    file,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                ).use { writer ->
                    rows.forEach { (_, narrative) ->
                        writer.write(json.encodeToString(narrative))
                        writer.newLine()
                        count++
                    }
                }
            }

            val bytes = Files.size(file)
            Output.success("Backed up $count event(s) to $file ($bytes bytes)")
            return count
        }

        fun parseRow(item: Map<String, AttributeValue>, json: Json): Pair<Long, NarrativeEvent> {
            val eventId = (item["event_id"] as? AttributeValue.N)?.value?.toLong()
                ?: error("event log row missing numeric event_id")
            val authority = (item["authority"] as? AttributeValue.S)?.value
                ?: error("event log row $eventId missing authority")
            val eventData = (item["event_data"] as? AttributeValue.S)?.value
                ?: error("event log row $eventId missing event_data")
            val createdAt = (item["created_at"] as? AttributeValue.N)?.value?.toLong()
                ?: error("event log row $eventId missing created_at")
            val event = json.decodeFromString<DomainEvent>(eventData)
            val narrative = NarrativeEvent(
                whenHappened = Instant.fromEpochMilliseconds(createdAt),
                authority = authority,
                event = event,
            )
            return eventId to narrative
        }
    }
}
