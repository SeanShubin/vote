package com.seanshubin.vote.backend.repository

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*
import com.seanshubin.vote.contract.EventLog
import com.seanshubin.vote.contract.EventLogPausedException
import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.domain.EventEnvelope
import kotlinx.datetime.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DynamoDbEventLog(
    private val dynamoDb: DynamoDbClient,
    private val json: Json
) : EventLog {

    override fun appendEvent(authority: String, whenHappened: Instant, event: DomainEvent) {
        // Read-then-write rather than a conditional PutItem because pause is
        // an operator-driven flag, not a contended race — and a clear
        // exception is far more useful than a ConditionalCheckFailed wrapper.
        if (isPaused()) throw EventLogPausedException()

        val eventType = event::class.simpleName ?: "Unknown"
        val eventData = json.encodeToString(event)

        // Generate next event ID using atomic counter
        val eventId = getNextEventId()

        // Store the event
        runBlocking {
            dynamoDb.putItem(PutItemRequest {
                tableName = DynamoDbSingleTableSchema.EVENT_LOG_TABLE
                item = mapOf(
                    "PK" to AttributeValue.S(DynamoDbSingleTableSchema.EVENT_LOG_PK),
                    "event_id" to AttributeValue.N(eventId.toString()),
                    "authority" to AttributeValue.S(authority),
                    "event_type" to AttributeValue.S(eventType),
                    "event_data" to AttributeValue.S(eventData),
                    "created_at" to AttributeValue.N(whenHappened.toEpochMilliseconds().toString())
                )
            })
        }
    }

    override fun eventsToSync(lastEventSynced: Long): List<EventEnvelope> {
        return runBlocking {
            // Query the single EVENT_LOG partition for event_id > lastSynced.
            // DynamoDB seeks straight to that range and returns rows already
            // ordered by event_id ascending. Query is still 1 MB-capped per
            // call, so loop on lastEvaluatedKey until the whole range is read.
            val rawItems = mutableListOf<Map<String, AttributeValue>>()
            var startKey: Map<String, AttributeValue>? = null
            do {
                val response = dynamoDb.query(QueryRequest {
                    tableName = DynamoDbSingleTableSchema.EVENT_LOG_TABLE
                    keyConditionExpression = "PK = :pk AND event_id > :lastSynced"
                    expressionAttributeValues = mapOf(
                        ":pk" to AttributeValue.S(DynamoDbSingleTableSchema.EVENT_LOG_PK),
                        ":lastSynced" to AttributeValue.N(lastEventSynced.toString()),
                    )
                    exclusiveStartKey = startKey
                })
                response.items?.let { rawItems.addAll(it) }
                startKey = response.lastEvaluatedKey
            } while (startKey != null)

            // Already ordered by event_id — the Query sort key — across every
            // page, so no explicit sort is needed.
            rawItems.map { item ->
                val eventId = item["event_id"]?.asN()?.toLong() ?: error("Missing event_id")
                val authority = item["authority"]?.asS() ?: error("Missing authority")
                val eventData = item["event_data"]?.asS() ?: error("Missing event_data")
                val createdAt = item["created_at"]?.asN()?.toLong() ?: error("Missing created_at")

                val domainEvent = try {
                    json.decodeFromString<DomainEvent>(eventData)
                } catch (e: SerializationException) {
                    throw IllegalStateException(
                        "Event log contains event_id=$eventId of an unknown type — schema mismatch with current code. Reset the event log via the admin tool.",
                        e,
                    )
                }

                EventEnvelope(
                    eventId = eventId,
                    whenHappened = Instant.fromEpochMilliseconds(createdAt),
                    authority = authority,
                    event = domainEvent
                )
            }
        }
    }

    override fun eventCount(): Int {
        return runBlocking {
            // Count the EVENT_LOG partition. Query (not Scan) so it touches
            // only the log's own partition; Count is still 1 MB-capped per
            // call, so loop on lastEvaluatedKey.
            var count = 0
            var startKey: Map<String, AttributeValue>? = null
            do {
                val response = dynamoDb.query(QueryRequest {
                    tableName = DynamoDbSingleTableSchema.EVENT_LOG_TABLE
                    keyConditionExpression = "PK = :pk"
                    expressionAttributeValues = mapOf(
                        ":pk" to AttributeValue.S(DynamoDbSingleTableSchema.EVENT_LOG_PK),
                    )
                    select = Select.Count
                    exclusiveStartKey = startKey
                })
                count += response.count ?: 0
                startKey = response.lastEvaluatedKey
            } while (startKey != null)
            count
        }
    }

    override fun setPaused(paused: Boolean) {
        runBlocking {
            dynamoDb.putItem(PutItemRequest {
                tableName = DynamoDbOperatorStateSchema.TABLE
                item = mapOf(
                    "PK" to AttributeValue.S(DynamoDbOperatorStateSchema.EVENT_LOG_PAUSED_PK),
                    "paused" to AttributeValue.Bool(paused),
                )
            })
        }
    }

    override fun isPaused(): Boolean {
        return runBlocking {
            val response = dynamoDb.getItem(GetItemRequest {
                tableName = DynamoDbOperatorStateSchema.TABLE
                key = mapOf(
                    "PK" to AttributeValue.S(DynamoDbOperatorStateSchema.EVENT_LOG_PAUSED_PK),
                )
                // Strongly-consistent so a recent pause from the operator is
                // visible immediately to every Lambda — the whole point of the
                // pause is to coordinate across the deploy window.
                consistentRead = true
            })
            response.item?.get("paused")?.asBoolOrNull() ?: false
        }
    }

    private fun getNextEventId(): Long {
        return runBlocking {
            // Use main table with PK=METADATA, SK=EVENT_COUNTER for atomic counter
            val response = dynamoDb.updateItem(UpdateItemRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                key = mapOf(
                    "PK" to AttributeValue.S(DynamoDbSingleTableSchema.METADATA_PK),
                    "SK" to AttributeValue.S(DynamoDbSingleTableSchema.EVENT_COUNTER_SK)
                )
                updateExpression = "ADD ${DynamoDbSingleTableSchema.NEXT_EVENT_ID_ATTR} :inc"
                expressionAttributeValues = mapOf(":inc" to AttributeValue.N("1"))
                returnValues = ReturnValue.UpdatedNew
            })

            response.attributes?.get(DynamoDbSingleTableSchema.NEXT_EVENT_ID_ATTR)?.asN()?.toLong() ?: 1L
        }
    }

    /**
     * Force the event-id counter to [value] so the next [appendEvent] assigns
     * `value + 1`. Used by rebuild-projection: dropping vote_data wipes the
     * EVENT_COUNTER item, and unless it is re-seeded to the max event id the
     * counter silently restarts at 1 — assigning IDs at or below the projection
     * cursor, so every subsequent event is appended over an old row AND skipped
     * by sync. Mirrors the cursor re-seed (`initializeLastSynced`) that already
     * happens on rebuild.
     */
    fun seedEventCounter(value: Long) {
        runBlocking {
            dynamoDb.updateItem(UpdateItemRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                key = mapOf(
                    "PK" to AttributeValue.S(DynamoDbSingleTableSchema.METADATA_PK),
                    "SK" to AttributeValue.S(DynamoDbSingleTableSchema.EVENT_COUNTER_SK),
                )
                updateExpression = "SET ${DynamoDbSingleTableSchema.NEXT_EVENT_ID_ATTR} = :val"
                expressionAttributeValues = mapOf(":val" to AttributeValue.N(value.toString()))
                returnValues = ReturnValue.None
            })
        }
    }

    private fun <T> runBlocking(block: suspend () -> T): T {
        return kotlinx.coroutines.runBlocking {
            block()
        }
    }
}
