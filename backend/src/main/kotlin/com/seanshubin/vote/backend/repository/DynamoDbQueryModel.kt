package com.seanshubin.vote.backend.repository

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*
import com.seanshubin.vote.contract.QueryModel
import com.seanshubin.vote.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json

class DynamoDbQueryModel(
    private val dynamoDb: DynamoDbClient,
    private val json: Json
) : QueryModel {

    override fun findUserByName(name: String): User {
        return searchUserByName(name) ?: error("User not found: $name")
    }

    override fun findUserByEmail(email: String): User {
        return searchUserByEmail(email) ?: error("User not found with email: $email")
    }

    override fun searchUserByName(name: String): User? {
        return runBlocking {
            val response = dynamoDb.getItem(GetItemRequest {
                tableName = DynamoDbSchema.USERS_TABLE
                key = mapOf("name" to AttributeValue.S(name))
            })

            response.item?.let { itemToUser(it) }
        }
    }

    override fun searchUserByEmail(email: String): User? {
        return runBlocking {
            val response = dynamoDb.query(QueryRequest {
                tableName = DynamoDbSchema.USERS_TABLE
                indexName = "email-index"
                keyConditionExpression = "email = :e"
                expressionAttributeValues = mapOf(":e" to AttributeValue.S(email))
            })

            response.items?.firstOrNull()?.let { itemToUser(it) }
        }
    }

    override fun userCount(): Int {
        return runBlocking {
            val response = dynamoDb.scan(ScanRequest {
                tableName = DynamoDbSchema.USERS_TABLE
                select = Select.Count
            })
            response.count ?: 0
        }
    }

    override fun electionCount(): Int {
        return runBlocking {
            val response = dynamoDb.scan(ScanRequest {
                tableName = DynamoDbSchema.ELECTIONS_TABLE
                select = Select.Count
            })
            response.count ?: 0
        }
    }

    override fun candidateCount(electionName: String): Int {
        return runBlocking {
            val response = dynamoDb.query(QueryRequest {
                tableName = DynamoDbSchema.CANDIDATES_TABLE
                keyConditionExpression = "election_name = :en"
                expressionAttributeValues = mapOf(":en" to AttributeValue.S(electionName))
                select = Select.Count
            })
            response.count ?: 0
        }
    }

    override fun voterCount(electionName: String): Int {
        return runBlocking {
            val response = dynamoDb.query(QueryRequest {
                tableName = DynamoDbSchema.ELIGIBLE_VOTERS_TABLE
                keyConditionExpression = "election_name = :en"
                expressionAttributeValues = mapOf(":en" to AttributeValue.S(electionName))
                select = Select.Count
            })
            response.count ?: 0
        }
    }

    override fun tableCount(): Int {
        return 7
    }

    override fun listUsers(): List<User> {
        return runBlocking {
            val response = dynamoDb.scan(ScanRequest {
                tableName = DynamoDbSchema.USERS_TABLE
            })

            response.items?.map { itemToUser(it) } ?: emptyList()
        }
    }

    override fun listElections(): List<ElectionSummary> {
        return runBlocking {
            val response = dynamoDb.scan(ScanRequest {
                tableName = DynamoDbSchema.ELECTIONS_TABLE
            })

            response.items?.map { itemToElectionSummary(it) } ?: emptyList()
        }
    }

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

    override fun lastSynced(): Long? {
        return runBlocking {
            val response = dynamoDb.getItem(GetItemRequest {
                tableName = DynamoDbSchema.SYNC_STATE_TABLE
                key = mapOf("id" to AttributeValue.S("last_synced"))
            })

            response.item?.get("last_synced")?.asN()?.toLong()
        }
    }

    override fun searchElectionByName(name: String): ElectionSummary? {
        return runBlocking {
            val response = dynamoDb.getItem(GetItemRequest {
                tableName = DynamoDbSchema.ELECTIONS_TABLE
                key = mapOf("election_name" to AttributeValue.S(name))
            })

            response.item?.let { itemToElectionSummary(it) }
        }
    }

    override fun listCandidates(electionName: String): List<String> {
        return runBlocking {
            val response = dynamoDb.query(QueryRequest {
                tableName = DynamoDbSchema.CANDIDATES_TABLE
                keyConditionExpression = "election_name = :en"
                expressionAttributeValues = mapOf(":en" to AttributeValue.S(electionName))
            })

            response.items?.mapNotNull { it["candidate_name"]?.asS() } ?: emptyList()
        }
    }

    override fun listRankings(voterName: String, electionName: String): List<Ranking> {
        return runBlocking {
            val response = dynamoDb.getItem(GetItemRequest {
                tableName = DynamoDbSchema.BALLOTS_TABLE
                key = mapOf(
                    "election_name" to AttributeValue.S(electionName),
                    "voter_name" to AttributeValue.S(voterName)
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
                tableName = DynamoDbSchema.BALLOTS_TABLE
                keyConditionExpression = "election_name = :en"
                expressionAttributeValues = mapOf(":en" to AttributeValue.S(electionName))
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

    override fun searchBallot(voterName: String, electionName: String): BallotSummary? {
        return runBlocking {
            val response = dynamoDb.getItem(GetItemRequest {
                tableName = DynamoDbSchema.BALLOTS_TABLE
                key = mapOf(
                    "election_name" to AttributeValue.S(electionName),
                    "voter_name" to AttributeValue.S(voterName)
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

    override fun listBallots(electionName: String): List<RevealedBallot> {
        return runBlocking {
            val response = dynamoDb.query(QueryRequest {
                tableName = DynamoDbSchema.BALLOTS_TABLE
                keyConditionExpression = "election_name = :en"
                expressionAttributeValues = mapOf(":en" to AttributeValue.S(electionName))
            })

            response.items?.mapNotNull { item ->
                itemToRevealedBallot(item, electionName)
            } ?: emptyList()
        }
    }

    override fun listVoterNames(): List<String> {
        return runBlocking {
            val response = dynamoDb.scan(ScanRequest {
                tableName = DynamoDbSchema.BALLOTS_TABLE
                projectionExpression = "voter_name"
            })

            response.items?.mapNotNull { it["voter_name"]?.asS() }?.distinct() ?: emptyList()
        }
    }

    override fun listVotersForElection(electionName: String): List<String> {
        return runBlocking {
            val response = dynamoDb.query(QueryRequest {
                tableName = DynamoDbSchema.ELIGIBLE_VOTERS_TABLE
                keyConditionExpression = "election_name = :en"
                expressionAttributeValues = mapOf(":en" to AttributeValue.S(electionName))
            })

            response.items?.mapNotNull { it["voter_name"]?.asS() } ?: emptyList()
        }
    }

    override fun listUserNames(): List<String> {
        return runBlocking {
            val response = dynamoDb.scan(ScanRequest {
                tableName = DynamoDbSchema.USERS_TABLE
                projectionExpression = "#n"
                expressionAttributeNames = mapOf("#n" to "name")
            })

            response.items?.mapNotNull { it["name"]?.asS() } ?: emptyList()
        }
    }

    override fun listPermissions(role: Role): List<Permission> {
        return Permission.entries.filter { roleHasPermission(role, it) }
    }

    private fun itemToUser(item: Map<String, AttributeValue>): User {
        return User(
            name = item["name"]?.asS() ?: error("Missing name"),
            email = item["email"]?.asS() ?: error("Missing email"),
            salt = item["salt"]?.asS() ?: error("Missing salt"),
            hash = item["hash"]?.asS() ?: error("Missing hash"),
            role = Role.valueOf(item["role"]?.asS() ?: error("Missing role"))
        )
    }

    private fun itemToElectionSummary(item: Map<String, AttributeValue>): ElectionSummary {
        return ElectionSummary(
            electionName = item["election_name"]?.asS() ?: error("Missing election_name"),
            ownerName = item["owner_name"]?.asS() ?: error("Missing owner_name"),
            secretBallot = item["secret_ballot"]?.asBool() ?: false,
            noVotingBefore = item["no_voting_before"]?.asN()?.toLong()?.let { Instant.fromEpochMilliseconds(it) },
            noVotingAfter = item["no_voting_after"]?.asN()?.toLong()?.let { Instant.fromEpochMilliseconds(it) },
            allowEdit = item["allow_edit"]?.asBool() ?: true,
            allowVote = item["allow_vote"]?.asBool() ?: true
        )
    }

    private fun itemToRevealedBallot(item: Map<String, AttributeValue>, electionName: String): RevealedBallot? {
        val voterName = item["voter_name"]?.asS() ?: return null
        val rankingsJson = item["rankings"]?.asS() ?: return null
        val rankings = json.decodeFromString<List<Ranking>>(rankingsJson)
        val confirmation = item["confirmation"]?.asS() ?: return null
        val whenCast = item["when_cast"]?.asN()?.toLong()?.let { Instant.fromEpochMilliseconds(it) }
            ?: return null

        return RevealedBallot(
            voterName = voterName,
            electionName = electionName,
            rankings = rankings,
            confirmation = confirmation,
            whenCast = whenCast
        )
    }
}
