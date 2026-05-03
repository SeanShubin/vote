package com.seanshubin.vote.backend.repository

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*
import com.seanshubin.vote.contract.CommandModel
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
                // GSI attributes for email lookup — keyed by lowercase email so
                // the equality lookup is case-insensitive. Display case is the
                // "email" attribute. Blank email skips the GSI entirely:
                // DynamoDB simply omits items missing a GSI key from the
                // index, so `searchUserByEmail` will never find this user
                // (which is the desired behavior — they have no email).
                val base = mapOf(
                    "PK" to AttributeValue.S(DynamoDbSingleTableSchema.userPK(userName)),
                    "SK" to AttributeValue.S(DynamoDbSingleTableSchema.METADATA_SK),
                    "entity_type" to AttributeValue.S("USER"),
                    "name" to AttributeValue.S(userName),
                    "email" to AttributeValue.S(email),
                    "salt" to AttributeValue.S(salt),
                    "hash" to AttributeValue.S(hash),
                    "role" to AttributeValue.S(role.name),
                )
                item = if (email.isEmpty()) base else base + mapOf(
                    "GSI1PK" to AttributeValue.S(DynamoDbSingleTableSchema.emailKey(email)),
                    "GSI1SK" to AttributeValue.S(DynamoDbSingleTableSchema.userPK(userName)),
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
            // Cascade: drop ballot items cast by this user. Matches MySQL's
            // FK CASCADE on ballots.voter_name → users(name); without this the
            // ballot items survive and point at a deleted voter.
            val ballotItems = dynamoDb.scan(ScanRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                filterExpression = "begins_with(SK, :prefix) AND voter_name = :voter"
                expressionAttributeValues = mapOf(
                    ":prefix" to AttributeValue.S(DynamoDbSingleTableSchema.BALLOT_PREFIX),
                    ":voter" to AttributeValue.S(userName),
                )
            }).items
            ballotItems?.forEach { item ->
                val pk = item["PK"]?.asS() ?: return@forEach
                val sk = item["SK"]?.asS() ?: return@forEach
                dynamoDb.deleteItem(DeleteItemRequest {
                    tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                    key = mapOf(
                        "PK" to AttributeValue.S(pk),
                        "SK" to AttributeValue.S(sk),
                    )
                })
            }
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
                // Blank email clears both the email attribute and the GSI keys,
                // which removes the user from the email-index. A non-blank
                // value sets all three so the user reappears in the index
                // under the new lowercased email.
                if (email.isEmpty()) {
                    updateExpression = "SET email = :e REMOVE GSI1PK, GSI1SK"
                    expressionAttributeValues = mapOf(":e" to AttributeValue.S(""))
                } else {
                    updateExpression = "SET email = :e, GSI1PK = :gsi1pk, GSI1SK = :gsi1sk"
                    expressionAttributeValues = mapOf(
                        ":e" to AttributeValue.S(email),
                        ":gsi1pk" to AttributeValue.S(DynamoDbSingleTableSchema.emailKey(email)),
                        ":gsi1sk" to AttributeValue.S(DynamoDbSingleTableSchema.userPK(userName)),
                    )
                }
            })
        }
    }

    // Election commands
    override fun addElection(authority: String, owner: String, electionName: String, description: String) {
        runBlocking {
            dynamoDb.putItem(PutItemRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                item = mapOf(
                    "PK" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName)),
                    "SK" to AttributeValue.S(DynamoDbSingleTableSchema.METADATA_SK),
                    "entity_type" to AttributeValue.S("ELECTION"),
                    "election_name" to AttributeValue.S(electionName),
                    "owner_name" to AttributeValue.S(owner),
                    "description" to AttributeValue.S(description),
                )
            })
        }
    }

    override fun setElectionDescription(authority: String, electionName: String, description: String) {
        runBlocking {
            dynamoDb.updateItem(UpdateItemRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                key = mapOf(
                    "PK" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName)),
                    "SK" to AttributeValue.S(DynamoDbSingleTableSchema.METADATA_SK),
                )
                updateExpression = "SET description = :description"
                expressionAttributeValues = mapOf(
                    ":description" to AttributeValue.S(description),
                )
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

            // Delete all related items (candidates, ballots)
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

    override fun setTiers(authority: String, electionName: String, tierNames: List<String>) {
        // Tiers ride as an attribute on the election's METADATA item rather
        // than as separate items: reading the election (frequent path) gets
        // tiers in the same request, matching the denormalized-for-perf shape
        // of this single-table store. UpdateItem replaces the whole list
        // atomically, which matches the "all-or-nothing while no ballots"
        // semantics enforced one layer up in ServiceImpl.
        runBlocking {
            if (tierNames.isEmpty()) {
                dynamoDb.updateItem(UpdateItemRequest {
                    tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                    key = mapOf(
                        "PK" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName)),
                        "SK" to AttributeValue.S(DynamoDbSingleTableSchema.METADATA_SK)
                    )
                    updateExpression = "REMOVE tiers"
                })
            } else {
                dynamoDb.updateItem(UpdateItemRequest {
                    tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                    key = mapOf(
                        "PK" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName)),
                        "SK" to AttributeValue.S(DynamoDbSingleTableSchema.METADATA_SK)
                    )
                    updateExpression = "SET tiers = :tiers"
                    expressionAttributeValues = mapOf(
                        ":tiers" to AttributeValue.L(tierNames.map { AttributeValue.S(it) })
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

            // Cascade: strip the removed candidate names from each ballot's
            // rankings JSON in this election. Without this, ballots would carry
            // ghost rankings for candidates that no longer exist on the election.
            val removed = candidateNames.toSet()
            val ballotItems = dynamoDb.query(QueryRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                keyConditionExpression = "PK = :pk AND begins_with(SK, :prefix)"
                expressionAttributeValues = mapOf(
                    ":pk" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName)),
                    ":prefix" to AttributeValue.S(DynamoDbSingleTableSchema.BALLOT_PREFIX),
                )
            }).items
            ballotItems?.forEach { item ->
                val sk = item["SK"]?.asS() ?: return@forEach
                val rankingsJson = item["rankings"]?.asS() ?: return@forEach
                val rankings = json.decodeFromString<List<Ranking>>(rankingsJson)
                val filtered = rankings.filter { it.candidateName !in removed }
                if (filtered.size != rankings.size) {
                    dynamoDb.updateItem(UpdateItemRequest {
                        tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                        key = mapOf(
                            "PK" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName)),
                            "SK" to AttributeValue.S(sk),
                        )
                        updateExpression = "SET rankings = :r"
                        expressionAttributeValues = mapOf(
                            ":r" to AttributeValue.S(json.encodeToString(filtered)),
                        )
                    })
                }
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

    override fun deleteBallot(authority: String, voterName: String, electionName: String) {
        runBlocking {
            dynamoDb.deleteItem(DeleteItemRequest {
                tableName = DynamoDbSingleTableSchema.MAIN_TABLE
                key = mapOf(
                    "PK" to AttributeValue.S(DynamoDbSingleTableSchema.electionPK(electionName)),
                    "SK" to AttributeValue.S(DynamoDbSingleTableSchema.ballotSK(voterName))
                )
            })
        }
    }
}
