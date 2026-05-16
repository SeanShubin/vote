package com.seanshubin.vote.backend.repository

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*
import com.seanshubin.vote.contract.SystemSettings
import com.seanshubin.vote.domain.FeatureFlag

class DynamoDbSystemSettings(
    private val dynamoDb: DynamoDbClient,
) : SystemSettings {
    override fun isEnabled(flag: FeatureFlag): Boolean {
        return runBlocking {
            val response = dynamoDb.getItem(GetItemRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                key = mapOf(
                    "PK" to AttributeValue.S(DynamoDbSingleTableSchema.METADATA_PK),
                    "SK" to AttributeValue.S(DynamoDbSingleTableSchema.featureFlagSK(flag.name)),
                )
                // Strongly-consistent so an owner's flip is visible to every
                // Lambda on the next request — the whole point of a runtime
                // flag is fast propagation.
                consistentRead = true
            })
            response.item?.get("enabled")?.asBoolOrNull() ?: flag.defaultEnabled
        }
    }

    override fun setEnabled(flag: FeatureFlag, enabled: Boolean) {
        runBlocking {
            dynamoDb.putItem(PutItemRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                item = mapOf(
                    "PK" to AttributeValue.S(DynamoDbSingleTableSchema.METADATA_PK),
                    "SK" to AttributeValue.S(DynamoDbSingleTableSchema.featureFlagSK(flag.name)),
                    "enabled" to AttributeValue.Bool(enabled),
                )
            })
        }
    }

    override fun listAll(): Map<FeatureFlag, Boolean> =
        // Resolve per-flag with a single GetItem each. With a handful of
        // flags this is cheaper than scanning, simpler than batch-getting,
        // and the polling cadence is 10s so the extra RTT is fine.
        FeatureFlag.entries.associateWith { isEnabled(it) }

    private fun <T> runBlocking(block: suspend () -> T): T {
        return kotlinx.coroutines.runBlocking { block() }
    }
}
