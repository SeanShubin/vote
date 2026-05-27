package com.seanshubin.vote.tools.commands

import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.domain.Ranking
import com.seanshubin.vote.tools.lib.NarrativeEvent

/**
 * One-shot case normalization for the event log.
 *
 * Rewrites every event so all references to a user / election / candidate /
 * tier name use the *first-occurrence* display case. Subsequent events that
 * referenced the same entity with a different case ("alice" when the user
 * was registered as "Alice") are rewritten to the canonical form. Renames
 * update the canonical mapping going forward — anything cast after the
 * rename uses the new display case.
 *
 * Two failure modes:
 *   - **Hard collision** — two independent registrations of the same
 *     lowercase identity ([DomainEvent.UserRegisteredViaDiscord] twice
 *     with case-variant names, [DomainEvent.ElectionCreated] twice with
 *     case-variant names, a [DomainEvent.CandidateRenamed] whose new
 *     name lands on a *different* existing candidate). The new code
 *     wouldn't allow this, so the only way it lands in a backup is if
 *     the old (broken) code accepted it. Aborts with a report so the
 *     operator picks a winner manually.
 *   - **Within-event duplicate** — a single [DomainEvent.CandidatesAdded]
 *     or [DomainEvent.TiersSet] listing case-variants of the same name.
 *     Deduped silently (first-occurrence wins) since the new code already
 *     treats them as one.
 */
object NormalizeCaseTransform {

    sealed interface Result {
        data class Ok(
            val events: List<NarrativeEvent>,
            val rewrites: Int,
        ) : Result

        data class Collisions(
            val report: List<String>,
        ) : Result
    }

    fun transform(events: List<NarrativeEvent>): Result {
        val state = State()
        val transformed = events.mapIndexed { index, narrative ->
            narrative.copy(event = state.process(index + 1L, narrative.event))
        }
        return if (state.collisions.isEmpty()) {
            Result.Ok(transformed, state.rewrites)
        } else {
            Result.Collisions(state.collisions.toList())
        }
    }

    private class State {
        // Lowercase → first-seen display case. Updated when a rename event
        // moves the display case to a new value.
        val userCanonical = mutableMapOf<String, String>()
        val electionCanonical = mutableMapOf<String, String>()
        // Per-election, lowercase → display case for that election's
        // candidates and tiers.
        val candidates = mutableMapOf<String, MutableMap<String, String>>()
        val tiers = mutableMapOf<String, MutableMap<String, String>>()

        val collisions = mutableListOf<String>()
        var rewrites = 0

