package com.seanshubin.vote.backend.service

import com.seanshubin.vote.contract.CommandModel
import com.seanshubin.vote.contract.EventLog
import com.seanshubin.vote.contract.QueryModel
import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.domain.Role

class EventApplier(
    private val eventLog: EventLog,
    private val commandModel: CommandModel,
    private val queryModel: QueryModel,
) {
    fun synchronize() {
        val lastSynced = queryModel.lastSynced() ?: 0
        val newEvents = eventLog.eventsToSync(lastSynced)
        newEvents.forEach { envelope ->
            apply(envelope.authority, envelope.event)
            commandModel.setLastSynced(envelope.eventId)
        }
    }

    fun apply(authority: String, event: DomainEvent) {
        when (event) {
            is DomainEvent.UserRoleChanged -> {
                commandModel.setRole(authority, event.userName, event.newRole)
            }
            is DomainEvent.OwnershipTransferred -> {
                // Demote the outgoing owner first so the "exactly one OWNER" invariant
                // holds at every projection sync point.
                commandModel.setRole(authority, event.fromUserName, Role.SECONDARY_ROLE)
                commandModel.setRole(authority, event.toUserName, Role.PRIMARY_ROLE)
            }
            is DomainEvent.UserRemoved -> {
                commandModel.removeUser(authority, event.userName)
            }
            is DomainEvent.UserNameChanged -> {
                commandModel.setUserName(authority, event.oldUserName, event.newUserName)
            }
            is DomainEvent.UserRegisteredViaDiscord -> {
                commandModel.createUserViaDiscord(
                    authority = authority,
                    userName = event.name,
                    discordId = event.discordId,
                    discordDisplayName = event.discordDisplayName,
                    role = event.role,
                )
            }
            is DomainEvent.UserRegistered -> {
                // No Discord credential — the dev-login bypass. Reuses the
                // user-creation projection write with the discord fields
                // left blank, which is exactly the user's state: registered,
                // no linked Discord identity.
                commandModel.createUserViaDiscord(
                    authority = authority,
                    userName = event.name,
                    discordId = "",
                    discordDisplayName = "",
                    role = event.role,
                )
            }
            is DomainEvent.DiscordCredentialLinked -> {
                commandModel.linkDiscordCredential(
                    authority = authority,
                    userName = event.userName,
                    discordId = event.discordId,
                    discordDisplayName = event.discordDisplayName,
                )
            }
            is DomainEvent.DiscordDisplayNameChanged -> {
                commandModel.setDiscordDisplayName(
                    authority = authority,
                    userName = event.userName,
                    discordDisplayName = event.newDiscordDisplayName,
                )
            }
            is DomainEvent.ElectionCreated -> {
                commandModel.addElection(authority, event.ownerName, event.electionName, event.description)
            }
            is DomainEvent.ElectionDeleted -> {
                commandModel.deleteElection(authority, event.electionName)
            }
            is DomainEvent.ElectionDescriptionChanged -> {
                commandModel.setElectionDescription(authority, event.electionName, event.newDescription)
            }
            is DomainEvent.ElectionOwnerChanged -> {
                commandModel.setElectionOwner(authority, event.electionName, event.newOwnerName)
            }
            is DomainEvent.CandidatesAdded -> {
                commandModel.addCandidates(authority, event.electionName, event.candidateNames)
            }
            is DomainEvent.CandidatesRemoved -> {
                commandModel.removeCandidates(authority, event.electionName, event.candidateNames)
            }
            is DomainEvent.CandidateRenamed -> {
                commandModel.renameCandidate(authority, event.electionName, event.oldName, event.newName)
            }
            is DomainEvent.TiersSet -> {
                commandModel.setTiers(authority, event.electionName, event.tierNames)
            }
            is DomainEvent.TierRenamed -> {
                commandModel.renameTier(authority, event.electionName, event.oldName, event.newName)
            }
            is DomainEvent.BallotCast -> {
                commandModel.castBallot(
                    authority = authority,
                    voterName = event.voterName,
                    electionName = event.electionName,
                    rankings = event.rankings,
                    confirmation = event.confirmation,
                    now = event.whenCast
                )
            }
            is DomainEvent.BallotTimestampUpdated -> {
                commandModel.updateWhenCast(authority, event.confirmation, event.newWhenCast)
            }
            is DomainEvent.BallotRankingsChanged -> {
                commandModel.setRankings(authority, event.confirmation, event.electionName, event.newRankings)
            }
            is DomainEvent.BallotDeleted -> {
                commandModel.deleteBallot(authority, event.voterName, event.electionName)
            }
        }
    }
}
