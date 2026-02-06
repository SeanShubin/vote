package com.seanshubin.vote.domain

import com.seanshubin.vote.domain.Preference.Companion.places
import com.seanshubin.vote.domain.Preference.Companion.strongestPaths
import com.seanshubin.vote.domain.Ranking.Companion.prefers
import com.seanshubin.vote.domain.RevealedBallot.Companion.effectiveRankings
import com.seanshubin.vote.domain.RevealedBallot.Companion.matchRankingsOrderToCandidates
import kotlinx.serialization.Serializable

@Serializable
data class Tally(
    val electionName: String,
    val candidateNames: List<String>,
    val secretBallot: Boolean,
    val ballots: List<Ballot>,
    val preferences: List<List<Preference>>,
    val strongestPathMatrix: List<List<Preference>>,
    val places: List<Place>,
    val whoVoted: List<String>
) {
    companion object {
        fun countBallots(
            electionName: String,
            secretBallot: Boolean,
            candidates: List<String>,
            ballots: List<RevealedBallot>
        ): Tally {
            val initialTally = BallotCounter(electionName, secretBallot, candidates, ballots).countBallots()
            val candidatesSortedByPlaceThenAlpha = initialTally.places.map { it.candidateName }
            val sortedTallyToMakeItEasierToExplainResults =
                BallotCounter(electionName, secretBallot, candidatesSortedByPlaceThenAlpha, ballots).countBallots()
            placesBetterNotHaveChangedOrAlgorithmIsBroken(
                initialTally.places,
                sortedTallyToMakeItEasierToExplainResults.places
            )
            return sortedTallyToMakeItEasierToExplainResults
        }

        private fun placesBetterNotHaveChangedOrAlgorithmIsBroken(first: List<Place>, second: List<Place>) {
            require(first == second) {
                "Changing the order of candidates affected the results, something is wrong with the algorithm\n$first\n$second"
            }
        }

        class BallotCounter(
            val electionName: String,
            val secretBallot: Boolean,
            val candidates: List<String>,
            val rawBallots: List<RevealedBallot>
        ) {
            fun countBallots(): Tally {
                val emptyPreferences = createEmptyPreferences()
                val preferences = rawBallots.map { it.rankings }.fold(emptyPreferences, ::accumulateRankings)
                val strongestPaths = preferences.strongestPaths()
                val places = strongestPaths.places(candidates)
                val whoVoted = rawBallots.map { it.voterName }.sorted()
                val rankSortedBallots =
                    rawBallots.effectiveRankings(candidates).matchRankingsOrderToCandidates(candidates)
                val ballots = if (secretBallot) {
                    rankSortedBallots.map { it.makeSecret() }.sortedBy { it.confirmation }
                } else {
                    rankSortedBallots.sortedBy { it.voterName }
                }.sortedWith(Ballot.Companion.BallotComparator)
                return Tally(
                    electionName,
                    candidates,
                    secretBallot,
                    ballots,
                    preferences,
                    strongestPaths,
                    places,
                    whoVoted
                )
            }

            fun createEmptyPreferences(): List<List<Preference>> =
                candidates.map { a ->
                    candidates.map { b ->
                        Preference(a, 0, b)
                    }
                }

            fun accumulateRankings(
                preferences: List<List<Preference>>,
                rankings: List<Ranking>
            ): List<List<Preference>> =
                preferences.indices.map { i ->
                    preferences.indices.map { j ->
                        val candidateA = candidates[i]
                        val candidateB = candidates[j]
                        val currentPreference = preferences[i][j]
                        if (rankings.prefers(candidateA, candidateB)) {
                            currentPreference.incrementStrength()
                        } else {
                            currentPreference
                        }
                    }
                }
        }
    }
}