        fun process(eventId: Long, event: DomainEvent): DomainEvent = when (event) {
            is DomainEvent.UserRegisteredViaDiscord -> {
                event.copy(name = introduceUser(event.name, eventId, "UserRegisteredViaDiscord"))
            }
            is DomainEvent.UserRegistered -> {
                event.copy(name = introduceUser(event.name, eventId, "UserRegistered"))
            }
            is DomainEvent.UserRoleChanged -> {
                event.copy(userName = lookupUser(event.userName, eventId, "UserRoleChanged"))
            }
            is DomainEvent.OwnershipTransferred -> {
                event.copy(
                    fromUserName = lookupUser(event.fromUserName, eventId, "OwnershipTransferred.from"),
                    toUserName = lookupUser(event.toUserName, eventId, "OwnershipTransferred.to"),
                )
            }
            is DomainEvent.UserRemoved -> {
                val canonical = lookupUser(event.userName, eventId, "UserRemoved")
                // Drop the user from the canonical map so a later
                // re-registration of the same lowercase name registers
                // freshly under its own case.
                userCanonical.remove(canonical.lowercase())
                event.copy(userName = canonical)
            }
            is DomainEvent.UserNameChanged -> {
                val canonicalOld = lookupUser(event.oldUserName, eventId, "UserNameChanged.old")
                // The new name takes over the slot. Treat as rename of the
                // canonical entry; if newName's lowercase already exists as
                // a *different* user, that's a hard collision.
                val newLower = event.newUserName.lowercase()
                val oldLower = canonicalOld.lowercase()
                if (newLower != oldLower) {
                    val collide = userCanonical[newLower]
                    if (collide != null) {
                        collisions += "Event #$eventId UserNameChanged: renaming '$canonicalOld' → '${event.newUserName}' would collide with existing user '$collide'"
                    }
                    userCanonical.remove(oldLower)
                }
                userCanonical[newLower] = event.newUserName
                event.copy(oldUserName = canonicalOld, newUserName = event.newUserName)
            }
            is DomainEvent.DiscordCredentialLinked -> {
                event.copy(userName = lookupUser(event.userName, eventId, "DiscordCredentialLinked"))
            }
            is DomainEvent.DiscordDisplayNameChanged -> {
                event.copy(userName = lookupUser(event.userName, eventId, "DiscordDisplayNameChanged"))
            }
            is DomainEvent.ElectionCreated -> {
                val canonicalOwner = lookupUser(event.ownerName, eventId, "ElectionCreated.owner")
                val canonicalElection = introduceElection(event.electionName, eventId)
                event.copy(ownerName = canonicalOwner, electionName = canonicalElection)
            }
            is DomainEvent.ElectionDeleted -> {
                val canonical = lookupElection(event.electionName, eventId, "ElectionDeleted")
                electionCanonical.remove(canonical.lowercase())
                candidates.remove(canonical.lowercase())
                tiers.remove(canonical.lowercase())
                event.copy(electionName = canonical)
            }
            is DomainEvent.ElectionOwnerChanged -> {
                event.copy(
                    electionName = lookupElection(event.electionName, eventId, "ElectionOwnerChanged"),
                    newOwnerName = lookupUser(event.newOwnerName, eventId, "ElectionOwnerChanged.newOwner"),
                )
            }
            is DomainEvent.ElectionDescriptionChanged -> {
                event.copy(electionName = lookupElection(event.electionName, eventId, "ElectionDescriptionChanged"))
            }
            is DomainEvent.ElectionNameChanged -> {
                val canonicalOld = lookupElection(event.oldName, eventId, "ElectionNameChanged.old")
                // The new name takes over the slot. If newName's lowercase
                // already names a *different* election, that's a hard
                // collision the new code wouldn't allow.
                val newLower = event.newName.lowercase()
                val oldLower = canonicalOld.lowercase()
                if (newLower != oldLower) {
                    val collide = electionCanonical[newLower]
                    if (collide != null) {
                        collisions += "Event #$eventId ElectionNameChanged: renaming '$canonicalOld' → '${event.newName}' would collide with existing election '$collide'"
                    }
                    electionCanonical.remove(oldLower)
                    // Move the per-election candidate/tier tables to the new key.
                    candidates.remove(oldLower)?.let { candidates[newLower] = it }
                    tiers.remove(oldLower)?.let { tiers[newLower] = it }
                }
                electionCanonical[newLower] = event.newName
                event.copy(oldName = canonicalOld, newName = event.newName)
            }
            is DomainEvent.ElectionManagerAdded -> {
                event.copy(
                    electionName = lookupElection(event.electionName, eventId, "ElectionManagerAdded"),
                    userName = lookupUser(event.userName, eventId, "ElectionManagerAdded.user"),
                )
            }
            is DomainEvent.ElectionManagerRemoved -> {
                event.copy(
                    electionName = lookupElection(event.electionName, eventId, "ElectionManagerRemoved"),
                    userName = lookupUser(event.userName, eventId, "ElectionManagerRemoved.user"),
                )
            }
            is DomainEvent.CandidatesAdded -> {
                val canonicalElection = lookupElection(event.electionName, eventId, "CandidatesAdded")
                val table = candidates.getOrPut(canonicalElection.lowercase()) { mutableMapOf() }
                // Within-event dedupe + cross-event dedupe in one pass:
                // a name whose lowercase is already in the per-election
                // table is dropped (the existing canonical wins). New
                // names introduce their case as canonical.
                val keep = mutableListOf<String>()
                event.candidateNames.forEach { name ->
                    val lower = name.lowercase()
                    if (lower in table) {
                        rewrites++
                    } else {
                        table[lower] = name
                        keep += name
                    }
                }
                event.copy(electionName = canonicalElection, candidateNames = keep)
            }
            is DomainEvent.CandidatesRemoved -> {
                val canonicalElection = lookupElection(event.electionName, eventId, "CandidatesRemoved")
                val table = candidates.getOrPut(canonicalElection.lowercase()) { mutableMapOf() }
                val rewritten = event.candidateNames.map { name ->
                    val canonical = table.remove(name.lowercase()) ?: name
                    if (canonical != name) rewrites++
                    canonical
                }
                event.copy(electionName = canonicalElection, candidateNames = rewritten)
            }
            is DomainEvent.CandidateRenamed -> {
                val canonicalElection = lookupElection(event.electionName, eventId, "CandidateRenamed")
                val table = candidates.getOrPut(canonicalElection.lowercase()) { mutableMapOf() }
                val canonicalOld = table[event.oldName.lowercase()] ?: event.oldName
                if (canonicalOld != event.oldName) rewrites++
                val newLower = event.newName.lowercase()
                val oldLower = canonicalOld.lowercase()
                if (newLower != oldLower && newLower in table) {
                    collisions += "Event #$eventId CandidateRenamed in '$canonicalElection': renaming '$canonicalOld' → '${event.newName}' would collide with existing candidate '${table[newLower]}'"
                }
                table.remove(oldLower)
                table[newLower] = event.newName
                event.copy(electionName = canonicalElection, oldName = canonicalOld, newName = event.newName)
            }
            is DomainEvent.TiersSet -> {
                val canonicalElection = lookupElection(event.electionName, eventId, "TiersSet")
                // TiersSet replaces the whole list. Within-input dedupe:
                // ["Gold", "gold"] becomes ["Gold"]. The new list IS the
                // canonical mapping going forward.
                val table = mutableMapOf<String, String>()
                val keep = mutableListOf<String>()
                event.tierNames.forEach { name ->
                    val lower = name.lowercase()
                    if (lower in table) {
                        rewrites++
                    } else {
                        table[lower] = name
                        keep += name
                    }
                }
                tiers[canonicalElection.lowercase()] = table
                event.copy(electionName = canonicalElection, tierNames = keep)
            }
            is DomainEvent.TierRenamed -> {
                val canonicalElection = lookupElection(event.electionName, eventId, "TierRenamed")
                val table = tiers.getOrPut(canonicalElection.lowercase()) { mutableMapOf() }
                val canonicalOld = table[event.oldName.lowercase()] ?: event.oldName
                if (canonicalOld != event.oldName) rewrites++
                val newLower = event.newName.lowercase()
                val oldLower = canonicalOld.lowercase()
                if (newLower != oldLower && newLower in table) {
                    collisions += "Event #$eventId TierRenamed in '$canonicalElection': renaming '$canonicalOld' → '${event.newName}' would collide with existing tier '${table[newLower]}'"
                }
                table.remove(oldLower)
                table[newLower] = event.newName
                event.copy(electionName = canonicalElection, oldName = canonicalOld, newName = event.newName)
            }
            is DomainEvent.BallotCast -> {
                val canonicalElection = lookupElection(event.electionName, eventId, "BallotCast")
                val canonicalVoter = lookupUser(event.voterName, eventId, "BallotCast.voter")
                event.copy(
                    voterName = canonicalVoter,
                    electionName = canonicalElection,
                    rankings = canonicalizeRankings(event.rankings, canonicalElection),
                )
            }
            is DomainEvent.BallotTimestampUpdated -> event
            is DomainEvent.BallotRankingsChanged -> {
                val canonicalElection = lookupElection(event.electionName, eventId, "BallotRankingsChanged")
                event.copy(
                    electionName = canonicalElection,
                    newRankings = canonicalizeRankings(event.newRankings, canonicalElection),
                )
            }
            is DomainEvent.BallotDeleted -> {
                event.copy(
                    voterName = lookupUser(event.voterName, eventId, "BallotDeleted.voter"),
                    electionName = lookupElection(event.electionName, eventId, "BallotDeleted"),
                )
            }
            is DomainEvent.CandidateNoteSet -> {
                val canonicalElection = lookupElection(event.electionName, eventId, "CandidateNoteSet")
                val canonicalVoter = lookupUser(event.voterName, eventId, "CandidateNoteSet.voter")
                val candidateTable = candidates[canonicalElection.lowercase()] ?: emptyMap()
                val canonicalCandidate = candidateTable[event.candidateName.lowercase()] ?: event.candidateName
                if (canonicalCandidate != event.candidateName) rewrites++
                event.copy(
                    electionName = canonicalElection,
                    voterName = canonicalVoter,
                    candidateName = canonicalCandidate,
                )
            }
            is DomainEvent.CandidateNoteDeleted -> {
                val canonicalElection = lookupElection(event.electionName, eventId, "CandidateNoteDeleted")
                val canonicalVoter = lookupUser(event.voterName, eventId, "CandidateNoteDeleted.voter")
                val candidateTable = candidates[canonicalElection.lowercase()] ?: emptyMap()
                val canonicalCandidate = candidateTable[event.candidateName.lowercase()] ?: event.candidateName
                if (canonicalCandidate != event.candidateName) rewrites++
                event.copy(
                    electionName = canonicalElection,
                    voterName = canonicalVoter,
                    candidateName = canonicalCandidate,
                )
            }
        }

