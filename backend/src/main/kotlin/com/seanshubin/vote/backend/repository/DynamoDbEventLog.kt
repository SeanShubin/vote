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
            val response = dynamoDb.scan(ScanRequest {
                tableName = DynamoDbSingleTableSchema.EVENT_LOG_TABLE
                filterExpression = "event_id > :lastSynced"
                expressionAttributeValues = mapOf(
                    ":lastSynced" to AttributeValue.N(lastEventSynced.toString())
                )
            })

            response.items?.map { item ->
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
            }?.sortedBy { it.eventId } ?: emptyList()
        }
    }

    override fun eventCount(): Int {
        return runBlocking {
            val response = dynamoDb.scan(ScanRequest {
                tableName = DynamoDbSingleTableSchema.EVENT_LOG_TABLE
                select = Select.Count
            })
            response.count ?: 0
        }
    }

    override fun setPaused(paused: Boolean) {
        runBlocking {
            dynamoDb.putItem(PutItemRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                item = mapOf(
                    "PK" to AttributeValue.S(DynamoDbSingleTableSchema.METADATA_PK),
                    "SK" to AttributeValue.S(DynamoDbSingleTableSchema.EVENT_LOG_PAUSED_SK),
                    "paused" to AttributeValue.Bool(paused),
                )
            })
        }
    }

    override fun isPaused(): Boolean {
        return runBlocking {
            val response = dynamoDb.getItem(GetItemRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                key = mapOf(
                    "PK" to AttributeValue.S(DynamoDbSingleTableSchema.METADATA_PK),
                    "SK" to AttributeValue.S(DynamoDbSingleTableSchema.EVENT_LOG_PAUSED_SK),
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
                    "PK" to AttributeValue.S("METADATA"),
                    "SK" to AttributeValue.S("EVENT_COUNTER")
                )
                updateExpression = "ADD next_event_id :inc"
                expressionAttributeValues = mapOf(":inc" to AttributeValue.N("1"))
                returnValues = ReturnValue.UpdatedNew
            })

            response.attributes?.get("next_event_id")?.asN()?.toLong() ?: 1L
        }
    }

    private fun <T> runBlocking(block: suspend () -> T): T {
        return kotlinx.coroutines.runBlocking {
            block()
        }
    }
}
