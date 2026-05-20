package com.seanshubin.vote.contract

import kotlinx.serialization.Serializable

/**
 * Owner-facing report of what is actually running versus what the deploy
 * pipeline last published.
 *
 * [backendGitHash] is the commit compiled into the running backend jar —
 * the irrefutable "what code is the Lambda executing". [deployManifest] is
 * the pipeline's last-published manifest, or null when it can't be read
 * (e.g. before the first deploy that writes one). When the two git hashes
 * disagree, the running backend is not the last thing the pipeline deployed.
 */
@Serializable
data class DeployedVersions(
    val backendGitHash: String,
    val deployManifest: DeployManifest?,
)
