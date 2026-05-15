package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable

/**
 * One cell of the pairwise preference matrix: a directed edge
 * [origin] → [destination] carrying the number of ballots that ranked
 * [origin] above [destination]. Single-hop only.
 *
 * Earlier versions of this class also carried multi-hop paths and
 * strength chains so Schulze's strongest-path closure could be expressed
 * as a list of [Preference] objects. Tideman's Ranked Pairs doesn't need
 * any of that — it operates on the direct matrix and locks individual
 * edges into a DAG — so the type collapsed back to a simple direct edge.
 */
@Serializable
data class Preference(
    val origin: String,
    val strength: Int,
    val destination: String,
) {
    fun incrementStrength(): Preference = copy(strength = strength + 1)

    override fun toString(): String = "$origin-($strength)-$destination"

    companion object {
        fun createPreferenceMatrix(
            candidates: List<String>,
            strengthMatrix: List<List<Int>>,
        ): List<List<Preference>> = candidates.indices.map { i ->
            candidates.indices.map { j ->
                Preference(candidates[i], strengthMatrix[i][j], candidates[j])
            }
        }
    }
}