        private fun introduceUser(name: String, eventId: Long, where: String): String {
            val lower = name.lowercase()
            val existing = userCanonical[lower]
            return when {
                existing == null -> {
                    userCanonical[lower] = name
                    name
                }
                existing == name -> name
                else -> {
                    collisions += "Event #$eventId $where: user '$name' collides with existing user '$existing' (case-variant of same identity)"
                    name
                }
            }
        }

        private fun lookupUser(name: String, eventId: Long, where: String): String {
            val canonical = userCanonical[name.lowercase()]
            return when {
                canonical == null -> {
                    // Should not happen in a well-formed log — flag so the
                    // operator sees the inconsistency rather than have it
                    // silently propagate.
                    collisions += "Event #$eventId $where: references unknown user '$name'"
                    name
                }
                canonical == name -> name
                else -> {
                    rewrites++
                    canonical
                }
            }
        }

        private fun introduceElection(name: String, eventId: Long): String {
            val lower = name.lowercase()
            val existing = electionCanonical[lower]
            return when {
                existing == null -> {
                    electionCanonical[lower] = name
                    name
                }
                existing == name -> name
                else -> {
                    collisions += "Event #$eventId ElectionCreated: election '$name' collides with existing election '$existing'"
                    name
                }
            }
        }

        private fun lookupElection(name: String, eventId: Long, where: String): String {
            val canonical = electionCanonical[name.lowercase()]
            return when {
                canonical == null -> {
                    collisions += "Event #$eventId $where: references unknown election '$name'"
                    name
                }
                canonical == name -> name
                else -> {
                    rewrites++
                    canonical
                }
            }
        }

        private fun canonicalizeRankings(rankings: List<Ranking>, electionDisplay: String): List<Ranking> {
            val electionKey = electionDisplay.lowercase()
            val candidateTable = candidates[electionKey] ?: emptyMap()
            val tierTable = tiers[electionKey] ?: emptyMap()
            return rankings.map { ranking ->
                val canonicalCandidate = candidateTable[ranking.candidateName.lowercase()] ?: ranking.candidateName
                if (canonicalCandidate != ranking.candidateName) rewrites++
                val canonicalTier = ranking.tier?.let { tier ->
                    val canonical = tierTable[tier.lowercase()] ?: tier
                    if (canonical != tier) rewrites++
                    canonical
                }
                ranking.copy(candidateName = canonicalCandidate, tier = canonicalTier)
            }
        }
    }
}
