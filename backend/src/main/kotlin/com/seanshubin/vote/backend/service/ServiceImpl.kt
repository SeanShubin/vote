package com.seanshubin.vote.backend.service

import com.seanshubin.vote.backend.auth.DiscordConfigProvider
import com.seanshubin.vote.backend.auth.DiscordOAuthClient
import com.seanshubin.vote.backend.auth.TokenEncoder
import com.seanshubin.vote.backend.validation.Validation
import com.seanshubin.vote.contract.*
import com.seanshubin.vote.domain.*
import java.security.SecureRandom

class ServiceImpl(
    private val integrations: Integrations,
    private val eventLog: EventLog,
    private val commandModel: CommandModel,
    private val queryModel: QueryModel,
    private val rawTableScanner: RawTableScanner,
    private val tokenEncoder: TokenEncoder,
    private val discordConfigProvider: DiscordConfigProvider = DiscordConfigProvider { null },
    private val discordOAuthClient: DiscordOAuthClient = DiscordOAuthClient(),
) : Service {
    private val secureRandom = SecureRandom()
    private val clock = integrations.clock
    private val uniqueIdGenerator = integrations.uniqueIdGenerator
    private val relationalProjection = DynamoToRelational(queryModel, eventLog)
    private val eventApplier = EventApplier(eventLog, commandModel, queryModel)

    override fun synchronize() = eventApplier.synchronize()

    override fun health(): String {
        return try {
            queryModel.userCount()
            "ok"
        } catch (e: Exception) {
            e.message ?: "error"
        }
    }

    override fun refresh(refreshToken: RefreshToken): Tokens {
        val user = requireSessionUser(refreshToken.userName)
        val accessToken = AccessToken(user.name, user.role)
        return Tokens(accessToken, refreshToken)
    }

    override fun authenticateWithToken(accessToken: AccessToken): Tokens {
        val user = requireSessionUser(accessToken.userName)
        val newAccessToken = AccessToken(user.name, user.role)
        val refreshToken = RefreshToken(user.name)
        return Tokens(newAccessToken, refreshToken)
    }

    override fun permissionsForRole(role: Role): List<Permission> {
        // Pure lookup of the static role→permissions table — no user data.
        // The mapping is in the source code, so it isn't sensitive. No auth.
        return queryModel.listPermissions(role)
    }

    override fun setRole(accessToken: AccessToken, userName: String, role: Role) {
        val targetUser = queryModel.searchUserByName(userName)
            ?: throw ServiceException(ServiceException.Category.NOT_FOUND, "User not found: $userName")

        val callerPermissions = UserRolePermissions(
            userName = accessToken.userName,
            role = accessToken.role,
            permissions = queryModel.listPermissions(accessToken.role),
        )
        val target = UserRole(targetUser.name, targetUser.role)
        when (val result = callerPermissions.canChangeRole(target, role)) {
            is RoleChangeResult.Denied -> throw ServiceException(
                ServiceException.Category.UNAUTHORIZED,
                result.reason,
            )
            is RoleChangeResult.Ok -> Unit
        }

        // The predicate already accepted this transition. Promotion to OWNER is
        // an ownership handoff: emit a single OwnershipTransferred event so the
        // event log records the atomic intent rather than two unrelated role changes.
        val event = if (role == Role.PRIMARY_ROLE) {
            DomainEvent.OwnershipTransferred(
                fromUserName = accessToken.userName,
                toUserName = targetUser.name,
            )
        } else {
            DomainEvent.UserRoleChanged(targetUser.name, role)
        }
        eventLog.appendEvent(accessToken.userName, clock.now(), event)
        synchronize()
    }

    override fun removeUser(accessToken: AccessToken, userName: String) {
        val targetUser = queryModel.searchUserByName(userName)
            ?: throw ServiceException(ServiceException.Category.NOT_FOUND, "User not found: $userName")

        if (isSelf(accessToken, targetUser.name)) {
            // Self-delete — anyone can leave the system, with two integrity rules:
            // 1. You must delete your own elections first, so removing you can't
            //    leave orphan elections pointing at a non-existent owner.
            // 2. The OWNER additionally must be alone (no other users) and the
            //    system must be empty of elections — exactly one OWNER must exist
            //    while the system is non-empty. Transfer ownership first, or wipe
            //    everything else, before the lone OWNER can leave back to empty.
            if (targetUser.role == Role.OWNER) {
                val otherUsers = queryModel.userCount() > 1
                val anyElections = queryModel.electionCount() > 0
                if (otherUsers || anyElections) {
                    throw ServiceException(
                        ServiceException.Category.UNSUPPORTED,
                        "OWNER cannot self-delete while other users or elections exist — transfer ownership first, or delete all other users and elections",
                    )
                }
            } else {
                val ownedElections = queryModel.electionsOwnedCount(targetUser.name)
                if (ownedElections > 0) {
                    throw ServiceException(
                        ServiceException.Category.UNSUPPORTED,
                        "Cannot self-delete while you own elections ($ownedElections) — delete your elections first",
                    )
                }
            }
        } else {
            // Deleting another user requires MANAGE_USERS and a strictly higher role
            // than the target (so e.g. an ADMIN can't remove the OWNER).
            requirePermission(accessToken, Permission.MANAGE_USERS)
            requireGreaterRole(accessToken, targetUser)
        }

        eventLog.appendEvent(
            accessToken.userName,
            clock.now(),
            DomainEvent.UserRemoved(targetUser.name)
        )
        synchronize()
    }

    override fun listUserNames(accessToken: AccessToken): List<String> {
        requirePermission(accessToken, Permission.USE_APPLICATION)
        return queryModel.listUsers().map { it.name }.sortedBy { it.lowercase() }
    }

    override fun listUsers(accessToken: AccessToken): List<UserNameRole> {
        requirePermission(accessToken, Permission.MANAGE_USERS)
        val callerPermissions = UserRolePermissions(
            userName = accessToken.userName,
            role = accessToken.role,
            permissions = queryModel.listPermissions(accessToken.role),
        )
        return queryModel.listUsers().map { user ->
            val target = UserRole(user.name, user.role)
            UserNameRole(
                userName = user.name,
                role = user.role,
                allowedRoles = callerPermissions.listedRolesFor(target),
                discordId = user.discordId,
                discordDisplayName = user.discordDisplayName,
            )
        }
    }

    override fun addElection(accessToken: AccessToken, userName: String, electionName: String, description: String) {
        // VALIDATION SECTION
        requirePermission(accessToken, Permission.USE_APPLICATION)

        val validUserName = Validation.validateUserName(userName)
        val validElectionName = Validation.validateElectionName(electionName)
        val validDescription = Validation.validateElectionDescription(description)

        // Ensure user exists; resolve to the canonical-case stored name so the
        // owner_name we record matches whatever case the user is stored under,
        // even if the caller passed a different case.
        val owner = queryModel.searchUserByName(validUserName)
            ?: throw ServiceException(ServiceException.Category.NOT_FOUND, "User not found: $validUserName")

        // Ensure election name is unique
        require(queryModel.searchElectionByName(validElectionName) == null) {
            "Election already exists: $validElectionName"
        }

        // EXECUTION SECTION
        eventLog.appendEvent(
            accessToken.userName,
            clock.now(),
            DomainEvent.ElectionCreated(owner.name, validElectionName, validDescription)
        )
        synchronize()
    }

    override fun setElectionDescription(accessToken: AccessToken, electionName: String, description: String) {
        // VALIDATION SECTION
        requirePermission(accessToken, Permission.USE_APPLICATION)
        requireIsElectionOwner(accessToken, electionName)

        val validElectionName = Validation.validateElectionName(electionName)
        val validDescription = Validation.validateElectionDescription(description)

        // EXECUTION SECTION
        eventLog.appendEvent(
            accessToken.userName,
            clock.now(),
            DomainEvent.ElectionDescriptionChanged(validElectionName, validDescription),
        )
        synchronize()
    }

    override fun updateUser(accessToken: AccessToken, userName: String, userUpdates: UserUpdates) {
        // VALIDATION SECTION
        requirePermission(accessToken, Permission.USE_APPLICATION)
        val targetUser = queryModel.searchUserByName(userName)
            ?: throw ServiceException(ServiceException.Category.NOT_FOUND, "User not found: $userName")
        if (!isSelf(accessToken, targetUser.name)) {
            // Editing another user is a moderation action — needs MANAGE_USERS
            // and a strictly higher role than the target.
            requirePermission(accessToken, Permission.MANAGE_USERS)
            requireGreaterRole(accessToken, targetUser)
        }

        val validNewUserName = userUpdates.userName?.let { Validation.validateUserName(it) }

        // Conflict check: a hit on a *different* user is a conflict; a hit on
        // the same user (e.g. case-only rename "Alice" → "alice") is allowed.
        validNewUserName?.let { newName ->
            val conflict = queryModel.searchUserByName(newName)
            require(conflict == null || conflict.name == targetUser.name) {
                "User name already exists: $newName"
            }
        }

        // EXECUTION SECTION
        validNewUserName?.let {
            eventLog.appendEvent(
                accessToken.userName,
                clock.now(),
                DomainEvent.UserNameChanged(targetUser.name, it)
            )
        }
        synchronize()
    }

    override fun getUser(accessToken: AccessToken, userName: String): UserNameEmail {
        // Allowed for self (you can always look up your own profile) or for
        // ADMIN+ moderators. Anyone else is rejected.
        if (!isSelf(accessToken, userName)) {
            requirePermission(accessToken, Permission.MANAGE_USERS)
        }
        val user = queryModel.findUserByName(userName)
        return with(UserNameEmail.Companion) { user.toUserNameEmail() }
    }

    override fun getElection(accessToken: AccessToken, electionName: String): ElectionDetail {
        requirePermission(accessToken, Permission.VIEW_APPLICATION)
        val election = queryModel.searchElectionByName(electionName)
            ?: throw ServiceException(ServiceException.Category.NOT_FOUND, "Election not found: $electionName")
        val candidateCount = queryModel.candidateCount(electionName)
        val ballotCount = queryModel.ballotCount(electionName)
        val tiers = queryModel.listTiers(electionName)
        return election.toElectionDetail(candidateCount, ballotCount, tiers)
    }

    override fun deleteElection(accessToken: AccessToken, electionName: String) {
        // The election owner can delete their own; ADMIN+ can delete any election
        // (moderation). Anyone else is rejected.
        val election = queryModel.searchElectionByName(electionName)
            ?: throw ServiceException(
                ServiceException.Category.NOT_FOUND,
                "Election '$electionName' not found"
            )
        val isOwnerOfElection = election.ownerName.equals(accessToken.userName, ignoreCase = true)
        val canModerate = hasPermission(accessToken, Permission.MANAGE_USERS)
        if (!isOwnerOfElection && !canModerate) {
            throw ServiceException(
                ServiceException.Category.UNAUTHORIZED,
                "Only the election owner or an ADMIN can delete '$electionName'"
            )
        }
        eventLog.appendEvent(
            accessToken.userName,
            clock.now(),
            DomainEvent.ElectionDeleted(electionName)
        )
        synchronize()
    }

    override fun transferElectionOwnership(
        accessToken: AccessToken,
        electionName: String,
        newOwnerName: String,
    ) {
        // Same gate as deleteElection: the current owner can hand off their
        // own election; ADMIN+ moderators can hand off any.
        val election = queryModel.searchElectionByName(electionName)
            ?: throw ServiceException(
                ServiceException.Category.NOT_FOUND,
                "Election '$electionName' not found"
            )
        val isOwnerOfElection = election.ownerName.equals(accessToken.userName, ignoreCase = true)
        val canModerate = hasPermission(accessToken, Permission.MANAGE_USERS)
        if (!isOwnerOfElection && !canModerate) {
            throw ServiceException(
                ServiceException.Category.UNAUTHORIZED,
                "Only the election owner or an ADMIN can transfer '$electionName'"
            )
        }

        val validNewOwnerName = Validation.validateUserName(newOwnerName)
        // Resolve to the canonical stored case so the projection records the
        // owner exactly as the user is registered, mirroring addElection.
        val newOwner = queryModel.searchUserByName(validNewOwnerName)
            ?: throw ServiceException(
                ServiceException.Category.NOT_FOUND,
                "User not found: $validNewOwnerName"
            )

        // No-op handoff: the requested new owner already owns this election.
        // Skipping the event keeps the audit log honest — only real changes
        // get a row.
        if (election.ownerName.equals(newOwner.name, ignoreCase = true)) {
            return
        }

        eventLog.appendEvent(
            accessToken.userName,
            clock.now(),
            DomainEvent.ElectionOwnerChanged(election.electionName, newOwner.name),
        )
        synchronize()
    }

    override fun listElections(accessToken: AccessToken): List<ElectionSummary> {
        requirePermission(accessToken, Permission.VIEW_APPLICATION)
        return queryModel.listElections()
    }

    override fun listTables(accessToken: AccessToken): List<String> {
        requirePermission(accessToken, Permission.VIEW_SECRETS)
        return rawTableScanner.listRawTableNames()
    }

    override fun listDebugTables(accessToken: AccessToken): List<String> {
        requirePermission(accessToken, Permission.VIEW_SECRETS)
        return relationalProjection.listDebugTableNames()
    }

    override fun userCount(accessToken: AccessToken): Int {
        requirePermission(accessToken, Permission.VIEW_APPLICATION)
        return queryModel.userCount()
    }

    override fun electionCount(accessToken: AccessToken): Int {
        requirePermission(accessToken, Permission.VIEW_APPLICATION)
        return queryModel.electionCount()
    }

    override fun tableCount(accessToken: AccessToken): Int {
        requirePermission(accessToken, Permission.VIEW_SECRETS)
        return queryModel.tableCount()
    }

    override fun eventCount(accessToken: AccessToken): Int {
        requirePermission(accessToken, Permission.VIEW_SECRETS)
        return eventLog.eventCount()
    }

    override fun tableData(accessToken: AccessToken, tableName: String): TableData {
        requirePermission(accessToken, Permission.VIEW_SECRETS)
        return rawTableScanner.scanRawTable(tableName)
    }

    override fun debugTableData(accessToken: AccessToken, tableName: String): TableData {
        requirePermission(accessToken, Permission.VIEW_SECRETS)
        return relationalProjection.project(tableName)
    }

    override fun eventData(accessToken: AccessToken): TableData {
        requirePermission(accessToken, Permission.VIEW_SECRETS)
        return relationalProjection.project(DynamoToRelational.EVENT_LOG)
    }

    override fun addCandidates(accessToken: AccessToken, electionName: String, candidateNames: List<String>) {
        // VALIDATION SECTION
        requirePermission(accessToken, Permission.USE_APPLICATION)
        requireIsElectionOwner(accessToken, electionName)

        val validElectionName = Validation.validateElectionName(electionName)
        val validCandidateNames = Validation.validateCandidateNames(candidateNames)
        // Detail pages classify each name as candidate-or-tier by lookup
        // against the tier list; a name in both lists would render
        // ambiguously, so reject the collision before storing.
        val existingTiers = queryModel.listTiers(validElectionName)
        Validation.validateCandidatesAndTiersDistinct(validCandidateNames, existingTiers)

        // Filter to names not already present. Letting the caller pass a
        // superset (e.g. the user pastes a list that overlaps existing
        // candidates) keeps the UI flow simple — no client-side diffing.
        val existing = queryModel.listCandidates(validElectionName)
        val newOnly = validCandidateNames.filter { it !in existing }
        if (newOnly.isEmpty()) return

        // EXECUTION SECTION
        eventLog.appendEvent(
            accessToken.userName,
            clock.now(),
            DomainEvent.CandidatesAdded(validElectionName, newOnly)
        )
        synchronize()
    }

    override fun removeCandidate(accessToken: AccessToken, electionName: String, candidateName: String) {
        // VALIDATION SECTION
        requirePermission(accessToken, Permission.USE_APPLICATION)
        requireIsElectionOwner(accessToken, electionName)

        val validElectionName = Validation.validateElectionName(electionName)
        val validCandidateName = Validation.validateCandidateName(candidateName)

        // Idempotent: removing a candidate that isn't there is a silent
        // no-op rather than an error. Two clients hitting the button at
        // the same time should both succeed (the second sees an empty diff).
        val existing = queryModel.listCandidates(validElectionName)
        if (validCandidateName !in existing) return

        // EXECUTION SECTION
        // Event stays plural to keep the audit log shape consistent with
        // CandidatesAdded; the single-row API just wraps the name.
        eventLog.appendEvent(
            accessToken.userName,
            clock.now(),
            DomainEvent.CandidatesRemoved(validElectionName, listOf(validCandidateName))
        )
        synchronize()
    }

    override fun listCandidates(accessToken: AccessToken, electionName: String): List<String> {
        requirePermission(accessToken, Permission.VIEW_APPLICATION)
        return queryModel.listCandidates(electionName)
    }

    override fun renameCandidate(
        accessToken: AccessToken,
        electionName: String,
        oldName: String,
        newName: String,
    ) {
        // VALIDATION SECTION
        requirePermission(accessToken, Permission.USE_APPLICATION)
        requireIsElectionOwner(accessToken, electionName)

        val validElectionName = Validation.validateElectionName(electionName)
        val validOldName = Validation.validateCandidateName(oldName)
        val validNewName = Validation.validateCandidateName(newName)

        // Idempotent no-op: renaming a candidate to its own name doesn't need
        // an event in the log. Match comparisons against the *normalized*
        // names so trailing whitespace / case-only changes don't mint events
        // either (validateCandidateName preserves case).
        if (validOldName == validNewName) return

        val existing = queryModel.listCandidates(validElectionName)
        if (validOldName !in existing) {
            throw ServiceException(
                ServiceException.Category.NOT_FOUND,
                "Candidate not found: $validOldName"
            )
        }
        if (validNewName in existing) {
            throw ServiceException(
                ServiceException.Category.CONFLICT,
                "Candidate already exists: $validNewName"
            )
        }
        val tiers = queryModel.listTiers(validElectionName)
        Validation.validateCandidatesAndTiersDistinct(listOf(validNewName), tiers)

        // EXECUTION SECTION
        eventLog.appendEvent(
            accessToken.userName,
            clock.now(),
            DomainEvent.CandidateRenamed(validElectionName, validOldName, validNewName)
        )
        synchronize()
    }

    override fun candidateBallotCounts(accessToken: AccessToken, electionName: String): Map<String, Int> {
        requirePermission(accessToken, Permission.VIEW_APPLICATION)
        return queryModel.candidateBallotCounts(electionName)
    }

    override fun setTiers(accessToken: AccessToken, electionName: String, tierNames: List<String>) {
        // VALIDATION SECTION
        requirePermission(accessToken, Permission.USE_APPLICATION)
        requireIsElectionOwner(accessToken, electionName)

        val validElectionName = Validation.validateElectionName(electionName)
        val validTierNames = Validation.validateTierNames(tierNames)
        val existingCandidates = queryModel.listCandidates(validElectionName)
        Validation.validateCandidatesAndTiersDistinct(existingCandidates, validTierNames)

        // No "no ballots" lock anymore. Rename goes through [renameTier]
        // (cascading), so TiersSet here only ever adds/removes/reorders.
        // Removing a tier here leaves any Ranking.tier annotation pointing
        // at it as a dangling label; the event applier (or query-time
        // projection) treats those as "cleared no tier" (null). That's
        // lossy for the voter's intent on the removed tier — owners
        // should warn-then-confirm in the UI before removing.

        // EXECUTION SECTION
        eventLog.appendEvent(
            accessToken.userName,
            clock.now(),
            DomainEvent.TiersSet(validElectionName, validTierNames)
        )
        synchronize()
    }

    override fun listTiers(accessToken: AccessToken, electionName: String): List<String> {
        requirePermission(accessToken, Permission.VIEW_APPLICATION)
        return queryModel.listTiers(electionName)
    }

    override fun renameTier(
        accessToken: AccessToken,
        electionName: String,
        oldName: String,
        newName: String,
    ) {
        // VALIDATION SECTION
        requirePermission(accessToken, Permission.USE_APPLICATION)
        requireIsElectionOwner(accessToken, electionName)

        val validElectionName = Validation.validateElectionName(electionName)
        val validOldName = Validation.validateTierName(oldName)
        val validNewName = Validation.validateTierName(newName)

        // Idempotent no-op: renaming to the same name doesn't earn an event.
        if (validOldName == validNewName) return

        val existing = queryModel.listTiers(validElectionName)
        if (validOldName !in existing) {
            throw ServiceException(
                ServiceException.Category.NOT_FOUND,
                "Tier not found: $validOldName"
            )
        }
        if (validNewName in existing) {
            throw ServiceException(
                ServiceException.Category.CONFLICT,
                "Tier already exists: $validNewName"
            )
        }
        val candidates = queryModel.listCandidates(validElectionName)
        Validation.validateCandidatesAndTiersDistinct(candidates, listOf(validNewName))

        // EXECUTION SECTION
        eventLog.appendEvent(
            accessToken.userName,
            clock.now(),
            DomainEvent.TierRenamed(validElectionName, validOldName, validNewName)
        )
        synchronize()
    }

    override fun castBallot(
        accessToken: AccessToken,
        voterName: String,
        electionName: String,
        rankings: List<Ranking>
    ): String {
        // VALIDATION SECTION
        requirePermission(accessToken, Permission.VOTE)

        val validVoterName = Validation.validateUserName(voterName)
        val validElectionName = Validation.validateElectionName(electionName)
        val validRankings = Validation.validateRankings(rankings)

        // Identity check: the body's voterName ("what") must match the token's userName ("who").
        // Compared case-insensitively because usernames are case-insensitive for lookup;
        // the canonical case (accessToken.userName) is what gets recorded on the ballot.
        // No proxy/delegation is supported today; if it ever is, gate it on an explicit permission.
        if (!validVoterName.equals(accessToken.userName, ignoreCase = true)) {
            throw ServiceException(
                ServiceException.Category.UNAUTHORIZED,
                "Cannot cast ballot as $validVoterName when authenticated as ${accessToken.userName}"
            )
        }

        queryModel.searchElectionByName(validElectionName)
            ?: throw ServiceException(ServiceException.Category.NOT_FOUND, "Election not found: $validElectionName")

        // No is-launched check (elections are live as soon as they exist) and no
        // eligibility list (anyone authenticated can vote on any election).

        val candidates = queryModel.listCandidates(validElectionName)
        val tiers = queryModel.listTiers(validElectionName)
        // Rankings are candidate-only in storage; each ranking carries an
        // optional [Ranking.tier] annotation referencing one of the
        // election's tiers. validateRankingsMatchCandidates checks both
        // the candidate names and the tier annotations against the
        // election's current configuration.
        Validation.validateRankingsMatchCandidates(validRankings, candidates, tiers)

        // EXECUTION SECTION
        val confirmation = uniqueIdGenerator.generate()
        val whenCast = clock.now()
        eventLog.appendEvent(
            accessToken.userName,
            whenCast,
            DomainEvent.BallotCast(accessToken.userName, validElectionName, validRankings, confirmation, whenCast)
        )
        synchronize()
        return confirmation
    }

    override fun deleteBallot(accessToken: AccessToken, voterName: String, electionName: String) {
        // VALIDATION SECTION
        requirePermission(accessToken, Permission.VOTE)

        val validVoterName = Validation.validateUserName(voterName)
        val validElectionName = Validation.validateElectionName(electionName)

        // Identity check mirrors castBallot: only the authenticated voter can
        // remove their own ballot. Case-insensitive to match the username
        // semantics; canonical case is recorded on the event.
        if (!validVoterName.equals(accessToken.userName, ignoreCase = true)) {
            throw ServiceException(
                ServiceException.Category.UNAUTHORIZED,
                "Cannot delete ballot for $validVoterName when authenticated as ${accessToken.userName}"
            )
        }

        queryModel.searchElectionByName(validElectionName)
            ?: throw ServiceException(ServiceException.Category.NOT_FOUND, "Election not found: $validElectionName")

        // Idempotent: if no ballot exists, the post-condition (no ballot) already
        // holds, so emit nothing rather than throwing.
        queryModel.searchBallot(accessToken.userName, validElectionName)
            ?: return

        // EXECUTION SECTION
        eventLog.appendEvent(
            accessToken.userName,
            clock.now(),
            DomainEvent.BallotDeleted(accessToken.userName, validElectionName)
        )
        synchronize()
    }

    override fun listRankings(accessToken: AccessToken, voterName: String, electionName: String): List<Ranking> {
        requirePermission(accessToken, Permission.VIEW_APPLICATION)
        return queryModel.listRankings(voterName, electionName)
    }

    override fun tally(accessToken: AccessToken, electionName: String): ElectionTally {
        requirePermission(accessToken, Permission.VIEW_APPLICATION)
        queryModel.searchElectionByName(electionName)
            ?: throw ServiceException(ServiceException.Category.NOT_FOUND, "Election not found: $electionName")
        val candidates = queryModel.listCandidates(electionName)
        val tiers = queryModel.listTiers(electionName)
        val ballots = queryModel.listBallots(electionName)
        // Tally projects each ballot into its virtual form (candidates +
        // materialized tier markers) before running Schulze. Storage only
        // carries candidate rankings with a tier annotation; the markers
        // are produced at compute time so a tier rename never invalidates
        // a recorded ballot. When tiers is empty the projection is a no-op.
        // Secret-ballot toggle dropped — tally always shows ranked ballots.
        val tally = Tally.countBallots(
            electionName = electionName,
            secretBallot = false,
            candidates = candidates,
            tiers = tiers,
            ballots = ballots,
        )
        val sections = TallySection.compute(tally.places, tiers)
        return ElectionTally(tally, tiers, sections)
    }

    override fun getBallot(accessToken: AccessToken, voterName: String, electionName: String): BallotSummary? {
        requirePermission(accessToken, Permission.VIEW_APPLICATION)
        return queryModel.searchBallot(voterName, electionName)
    }

    override fun getUserActivity(accessToken: AccessToken): UserActivity {
        // Re-fetch the user's role from the projection rather than trusting
        // the access token: tokens are 10-min lived, so a recent role change
        // (e.g. ownership transfer) might not be reflected there yet. If the
        // principal vanished, requireSessionUser raises 401 so the standard
        // session-lost path on the frontend logs them out cleanly.
        val user = requireSessionUser(accessToken.userName)
        return UserActivity(
            userName = user.name,
            role = user.role,
            electionsOwnedCount = queryModel.electionsOwnedCount(user.name),
            ballotsCastCount = queryModel.ballotsCastCount(user.name),
        )
    }

    override fun discordLoginStart(): DiscordLoginStart {
        val config = discordConfigProvider.current()
            ?: throw ServiceException(
                ServiceException.Category.UNSUPPORTED,
                "Discord login is not configured in this environment",
            )
        // 16 random bytes (128 bits) is overkill for a 5-minute CSRF nonce
        // but cheap. Hex-encoded so it survives URL transit unmodified.
        val state = randomHex(16)
        return DiscordLoginStart(
            authorizeUrl = discordOAuthClient.buildAuthorizeUrl(config, state),
            state = state,
        )
    }

    override fun discordLoginComplete(code: String): Tokens {
        val config = discordConfigProvider.current()
            ?: throw ServiceException(
                ServiceException.Category.UNSUPPORTED,
                "Discord login is not configured in this environment",
            )
        val identity = when (val result = discordOAuthClient.authenticate(config, code)) {
            is DiscordOAuthClient.AuthResult.Ok -> result.identity
            is DiscordOAuthClient.AuthResult.NotInGuild -> throw ServiceException(
                ServiceException.Category.UNAUTHORIZED,
                "You must be a member of The Rippaverse Discord server to use this app",
            )
            is DiscordOAuthClient.AuthResult.Error -> throw ServiceException(
                ServiceException.Category.UNAUTHORIZED,
                "Discord authorization failed: ${result.reason}",
            )
        }

        // Find-or-create by Discord ID. The id is a Discord snowflake — stable
        // across username changes — so it's the right join key. We never key
        // off display name; that's mutable.
        val existing = queryModel.searchUserByDiscordId(identity.discordId)
        val resolvedUser: User = existing ?: createDiscordStubUser(identity)
        val accessToken = AccessToken(resolvedUser.name, resolvedUser.role)
        val refreshToken = RefreshToken(resolvedUser.name)
        return Tokens(accessToken, refreshToken)
    }

    private fun createDiscordStubUser(identity: DiscordOAuthClient.DiscordIdentity): User {
        // The first user to ever register claims ownership; everyone after
        // them lands as a VOTER. Under Discord-only login there's no separate
        // admin bootstrap step, so first-in becomes OWNER automatically.
        val role = if (queryModel.userCount() == 0) Role.PRIMARY_ROLE else Role.DEFAULT_ROLE
        val candidateName = uniqueUserName(identity.displayName)
        eventLog.appendEvent(
            "system",
            clock.now(),
            DomainEvent.UserRegisteredViaDiscord(
                name = candidateName,
                discordId = identity.discordId,
                discordDisplayName = identity.displayName,
                role = role,
            ),
        )
        synchronize()
        return queryModel.findUserByName(candidateName)
    }

    /**
     * Generate a username that doesn't collide with an existing one, derived
     * from [base]. Tries [base], then [base]-2, [base]-3, ... — stops as soon
     * as the candidate is free. Bounded to keep a malicious display name from
     * blowing up the search; in practice the first attempt almost always works
     * for a community-gated app where "Sean" + "Sean-2" + "Sean-3" is plenty.
     */
    private fun uniqueUserName(base: String): String {
        val cleaned = Validation.validateUserName(base.ifBlank { "user" })
        if (queryModel.searchUserByName(cleaned) == null) return cleaned
        for (suffix in 2..1000) {
            val candidate = "$cleaned-$suffix"
            if (queryModel.searchUserByName(candidate) == null) return candidate
        }
        // 999 collisions on a single Discord display name is implausible —
        // appending a random suffix in the unreachable-tail case is defensive
        // belt-and-braces, not a feature we expect to fire.
        return "$cleaned-${randomHex(4)}"
    }

    private fun randomHex(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Look up the user who *owns* the current session. Used wherever we need
     * the principal's projection row — `/refresh`, `/authenticateWithToken`,
     * `/me/activity`. Missing means the user was deleted while their token
     * was still in flight; raise UNAUTHORIZED so the request lands on the
     * frontend's standard 401 path, which clears the session and routes to
     * /login. Anywhere else that looks up a principal user MUST go through
     * here so that "user vanished" is uniformly a logout, not a 500 or 404.
     */
    private fun requireSessionUser(userName: String): User {
        return queryModel.searchUserByName(userName)
            ?: throw ServiceException(
                ServiceException.Category.UNAUTHORIZED,
                "Session user no longer exists: $userName",
            )
    }

    // Permission checking helpers
    private fun hasPermission(accessToken: AccessToken, permission: Permission): Boolean {
        return queryModel.roleHasPermission(accessToken.role, permission)
    }

    private fun requirePermission(accessToken: AccessToken, permission: Permission) {
        if (!hasPermission(accessToken, permission)) {
            throw ServiceException(
                ServiceException.Category.UNAUTHORIZED,
                "User ${accessToken.userName} with role ${accessToken.role} does not have permission $permission"
            )
        }
    }

    private fun isSelf(accessToken: AccessToken, userName: String): Boolean {
        return accessToken.userName.equals(userName, ignoreCase = true)
    }

    /**
     * Throws unless the caller's role is STRICTLY GREATER than the target's.
     * Reading the negation: "if caller is not strictly greater (i.e. less or
     * equal) → throw." So OWNER vs OWNER is rejected; ADMIN vs ADMIN is
     * rejected; ADMIN vs USER passes; OWNER vs ADMIN passes.
     */
    private fun requireGreaterRole(accessToken: AccessToken, target: User) {
        if (accessToken.role <= target.role) {
            throw ServiceException(
                ServiceException.Category.UNAUTHORIZED,
                "${accessToken.userName} with role ${accessToken.role} does not have greater role than ${target.name} with role ${target.role}",
            )
        }
    }

    private fun requireIsElectionOwner(accessToken: AccessToken, electionName: String) {
        val election = queryModel.searchElectionByName(electionName)
            ?: throw ServiceException(
                ServiceException.Category.NOT_FOUND,
                "Election '$electionName' not found"
            )
        if (!election.ownerName.equals(accessToken.userName, ignoreCase = true)) {
            throw ServiceException(
                ServiceException.Category.UNAUTHORIZED,
                "User ${accessToken.userName} is not the owner of election '$electionName'"
            )
        }
    }

}
