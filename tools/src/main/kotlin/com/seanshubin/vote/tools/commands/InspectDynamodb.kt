package com.seanshubin.vote.tools.commands

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.GetItemRequest
import aws.sdk.kotlin.services.dynamodb.model.QueryRequest
import aws.sdk.kotlin.services.dynamodb.model.ScanRequest
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.seanshubin.vote.tools.lib.DynamoClient
import kotlinx.coroutines.runBlocking

class InspectDynamodbTables : CliktCommand(name = "inspect-dynamodb-tables") {
    override fun help(context: Context) = "Show DynamoDB table overview."

    override fun run() = runBlocking {
        println("=== DynamoDB Tables Overview (Single-Table Design) ===")
        println()
        DynamoClient.create().use { client ->
            val main = countAll(client, DynamoClient.TABLE_DATA)
            val events = countAll(client, DynamoClient.TABLE_EVENT_LOG)
            println("vote_data (main table):   $main records")
            println("vote_event_log:           $events events")
        }
        println()
        println("Use scripts/dev inspect-dynamodb-all to see complete data breakdown.")
    }
}

class InspectDynamodbUsers : CliktCommand(name = "inspect-dynamodb-users") {
    override fun help(context: Context) = "List users from DynamoDB."

    override fun run() = runBlocking {
        println("=== Users (Single-Table) ===")
        println()
        DynamoClient.create().use { client ->
            val items = scanByPkPrefix(client, "USER#", "METADATA")
            if (items.isEmpty()) {
                println("No users found.")
                return@use
            }
            println("Found ${items.size} user(s):")
            println()
            items.forEach { item ->
                println("Name:  ${DynamoClient.s(item, "name") ?: ""}")
                println("Email: ${DynamoClient.s(item, "email") ?: ""}")
                println("Role:  ${DynamoClient.s(item, "role") ?: ""}")
                println("Salt:  ${DynamoClient.s(item, "salt") ?: ""}")
                println("Hash:  ${DynamoClient.s(item, "hash") ?: ""}")
                println()
            }
        }
    }
}

class InspectDynamodbElections : CliktCommand(name = "inspect-dynamodb-elections") {
    override fun help(context: Context) = "List elections from DynamoDB."

    override fun run() = runBlocking {
        println("=== Elections (Single-Table) ===")
        println()
        DynamoClient.create().use { client ->
            val items = scanByPkPrefix(client, "ELECTION#", "METADATA")
            if (items.isEmpty()) {
                println("No elections found.")
                return@use
            }
            println("Found ${items.size} election(s):")
            println()
            items.forEach { item ->
                println("Election:         ${DynamoClient.s(item, "electionName") ?: ""}")
                println("Owner:            ${DynamoClient.s(item, "ownerName") ?: ""}")
                println()
            }
        }
    }
}

class InspectDynamodbCandidates : CliktCommand(name = "inspect-dynamodb-candidates") {
    override fun help(context: Context) = "List candidates (optionally filtered by election)."
    val election: String? by argument(name = "ELECTION").optional()

    override fun run() = runBlocking {
        println("=== Candidates (Single-Table) ===")
        println()
        DynamoClient.create().use { client ->
            val items = if (election != null) {
                queryByPkSkPrefix(client, "ELECTION#$election", "CANDIDATE#")
            } else {
                scanBySkPrefix(client, "CANDIDATE#")
            }
            if (items.isEmpty()) {
                if (election != null) println("No candidates found for election: $election")
                else println("No candidates found.")
                return@use
            }
            if (election != null) {
                println("Found ${items.size} candidate(s) for election: $election")
                println()
                items.forEach { item ->
                    println("  - ${DynamoClient.s(item, "candidateName") ?: ""}")
                }
            } else {
                println("Found ${items.size} candidate(s):")
                println()
                items.forEach { item ->
                    println("Election:  ${DynamoClient.s(item, "electionName") ?: ""}")
                    println("Candidate: ${DynamoClient.s(item, "candidateName") ?: ""}")
                    println()
                }
                println("Tip: Pass an election name to filter:")
                println("  scripts/dev inspect-dynamodb-candidates \"Best Programming Language\"")
            }
        }
    }
}

