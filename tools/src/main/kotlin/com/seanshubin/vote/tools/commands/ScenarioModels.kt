package com.seanshubin.vote.tools.commands

import kotlinx.serialization.Serializable

@Serializable
internal data class ScenarioCandidate(
    val originalName: String,
    val displayName: String
)

@Serializable
internal data class ScenarioVoter(
    val originalName: String,
    val displayName: String,
    val email: String
)

@Serializable
internal data class ScenarioRanking(
    val candidateOriginalName: String,
    val candidateDisplayName: String,
    val rank: Int
)

@Serializable
internal data class ScenarioBallot(
    val voterOriginalName: String,
    val voterDisplayName: String,
    val confirmation: String,
    val rankings: List<ScenarioRanking>
)

@Serializable
internal data class Scenario(
    val scenarioNumber: String,
    val scenarioName: String,
    val displayName: String,
    val electionName: String,
    val ownerName: String,
    val candidates: List<ScenarioCandidate>,
    val voters: List<ScenarioVoter>,
    val ballots: List<ScenarioBallot>
)
