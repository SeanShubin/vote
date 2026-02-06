package com.seanshubin.vote.domain

import com.seanshubin.vote.domain.Place.Companion.adjustForTies
import kotlinx.serialization.Serializable

@Serializable
class Preference private constructor(val path: List<String>, val strengths: List<Int>) {
    constructor(origin: String, strength: Int, destination: String) : this(
        listOf(origin, destination),
        listOf(strength)
    )

    val origin: String get() = path[0]
    val strength: Int =
        strengths.minOrNull() ?: throw RuntimeException("empty list of strengths should not be possible")
    val destination: String get() = path.last()

    operator fun plus(that: Preference): Preference {
        return if (this.path == that.path) {
            addStrengths(that)
        } else if (this.destination == that.origin) {
            append(that)
        } else {
            throw IllegalArgumentException("unable to append or add strengths")
        }
    }

    private fun addStrengths(that: Preference): Preference {
        val newStrengths = this.strengths.zip(that.strengths).map { (a, b) -> a + b }
        return Preference(path, newStrengths)
    }

    fun incrementStrength(): Preference {
        require(strengths.size == 1) {
            "Size of strengths must be exactly 1"
        }
        require(path.size == 2) {
            "Size of path must be exactly 2"
        }
        return Preference(origin, strength + 1, destination)
    }

    private fun append(that: Preference): Preference {
        val newPath = this.path + that.path.drop(1)
        val newStrengths = this.strengths + that.strengths
        return Preference(newPath, newStrengths)
    }

    override fun toString(): String {
        val strengthStrings = strengths.map { "-($it)-" }
        val parts: List<String> = listOf(path[0]) + strengthStrings.zip(path.drop(1)).flatMap { it.toList() }
        return parts.joinToString("")
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as Preference

        if (path != other.path) return false
        if (strengths != other.strengths) return false

        return true
    }

    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + strengths.hashCode()
        return result
    }

    companion object {
        fun createPreferenceMatrix(candidates: List<String>, strengthMatrix: List<List<Int>>): List<List<Preference>> {
            return candidates.indices.map { i ->
                candidates.indices.map { j ->
                    Preference(candidates[i], strengthMatrix[i][j], candidates[j])
                }
            }
        }

        fun List<List<Preference>>.strongestPaths(): List<List<Preference>> {
            fun operation(current: List<List<Preference>>, k: Int): List<List<Preference>> {
                return current.indices.map { i ->
                    current[i].indices.map { j ->
                        val old = current[i][j]
                        if (i == j || i == k || j == k) old
                        else {
                            val left = current[i][k]
                            val right = current[k][j]
                            val new = left + right
                            if (new.strength > old.strength) new
                            else old
                        }
                    }
                }
            }
            return this.indices.fold(this, ::operation)
        }

        fun List<List<Preference>>.isUndefeated(target: Int, alreadyChecked: List<Int>): Boolean {
            val isCompetitor = { x: Int -> target != x && !alreadyChecked.contains(x) }
            return !this.indices.filter(isCompetitor).any { i ->
                this[i][target].strength > this[target][i].strength
            }
        }

        fun List<List<Preference>>.groupByPlace(remaining: List<Int>, soFar: List<List<Int>>): List<List<Int>> =
            if (remaining.isEmpty()) {
                soFar
            } else {
                val alreadyChecked = soFar.flatten()
                val (undefeated, newRemaining) = remaining.partition { isUndefeated(it, alreadyChecked) }
                val newSoFar = soFar + listOf(undefeated)
                groupByPlace(newRemaining, newSoFar)
            }

        fun List<List<Preference>>.places(candidates: List<String>): List<Place> {
            val groupedByPlace = this.groupByPlace(this.indices.toList(), emptyList())
            val sizes = groupedByPlace.map { it.size }
            val placeList = groupedByPlace.indices.flatMap { placeIndex ->
                groupedByPlace[placeIndex].map { index ->
                    val rank = sizes.take(placeIndex).sum() + 1
                    Place(rank, candidates[index])
                }.sortedBy { it.candidateName }
            }
            return placeList.adjustForTies()
        }
    }
}