class InspectDynamodbVoters : CliktCommand(name = "inspect-dynamodb-voters") {
    override fun help(context: Context) = "List eligible voters (optionally filtered by election)."
    val election: String? by argument(name = "ELECTION").optional()

    override fun run() = runBlocking {
        println("=== Eligible Voters (Single-Table) ===")
        println()
        DynamoClient.create().use { client ->
            val items = if (election != null) {
                queryByPkSkPrefix(client, "ELECTION#$election", "VOTER#")
            } else {
                scanBySkPrefix(client, "VOTER#")
            }
            if (items.isEmpty()) {
                if (election != null) println("No eligible voters found for election: $election")
                else println("No eligible voters found.")
                return@use
            }
            if (election != null) {
                println("Found ${items.size} eligible voter(s) for election: $election")
                println()
                items.forEach { item -> println("  - ${DynamoClient.s(item, "voterName") ?: ""}") }
            } else {
                println("Found ${items.size} eligible voter(s):")
                println()
                items.forEach { item ->
                    println("Election: ${DynamoClient.s(item, "electionName") ?: ""}")
                    println("Voter:    ${DynamoClient.s(item, "voterName") ?: ""}")
                    println()
                }
                println("Tip: Pass an election name to filter:")
                println("  scripts/dev inspect-dynamodb-voters \"Best Programming Language\"")
            }
        }
    }
}

class InspectDynamodbBallots : CliktCommand(name = "inspect-dynamodb-ballots") {
    override fun help(context: Context) = "List ballots (optionally filtered by election)."
    val election: String? by argument(name = "ELECTION").optional()

    override fun run() = runBlocking {
        println("=== Ballots (Single-Table) ===")
        println()
        DynamoClient.create().use { client ->
            val items = if (election != null) {
                queryByPkSkPrefix(client, "ELECTION#$election", "BALLOT#")
            } else {
                scanBySkPrefix(client, "BALLOT#")
            }
            if (items.isEmpty()) {
                if (election != null) println("No ballots found for election: $election")
                else println("No ballots found.")
                return@use
            }
            println("Found ${items.size} ballot(s)${if (election != null) " for election: $election" else ""}:")
            println()
            items.forEach { item ->
                if (election == null) println("Election:     ${DynamoClient.s(item, "electionName") ?: ""}")
                println("Voter:        ${DynamoClient.s(item, "voterName") ?: ""}")
                println("Rankings:     ${DynamoClient.s(item, "rankings") ?: ""}")
                println("Confirmation: ${DynamoClient.s(item, "confirmation") ?: ""}")
                println("When Cast:    ${DynamoClient.n(item, "whenCast") ?: ""} (epoch ms)")
                println()
            }
            if (election == null) {
                println("Tip: Pass an election name to filter:")
                println("  scripts/dev inspect-dynamodb-ballots \"Best Programming Language\"")
            }
        }
    }
}

class InspectDynamodbEventLog : CliktCommand(name = "inspect-dynamodb-event-log") {
    override fun help(context: Context) = "Dump the event log."

    override fun run() = runBlocking {
        println("=== Event Log Table ===")
        println()
        DynamoClient.create().use { client ->
            val items = scanAll(client, DynamoClient.TABLE_EVENT_LOG)
            if (items.isEmpty()) {
                println("No events found.")
                return@use
            }
            println("Found ${items.size} event(s):")
            println()
            items.sortedBy { (DynamoClient.n(it, "event_id") ?: "0").toLongOrNull() ?: 0L }
                .forEach { item ->
                    println("Event ID:   ${DynamoClient.n(item, "event_id") ?: ""}")
                    println("Authority:  ${DynamoClient.s(item, "authority") ?: ""}")
                    println("Type:       ${DynamoClient.s(item, "event_type") ?: ""}")
                    println("Data:       ${DynamoClient.s(item, "event_data") ?: ""}")
                    println("Created At: ${DynamoClient.n(item, "created_at") ?: ""} (epoch ms)")
                    println()
                }
        }
    }
}

class InspectDynamodbSyncState : CliktCommand(name = "inspect-dynamodb-sync-state") {
    override fun help(context: Context) = "Show the sync state record."

