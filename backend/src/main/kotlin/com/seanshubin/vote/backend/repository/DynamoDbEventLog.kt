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

    /**
     * Append [event] to the log. The id is derived from the log itself —
     * `max(event_id) + 1` — so the append-only log is the *sole* source of
     * truth for id allocation. (The SQL backend gets this for free via the
     * table's AUTO_INCREMENT; this is the DynamoDB equivalent.) No id-allocation
     * state lives in the projection, so dropping/rebuilding the projection can
     * never desync a counter and cause id reuse — the failure mode that
     * silently overwrote events when the counter lived in vote_data.
     *
     * Concurrency: two appenders can read the same max and target the same id.
     * The conditional put (`attribute_not_exists(event_id)`) lets exactly one
     * win; the loser re-reads max and retries. This same condition is what makes
     * the log append-only — a write can never overwrite an existing id. At this
     * write volume real contention is rare, so a small bounded retry suffices.
     */
    override fun appendEvent(authority: String, whenHappened: Instant, event: DomainEvent) {
        if (isPaused()) throw EventLogPausedException()

        val eventType = event::class.simpleName ?: "Unknown"
        val eventData = json.encodeToString(event)

        runBlocking {
            repeat(MAX_APPEND_ATTEMPTS) {
                val eventId = maxEventId() + 1
                try {
                    dynamoDb.putItem(PutItemRequest {
                        tableName = DynamoDbSingleTableSchema.EVENT_LOG_TABLE
                        conditionExpression = "attribute_not_exists(event_id)"
                        item = mapOf(
                            "PK" to AttributeValue.S(DynamoDbSingleTableSchema.EVENT_LOG_PK),
                            "event_id" to AttributeValue.N(eventId.toString()),
                            "authority" to AttributeValue.S(authority),
                            "event_type" to AttributeValue.S(eventType),
                            "event_data" to AttributeValue.S(eventData),
                            "created_at" to AttributeValue.N(whenHappened.toEpochMilliseconds().toString())
                        )
                    })
                    return@runBlocking
                } catch (e: ConditionalCheckFailedException) {
                    // Another appender claimed this id first. Re-read max and retry.
                }
            }
            throw IllegalStateException(
                "appendEvent gave up after $MAX_APPEND_ATTEMPTS id-collision retries — " +
                    "unexpected sustained contention on the event log."
            )
        }
    }

    /**
     * Highest event id currently in the log, or 0 when the log is empty.
     * Strongly consistent so a sequential append reads the id it just wrote and
     * lands on the first attempt; the conditional put still backstops the rare
     * concurrent case where two appenders read the same max.
     */
    private suspend fun maxEventId(): Long {
        val response = dynamoDb.query(QueryRequest {
            tableName = DynamoDbSingleTableSchema.EVENT_LOG_TABLE
            keyConditionExpression = "PK = :pk"
            expressionAttributeValues = mapOf(
                ":pk" to AttributeValue.S(DynamoDbSingleTableSchema.EVENT_LOG_PK),
            )
            scanIndexForward = false
            limit = 1
            consistentRead = true
        })
        return response.items?.firstOrNull()?.get("event_id")?.asN()?.toLong() ?: 0
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

    private fun <T> runBlocking(block: suspend () -> T): T {
        return kotlinx.coroutines.runBlocking {
            block()
        }
    }

    private companion object {
        // Append derives its id from the log's current max and guards the write
        // with attribute_not_exists; a lost race retries with a fresh max. This
        // bounds the retries so a pathological hot loop fails loudly instead of
        // spinning. Generous — real contention at this volume is near-zero.
        const val MAX_APPEND_ATTEMPTS = 16
    }
}
