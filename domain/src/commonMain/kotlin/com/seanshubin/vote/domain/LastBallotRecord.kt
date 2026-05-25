package com.seanshubin.vote.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Snapshot of a previously-cast ballot for the calling voter on one
 * election, sourced from the event log rather than the projection. Returned
 * by `Service.getMyLastBallotRankings` so the Vote tab can offer a
 * "Restore last ballot" affordance when the current ballot is empty.
 *
 * Reads the event log directly because the projection forgets a deleted
 * ballot — the BallotDeleted event physically removes the row. The event
 * log is the only place the last cast rankings still exist after a delete.
 */
@Serializable
data class LastBallotRecord(
    val rankings: List<Ranking>,
    val whenCast: Instant,
)
