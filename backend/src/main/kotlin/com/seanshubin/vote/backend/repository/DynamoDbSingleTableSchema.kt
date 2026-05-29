package com.seanshubin.vote.backend.repository

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*

/**
 * Lightweight, stringly-typed value description of a DynamoDB table's shape
 * — enough to compare what code expects against what's live in DynamoDB and
 * report any difference in a human-readable form. Intentionally narrower
 * than the SDK's CreateTableRequest: only the fields that matter for
 * "would the application work against this table" (keys, attribute types,
 * GSIs, projections). Things like billing mode, TTL, and PITR settings are
 * left out because the rebuild-projection tool reasserts them on every
 * create — drift there is not a correctness problem.
 */
data class TableShape(
    val attributeDefinitions: Set<AttrDef>,
    val keySchema: List<KeyElement>,
    val gsis: Set<GsiShape>,
) {
    /** Return null if [other] matches; otherwise a human-readable summary of every difference. */
    fun diffFrom(other: TableShape?): String? {
        if (other == null) return "live table does not exist"
        val problems = mutableListOf<String>()
        val missingAttrs = attributeDefinitions - other.attributeDefinitions
        val extraAttrs = other.attributeDefinitions - attributeDefinitions
        if (missingAttrs.isNotEmpty()) problems.add("missing attribute definitions: $missingAttrs")
        if (extraAttrs.isNotEmpty()) problems.add("unexpected attribute definitions: $extraAttrs")
        if (keySchema != other.keySchema) {
            problems.add("key schema differs: expected $keySchema, live ${other.keySchema}")
        }
        val expectedGsisByName = gsis.associateBy { it.name }
        val liveGsisByName = other.gsis.associateBy { it.name }
        val missingGsis = expectedGsisByName.keys - liveGsisByName.keys
        val extraGsis = liveGsisByName.keys - expectedGsisByName.keys
        if (missingGsis.isNotEmpty()) problems.add("missing GSIs: $missingGsis")
        if (extraGsis.isNotEmpty()) problems.add("unexpected GSIs: $extraGsis")
        for (name in expectedGsisByName.keys intersect liveGsisByName.keys) {
            val expected = expectedGsisByName.getValue(name)
            val live = liveGsisByName.getValue(name)
            if (expected != live) {
                problems.add("GSI $name differs: expected $expected, live $live")
            }
        }
        return if (problems.isEmpty()) null else problems.joinToString("; ")
    }
}

data class AttrDef(val name: String, val type: String) {
    fun toAttributeDefinition() = AttributeDefinition {
        attributeName = name
        attributeType = ScalarAttributeType.fromValue(type)
    }
}

data class KeyElement(val name: String, val keyType: String) {
    fun toKeySchemaElement() = KeySchemaElement {
        attributeName = name
        keyType = KeyType.fromValue(this@KeyElement.keyType)
    }
}

data class GsiShape(
    val name: String,
    val keySchema: List<KeyElement>,
    val projectionType: String,
) {
    fun toGsi() = GlobalSecondaryIndex {
        indexName = name
        keySchema = this@GsiShape.keySchema.map { it.toKeySchemaElement() }
        projection = Projection { projectionType = ProjectionType.fromValue(this@GsiShape.projectionType) }
    }
}

object DynamoDbSingleTableSchema {
    const val MAIN_TABLE = "vote_data"
    const val EVENT_LOG_TABLE = "vote_event_log"

    // Sparse GSI names — kept in lock-step with deploy/template.yaml.
    // Each is keyed on an attribute that only one entity type carries, so
    // the index contains exactly the items needed for its query path.
    const val DISCORD_ID_INDEX = "discord-id-index"
    const val VOTER_NAME_INDEX = "voter-name-index"
    const val OWNER_NAME_INDEX = "owner-name-index"
    const val MANAGER_USER_INDEX = "manager-user-index"
    const val ELECTION_LISTING_INDEX = "election-listing-index"