    override fun run() = runBlocking {
        println("=== Sync State (Single-Table) ===")
        println()
        DynamoClient.create().use { client ->
            val response = client.getItem(GetItemRequest {
                tableName = DynamoClient.TABLE_DATA
                key = mapOf(
                    "PK" to AttributeValue.S("METADATA"),
                    "SK" to AttributeValue.S("SYNC")
                )
            })
            val item = response.item
            if (item == null || item.isEmpty()) {
                println("No sync state found.")
                return@use
            }
            println("Sync state:")
            println()
            val lastSynced = DynamoClient.n(item, "last_synced")
            if (lastSynced != null) {
                println("Last Synced: $lastSynced (epoch ms)")
            } else {
                println("Last Synced: Not set")
            }
        }
    }
}

class InspectDynamodbRaw : CliktCommand(name = "inspect-dynamodb-raw") {
    override fun help(context: Context) = "Raw scan of a DynamoDB table."
    val tableName: String? by argument(name = "TABLE").optional()

    override fun run() = runBlocking {
        val table = tableName ?: run {
            println("Usage: scripts/dev inspect-dynamodb-raw <table-name>")
            println()
            println("Available tables:")
            println("  ${DynamoClient.TABLE_DATA}      - Main single table")
            println("  ${DynamoClient.TABLE_EVENT_LOG} - Event sourcing log")
            return@runBlocking
        }
        println("=== Raw DynamoDB Scan of $table ===")
        println()
        DynamoClient.create().use { client ->
            val items = scanAll(client, table)
            items.forEach { item ->
                println(itemToJson(item))
            }
        }
    }
}

class InspectDynamodbRawAll : CliktCommand(name = "inspect-dynamodb-raw-all") {
    override fun help(context: Context) = "Raw view of vote_data + vote_event_log grouped by entity type."

    override fun run() = runBlocking {
        println("=========================================")
        println("Raw DynamoDB Storage (Mechanical View)")
        println("=========================================")
        println()
        println("This shows ACTUAL storage with PK/SK composite keys.")
        println()

        DynamoClient.create().use { client ->
            val items = scanAll(client, DynamoClient.TABLE_DATA)
            println("Found ${items.size} items in ${DynamoClient.TABLE_DATA}:")
            println()

            fun group(label: String, predicate: (Map<String, AttributeValue>) -> Boolean) {
                println("--- $label ---")
                items.filter(predicate).forEach { println(itemToJson(it)) }
                println()
            }

            group("USERS (PK=USER#*, SK=METADATA)") {
                (DynamoClient.s(it, "PK") ?: "").startsWith("USER#") &&
                    DynamoClient.s(it, "SK") == "METADATA"
            }
            group("ELECTIONS (PK=ELECTION#*, SK=METADATA)") {
                (DynamoClient.s(it, "PK") ?: "").startsWith("ELECTION#") &&
                    DynamoClient.s(it, "SK") == "METADATA"
            }
            group("CANDIDATES (PK=ELECTION#*, SK=CANDIDATE#*)") {
                (DynamoClient.s(it, "SK") ?: "").startsWith("CANDIDATE#")
            }
            group("ELIGIBLE VOTERS (PK=ELECTION#*, SK=VOTER#*)") {
                (DynamoClient.s(it, "SK") ?: "").startsWith("VOTER#")
            }
            group("BALLOTS (PK=ELECTION#*, SK=BALLOT#*)") {
                (DynamoClient.s(it, "SK") ?: "").startsWith("BALLOT#")
            }
            group("SYNC STATE (PK=METADATA, SK=SYNC)") {
                DynamoClient.s(it, "PK") == "METADATA" &&
                    DynamoClient.s(it, "SK") == "SYNC"
            }

            val events = scanAll(client, DynamoClient.TABLE_EVENT_LOG)
            println("=========================================")
            println("EVENT LOG TABLE")
            println("=========================================")
            println()
            if (events.isEmpty()) {
                println("No events found.")
            } else {
                println("Found ${events.size} events (showing first 5):")
                println()
                events.sortedBy { (DynamoClient.n(it, "event_id") ?: "0").toLongOrNull() ?: 0L }
                    .take(5)
                    .forEach { println(itemToJson(it)) }
            }
        }
    }
}

class InspectDynamodbRawKeys : CliktCommand(name = "inspect-dynamodb-raw-keys") {
    override fun help(context: Context) = "Show only the PK/SK pairs for vote_data."

