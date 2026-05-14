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
    @SerialName("UserRoleChanged")
    data class UserRoleChanged(
        val userName: String,
        val newRole: Role
    ) : DomainEvent

    /**
     * Atomic ownership handoff: [fromUserName] (current OWNER) is demoted to AUDITOR
     * and [toUserName] is promoted to OWNER. Emitted instead of two UserRoleChanged
     * events so the projection — and any future audit consumer — sees the transfer
     * as a single semantic operation.
     */
    @Serializable
    @SerialName("OwnershipTransferred")
    data class OwnershipTransferred(
        val fromUserName: String,
        val toUserName: String,
    ) : DomainEvent

    @Serializable
    @SerialName("UserRemoved")
    data class UserRemoved(
        val userName: String
    ) : DomainEvent

    @Serializable
    @SerialName("UserNameChanged")
    data class UserNameChanged(
        val oldUserName: String,
        val newUserName: String
    ) : DomainEvent

    /**
     * First-time Discord login: a Discord-authenticated principal that
     * doesn't yet match any existing user. Lands in [Role.NO_ACCESS] —
     * an admin then grants a role outright.
     *
     * [name] is derived from the Discord display name, made unique against
     * existing users by the service.
     */
    @Serializable
    @SerialName("UserRegisteredViaDiscord")
    data class UserRegisteredViaDiscord(
        val name: String,
        val discordId: String,
        val discordDisplayName: String,
        val role: Role,
    ) : DomainEvent

    /**
     * Attaches a Discord credential to an existing user. Emitted when a
     * Discord-authenticated user opts to add Discord to an already-
     * authenticated session (not exposed today, but the event shape
     * supports it).
     */
    @Serializable
    @SerialName("DiscordCredentialLinked")
    data class DiscordCredentialLinked(
        val userName: String,
        val discordId: String,
        val discordDisplayName: String,
    ) : DomainEvent

    /**
     * Election Management Events
     */
    @Serializable
    @SerialName("ElectionCreated")
    data class ElectionCreated(
        val ownerName: String,
        val electionName: String,
        // Default to "" so existing event-log entries (which never had this field)
        // still deserialize cleanly. New events always include it.
        val description: String = ""
    ) : DomainEvent

    @Serializable
    @SerialName("ElectionDeleted")
    data class ElectionDeleted(
        val electionName: String
    ) : DomainEvent

    /**
     * Election ownership handoff. Distinct from [OwnershipTransferred], which
     * moves the global OWNER role between users — this just changes who owns
     * a single election. The new owner gains the same election-edit/delete
     * authority the previous owner had; nothing else about either user changes.
     */
    @Serializable
    @SerialName("ElectionOwnerChanged")
    data class ElectionOwnerChanged(
        val electionName: String,
        val newOwnerName: String,
    ) : DomainEvent

    /**
     * Description edits after creation. The owner can change the description
     * freely — unlike tier names, it isn't part of the meaning of any cast
     * ballot, so there's no "no ballots exist" lock here.
     */
    @Serializable
    @SerialName("ElectionDescriptionChanged")
    data class ElectionDescriptionChanged(
        val electionName: String,
        val newDescription: String,
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
     * Rename a candidate in place. The new name takes over wherever the old
     * name appeared — the candidate row itself plus every ranking in every
     * cast ballot for this election. Ballots' [Ranking.rank] values are
     * preserved; only the [Ranking.candidateName] is rewritten.
     */
    @Serializable
    @SerialName("CandidateRenamed")
    data class CandidateRenamed(
        val electionName: String,
        val oldName: String,
        val newName: String,
    ) : DomainEvent

    /**
     * Tier list management. Tier names are the thresholds candidates
     * cleared in a voter's ranking; they're projected into every ballot
     * as virtual candidates at tally time. Empty list disables tier
     * voting; non-empty enables it.
     *
     * Renames are intentionally **not** done via this event — that's
     * [TierRenamed]. TiersSet handles add/remove (and reorder, if the
     * resulting list is otherwise unchanged): when a tier is removed
     * here, the projection at compute time treats rankings still
     * annotated with that tier as `tier = null` (cleared no tier).
     */
    @Serializable
    @SerialName("TiersSet")
    data class TiersSet(
        val electionName: String,
        val tierNames: List<String>
    ) : DomainEvent

    /**
     * Rename a tier in place. The new name takes over wherever the old
     * one appeared — the tier row itself plus every [Ranking.tier]
     * annotation on every cast ballot for this election. Since the
     * voter's intent is encoded as "this candidate cleared the
     * second-prestige tier" via the label, swapping the label preserves
     * the intent — Schulze sees the same virtual ballot before and after.
     */
    @Serializable
    @SerialName("TierRenamed")
    data class TierRenamed(
        val electionName: String,
        val oldName: String,
        val newName: String,
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

    @Serializable
    @SerialName("BallotDeleted")
    data class BallotDeleted(
        val voterName: String,
        val electionName: String
    ) : DomainEvent
}