    /**
     * Sparse-GSI marker attribute carried only by election METADATA items.
     * Constant value so every election lands in one partition under
     * [ELECTION_LISTING_INDEX], turning `listElections` into a Query
     * instead of a full-table Scan. Other entity types never set this
     * attribute, so the index stays sparse to elections only.
     */
    const val ELECTION_LISTING_ATTR = "election_listing"
    const val ELECTION_LISTING_VALUE = "ALL"

    /**
     * Constant partition key for every row in [EVENT_LOG_TABLE]. The log
     * lives in one partition with event_id as the sort key, so sync can
     * Query "PK = EVENT_LOG AND event_id > lastSynced" directly instead of
     * scanning the whole table. Append volume is far below the per-partition
     * write ceiling, so a single partition is fine here.
     */
    const val EVENT_LOG_PK = "EVENT_LOG"

    // Entity type prefixes
    const val USER_PREFIX = "USER#"
    const val ELECTION_PREFIX = "ELECTION#"
    const val CANDIDATE_PREFIX = "CANDIDATE#"
    const val MANAGER_PREFIX = "MANAGER#"
    const val BALLOT_PREFIX = "BALLOT#"
    /**
     * Sort-key prefix for candidate-note items, packing candidate and voter
     * into one SK so `begins_with(SK, NOTE#)` returns every note for an
     * election in one Query and `begins_with(SK, NOTE#<candidate>#)` returns
     * every note for one candidate. # is a safe delimiter — already in use
     * for the entity-prefix separator everywhere else.
     */
    const val NOTE_PREFIX = "NOTE#"
    const val METADATA_SK = "METADATA"
    const val SYNC_SK = "SYNC"

    /**
     * Partition holding event-derived system singletons in vote_data: the
     * projection cursor ([SYNC_SK], `last_synced`) and the event-id counter
     * ([EVENT_COUNTER_SK], `next_event_id`). Both are wiped when
     * rebuild-projection drops vote_data, so both must be re-seeded on
     * rebuild — see DynamoDbStartup's counter-invariant guard.
     */
    const val METADATA_PK = "METADATA"
    const val EVENT_COUNTER_SK = "EVENT_COUNTER"
    const val NEXT_EVENT_ID_ATTR = "next_event_id"
    const val LAST_SYNCED_ATTR = "last_synced"

    suspend fun createTables(dynamoDb: DynamoDbClient) {
        // Each table is created independently so an already-existing one
        // doesn't abort creation of the others. This matters now that
        // vote_event_log is app-managed: in production vote_data always
        // exists, and a single createMainTable-then-createEventLogTable
        // sequence would throw on the first call and never reach — never
        // recreate — a missing event-log table.
        createIfMissing { createMainTable(dynamoDb) }
        createIfMissing { createEventLogTable(dynamoDb) }
    }

    private suspend fun createIfMissing(create: suspend () -> Unit) {
        try {
            create()
        } catch (e: ResourceInUseException) {
            // Table already exists — nothing to do.
        }
    }

