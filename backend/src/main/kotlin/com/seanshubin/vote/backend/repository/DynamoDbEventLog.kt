package com.seanshubin.vote.backend.repository

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*
import com.seanshubin.vote.contract.EventLog
import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.domain.EventEnvelope
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DynamoDbEventLog(
    private val dynamoDb: DynamoDbClient,
    private val json: Json
) : EventLog {

    override fun appendEvent(authority: String, whenHappened: Instant, event: DomainEvent) {
        val eventType = event::class.simpleName ?: "Unknown"
        val eventData = json.encodeToString(event)

        // Generate next event ID using atomic counter
        val eventId = getNextEventId()

        // Store the event
        runBlocking {
            dynamoDb.putItem(PutItemRequest {
                tableName = DynamoDbSchema.EVENT_LOG_TABLE
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
                tableName = DynamoDbSchema.EVENT_LOG_TABLE
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

                val domainEvent = json.decodeFromString<DomainEvent>(eventData)

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
                tableName = DynamoDbSchema.EVENT_LOG_TABLE
                select = Select.Count
            })
            response.count ?: 0
        }
    }

    private fun getNextEventId(): Long {
        return runBlocking {
            val response = dynamoDb.updateItem(UpdateItemRequest {
                tableName = DynamoDbSchema.SYNC_STATE_TABLE
                key = mapOf("id" to AttributeValue.S("event_counter"))
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
