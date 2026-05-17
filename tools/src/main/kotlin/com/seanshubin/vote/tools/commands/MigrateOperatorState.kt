package com.seanshubin.vote.tools.commands

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.DeleteItemRequest
import aws.sdk.kotlin.services.dynamodb.model.PutItemRequest
import aws.sdk.kotlin.services.dynamodb.model.ScanRequest
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.seanshubin.vote.tools.lib.DynamoClient
import com.seanshubin.vote.tools.lib.Output
import kotlinx.coroutines.runBlocking

/**
 * One-shot post-deploy migration for the operator-state split.
 *
 * Before this fix, the pause flag and feature flags lived in vote_data at
 *   PK=METADATA, SK=EVENT_LOG_PAUSED
 *   PK=METADATA, SK=FEATURE_FLAG#<name>
 *
 * After the deploy, those readers/writers point at vote_operator_state.
 * This tool moves any existing rows from vote_data → vote_operator_state
 * and deletes the originals so vote_data has no orphan operator-state rows.
 *
 * Idempotent: re-running after a successful run is a no-op (nothing to
 * find in vote_data). Safe to run alongside live traffic — the new code
 * already reads/writes the new table; this just cleans up the old rows
 * and seeds initial values from whatever the old code last persisted.
 */
class MigrateOperatorState : CliktCommand(name = "migrate-operator-state") {
    private val prod by option("--prod", help = "Target real AWS DynamoDB instead of DynamoDB Local.").flag()
    private val yes by option("--yes", help = "Skip the confirmation prompt (scripted use).").flag()
    private val dryRun by option("--dry-run", help = "Report what would change without writing.").flag()

    override fun help(context: Context) =
        "Move EVENT_LOG_PAUSED and FEATURE_FLAG#* rows from vote_data into vote_operator_state, then delete the originals. Idempotent."

    override fun run() = runBlocking {
        val target = DynamoClient.describe(prod)
        Output.banner("Migrating operator state to $target")

        DynamoClient.createFor(prod).use { client ->
            val rows = scanOperatorStateRows(client)
            if (rows.isEmpty()) {
                Output.success("Nothing to migrate — vote_data has no operator-state rows. (You're already clean, or migration ran previously.)")
                return@use
            }

            Output.section("Found ${rows.size} row(s) to migrate")
            rows.forEach { row ->
                println("  ${row.describe()}")
            }

            if (dryRun) {
                Output.success("Dry run complete; no changes written.")
                return@use
            }

            if (!yes) requireConfirmation(prod)

            rows.forEach { row ->
                copyToOperatorState(client, row)
                deleteFromMainTable(client, row)
            }
            Output.success("Migrated ${rows.size} row(s) into ${DynamoClient.TABLE_OPERATOR_STATE} and removed originals from ${DynamoClient.TABLE_DATA}.")
        }
    }

    /**
     * One row to be migrated. [oldSk] is the existing SK in vote_data
     * (e.g., "EVENT_LOG_PAUSED" or "FEATURE_FLAG#SECRET_BALLOT"); we reuse
     * it verbatim as the new table's PK so the row identity carries over
     * exactly and the post-migration shape matches what the new code writes.
     */
    private data class Row(val oldSk: String, val attributes: Map<String, AttributeValue>) {
        fun describe(): String {
            val valueDesc = when {
                attributes.containsKey("paused") -> "paused=${attributes["paused"]?.asBoolOrNull()}"
                attributes.containsKey("enabled") -> "enabled=${attributes["enabled"]?.asBoolOrNull()}"
                else -> "(unknown shape)"
            }
            return "$oldSk → $valueDesc"
        }
    }

    private suspend fun scanOperatorStateRows(client: DynamoDbClient): List<Row> {
        // Scan vote_data for PK=METADATA items whose SK is one of the
        // operator-state shapes. The other PK=METADATA items (SYNC,
        // EVENT_COUNTER) are event-derived system state that stays in
        // vote_data; we leave them alone.
        val rows = mutableListOf<Row>()
        var startKey: Map<String, AttributeValue>? = null
        do {
            val response = client.scan(ScanRequest {
                tableName = DynamoClient.TABLE_DATA
                filterExpression = "PK = :pk AND (SK = :pause OR begins_with(SK, :flagPrefix))"
                expressionAttributeValues = mapOf(
                    ":pk" to AttributeValue.S("METADATA"),
                    ":pause" to AttributeValue.S("EVENT_LOG_PAUSED"),
                    ":flagPrefix" to AttributeValue.S("FEATURE_FLAG#"),
                )
                exclusiveStartKey = startKey
            })
            response.items?.forEach { item ->
                val sk = (item["SK"] as? AttributeValue.S)?.value ?: return@forEach
                rows.add(Row(sk, item))
            }
            startKey = response.lastEvaluatedKey
        } while (startKey != null)
        return rows
    }

    private suspend fun copyToOperatorState(client: DynamoDbClient, row: Row) {
        // Build the new item: PK = the old SK, plus the original attribute
        // (paused/enabled). Drop PK and SK from the source row since the
        // new table has a different key shape.
        val attrs = row.attributes.filterKeys { it != "PK" && it != "SK" }.toMutableMap()
        attrs["PK"] = AttributeValue.S(row.oldSk)
        client.putItem(PutItemRequest {
            tableName = DynamoClient.TABLE_OPERATOR_STATE
            item = attrs
        })
    }

    private suspend fun deleteFromMainTable(client: DynamoDbClient, row: Row) {
        client.deleteItem(DeleteItemRequest {
            tableName = DynamoClient.TABLE_DATA
            key = mapOf(
                "PK" to AttributeValue.S("METADATA"),
                "SK" to AttributeValue.S(row.oldSk),
            )
        })
    }

    private fun requireConfirmation(prod: Boolean) {
        if (prod) {
            print("Type 'migrate production' to continue: ")
            val typed = readlnOrNull()?.trim()
            if (typed != "migrate production") Output.error("Aborted (got: ${typed ?: "<eof>"}).")
        } else {
            print("Migrate local DynamoDB? Type 'y' to continue: ")
            val typed = readlnOrNull()?.trim()
            if (typed != "y" && typed != "yes") Output.error("Aborted.")
        }
    }
}