    /**
     * Declarative description of vote_data's required schema. The canonical
     * source of truth for the projection table's shape — every code path
     * that creates, compares, or recreates the table reads from this single
     * value. Keeping createMainTable, the shape comparison, and the
     * rebuild-projection tool in lock-step is automatic this way.
     */
    val expectedMainTableShape: TableShape = TableShape(
        attributeDefinitions = setOf(
            AttrDef("PK", "S"),
            AttrDef("SK", "S"),
            AttrDef("discord_id", "S"),
            AttrDef("voter_name", "S"),
            AttrDef("owner_name", "S"),
            AttrDef("user_name", "S"),
            AttrDef(ELECTION_LISTING_ATTR, "S"),
            AttrDef("election_name", "S"),
        ),
        keySchema = listOf(
            KeyElement("PK", "HASH"),
            KeyElement("SK", "RANGE"),
        ),
        gsis = setOf(
            GsiShape(
                name = DISCORD_ID_INDEX,
                keySchema = listOf(KeyElement("discord_id", "HASH")),
                projectionType = "ALL",
            ),
            GsiShape(
                name = VOTER_NAME_INDEX,
                keySchema = listOf(KeyElement("voter_name", "HASH"), KeyElement("SK", "RANGE")),
                projectionType = "ALL",
            ),
            GsiShape(
                name = OWNER_NAME_INDEX,
                keySchema = listOf(KeyElement("owner_name", "HASH"), KeyElement("SK", "RANGE")),
                projectionType = "ALL",
            ),
            GsiShape(
                name = MANAGER_USER_INDEX,
                keySchema = listOf(KeyElement("user_name", "HASH"), KeyElement("SK", "RANGE")),
                projectionType = "ALL",
            ),
            GsiShape(
                name = ELECTION_LISTING_INDEX,
                keySchema = listOf(
                    KeyElement(ELECTION_LISTING_ATTR, "HASH"),
                    KeyElement("election_name", "RANGE"),
                ),
                projectionType = "ALL",
            ),
        ),
    )

    private suspend fun createMainTable(dynamoDb: DynamoDbClient) {
        createMainTable(dynamoDb, expectedMainTableShape)
    }

    /**
     * CreateTable from a [TableShape]. The rebuild-projection tool calls
     * this directly after dropping the live table so the new table is
     * created with all GSIs declared upfront — CreateTable accepts
     * multi-GSI; UpdateTable enforces a 1-GSI-per-call limit (the entire
     * reason the projection-rebuild ceremony exists at all).
     *
     * Re-asserts PointInTimeRecovery enablement after table creation.
     * PITR is set via a separate UpdateContinuousBackups call because
     * CreateTable doesn't accept PITR settings inline. The 35-day
     * point-in-time backup window PITR provides is partly redundant with
     * the event-log-as-source-of-truth model (any state can be rebuilt
     * by replaying events) but cheap insurance against operator error
     * that affects both vote_data and vote_event_log simultaneously.
     */
    suspend fun createMainTable(dynamoDb: DynamoDbClient, shape: TableShape) {
        dynamoDb.createTable(CreateTableRequest {
            tableName = MAIN_TABLE
            keySchema = shape.keySchema.map { it.toKeySchemaElement() }
            attributeDefinitions = shape.attributeDefinitions.map { it.toAttributeDefinition() }
            globalSecondaryIndexes = shape.gsis.map { it.toGsi() }
            billingMode = BillingMode.PayPerRequest
        })
        enablePointInTimeRecovery(dynamoDb, MAIN_TABLE)
    }

    /**
     * Best-effort PITR enablement. Swallows errors from LocalStack (whose
     * Community edition doesn't implement UpdateContinuousBackups) so the
     * local integration tests that exercise [createMainTable] don't fail.
     * In production this call succeeds; in LocalStack the absence of PITR
     * is harmless because the test data is ephemeral.
     */
    private suspend fun enablePointInTimeRecovery(dynamoDb: DynamoDbClient, table: String) {
        try {
            dynamoDb.updateContinuousBackups(UpdateContinuousBackupsRequest {
                tableName = table
                pointInTimeRecoverySpecification = PointInTimeRecoverySpecification {
                    pointInTimeRecoveryEnabled = true
                }
            })
        } catch (_: Exception) {
            // LocalStack Community: UpdateContinuousBackups is not implemented.
            // Production: this call doesn't throw. Either way, swallow.
        }
    }

    /**
     * Drop the projection table. Used by the rebuild-projection tool before
     * recreating with possibly-different shape. Caller is responsible for
     * pausing the event log first if writes need to be blocked during the
     * gap.
     */
    suspend fun deleteMainTable(dynamoDb: DynamoDbClient) {
        try {
            dynamoDb.deleteTable(DeleteTableRequest { tableName = MAIN_TABLE })
        } catch (_: ResourceNotFoundException) {
            // Already gone; idempotent for retries.
        }
    }

