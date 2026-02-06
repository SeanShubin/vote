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

class DynamoDbSingleTableCommandModel(
    private val dynamoDb: DynamoDbClient,
    private val json: Json
) : CommandModel {

    // Sync state commands
    override fun setLastSynced(lastSynced: Long) {
        runBlocking {
            dynamoDb.updateItem(UpdateItemRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                key = mapOf(
                    "PK" to AttributeValue.S("METADATA"),
                    "SK" to AttributeValue.S(DynamoDbSingleTableSchema.SYNC_SK)
                )
                updateExpression = "SET last_synced = :val"
                expressionAttributeValues = mapOf(":val" to AttributeValue.N(lastSynced.toString()))
            })
        }
    }

    override fun initializeLastSynced(lastSynced: Long) {
        runBlocking {
            dynamoDb.putItem(PutItemRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                item = mapOf(
                    "PK" to AttributeValue.S("METADATA"),
                    "SK" to AttributeValue.S(DynamoDbSingleTableSchema.SYNC_SK),
                    "entity_type" to AttributeValue.S("SYNC"),
                    "last_synced" to AttributeValue.N(lastSynced.toString())
                )
            })
        }
    }

    // User commands
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
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                item = mapOf(
                    "PK" to AttributeValue.S(DynamoDbSingleTableSchema.userPK(userName)),
                    "SK" to AttributeValue.S(DynamoDbSingleTableSchema.METADATA_SK),
                    "entity_type" to AttributeValue.S("USER"),
                    "name" to AttributeValue.S(userName),
                    "email" to AttributeValue.S(email),
                    "salt" to AttributeValue.S(salt),
                    "hash" to AttributeValue.S(hash),
                    "role" to AttributeValue.S(role.name),
                    // GSI attributes for email lookup
                    "GSI1PK" to AttributeValue.S(email),
                    "GSI1SK" to AttributeValue.S(DynamoDbSingleTableSchema.userPK(userName))
                )
            })
        }
    }

    override fun setRole(authority: String, userName: String, role: Role) {
        runBlocking {
            dynamoDb.updateItem(UpdateItemRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                key = mapOf(
                    "PK" to AttributeValue.S(DynamoDbSingleTableSchema.userPK(userName)),
                    "SK" to AttributeValue.S(DynamoDbSingleTableSchema.METADATA_SK)
                )
                updateExpression = "SET #r = :role"
                expressionAttributeNames = mapOf("#r" to "role")
                expressionAttributeValues = mapOf(":role" to AttributeValue.S(role.name))
            })
        }
    }

    override fun removeUser(authority: String, userName: String) {
        runBlocking {
            dynamoDb.deleteItem(DeleteItemRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                key = mapOf(
                    "PK" to AttributeValue.S(DynamoDbSingleTableSchema.userPK(userName)),
                    "SK" to AttributeValue.S(DynamoDbSingleTableSchema.METADATA_SK)
                )
            })
        }
    }

    override fun setPassword(authority: String, userName: String, salt: String, hash: String) {
        runBlocking {
            dynamoDb.updateItem(UpdateItemRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                key = mapOf(
                    "PK" to AttributeValue.S(DynamoDbSingleTableSchema.userPK(userName)),
                    "SK" to AttributeValue.S(DynamoDbSingleTableSchema.METADATA_SK)
                )
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
            // Get old user item
            val oldUserItem = dynamoDb.getItem(GetItemRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                key = mapOf(
                    "PK" to AttributeValue.S(DynamoDbSingleTableSchema.userPK(oldUserName)),
                    "SK" to AttributeValue.S(DynamoDbSingleTableSchema.METADATA_SK)
                )
            }).item

            oldUserItem?.let { user ->
                // Delete old item
                dynamoDb.deleteItem(DeleteItemRequest {
                    tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                    key = mapOf(
                        "PK" to AttributeValue.S(DynamoDbSingleTableSchema.userPK(oldUserName)),
                        "SK" to AttributeValue.S(DynamoDbSingleTableSchema.METADATA_SK)
                    )
                })

                // Create new item with updated name
                dynamoDb.putItem(PutItemRequest {
                    tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                    item = user.toMutableMap().apply {
                        this["PK"] = AttributeValue.S(DynamoDbSingleTableSchema.userPK(newUserName))
                        this["name"] = AttributeValue.S(newUserName)
                        this["GSI1SK"] = AttributeValue.S(DynamoDbSingleTableSchema.userPK(newUserName))
                    }
                })
            }

            // Update elections owned by this user
            val elections = dynamoDb.scan(ScanRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                filterExpression = "begins_with(PK, :prefix) AND SK = :sk AND owner_name = :owner"
                expressionAttributeValues = mapOf(
                    ":prefix" to AttributeValue.S(DynamoDbSingleTableSchema.ELECTION_PREFIX),
                    ":sk" to AttributeValue.S(DynamoDbSingleTableSchema.METADATA_SK),
                    ":owner" to AttributeValue.S(oldUserName)
                )
            }).items

            elections?.forEach { election ->
                val electionName = election["election_name"]?.asS() ?: return@forEach
                dynamoDb.updateItem(UpdateItemRequest {
                    tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                    key = mapOf(
                        "PK" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName)),
                        "SK" to AttributeValue.S(DynamoDbSingleTableSchema.METADATA_SK)
                    )
                    updateExpression = "SET owner_name = :owner"
                    expressionAttributeValues = mapOf(":owner" to AttributeValue.S(newUserName))
                })
            }

            // Update eligible voter entries
            val voterItems = dynamoDb.scan(ScanRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                filterExpression = "begins_with(SK, :prefix) AND voter_name = :voter"
                expressionAttributeValues = mapOf(
                    ":prefix" to AttributeValue.S(DynamoDbSingleTableSchema.VOTER_PREFIX),
                    ":voter" to AttributeValue.S(oldUserName)
                )
            }).items

            voterItems?.forEach { voterItem ->
                val pk = voterItem["PK"]?.asS() ?: return@forEach
                val oldSk = voterItem["SK"]?.asS() ?: return@forEach

                dynamoDb.deleteItem(DeleteItemRequest {
                    tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                    key = mapOf(
                        "PK" to AttributeValue.S(pk),
                        "SK" to AttributeValue.S(oldSk)
                    )
                })

                dynamoDb.putItem(PutItemRequest {
                    tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                    item = voterItem.toMutableMap().apply {
                        this["SK"] = AttributeValue.S(DynamoDbSingleTableSchema.voterSK(newUserName))
                        this["voter_name"] = AttributeValue.S(newUserName)
                    }
                })
            }

            // Update ballot entries
            val ballotItems = dynamoDb.scan(ScanRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                filterExpression = "begins_with(SK, :prefix) AND voter_name = :voter"
                expressionAttributeValues = mapOf(
                    ":prefix" to AttributeValue.S(DynamoDbSingleTableSchema.BALLOT_PREFIX),
                    ":voter" to AttributeValue.S(oldUserName)
                )
            }).items

            ballotItems?.forEach { ballotItem ->
                val pk = ballotItem["PK"]?.asS() ?: return@forEach
                val oldSk = ballotItem["SK"]?.asS() ?: return@forEach

                dynamoDb.deleteItem(DeleteItemRequest {
                    tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                    key = mapOf(
                        "PK" to AttributeValue.S(pk),
                        "SK" to AttributeValue.S(oldSk)
                    )
                })

                dynamoDb.putItem(PutItemRequest {
                    tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                    item = ballotItem.toMutableMap().apply {
                        this["SK"] = AttributeValue.S(DynamoDbSingleTableSchema.ballotSK(newUserName))
                        this["voter_name"] = AttributeValue.S(newUserName)
                    }
                })
            }
        }
    }

    override fun setEmail(authority: String, userName: String, email: String) {
        runBlocking {
            dynamoDb.updateItem(UpdateItemRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                key = mapOf(
                    "PK" to AttributeValue.S(DynamoDbSingleTableSchema.userPK(userName)),
                    "SK" to AttributeValue.S(DynamoDbSingleTableSchema.METADATA_SK)
                )
                updateExpression = "SET email = :e, GSI1PK = :gsi1pk"
                expressionAttributeValues = mapOf(
                    ":e" to AttributeValue.S(email),
                    ":gsi1pk" to AttributeValue.S(email)
                )
            })
        }
    }

    // Election commands
    override fun addElection(authority: String, owner: String, electionName: String) {
        runBlocking {
            dynamoDb.putItem(PutItemRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                item = mapOf(
                    "PK" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName)),
                    "SK" to AttributeValue.S(DynamoDbSingleTableSchema.METADATA_SK),
                    "entity_type" to AttributeValue.S("ELECTION"),
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
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                key = mapOf(
                    "PK" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName)),
                    "SK" to AttributeValue.S(DynamoDbSingleTableSchema.METADATA_SK)
                )
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
            // Delete election metadata
            dynamoDb.deleteItem(DeleteItemRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                key = mapOf(
                    "PK" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName)),
                    "SK" to AttributeValue.S(DynamoDbSingleTableSchema.METADATA_SK)
                )
            })

            // Delete all related items (candidates, voters, ballots)
            val relatedItems = dynamoDb.query(QueryRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                keyConditionExpression = "PK = :pk"
                expressionAttributeValues = mapOf(
                    ":pk" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName))
                )
            }).items

            relatedItems?.forEach { item ->
                val sk = item["SK"]?.asS() ?: return@forEach
                dynamoDb.deleteItem(DeleteItemRequest {
                    tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                    key = mapOf(
                        "PK" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName)),
                        "SK" to AttributeValue.S(sk)
                    )
                })
            }
        }
    }

    // Candidate commands
    override fun addCandidates(authority: String, electionName: String, candidateNames: List<String>) {
        if (candidateNames.isEmpty()) return

        runBlocking {
            for (candidateName in candidateNames) {
                dynamoDb.putItem(PutItemRequest {
                    tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                    item = mapOf(
                        "PK" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName)),
                        "SK" to AttributeValue.S(DynamoDbSingleTableSchema.candidateSK(candidateName)),
                        "entity_type" to AttributeValue.S("CANDIDATE"),
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
                    tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                    key = mapOf(
                        "PK" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName)),
                        "SK" to AttributeValue.S(DynamoDbSingleTableSchema.candidateSK(candidateName))
                    )
                })
            }
        }
    }

    // Voter commands
    override fun addVoters(authority: String, electionName: String, voterNames: List<String>) {
        if (voterNames.isEmpty()) return

        runBlocking {
            for (voterName in voterNames) {
                dynamoDb.putItem(PutItemRequest {
                    tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                    item = mapOf(
                        "PK" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName)),
                        "SK" to AttributeValue.S(DynamoDbSingleTableSchema.voterSK(voterName)),
                        "entity_type" to AttributeValue.S("VOTER"),
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
                    tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                    key = mapOf(
                        "PK" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName)),
                        "SK" to AttributeValue.S(DynamoDbSingleTableSchema.voterSK(voterName))
                    )
                })
            }
        }
    }

    // Ballot commands
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
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                item = mapOf(
                    "PK" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName)),
                    "SK" to AttributeValue.S(DynamoDbSingleTableSchema.ballotSK(voterName)),
                    "entity_type" to AttributeValue.S("BALLOT"),
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
            // Find ballot by confirmation
            val queryResponse = dynamoDb.query(QueryRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                keyConditionExpression = "PK = :pk AND begins_with(SK, :sk_prefix)"
                filterExpression = "confirmation = :conf"
                expressionAttributeValues = mapOf(
                    ":pk" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName)),
                    ":sk_prefix" to AttributeValue.S(DynamoDbSingleTableSchema.BALLOT_PREFIX),
                    ":conf" to AttributeValue.S(confirmation)
                )
            })

            val ballot = queryResponse.items?.firstOrNull()
            ballot?.let {
                val voterName = it["voter_name"]?.asS() ?: return@runBlocking

                dynamoDb.updateItem(UpdateItemRequest {
                    tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                    key = mapOf(
                        "PK" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName)),
                        "SK" to AttributeValue.S(DynamoDbSingleTableSchema.ballotSK(voterName))
                    )
                    updateExpression = "SET rankings = :r"
                    expressionAttributeValues = mapOf(":r" to AttributeValue.S(rankingsJson))
                })
            }
        }
    }

    override fun updateWhenCast(authority: String, confirmation: String, now: Instant) {
        runBlocking {
            // Find ballot by confirmation (need to scan since confirmation is not in key)
            val scanResponse = dynamoDb.scan(ScanRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                filterExpression = "begins_with(SK, :prefix) AND confirmation = :conf"
                expressionAttributeValues = mapOf(
                    ":prefix" to AttributeValue.S(DynamoDbSingleTableSchema.BALLOT_PREFIX),
                    ":conf" to AttributeValue.S(confirmation)
                )
            })

            val ballot = scanResponse.items?.firstOrNull()
            ballot?.let {
                val pk = it["PK"]?.asS() ?: return@runBlocking
                val sk = it["SK"]?.asS() ?: return@runBlocking

                dynamoDb.updateItem(UpdateItemRequest {
                    tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                    key = mapOf(
                        "PK" to AttributeValue.S(pk),
                        "SK" to AttributeValue.S(sk)
                    )
                    updateExpression = "SET when_cast = :wc"
                    expressionAttributeValues = mapOf(":wc" to AttributeValue.N(now.toEpochMilliseconds().toString()))
                })
            }
        }
    }
}
