package com.seanshubin.vote.backend.repository

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*
import com.seanshubin.vote.contract.CommandModel
import com.seanshubin.vote.domain.ElectionUpdates
import com.seanshubin.vote.domain.Ranking
import com.seanshubin.vote.domain.Role
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DynamoDbCommandModel(
    private val dynamoDb: DynamoDbClient,
    private val json: Json
) : CommandModel {

    override fun setLastSynced(lastSynced: Long) {
        runBlocking {
            dynamoDb.updateItem(UpdateItemRequest {
                tableName = DynamoDbSchema.SYNC_STATE_TABLE
                key = mapOf("id" to AttributeValue.S("last_synced"))
                updateExpression = "SET last_synced = :val"
                expressionAttributeValues = mapOf(":val" to AttributeValue.N(lastSynced.toString()))
            })
        }
    }

    override fun initializeLastSynced(lastSynced: Long) {
        runBlocking {
            dynamoDb.putItem(PutItemRequest {
                tableName = DynamoDbSchema.SYNC_STATE_TABLE
                item = mapOf(
                    "id" to AttributeValue.S("last_synced"),
                    "last_synced" to AttributeValue.N(lastSynced.toString())
                )
            })
        }
    }

    override fun createUser(
        authority: String,
        userName: String,
        email: String,
        salt: String,
        hash: String,
        role: Role
    ) {
        runBlocking {
            dynamoDb.putItem(PutItemRequest {
                tableName = DynamoDbSchema.USERS_TABLE
                item = mapOf(
                    "name" to AttributeValue.S(userName),
                    "email" to AttributeValue.S(email),
                    "salt" to AttributeValue.S(salt),
                    "hash" to AttributeValue.S(hash),
                    "role" to AttributeValue.S(role.name)
                )
            })
        }
    }

    override fun setRole(authority: String, userName: String, role: Role) {
        runBlocking {
            dynamoDb.updateItem(UpdateItemRequest {
                tableName = DynamoDbSchema.USERS_TABLE
                key = mapOf("name" to AttributeValue.S(userName))
                updateExpression = "SET #r = :role"
                expressionAttributeNames = mapOf("#r" to "role")
                expressionAttributeValues = mapOf(":role" to AttributeValue.S(role.name))
            })
        }
    }

    override fun removeUser(authority: String, userName: String) {
        runBlocking {
            dynamoDb.deleteItem(DeleteItemRequest {
                tableName = DynamoDbSchema.USERS_TABLE
                key = mapOf("name" to AttributeValue.S(userName))
            })
        }
    }

    override fun addElection(authority: String, owner: String, electionName: String) {
        runBlocking {
            dynamoDb.putItem(PutItemRequest {
                tableName = DynamoDbSchema.ELECTIONS_TABLE
                item = mapOf(
                    "election_name" to AttributeValue.S(electionName),
                    "owner_name" to AttributeValue.S(owner),
                    "secret_ballot" to AttributeValue.Bool(false),
                    "allow_vote" to AttributeValue.Bool(true),
                    "allow_edit" to AttributeValue.Bool(true)
                )
            })
        }
    }

    override fun updateElection(authority: String, electionName: String, updates: ElectionUpdates) {
        val updateExpressions = mutableListOf<String>()
        val attrValues = mutableMapOf<String, AttributeValue>()
        val attrNames = mutableMapOf<String, String>()

        updates.newElectionName?.let {
            updateExpressions.add("#en = :en")
            attrNames["#en"] = "election_name"
            attrValues[":en"] = AttributeValue.S(it)
        }
        updates.secretBallot?.let {
            updateExpressions.add("secret_ballot = :sb")
            attrValues[":sb"] = AttributeValue.Bool(it)
        }
        if (updates.clearNoVotingBefore == true) {
            updateExpressions.add("REMOVE no_voting_before")
        } else {
            updates.noVotingBefore?.let {
                updateExpressions.add("no_voting_before = :nvb")
                attrValues[":nvb"] = AttributeValue.N(it.toEpochMilliseconds().toString())
            }
        }
        if (updates.clearNoVotingAfter == true) {
            updateExpressions.add("REMOVE no_voting_after")
        } else {
            updates.noVotingAfter?.let {
                updateExpressions.add("no_voting_after = :nva")
                attrValues[":nva"] = AttributeValue.N(it.toEpochMilliseconds().toString())
            }
        }
        updates.allowVote?.let {
            updateExpressions.add("allow_vote = :av")
            attrValues[":av"] = AttributeValue.Bool(it)
        }
        updates.allowEdit?.let {
            updateExpressions.add("allow_edit = :ae")
            attrValues[":ae"] = AttributeValue.Bool(it)
        }

        if (updateExpressions.isEmpty()) return

        runBlocking {
            dynamoDb.updateItem(UpdateItemRequest {
                tableName = DynamoDbSchema.ELECTIONS_TABLE
                key = mapOf("election_name" to AttributeValue.S(electionName))
                updateExpression = "SET ${updateExpressions.joinToString(", ")}"
                expressionAttributeValues = attrValues
                if (attrNames.isNotEmpty()) {
                    expressionAttributeNames = attrNames
                }
            })
        }
    }

    override fun deleteElection(authority: String, electionName: String) {
        runBlocking {
            dynamoDb.deleteItem(DeleteItemRequest {
                tableName = DynamoDbSchema.ELECTIONS_TABLE
                key = mapOf("election_name" to AttributeValue.S(electionName))
            })
        }
    }

    override fun addCandidates(authority: String, electionName: String, candidateNames: List<String>) {
        if (candidateNames.isEmpty()) return

        runBlocking {
            for (candidateName in candidateNames) {
                dynamoDb.putItem(PutItemRequest {
                    tableName = DynamoDbSchema.CANDIDATES_TABLE
                    item = mapOf(
                        "election_name" to AttributeValue.S(electionName),
                        "candidate_name" to AttributeValue.S(candidateName)
                    )
                })
            }
        }
    }

    override fun removeCandidates(authority: String, electionName: String, candidateNames: List<String>) {
        if (candidateNames.isEmpty()) return

        runBlocking {
            for (candidateName in candidateNames) {
                dynamoDb.deleteItem(DeleteItemRequest {
                    tableName = DynamoDbSchema.CANDIDATES_TABLE
                    key = mapOf(
                        "election_name" to AttributeValue.S(electionName),
                        "candidate_name" to AttributeValue.S(candidateName)
                    )
                })
            }
        }
    }

    override fun addVoters(authority: String, electionName: String, voterNames: List<String>) {
        if (voterNames.isEmpty()) return

        runBlocking {
            for (voterName in voterNames) {
                dynamoDb.putItem(PutItemRequest {
                    tableName = DynamoDbSchema.ELIGIBLE_VOTERS_TABLE
                    item = mapOf(
                        "election_name" to AttributeValue.S(electionName),
                        "voter_name" to AttributeValue.S(voterName)
                    )
                })
            }
        }
    }

    override fun removeVoters(authority: String, electionName: String, voterNames: List<String>) {
        if (voterNames.isEmpty()) return

        runBlocking {
            for (voterName in voterNames) {
                dynamoDb.deleteItem(DeleteItemRequest {
                    tableName = DynamoDbSchema.ELIGIBLE_VOTERS_TABLE
                    key = mapOf(
                        "election_name" to AttributeValue.S(electionName),
                        "voter_name" to AttributeValue.S(voterName)
                    )
                })
            }
        }
    }

    override fun castBallot(
        authority: String,
        voterName: String,
        electionName: String,
        rankings: List<Ranking>,
        confirmation: String,
        now: Instant
    ) {
        val rankingsJson = json.encodeToString(rankings)

        runBlocking {
            dynamoDb.putItem(PutItemRequest {
                tableName = DynamoDbSchema.BALLOTS_TABLE
                item = mapOf(
                    "election_name" to AttributeValue.S(electionName),
                    "voter_name" to AttributeValue.S(voterName),
                    "rankings" to AttributeValue.S(rankingsJson),
                    "confirmation" to AttributeValue.S(confirmation),
                    "when_cast" to AttributeValue.N(now.toEpochMilliseconds().toString())
                )
            })
        }
    }

    override fun setRankings(authority: String, confirmation: String, electionName: String, rankings: List<Ranking>) {
        val rankingsJson = json.encodeToString(rankings)

        runBlocking {
            val queryResponse = dynamoDb.query(QueryRequest {
                tableName = DynamoDbSchema.BALLOTS_TABLE
                keyConditionExpression = "election_name = :en"
                filterExpression = "confirmation = :conf"
                expressionAttributeValues = mapOf(
                    ":en" to AttributeValue.S(electionName),
                    ":conf" to AttributeValue.S(confirmation)
                )
            })

            val ballot = queryResponse.items?.firstOrNull()
            ballot?.let {
                val voterName = it["voter_name"]?.asS() ?: return@runBlocking

                dynamoDb.updateItem(UpdateItemRequest {
                    tableName = DynamoDbSchema.BALLOTS_TABLE
                    key = mapOf(
                        "election_name" to AttributeValue.S(electionName),
                        "voter_name" to AttributeValue.S(voterName)
                    )
                    updateExpression = "SET rankings = :r"
                    expressionAttributeValues = mapOf(":r" to AttributeValue.S(rankingsJson))
                })
            }
        }
    }

    override fun updateWhenCast(authority: String, confirmation: String, now: Instant) {
        runBlocking {
            val scanResponse = dynamoDb.scan(ScanRequest {
                tableName = DynamoDbSchema.BALLOTS_TABLE
                filterExpression = "confirmation = :conf"
                expressionAttributeValues = mapOf(
                    ":conf" to AttributeValue.S(confirmation)
                )
            })

            val ballot = scanResponse.items?.firstOrNull()
            ballot?.let {
                val electionName = it["election_name"]?.asS() ?: return@runBlocking
                val voterName = it["voter_name"]?.asS() ?: return@runBlocking

                dynamoDb.updateItem(UpdateItemRequest {
                    tableName = DynamoDbSchema.BALLOTS_TABLE
                    key = mapOf(
                        "election_name" to AttributeValue.S(electionName),
                        "voter_name" to AttributeValue.S(voterName)
                    )
                    updateExpression = "SET when_cast = :wc"
                    expressionAttributeValues = mapOf(":wc" to AttributeValue.N(now.toEpochMilliseconds().toString()))
                })
            }
        }
    }

    override fun setPassword(authority: String, userName: String, salt: String, hash: String) {
        runBlocking {
            dynamoDb.updateItem(UpdateItemRequest {
                tableName = DynamoDbSchema.USERS_TABLE
                key = mapOf("name" to AttributeValue.S(userName))
                updateExpression = "SET salt = :s, #h = :hash"
                expressionAttributeNames = mapOf("#h" to "hash")
                expressionAttributeValues = mapOf(
                    ":s" to AttributeValue.S(salt),
                    ":hash" to AttributeValue.S(hash)
                )
            })
        }
    }

    override fun setUserName(authority: String, oldUserName: String, newUserName: String) {
        runBlocking {
            val user = dynamoDb.getItem(GetItemRequest {
                tableName = DynamoDbSchema.USERS_TABLE
                key = mapOf("name" to AttributeValue.S(oldUserName))
            }).item

            user?.let {
                dynamoDb.deleteItem(DeleteItemRequest {
                    tableName = DynamoDbSchema.USERS_TABLE
                    key = mapOf("name" to AttributeValue.S(oldUserName))
                })

                dynamoDb.putItem(PutItemRequest {
                    tableName = DynamoDbSchema.USERS_TABLE
                    item = it.toMutableMap().apply {
                        this["name"] = AttributeValue.S(newUserName)
                    }
                })
            }

            val elections = dynamoDb.query(QueryRequest {
                tableName = DynamoDbSchema.ELECTIONS_TABLE
                indexName = "owner-index"
                keyConditionExpression = "owner_name = :on"
                expressionAttributeValues = mapOf(":on" to AttributeValue.S(oldUserName))
            }).items

            elections?.forEach { election ->
                val electionName = election["election_name"]?.asS() ?: return@forEach
                dynamoDb.updateItem(UpdateItemRequest {
                    tableName = DynamoDbSchema.ELECTIONS_TABLE
                    key = mapOf("election_name" to AttributeValue.S(electionName))
                    updateExpression = "SET owner_name = :on"
                    expressionAttributeValues = mapOf(":on" to AttributeValue.S(newUserName))
                })
            }

            val voterItems = dynamoDb.scan(ScanRequest {
                tableName = DynamoDbSchema.ELIGIBLE_VOTERS_TABLE
                filterExpression = "voter_name = :vn"
                expressionAttributeValues = mapOf(":vn" to AttributeValue.S(oldUserName))
            }).items

            voterItems?.forEach { voterItem ->
                val electionName = voterItem["election_name"]?.asS() ?: return@forEach
                dynamoDb.deleteItem(DeleteItemRequest {
                    tableName = DynamoDbSchema.ELIGIBLE_VOTERS_TABLE
                    key = mapOf(
                        "election_name" to AttributeValue.S(electionName),
                        "voter_name" to AttributeValue.S(oldUserName)
                    )
                })
                dynamoDb.putItem(PutItemRequest {
                    tableName = DynamoDbSchema.ELIGIBLE_VOTERS_TABLE
                    item = mapOf(
                        "election_name" to AttributeValue.S(electionName),
                        "voter_name" to AttributeValue.S(newUserName)
                    )
                })
            }

            val ballotItems = dynamoDb.scan(ScanRequest {
                tableName = DynamoDbSchema.BALLOTS_TABLE
                filterExpression = "voter_name = :vn"
                expressionAttributeValues = mapOf(":vn" to AttributeValue.S(oldUserName))
            }).items

            ballotItems?.forEach { ballotItem ->
                val electionName = ballotItem["election_name"]?.asS() ?: return@forEach
                dynamoDb.deleteItem(DeleteItemRequest {
                    tableName = DynamoDbSchema.BALLOTS_TABLE
                    key = mapOf(
                        "election_name" to AttributeValue.S(electionName),
                        "voter_name" to AttributeValue.S(oldUserName)
                    )
                })
                dynamoDb.putItem(PutItemRequest {
                    tableName = DynamoDbSchema.BALLOTS_TABLE
                    item = ballotItem.toMutableMap().apply {
                        this["voter_name"] = AttributeValue.S(newUserName)
                    }
                })
            }
        }
    }

    override fun setEmail(authority: String, userName: String, email: String) {
        runBlocking {
            dynamoDb.updateItem(UpdateItemRequest {
                tableName = DynamoDbSchema.USERS_TABLE
                key = mapOf("name" to AttributeValue.S(userName))
                updateExpression = "SET email = :e"
                expressionAttributeValues = mapOf(":e" to AttributeValue.S(email))
            })
        }
    }
}
