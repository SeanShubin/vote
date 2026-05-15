package com.seanshubin.vote.domain

import com.seanshubin.vote.domain.Ranking.Companion.prefers
import kotlinx.serialization.Serializable

@Serializable
data class Tally(
    val electionName: String,
    val candidateNames: List<String>,
    val secretBallot: Boolean,
    val ballots: List<Ballot>,
    val preferences: List<List<Preference>>,
    val contests: List<RankedPairs.Contest>,
    val places: List<Place>,
    val whoVoted: List<String>,
) {
    companion object {
        fun countBallots(
            electionName: String,
            secretBallot: Boolean,
            candidates: List<String>,
            tiers: List<String>,
            ballots: List<Ballot.Revealed>,
        ): Tally {
            // Project each cast ballot into its virtual form (candidates +
            // materialized tier markers in one strict order). Storage carries
            // only candidate rankings with a tier annotation; the markers
            // exist as `kind = TIER` entries here only — at compute time —
            // so a tier rename is just a label swap on the annotation and
            // never breaks the recorded ballots.
            val projectedBallots = ballots.map { ballot ->
                ballot.copy(rankings = projectBallot(ballot.rankings, tiers))
            }
            // Tier markers join real candidates as nodes in the pairwise
            // matrix — they're "virtual candidates" the algorithm uses to
            // place real candidates relative to each tier line.
            val allNodes = candidates + tiers
            // Drop nodes nobody ever ranked (real or virtual) — they'd just
            // contribute a noise row/column and a bottom-of-the-list place
            // entry. Tier markers are produced by the projection, so they
            // appear in every ballot the projection ran over; the filter
            // mostly fires on candidates voters skipped.
            val rankedNodes = allNodes.filter { name ->
                projectedBallots.any { ballot ->
                    ballot.rankings.any { it.candidateName == name && it.rank != null }
                }
            }
            val initialTally = BallotCounter(electionName, secretBallot, rankedNodes, ballots, projectedBallots).countBallots()
            val nodesSortedByPlaceThenAlpha = initialTally.places.map { it.candidateName }
            val sortedTallyToMakeItEasierToExplainResults =
                BallotCounter(electionName, secretBallot, nodesSortedByPlaceThenAlpha, ballots, projectedBallots).countBallots()
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
            val rawBallots: List<Ballot.Revealed>,
            val projectedBallots: List<Ballot.Revealed>,
        ) {
            fun countBallots(): Tally {
                val emptyPreferences = createEmptyPreferences()
                // Pairwise math runs on the *projected* rankings so tier
                // markers participate as virtual candidates. The Tally's
                // ballots field below still gets rawBallots — display sees
                // what voters cast, not the synthesized expansion.
                val preferences = projectedBallots.map { it.rankings }.fold(emptyPreferences, ::accumulateRankings)
                val rankedPairs = RankedPairs.run(candidates, preferences)
                val whoVoted = rawBallots.map { it.voterName }.sorted()
                // Ballots flow through unchanged — display sees the same data the
                // algorithm sees. No synthesis of "last place" entries for omitted
                // candidates: that would defeat the explicit-only pairwise rule
                // and confuse anyone re-running the tally on a subset.
                val ballots = if (secretBallot) {
                    rawBallots.map { it.makeSecret() }.sortedBy { it.confirmation }
                } else {
                    rawBallots.sortedBy { it.voterName }
                }.sortedWith(Ballot.Companion.BallotComparator)
                return Tally(
                    electionName,
                    candidates,
                    secretBallot,
                    ballots,
                    preferences,
                    rankedPairs.contests,
                    rankedPairs.places,
                    whoVoted,
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
