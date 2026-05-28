package com.seanshubin.vote.backend.repository

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*
import com.seanshubin.vote.contract.QueryModel
import com.seanshubin.vote.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json

class DynamoDbSingleTableQueryModel(
    private val dynamoDb: DynamoDbClient,
    private val json: Json
) : QueryModel {

    // User queries
    override fun findUserByName(name: String): User {
        return searchUserByName(name) ?: error("User not found: $name")
    }

    override fun searchUserByName(name: String): User? {
        return runBlocking {
            val response = dynamoDb.getItem(GetItemRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                key = mapOf(
                    "PK" to AttributeValue.S(DynamoDbSingleTableSchema.userPK(name)),
                    "SK" to AttributeValue.S(DynamoDbSingleTableSchema.METADATA_SK)
                )
            })

            response.item?.let { itemToUser(it) }
        }
    }

    override fun searchUserByDiscordId(discordId: String): User? {
        // Blank discordId never matches anyone — users without a Discord
        // credential have an empty discord_id attribute (and so don't
        // appear in the sparse discord-id-index at all).
        if (discordId.isEmpty()) return null
        return runBlocking {
            val response = dynamoDb.query(QueryRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                indexName = DynamoDbSingleTableSchema.DISCORD_ID_INDEX
                keyConditionExpression = "discord_id = :did"
                expressionAttributeValues = mapOf(
                    ":did" to AttributeValue.S(discordId),
                )
            })
            response.items?.firstOrNull()?.let { itemToUser(it) }
        }
    }

    override fun userCount(): Int {
        return runBlocking {
            // Use scan for counting (acceptable for admin operations)
            val response = dynamoDb.scan(ScanRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                filterExpression = "begins_with(PK, :prefix) AND SK = :sk"
                expressionAttributeValues = mapOf(
                    ":prefix" to AttributeValue.S(DynamoDbSingleTableSchema.USER_PREFIX),
                    ":sk" to AttributeValue.S(DynamoDbSingleTableSchema.METADATA_SK)
                )
                select = Select.Count
            })
            response.count ?: 0
        }
    }

    override fun listUsers(): List<User> {
        return runBlocking {
            val response = dynamoDb.scan(ScanRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                filterExpression = "begins_with(PK, :prefix) AND SK = :sk"
                expressionAttributeValues = mapOf(
                    ":prefix" to AttributeValue.S(DynamoDbSingleTableSchema.USER_PREFIX),
                    ":sk" to AttributeValue.S(DynamoDbSingleTableSchema.METADATA_SK)
                )
            })

            response.items?.map { itemToUser(it) } ?: emptyList()
        }
    }

    override fun listUserNames(): List<String> {
        return runBlocking {
            val response = dynamoDb.scan(ScanRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                filterExpression = "begins_with(PK, :prefix) AND SK = :sk"
                expressionAttributeValues = mapOf(
                    ":prefix" to AttributeValue.S(DynamoDbSingleTableSchema.USER_PREFIX),
                    ":sk" to AttributeValue.S(DynamoDbSingleTableSchema.METADATA_SK)
                )
                projectionExpression = "#n"
                expressionAttributeNames = mapOf("#n" to "name")
            })

            response.items?.mapNotNull { it["name"]?.asS() } ?: emptyList()
        }
    }

    // Election queries
    override fun electionCount(): Int {
        return runBlocking {
            // Query the election-listing GSI's single partition — sparse to
            // election METADATA rows — instead of scanning the whole table.
            // Count is still 1MB-capped per Query call, so loop on
            // lastEvaluatedKey to cover the full set.
            var count = 0
            var startKey: Map<String, AttributeValue>? = null
            do {
                val response = dynamoDb.query(QueryRequest {
                    tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                    indexName = DynamoDbSingleTableSchema.ELECTION_LISTING_INDEX
                    keyConditionExpression = "#h = :v"
                    expressionAttributeNames = mapOf("#h" to DynamoDbSingleTableSchema.ELECTION_LISTING_ATTR)
                    expressionAttributeValues = mapOf(
                        ":v" to AttributeValue.S(DynamoDbSingleTableSchema.ELECTION_LISTING_VALUE),
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

    override fun searchElectionByName(name: String): ElectionSummary? {
        return runBlocking {
            val response = dynamoDb.getItem(GetItemRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                key = mapOf(
                    "PK" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(name)),
                    "SK" to AttributeValue.S(DynamoDbSingleTableSchema.METADATA_SK)
                )
            })

            response.item?.let { itemToElectionSummary(it) }
        }
    }

    override fun listElections(): List<ElectionSummary> {
        return runBlocking {
            // Query the sparse election-listing GSI (one partition, sorted by
            // election_name) instead of scanning the whole table. Loop on
            // lastEvaluatedKey so a list larger than one 1MB page still
            // returns in full.
            val items = mutableListOf<Map<String, AttributeValue>>()
            var startKey: Map<String, AttributeValue>? = null
            do {
                val response = dynamoDb.query(QueryRequest {
                    tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                    indexName = DynamoDbSingleTableSchema.ELECTION_LISTING_INDEX
                    keyConditionExpression = "#h = :v"
                    expressionAttributeNames = mapOf("#h" to DynamoDbSingleTableSchema.ELECTION_LISTING_ATTR)
                    expressionAttributeValues = mapOf(
                        ":v" to AttributeValue.S(DynamoDbSingleTableSchema.ELECTION_LISTING_VALUE),
                    )
                    exclusiveStartKey = startKey
                })
                response.items?.let { items.addAll(it) }
                startKey = response.lastEvaluatedKey
            } while (startKey != null)
            items.map { itemToElectionSummary(it) }
        }
    }

    // Candidate queries
    override fun candidateCount(electionName: String): Int {
        return runBlocking {
            val response = dynamoDb.query(QueryRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                keyConditionExpression = "PK = :pk AND begins_with(SK, :sk_prefix)"
                expressionAttributeValues = mapOf(
                    ":pk" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName)),
                    ":sk_prefix" to AttributeValue.S(DynamoDbSingleTableSchema.CANDIDATE_PREFIX)
                )
                select = Select.Count
            })
            response.count ?: 0
        }
    }

    override fun listCandidates(electionName: String): List<String> {
        return runBlocking {
            val response = dynamoDb.query(QueryRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                keyConditionExpression = "PK = :pk AND begins_with(SK, :sk_prefix)"
                expressionAttributeValues = mapOf(
                    ":pk" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName)),
                    ":sk_prefix" to AttributeValue.S(DynamoDbSingleTableSchema.CANDIDATE_PREFIX)
                )
            })

            response.items?.mapNotNull { it["candidate_name"]?.asS() } ?: emptyList()
        }
    }

    override fun listElectionManagers(electionName: String): List<String> {
        return runBlocking {
            val response = dynamoDb.query(QueryRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                keyConditionExpression = "PK = :pk AND begins_with(SK, :sk_prefix)"
                expressionAttributeValues = mapOf(
                    ":pk" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName)),
                    ":sk_prefix" to AttributeValue.S(DynamoDbSingleTableSchema.MANAGER_PREFIX)
                )
            })

            response.items?.mapNotNull { it["user_name"]?.asS() }
                ?.sortedWith(String.CASE_INSENSITIVE_ORDER)
                ?: emptyList()
        }
    }

    override fun candidateBallotCounts(electionName: String): Map<String, Int> {
        // Two queries — candidate list (for the zero-count entries) and the
        // ballot list (rankings JSON contains the candidate names). The
        // editor UI calls this once per setup-tab render, so a two-shot
        // scan is acceptable here.
        val candidates = listCandidates(electionName)
        if (candidates.isEmpty()) return emptyMap()
        val counts = candidates.associateWith { 0 }.toMutableMap()
        runBlocking {
            val ballotItems = dynamoDb.query(QueryRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                keyConditionExpression = "PK = :pk AND begins_with(SK, :sk_prefix)"
                expressionAttributeValues = mapOf(
                    ":pk" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName)),
                    ":sk_prefix" to AttributeValue.S(DynamoDbSingleTableSchema.BALLOT_PREFIX)
                )
            }).items
            ballotItems?.forEach { item ->
                val rankingsJson = item["rankings"]?.asS() ?: return@forEach
                val rankings = json.decodeFromString<List<Ranking>>(rankingsJson)
                rankings.map { it.candidateName }.toSet().forEach { name ->
                    if (name in counts) counts[name] = counts.getValue(name) + 1
                }
            }
        }
        return counts
    }

    override fun listTiers(electionName: String): List<String> {
        // Tiers live as a list attribute on the election METADATA item — same
        // GetItem shape as searchElectionByName, just projecting only the
        // tiers attribute. Missing attribute = no tiers configured.
        return runBlocking {
            val response = dynamoDb.getItem(GetItemRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                key = mapOf(
                    "PK" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName)),
                    "SK" to AttributeValue.S(DynamoDbSingleTableSchema.METADATA_SK)
                )
                projectionExpression = "tiers"
            })

            response.item?.get("tiers")?.asLOrNull()
                ?.mapNotNull { it.asSOrNull() }
                ?: emptyList()
        }
    }

    override fun ballotCount(electionName: String): Int {
        return runBlocking {
            val response = dynamoDb.query(QueryRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                keyConditionExpression = "PK = :pk AND begins_with(SK, :sk_prefix)"
                expressionAttributeValues = mapOf(
                    ":pk" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName)),
                    ":sk_prefix" to AttributeValue.S(DynamoDbSingleTableSchema.BALLOT_PREFIX)
                )
                select = Select.Count
            })
            response.count ?: 0
        }
    }

    // Ballot queries
    override fun searchBallot(voterName: String, electionName: String): BallotSummary? {
        return runBlocking {
            val response = dynamoDb.getItem(GetItemRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                key = mapOf(
                    "PK" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName)),
                    "SK" to AttributeValue.S(DynamoDbSingleTableSchema.ballotSK(voterName))
                )
            })

            response.item?.let { item ->
                BallotSummary(
                    voterName = voterName,
                    electionName = electionName,
                    confirmation = item["confirmation"]?.asS() ?: "",
                    whenCast = item["when_cast"]?.asN()?.toLong()?.let { Instant.fromEpochMilliseconds(it) }
                        ?: Instant.fromEpochMilliseconds(0)
                )
            }
        }
    }

    override fun listBallots(electionName: String): List<Ballot.Identified> {
        return runBlocking {
            val response = dynamoDb.query(QueryRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                keyConditionExpression = "PK = :pk AND begins_with(SK, :sk_prefix)"
                expressionAttributeValues = mapOf(
                    ":pk" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName)),
                    ":sk_prefix" to AttributeValue.S(DynamoDbSingleTableSchema.BALLOT_PREFIX)
                )
            })

            response.items?.mapNotNull { item ->
                itemToIdentifiedBallot(item, electionName)
            } ?: emptyList()
        }
    }

    override fun listRankings(voterName: String, electionName: String): List<Ranking> {
        return runBlocking {
            val response = dynamoDb.getItem(GetItemRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                key = mapOf(
                    "PK" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName)),
                    "SK" to AttributeValue.S(DynamoDbSingleTableSchema.ballotSK(voterName))
                )
            })

            response.item?.get("rankings")?.asS()?.let { rankingsJson ->
                json.decodeFromString(rankingsJson)
            } ?: emptyList()
        }
    }

    override fun listRankings(electionName: String): List<VoterElectionCandidateRank> {
        return runBlocking {
            val response = dynamoDb.query(QueryRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                keyConditionExpression = "PK = :pk AND begins_with(SK, :sk_prefix)"
                expressionAttributeValues = mapOf(
                    ":pk" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName)),
                    ":sk_prefix" to AttributeValue.S(DynamoDbSingleTableSchema.BALLOT_PREFIX)
                )
            })

            response.items?.flatMap { item ->
                val voterName = item["voter_name"]?.asS() ?: return@flatMap emptyList()
                val rankingsJson = item["rankings"]?.asS() ?: return@flatMap emptyList()
                val rankings = json.decodeFromString<List<Ranking>>(rankingsJson)

                rankings.mapNotNull { ranking ->
                    ranking.rank?.let { rank ->
                        VoterElectionCandidateRank(
                            voter = voterName,
                            election = electionName,
                            candidate = ranking.candidateName,
                            rank = rank
                        )
                    }
                }
            } ?: emptyList()
        }
    }

    // Permission and role queries
    override fun roleHasPermission(role: Role, permission: Permission): Boolean {
        return when (permission) {
            Permission.VIEW_APPLICATION -> role >= Role.OBSERVER
            Permission.VOTE -> role >= Role.VOTER
            Permission.USE_APPLICATION -> role >= Role.USER
            Permission.MANAGE_USERS -> role >= Role.ADMIN
            Permission.VIEW_SECRETS -> role >= Role.AUDITOR
            Permission.TRANSFER_OWNER -> role == Role.OWNER
        }
    }

    override fun listPermissions(role: Role): List<Permission> {
        return Permission.entries.filter { roleHasPermission(role, it) }
    }

    override fun electionsOwnedCount(userName: String): Int {
        return runBlocking {
            val response = dynamoDb.query(QueryRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                indexName = DynamoDbSingleTableSchema.OWNER_NAME_INDEX
                keyConditionExpression = "owner_name = :owner AND SK = :sk"
                expressionAttributeValues = mapOf(
                    ":owner" to AttributeValue.S(userName),
                    ":sk" to AttributeValue.S(DynamoDbSingleTableSchema.METADATA_SK),
                )
                select = Select.Count
            })
            response.count ?: 0
        }
    }

    override fun ballotsCastCount(userName: String): Int {
        return runBlocking {
            val response = dynamoDb.query(QueryRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                indexName = DynamoDbSingleTableSchema.VOTER_NAME_INDEX
                keyConditionExpression = "voter_name = :voter AND begins_with(SK, :prefix)"
                expressionAttributeValues = mapOf(
                    ":voter" to AttributeValue.S(userName),
                    ":prefix" to AttributeValue.S(DynamoDbSingleTableSchema.BALLOT_PREFIX),
                )
                select = Select.Count
            })
            response.count ?: 0
        }
    }

    // Sync state queries
    override fun lastSynced(): Long? {
        return runBlocking {
            val response = dynamoDb.getItem(GetItemRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                key = mapOf(
                    "PK" to AttributeValue.S("METADATA"),
                    "SK" to AttributeValue.S(DynamoDbSingleTableSchema.SYNC_SK)
                )
            })

            response.item?.get("last_synced")?.asN()?.toLong()
        }
    }

    override fun listCandidateNotes(electionName: String, candidateName: String): List<CandidateNote> {
        return runBlocking {
            val response = dynamoDb.query(QueryRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                keyConditionExpression = "PK = :pk AND begins_with(SK, :sk_prefix)"
                expressionAttributeValues = mapOf(
                    ":pk" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName)),
                    ":sk_prefix" to AttributeValue.S(
                        DynamoDbSingleTableSchema.noteSKPrefixForCandidate(candidateName)
                    ),
                )
            })
            response.items?.mapNotNull { itemToCandidateNote(it) }
                ?.sortedByDescending { it.lastUpdated }
                ?: emptyList()
        }
    }

    override fun listCandidateNotesByElection(electionName: String): List<CandidateNote> {
        return runBlocking {
            val response = dynamoDb.query(QueryRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                keyConditionExpression = "PK = :pk AND begins_with(SK, :sk_prefix)"
                expressionAttributeValues = mapOf(
                    ":pk" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName)),
                    ":sk_prefix" to AttributeValue.S(DynamoDbSingleTableSchema.NOTE_PREFIX),
                )
            })
            response.items?.mapNotNull { itemToCandidateNote(it) } ?: emptyList()
        }
    }

    private fun itemToCandidateNote(item: Map<String, AttributeValue>): CandidateNote? {
        val electionName = item["election_name"]?.asS() ?: return null
        val candidateName = item["candidate_name"]?.asS() ?: return null
        val voterName = item["voter_name"]?.asS() ?: return null
        val text = item["note_text"]?.asS() ?: return null
        val lastUpdated = item["last_updated"]?.asN()?.toLongOrNull()
            ?.let { Instant.fromEpochMilliseconds(it) }
            ?: return null
        return CandidateNote(
            electionName = electionName,
            candidateName = candidateName,
            voterName = voterName,
            text = text,
            lastUpdated = lastUpdated,
        )
    }

    // Metadata queries
    override fun tableCount(): Int {
        return 3  // vote_data, vote_event_log, vote_operator_state
    }

    // Helper functions to convert DynamoDB items to domain objects
    private fun itemToUser(item: Map<String, AttributeValue>): User {
        return User(
            name = item["name"]?.asS() ?: error("Missing name"),
            role = Role.valueOf(item["role"]?.asS() ?: error("Missing role")),
            discordId = item["discord_id"]?.asS() ?: "",
            discordDisplayName = item["discord_display_name"]?.asS() ?: "",
        )
    }

    private fun itemToElectionSummary(item: Map<String, AttributeValue>): ElectionSummary {
        return ElectionSummary(
            electionName = item["election_name"]?.asS() ?: error("Missing election_name"),
            ownerName = item["owner_name"]?.asS() ?: error("Missing owner_name"),
            // Older items may not have a description attribute — default to "".
            description = item["description"]?.asS() ?: "",
        )
    }

    private fun itemToIdentifiedBallot(item: Map<String, AttributeValue>, electionName: String): Ballot.Identified? {
        val voterName = item["voter_name"]?.asS() ?: return null
        val rankingsJson = item["rankings"]?.asS() ?: return null
        val rankings = json.decodeFromString<List<Ranking>>(rankingsJson)
        val confirmation = item["confirmation"]?.asS() ?: return null
        val whenCast = item["when_cast"]?.asN()?.toLong()?.let { Instant.fromEpochMilliseconds(it) }
            ?: return null

        return Ballot.Identified(
            voterName = voterName,
            electionName = electionName,
            rankings = rankings,
            confirmation = confirmation,
            whenCast = whenCast
        )
    }
}
