package com.seanshubin.vote.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * One voter's free-form note attached to one candidate in one election.
 * One note per (election, candidate, voter); a voter may edit their own
 * note at will. Other voters can read but not modify.
 */
@Serializable
data class CandidateNote(
    val electionName: String,
    val candidateName: String,
    val voterName: String,
    val text: String,
    val lastUpdated: Instant,
) {
    companion object {
        /**
         * Cap on [text]. Lives here rather than in backend validation
         * because both sides need it: the server enforces it, and the
         * editor shows the voter where they stand against it. Two copies
         * of the number would drift, and the drift would surface as a
         * save that the UI said was fine.
         *
         * Generous on purpose — these are long-form reviews, and an
         * earlier 4000 turned away real ones. Well clear of the 400KB
         * DynamoDB item limit even at 4 bytes per character, so storage is
         * not the binding constraint; the cap exists to stop a runaway
         * paste, not to shape how much a voter may say.
         */
        const val MAX_TEXT_LENGTH = 10000
    }
}
