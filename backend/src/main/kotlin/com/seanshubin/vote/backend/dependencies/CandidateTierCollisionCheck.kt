package com.seanshubin.vote.backend.dependencies

import com.seanshubin.vote.contract.QueryModel

/**
 * Block startup when any election in the event log has a candidate name
 * that collides (case-insensitively) with one of its tier names. The
 * detail pages classify each name as candidate-or-tier by membership
 * lookup, so an ambiguous name would render incorrectly. Old data
 * predating the cross-list validation could carry such a collision —
 * an admin must rename one of the colliding entries before the app will
 * come back up.
 */
class CandidateTierCollisionCheck(
    private val queryModel: QueryModel,
) {
    fun verify() {
        val offenders = queryModel.listElections().mapNotNull { summary ->
            val candidates = queryModel.listCandidates(summary.electionName)
            val tiers = queryModel.listTiers(summary.electionName)
            val candidateKeys = candidates.map { it.lowercase() }.toSet()
            val tierKeys = tiers.map { it.lowercase() }.toSet()
            val collisions = candidateKeys.intersect(tierKeys)
            if (collisions.isEmpty()) null
            else summary.electionName to collisions.toList().sorted()
        }
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (electionName, keys) ->
                "  - $electionName: ${keys.joinToString()}"
            }
            error(
                "Refusing to start: ${offenders.size} election(s) have names that " +
                    "appear as both a candidate and a tier (case-insensitive). " +
                    "Rename one side of each collision and restart.\n$report"
            )
        }
    }
}
