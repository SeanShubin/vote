package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable

@Serializable
data class VoterEligibility(val voterName: String, val eligible: Boolean)