    /**
     * Read the live shape of vote_data via DescribeTable, returning null if
     * the table doesn't exist. The shape comparison logic in
     * [TableShape.compareTo] uses this output to decide whether
     * rebuild-projection needs to recreate the table or can no-op.
     */
    suspend fun readLiveMainTableShape(dynamoDb: DynamoDbClient): TableShape? {
        val table = try {
            dynamoDb.describeTable(DescribeTableRequest { tableName = MAIN_TABLE }).table
                ?: return null
        } catch (_: ResourceNotFoundException) {
            return null
        }
        return TableShape(
            attributeDefinitions = (table.attributeDefinitions ?: emptyList()).map {
                AttrDef(
                    name = it.attributeName ?: error("AttributeDefinition missing name"),
                    type = it.attributeType?.value ?: error("AttributeDefinition missing type"),
                )
            }.toSet(),
            keySchema = (table.keySchema ?: emptyList()).map {
                KeyElement(
                    name = it.attributeName ?: error("KeySchemaElement missing name"),
                    keyType = it.keyType?.value ?: error("KeySchemaElement missing type"),
                )
            },
            gsis = (table.globalSecondaryIndexes ?: emptyList()).map { gsi ->
                GsiShape(
                    name = gsi.indexName ?: error("GSI missing name"),
                    keySchema = (gsi.keySchema ?: emptyList()).map {
                        KeyElement(
                            name = it.attributeName ?: error("GSI KeySchemaElement missing name"),
                            keyType = it.keyType?.value ?: error("GSI KeySchemaElement missing type"),
                        )
                    },
                    projectionType = gsi.projection?.projectionType?.value
                        ?: error("GSI missing projection type"),
                )
            }.toSet(),
        )
    }

    private suspend fun createEventLogTable(dynamoDb: DynamoDbClient) {
        dynamoDb.createTable(CreateTableRequest {
            tableName = EVENT_LOG_TABLE
            keySchema = listOf(
                KeySchemaElement {
                    attributeName = "PK"
                    keyType = KeyType.Hash
                },
                KeySchemaElement {
                    attributeName = "event_id"
                    keyType = KeyType.Range
                },
            )
            attributeDefinitions = listOf(
                AttributeDefinition {
                    attributeName = "PK"
                    attributeType = ScalarAttributeType.S
                },
                AttributeDefinition {
                    attributeName = "event_id"
                    attributeType = ScalarAttributeType.N
                },
            )
            billingMode = BillingMode.PayPerRequest
        })
    }

    // Helper functions to build keys.
    // Every user-supplied identifier is lowercased in the key because names
    // in this app are case-insensitive for uniqueness and lookup — only
    // passwords are case-sensitive, and there are no user passwords (Discord
    // OAuth only). The display case lives in the item's "name" /
    // "election_name" / "candidate_name" / "voter_name" / "user_name"
    // attributes; the key is purely a canonicalized lookup form.
    fun userPK(userName: String) = "$USER_PREFIX${userName.lowercase()}"
    fun electionPK(electionName: String) = "$ELECTION_PREFIX${electionName.lowercase()}"
    fun candidateSK(candidateName: String) = "$CANDIDATE_PREFIX${candidateName.lowercase()}"
    fun managerSK(userName: String) = "$MANAGER_PREFIX${userName.lowercase()}"
    fun ballotSK(voterName: String) = "$BALLOT_PREFIX${voterName.lowercase()}"

    /** SK that packs the (candidate, voter) pair onto an election partition. */
    fun noteSK(candidateName: String, voterName: String) =
        "$NOTE_PREFIX${candidateName.lowercase()}#${voterName.lowercase()}"

    /** Prefix that selects every note on one candidate. */
    fun noteSKPrefixForCandidate(candidateName: String) =
        "$NOTE_PREFIX${candidateName.lowercase()}#"
}
