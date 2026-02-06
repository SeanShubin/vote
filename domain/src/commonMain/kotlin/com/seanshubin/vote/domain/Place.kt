package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable

@Serializable
data class Place(val rank: Int, val candidateName: String) {
    override fun toString(): String = "$rank $candidateName"

    companion object {
        fun List<Place>.adjustForTies(): List<Place> {
            val grouped: List<List<Place>> = this.groupBy { it.rank }
                .toList()
                .sortedBy { (rank, _) -> rank }
                .map { (_, places) -> places }
            return grouped.flatMapIndexed { index, places ->
                val previousDuplicates = grouped.take(index).sumOf { it.size - 1 }
                places.map { it.copy(rank = index + previousDuplicates + 1) }
            }
        }
    }
}
