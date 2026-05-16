package com.seanshubin.vote.domain

import com.seanshubin.vote.domain.Ranking.Companion.prefers
import kotlinx.serialization.Serializable

/**
 * One side's tally of an election. Each side (PUBLIC and SECRET) is
 * counted independently — neither side's rankings influence the other's
 * preferences, contests, or places.
 *
 * Ballots in [ballots] always carry the side's rankings only; the other
 * side's rankings have been filtered out before tally. A ballot whose
 * voter cast nothing on [side] does not appear here at all — empty-side
 * ballots are excluded so the [whoVoted] list and ballot counts reflect
 * who actually participated on this side.
 *
 * Whether [ballots] contains [Ballot.Identified] or [Ballot.Anonymous] is
 * determined by the API layer, not by this type. Domain tally always
 * emits identified ballots; the service redacts secret-side ballots
 * before sending them to callers without VIEW_SECRETS.
 */
@Serializable
data class Tally(
    val electionName: String,
    val candidateNames: List<String>,
    val side: RankingSide,
    val ballots: List<Ballot>,
    val preferences: List<List<Preference>>,
    val contests: List<RankedPairs.Contest>,
    val places: List<Place>,
    val whoVoted: List<String>,
) {
    companion object {
        fun countBallots(
            electionName: String,
            side: RankingSide,
            candidates: List<String>,
            tiers: List<String>,
            ballots: List<Ballot.Identified>,
        ): Tally {
            // Restrict every ballot to its rankings on the requested side.
            // Implicit-mirror rule on the SECRET side: a voter who has no
            // explicit SECRET-tagged rankings is treated as having cast
            // SECRET = PUBLIC. This makes the data model match the user's
            // mental model — "secret matches public unless explicitly set"
            // — and means turning the SECRET_BALLOT flag on doesn't leave
            // the secret tally empty until voters re-engage. The PUBLIC
            // side has no such fallback: voters who cast only SECRET are
            // genuinely absent from the public tally.
            val sideBallots = ballots
                .map { ballot ->
                    val explicit = ballot.rankings.filter { it.side == side }
                    val effective = if (explicit.isNotEmpty() || side != RankingSide.SECRET) {
                        explicit
                    } else {
                        ballot.rankings
                            .filter { it.side == RankingSide.PUBLIC }
                            .map { it.copy(side = RankingSide.SECRET) }
                    }
                    ballot.copy(rankings = effective)
                }
                .filter { ballot -> ballot.rankings.any { it.rank != null } }
            // Project each cast ballot into its virtual form (candidates +
            // materialized tier markers in one strict order). Storage carries
            // only candidate rankings with a tier annotation; the markers
            // exist as `kind = TIER` entries here only — at compute time —
            // so a tier rename is just a label swap on the annotation and
            // never breaks the recorded ballots.
            val projectedBallots = sideBallots.map { ballot ->
                ballot.copy(rankings = projectBallot(ballot.rankings, tiers, side))
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
            val initialTally = BallotCounter(electionName, side, rankedNodes, sideBallots, projectedBallots).countBallots()
            val nodesSortedByPlaceThenAlpha = initialTally.places.map { it.candidateName }
            val sortedTallyToMakeItEasierToExplainResults =
                BallotCounter(electionName, side, nodesSortedByPlaceThenAlpha, sideBallots, projectedBallots).countBallots()
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
            val side: RankingSide,
            val candidates: List<String>,
            val rawBallots: List<Ballot.Identified>,
            val projectedBallots: List<Ballot.Identified>,
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
                //
                // Domain emits Identified ballots regardless of side; the API
                // layer redacts to Anonymous for secret-side callers who lack
                // VIEW_SECRETS. Sort by confirmation on the secret side so the
                // ballot order is stable and not voter-name-derived (which an
                // attacker could otherwise correlate with the public-side
                // tally's ordering).
                val ballots = if (side == RankingSide.SECRET) {
                    rawBallots.sortedBy { it.confirmation }
                } else {
                    rawBallots.sortedBy { it.voterName }
                }.sortedWith(Ballot.Companion.BallotComparator)
                return Tally(
                    electionName,
                    candidates,
                    side,
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
