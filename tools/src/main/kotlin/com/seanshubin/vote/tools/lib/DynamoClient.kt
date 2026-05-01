package com.seanshubin.vote.tools.lib

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.net.url.Url

object DynamoClient {
    const val ENDPOINT = "http://localhost:8000"
    const val REGION = "us-east-1"
    const val TABLE_DATA = "vote_data"
    const val TABLE_EVENT_LOG = "vote_event_log"

    fun create(): DynamoDbClient =
        DynamoDbClient {
            region = REGION
            endpointUrl = Url.parse(ENDPOINT)
            credentialsProvider = StaticCredentialsProvider(Credentials("dummy", "dummy"))
        }

    /**
     * Real AWS DynamoDB client (no endpoint override). Uses the default AWS
     * credential chain (env vars, ~/.aws/credentials, SSO, etc.). The caller
     * must already be authenticated for the target account.
     */
    fun createForProd(): DynamoDbClient =
        DynamoDbClient {
            region = REGION
        }

    fun createFor(prod: Boolean): DynamoDbClient =
        if (prod) createForProd() else create()

    fun describe(prod: Boolean): String =
        if (prod) "AWS DynamoDB (region $REGION)" else "DynamoDB Local at $ENDPOINT"

    /**
     * Render an AttributeValue using the same JSON shape as the AWS CLI ({"S": "..."}, {"N": "..."}, {"BOOL": ...}).
     */
    fun render(value: AttributeValue): String = when (value) {
        is AttributeValue.S -> "\"${value.value}\""
        is AttributeValue.N -> value.value
        is AttributeValue.Bool -> value.value.toString()
        is AttributeValue.Null -> "null"
        is AttributeValue.Ss -> value.value.joinToString(",", "[", "]") { "\"$it\"" }
        is AttributeValue.Ns -> value.value.joinToString(",", "[", "]")
        is AttributeValue.L -> value.value.joinToString(",", "[", "]") { render(it) }
        is AttributeValue.M -> value.value.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":${render(v)}" }
        else -> value.toString()
    }

    /**
     * Convenience extractors that mirror the bash scripts' `.field.S` / `.field.N` patterns.
     */
    fun s(item: Map<String, AttributeValue>, key: String): String? =
        (item[key] as? AttributeValue.S)?.value

    fun n(item: Map<String, AttributeValue>, key: String): String? =
        (item[key] as? AttributeValue.N)?.value

    fun bool(item: Map<String, AttributeValue>, key: String): Boolean? =
        (item[key] as? AttributeValue.Bool)?.value
}
