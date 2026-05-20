package com.seanshubin.vote.contract

import kotlinx.serialization.Serializable

/**
 * The `deployed-version.json` the deploy pipeline writes to the frontend
 * bucket after every deploy. The backend reads it back over HTTPS so an
 * operator can see what the pipeline last published.
 */
@Serializable
data class DeployManifest(
    val gitHash: String,
    val gitRef: String,
    val runNumber: String,
    val deployedAt: String,
)
