package com.seanshubin.vote.tools.lib

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.net.url.Url
import kotlinx.coroutines.delay
import kotlin.random.Random

object DynamoClient {
    const val ENDPOINT = "http://localhost:8000"
    const val REGION = "us-east-1"
    const val TABLE_DATA = "vote_data"
    const val TABLE_EVENT_LOG = "vote_event_log"
    const val TABLE_OPERATOR_STATE = "vote_operator_state"

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

/**
 * Retry a DynamoDB operation when it fails with a throttling exception.
 * On-demand tables can take ~30s to fully scale up under burst load, so
 * the AWS SDK's default retry policy (3 attempts, max ~20s total) isn't
 * enough for bulk operations like the rewrite-mode deploy ceremony's
 * nuke + restore steps. We use 8 attempts with exponential backoff +
 * jitter, capped at 30s per delay, for ~2 minutes of total tolerance.
 *
 * Matches by message rather than exception class because the AWS SDK
 * Kotlin throws the generic `DynamoDbException` (not the specific
 * `ProvisionedThroughputExceededException`) when on-demand auto-scaling
 * returns an error code outside the operation's known error mappings —
 * which is exactly the case that bit the first rewrite-mode deploy.
 */
internal suspend fun <T> retryOnThrottle(opName: String, block: suspend () -> T): T {
    var delayMs = 500L
    var lastError: Exception? = null
    repeat(8) { attempt ->
        try {
            return block()
        } catch (e: Exception) {
            val msg = e.message ?: ""
            val isThrottle = "Throughput exceeds" in msg ||
                "automatically scaling" in msg ||
                "Rate exceeded" in msg ||
                "Throttling" in msg ||
                "RequestLimit" in msg
            if (!isThrottle) throw e
            lastError = e
            val jitter = Random.nextLong(0, delayMs / 2 + 1)
            val totalDelay = delayMs + jitter
            println("[$opName] throttled (attempt ${attempt + 1}/8), backing off ${totalDelay}ms")
            delay(totalDelay)
            delayMs = minOf(delayMs * 2, 30_000L)
        }
    }
    throw lastError ?: error("retryOnThrottle($opName) exhausted without an error")
}
