package com.seanshubin.vote.contract

import kotlinx.serialization.Serializable

/**
 * Request body for PUT /election/{name}/candidate/{cand}/note. Empty text
 * is interpreted by the service as "delete my note."
 */
@Serializable
data class SetCandidateNoteRequest(
    val text: String,
)
