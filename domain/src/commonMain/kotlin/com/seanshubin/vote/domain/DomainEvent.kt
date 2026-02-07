package com.seanshubin.vote.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sealed interface for all domain events.
 * Events are the behavioral specification - they define what happened in the system.
 * All mutations are expressed as events and stored in the event log.
 */
@Serializable
sealed interface DomainEvent {
    /**
     * User Management Events
     */
    @Serializable
    @SerialName("UserRegistered")
    data class UserRegistered(
        val name: String,
        val email: String,
        val salt: String,
        val hash: String,
        val role: Role
    ) : DomainEvent

    @Serializable
    @SerialName("UserRoleChanged")
    data class UserRoleChanged(
        val userName: String,
        val newRole: Role
    ) : DomainEvent

    @Serializable
    @SerialName("UserRemoved")
    data class UserRemoved(
        val userName: String
    ) : DomainEvent

    @Serializable
    @SerialName("UserPasswordChanged")
    data class UserPasswordChanged(
        val userName: String,
        val newSalt: String,
        val newHash: String
    ) : DomainEvent

    @Serializable
    @SerialName("UserNameChanged")
    data class UserNameChanged(
        val oldUserName: String,
        val newUserName: String
    ) : DomainEvent

    @Serializable
    @SerialName("UserEmailChanged")
    data class UserEmailChanged(
        val userName: String,
        val newEmail: String
    ) : DomainEvent

    /**
     * Election Management Events
     */
    @Serializable
    @SerialName("ElectionCreated")
    data class ElectionCreated(
        val ownerName: String,
        val electionName: String
    ) : DomainEvent

    @Serializable
    @SerialName("ElectionUpdated")
    data class ElectionUpdated(
        val electionName: String,
        val secretBallot: Boolean? = null,
        val noVotingBefore: Instant? = null,
        val noVotingAfter: Instant? = null,
        val allowEdit: Boolean? = null,
        val allowVote: Boolean? = null
    ) : DomainEvent

    @Serializable
    @SerialName("ElectionDeleted")
    data class ElectionDeleted(
        val electionName: String
    ) : DomainEvent

    /**
     * Candidate Management Events
     */
    @Serializable
    @SerialName("CandidatesAdded")
    data class CandidatesAdded(
        val electionName: String,
        val candidateNames: List<String>
    ) : DomainEvent

    @Serializable
    @SerialName("CandidatesRemoved")
    data class CandidatesRemoved(
        val electionName: String,
        val candidateNames: List<String>
    ) : DomainEvent

    /**
     * Voter Eligibility Events
     */
    @Serializable
    @SerialName("VotersAdded")
    data class VotersAdded(
        val electionName: String,
        val voterNames: List<String>
    ) : DomainEvent

    @Serializable
    @SerialName("VotersRemoved")
    data class VotersRemoved(
        val electionName: String,
        val voterNames: List<String>
    ) : DomainEvent

    /**
     * Ballot Events
     */
    @Serializable
    @SerialName("BallotCast")
    data class BallotCast(
        val voterName: String,
        val electionName: String,
        val rankings: List<Ranking>,
        val confirmation: String,
        val whenCast: Instant
    ) : DomainEvent

    @Serializable
    @SerialName("BallotTimestampUpdated")
    data class BallotTimestampUpdated(
        val confirmation: String,
        val newWhenCast: Instant
    ) : DomainEvent

    @Serializable
    @SerialName("BallotRankingsChanged")
    data class BallotRankingsChanged(
        val confirmation: String,
        val electionName: String,
        val newRankings: List<Ranking>
    ) : DomainEvent
}