    override fun run() = runBlocking {
        println("=== DynamoDB Key Structure (PK/SK only) ===")
        println()
        DynamoClient.create().use { client ->
            val items = scanAll(client, DynamoClient.TABLE_DATA)
            if (items.isEmpty()) {
                println("No items found.")
                return@use
            }
            println("Found ${items.size} items:")
            println()
            println(String.format("%-35s %-35s %s", "PK", "SK", "Entity Type"))
            println(String.format("%-35s %-35s %s", "=".repeat(35), "=".repeat(35), "=".repeat(12)))

            items
                .map { item ->
                    Triple(
                        DynamoClient.s(item, "PK") ?: "",
                        DynamoClient.s(item, "SK") ?: "",
                        DynamoClient.s(item, "entity_type") ?: "unknown"
                    )
                }
                .sortedWith(compareBy({ it.first }, { it.second }))
                .forEach { (pk, sk, entity) ->
                    println(String.format("%-35s %-35s %s", pk, sk, entity))
                }
        }
    }
}

class InspectDynamodbAll : CliktCommand(name = "inspect-dynamodb-all") {
    override fun help(context: Context) = "Show full relational projection of single-table DynamoDB."

    override fun run() {
        println("=========================================")
        println("DynamoDB Single-Table Complete Dump")
        println("=========================================")
        println()
        InspectDynamodbTables().run()
        println()
        InspectDynamodbUsers().run()
        println()
        InspectDynamodbElections().run()
        println()
        InspectDynamodbBallots().run()
    }
}

// --- helpers shared by inspect-dynamodb-* ---

private suspend fun scanAll(client: DynamoDbClient, table: String): List<Map<String, AttributeValue>> {
    val result = mutableListOf<Map<String, AttributeValue>>()
    var startKey: Map<String, AttributeValue>? = null
    do {
        val response = client.scan(ScanRequest {
            tableName = table
            exclusiveStartKey = startKey
        })
        response.items?.let { result.addAll(it) }
        startKey = response.lastEvaluatedKey
    } while (startKey != null)
    return result
}

private suspend fun countAll(client: DynamoDbClient, table: String): Int =
    scanAll(client, table).size

private suspend fun scanByPkPrefix(
    client: DynamoDbClient,
    pkPrefix: String,
    sk: String
): List<Map<String, AttributeValue>> {
    val response = client.scan(ScanRequest {
        tableName = DynamoClient.TABLE_DATA
        filterExpression = "begins_with(PK, :prefix) AND SK = :sk"
        expressionAttributeValues = mapOf(
            ":prefix" to AttributeValue.S(pkPrefix),
            ":sk" to AttributeValue.S(sk)
        )
    })
    return response.items ?: emptyList()
}

private suspend fun scanBySkPrefix(client: DynamoDbClient, skPrefix: String): List<Map<String, AttributeValue>> {
    val response = client.scan(ScanRequest {
        tableName = DynamoClient.TABLE_DATA
        filterExpression = "begins_with(SK, :prefix)"
        expressionAttributeValues = mapOf(":prefix" to AttributeValue.S(skPrefix))
    })
    return response.items ?: emptyList()
}

private suspend fun queryByPkSkPrefix(
    client: DynamoDbClient,
    pk: String,
    skPrefix: String
): List<Map<String, AttributeValue>> {
    val response = client.query(QueryRequest {
        tableName = DynamoClient.TABLE_DATA
        keyConditionExpression = "PK = :pk AND begins_with(SK, :sk)"
        expressionAttributeValues = mapOf(
            ":pk" to AttributeValue.S(pk),
            ":sk" to AttributeValue.S(skPrefix)
        )
    })
    return response.items ?: emptyList()
}

private fun itemToJson(item: Map<String, AttributeValue>): String =
    item.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":${avToJson(v)}" }

private fun avToJson(v: AttributeValue): String = when (v) {
    is AttributeValue.S -> "{\"S\":\"${v.value.replace("\"", "\\\"")}\"}"
    is AttributeValue.N -> "{\"N\":\"${v.value}\"}"
    is AttributeValue.Bool -> "{\"BOOL\":${v.value}}"
    is AttributeValue.Null -> "{\"NULL\":true}"
    else -> "\"$v\""
}
