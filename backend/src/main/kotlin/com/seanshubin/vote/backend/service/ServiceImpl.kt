package com.seanshubin.vote.backend.service

import com.seanshubin.vote.backend.auth.TokenEncoder
import com.seanshubin.vote.backend.validation.Validation
import com.seanshubin.vote.contract.*
import com.seanshubin.vote.domain.*

class ServiceImpl(
    private val integrations: Integrations,
    private val eventLog: EventLog,
    private val commandModel: CommandModel,
    private val queryModel: QueryModel,
    private val rawTableScanner: RawTableScanner,
    private val tokenEncoder: TokenEncoder,
    private val frontendBaseUrl: String,
) : Service {
    private val clock = integrations.clock
    private val uniqueIdGenerator = integrations.uniqueIdGenerator
    private val notifications = integrations.notifications
    private val passwordUtil = integrations.passwordUtil
    private val emailSender = integrations.emailSender
    private val relationalProjection = DynamoToRelational(queryModel, eventLog)

    override fun synchronize() {
        // Synchronize events from EventLog to CommandModel
        val lastSynced = queryModel.lastSynced() ?: 0
        val newEvents = eventLog.eventsToSync(lastSynced)

        // Apply each event to the command model
        newEvents.forEach { envelope ->
            applyEvent(envelope.authority, envelope.event)
            commandModel.setLastSynced(envelope.eventId)
        }
    }

    private fun applyEvent(authority: String, event: DomainEvent) {
        when (event) {
            is DomainEvent.UserRegistered -> {
                commandModel.createUser(
                    authority = authority,
                    userName = event.name,
                    email = event.email,
                    salt = event.salt,
                    hash = event.hash,
                    role = event.role
                )
            }
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
            is DomainEvent.UserPasswordChanged -> {
                commandModel.setPassword(authority, event.userName, event.newSalt, event.newHash)
            }
            is DomainEvent.UserNameChanged -> {
                commandModel.setUserName(authority, event.oldUserName, event.newUserName)
            }
            is DomainEvent.UserEmailChanged -> {
                commandModel.setEmail(authority, event.userName, event.newEmail)
            }
            is DomainEvent.ElectionCreated -> {
                commandModel.addElection(authority, event.ownerName, event.electionName)
            }
            is DomainEvent.ElectionDeleted -> {
                commandModel.deleteElection(authority, event.electionName)
            }
            is DomainEvent.CandidatesAdded -> {
                commandModel.addCandidates(authority, event.electionName, event.candidateNames)
            }
            is DomainEvent.CandidatesRemoved -> {
                commandModel.removeCandidates(authority, event.electionName, event.candidateNames)
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
        }
    }

    override fun health(): String {
        return try {
            queryModel.userCount()
            "ok"
        } catch (e: Exception) {
            e.message ?: "error"
        }
    }

    override fun refresh(refreshToken: RefreshToken): Tokens {
        val user = queryModel.findUserByName(refreshToken.userName)
        val accessToken = AccessToken(user.name, user.role)
        return Tokens(accessToken, refreshToken)
    }

    override fun register(userName: String, email: String, password: String): Tokens {
        val validUserName = Validation.validateUserName(userName)
        val validEmail = Validation.validateEmail(email)
        val validPassword = Validation.validatePassword(password)

        // CONFLICT (409) — the request is well-formed but the resource already
        // exists. Telling the user *which* field collides is intentional: the
        // user explicitly asked for honest errors over enumeration-resistance.
        if (queryModel.searchUserByName(validUserName) != null) {
            throw ServiceException(
                ServiceException.Category.CONFLICT,
                "User name already exists: $validUserName"
            )
        }
        if (queryModel.searchUserByEmail(validEmail) != null) {
            throw ServiceException(
                ServiceException.Category.CONFLICT,
                "Email already exists: $validEmail"
            )
        }

        val role = if (queryModel.userCount() == 0) Role.OWNER else Role.USER
        val saltAndHash = passwordUtil.createSaltAndHash(validPassword)

        eventLog.appendEvent(
            "system",
            clock.now(),
            DomainEvent.UserRegistered(validUserName, validEmail, saltAndHash.salt, saltAndHash.hash, role)
        )
        synchronize()

        val user = queryModel.findUserByName(validUserName)
        val accessToken = AccessToken(user.name, user.role)
        val refreshToken = RefreshToken(user.name)
        return Tokens(accessToken, refreshToken)
    }

    override fun authenticate(nameOrEmail: String, password: String): Tokens {
        val validNameOrEmail = Validation.validateNameOrEmail(nameOrEmail)
        // Try as username first, then email — login accepts either form.
        // Distinguishing "no such user" (NOT_FOUND) from "wrong password"
        // (UNAUTHORIZED) is intentional honesty per the user's preference.
        val user = queryModel.searchUserByName(validNameOrEmail)
            ?: queryModel.searchUserByEmail(validNameOrEmail)
            ?: throw ServiceException(
                ServiceException.Category.NOT_FOUND,
                "No user found with username or email: $validNameOrEmail"
            )

        if (!passwordUtil.passwordMatches(password, user.salt, user.hash)) {
            throw ServiceException(
                ServiceException.Category.UNAUTHORIZED,
                "Wrong password for user: ${user.name}"
            )
        }

        val accessToken = AccessToken(user.name, user.role)
        val refreshToken = RefreshToken(user.name)
        return Tokens(accessToken, refreshToken)
    }

    override fun authenticateWithToken(accessToken: AccessToken): Tokens {
        val user = queryModel.findUserByName(accessToken.userName)
        val newAccessToken = AccessToken(user.name, user.role)
        val refreshToken = RefreshToken(user.name)
        return Tokens(newAccessToken, refreshToken)
    }

    override fun permissionsForRole(role: Role): List<Permission> {
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
                toUserName = userName,
            )
        } else {
            DomainEvent.UserRoleChanged(userName, role)
        }
        eventLog.appendEvent(accessToken.userName, clock.now(), event)
        synchronize()
    }

    override fun removeUser(accessToken: AccessToken, userName: String) {
        val targetUser = queryModel.searchUserByName(userName)
            ?: throw ServiceException(ServiceException.Category.NOT_FOUND, "User not found: $userName")

        if (isSelf(accessToken, userName)) {
            // Self-delete — anyone can leave the system, EXCEPT the OWNER, since
            // exactly one OWNER must exist. The lone-user edge case (an OWNER
            // who's the only user) is allowed — it deletes the system back to empty.
            if (targetUser.role == Role.OWNER && queryModel.userCount() > 1) {
                throw ServiceException(
                    ServiceException.Category.UNSUPPORTED,
                    "OWNER cannot self-delete while other users exist — transfer ownership first",
                )
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
            DomainEvent.UserRemoved(userName)
        )
        synchronize()
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
            UserNameRole(user.name, user.role, callerPermissions.listedRolesFor(target))
        }
    }

    override fun addElection(accessToken: AccessToken, userName: String, electionName: String) {
        // VALIDATION SECTION
        requirePermission(accessToken, Permission.USE_APPLICATION)

        val validUserName = Validation.validateUserName(userName)
        val validElectionName = Validation.validateElectionName(electionName)

        // Ensure user exists
        queryModel.searchUserByName(validUserName)
            ?: throw ServiceException(ServiceException.Category.NOT_FOUND, "User not found: $validUserName")

        // Ensure election name is unique
        require(queryModel.searchElectionByName(validElectionName) == null) {
            "Election already exists: $validElectionName"
        }

        // EXECUTION SECTION
        eventLog.appendEvent(
            accessToken.userName,
            clock.now(),
            DomainEvent.ElectionCreated(validUserName, validElectionName)
        )
        synchronize()
    }

    override fun updateUser(accessToken: AccessToken, userName: String, userUpdates: UserUpdates) {
        // VALIDATION SECTION
        requirePermission(accessToken, Permission.USE_APPLICATION)
        val targetUser = queryModel.searchUserByName(userName)
            ?: throw ServiceException(ServiceException.Category.NOT_FOUND, "User not found: $userName")
        if (!isSelf(accessToken, userName)) {
            requireGreaterRole(accessToken, targetUser)
        }

        // Validate new values if provided
        val validNewUserName = userUpdates.userName?.let { Validation.validateUserName(it) }
        val validNewEmail = userUpdates.email?.let { Validation.validateEmail(it) }

        // Check for conflicts
        validNewUserName?.let { newName ->
            require(queryModel.searchUserByName(newName) == null || newName == userName) {
                "User name already exists: $newName"
            }
        }
        validNewEmail?.let { newEmail ->
            val existingUser = queryModel.searchUserByEmail(newEmail)
            require(existingUser == null || existingUser.name == userName) {
                "Email already exists: $newEmail"
            }
        }

        // EXECUTION SECTION
        validNewUserName?.let {
            eventLog.appendEvent(
                accessToken.userName,
                clock.now(),
                DomainEvent.UserNameChanged(userName, it)
            )
        }
        validNewEmail?.let {
            eventLog.appendEvent(
                accessToken.userName,
                clock.now(),
                DomainEvent.UserEmailChanged(userName, it)
            )
        }
        synchronize()
    }

    override fun getUser(accessToken: AccessToken, userName: String): UserNameEmail {
        val user = queryModel.findUserByName(userName)
        return UserNameEmail(user.name, user.email)
    }

    override fun getElection(accessToken: AccessToken, electionName: String): ElectionDetail {
        val election = queryModel.searchElectionByName(electionName)
            ?: throw ServiceException(ServiceException.Category.NOT_FOUND, "Election not found: $electionName")
        val candidateCount = queryModel.candidateCount(electionName)
        val ballotCount = queryModel.ballotCount(electionName)
        return election.toElectionDetail(candidateCount, ballotCount)
    }

    override fun deleteElection(accessToken: AccessToken, electionName: String) {
        // The election owner can delete their own; ADMIN+ can delete any election
        // (moderation). Anyone else is rejected.
        val election = queryModel.searchElectionByName(electionName)
            ?: throw ServiceException(
                ServiceException.Category.NOT_FOUND,
                "Election '$electionName' not found"
            )
        val isOwnerOfElection = election.ownerName == accessToken.userName
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

    override fun listElections(accessToken: AccessToken): List<ElectionSummary> {
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
        return queryModel.userCount()
    }

    override fun electionCount(accessToken: AccessToken): Int {
        return queryModel.electionCount()
    }

    override fun tableCount(accessToken: AccessToken): Int {
        return queryModel.tableCount()
    }

    override fun eventCount(accessToken: AccessToken): Int {
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

    override fun setCandidates(accessToken: AccessToken, electionName: String, candidateNames: List<String>) {
        // VALIDATION SECTION
        requirePermission(accessToken, Permission.USE_APPLICATION)
        requireIsElectionOwner(accessToken, electionName)

        val validElectionName = Validation.validateElectionName(electionName)
        val validCandidateNames = Validation.validateCandidateNames(candidateNames)

        // DIFF COMPUTATION (pure logic, no side effects)
        val existing = queryModel.listCandidates(validElectionName)
        val changes = computeCandidateChanges(validElectionName, existing, validCandidateNames)

        // EXECUTION SECTION
        applyCandidateEvents(accessToken.userName, changes)
        synchronize()
    }

    override fun listCandidates(accessToken: AccessToken, electionName: String): List<String> {
        return queryModel.listCandidates(electionName)
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
        // No proxy/delegation is supported today; if it ever is, gate it on an explicit permission.
        if (validVoterName != accessToken.userName) {
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
        Validation.validateRankingsMatchCandidates(validRankings, candidates)

        // EXECUTION SECTION
        val confirmation = uniqueIdGenerator.generate()
        val whenCast = clock.now()
        eventLog.appendEvent(
            accessToken.userName,
            whenCast,
            DomainEvent.BallotCast(validVoterName, validElectionName, validRankings, confirmation, whenCast)
        )
        synchronize()
        return confirmation
    }

    override fun listRankings(accessToken: AccessToken, voterName: String, electionName: String): List<Ranking> {
        return queryModel.listRankings(voterName, electionName)
    }

    override fun tally(accessToken: AccessToken, electionName: String): Tally {
        queryModel.searchElectionByName(electionName)
            ?: throw ServiceException(ServiceException.Category.NOT_FOUND, "Election not found: $electionName")
        val candidates = queryModel.listCandidates(electionName)
        val ballots = queryModel.listBallots(electionName)
        // Secret-ballot toggle dropped — tally always shows ranked ballots.
        return Tally.countBallots(electionName, secretBallot = false, candidates, ballots)
    }

    override fun getBallot(accessToken: AccessToken, voterName: String, electionName: String): BallotSummary? {
        return queryModel.searchBallot(voterName, electionName)
    }

    override fun changePassword(accessToken: AccessToken, userName: String, password: String) {
        val validPassword = Validation.validatePassword(password)
        val saltAndHash = passwordUtil.createSaltAndHash(validPassword)
        eventLog.appendEvent(
            accessToken.userName,
            clock.now(),
            DomainEvent.UserPasswordChanged(userName, saltAndHash.salt, saltAndHash.hash)
        )
        synchronize()
    }

    override fun sendLoginLinkByEmail(email: String, baseUri: String) {
        notifications.sendMailEvent(email, "Login link")
    }

    override fun requestPasswordReset(nameOrEmail: String) {
        val validNameOrEmail = Validation.validateNameOrEmail(nameOrEmail)
        // Same lookup pattern as authenticate(): match by username first, then email.
        val user = queryModel.searchUserByName(validNameOrEmail)
            ?: queryModel.searchUserByEmail(validNameOrEmail)
            ?: throw ServiceException(
                ServiceException.Category.NOT_FOUND,
                "No user found with username or email: $validNameOrEmail"
            )

        val resetToken = tokenEncoder.encodeResetToken(user.name)
        val resetUrl = "$frontendBaseUrl/reset-password?token=$resetToken"
        val body = """
            Hello ${user.name},

            Use the link below to set a new password. The link expires in 1 hour.

            $resetUrl

            If you didn't request this, you can ignore this email — your password
            won't change unless someone follows the link.
        """.trimIndent()
        emailSender.send(user.email, "Reset your password", body)
        notifications.sendMailEvent(user.email, "Reset your password")
    }

    override fun resetPassword(resetToken: String, newPassword: String) {
        val userName = tokenEncoder.decodeResetToken(resetToken)
            ?: throw ServiceException(
                ServiceException.Category.UNAUTHORIZED,
                "Reset token is missing, expired, or invalid"
            )
        val user = queryModel.searchUserByName(userName)
            ?: throw ServiceException(
                ServiceException.Category.NOT_FOUND,
                "User no longer exists: $userName"
            )

        val validPassword = Validation.validatePassword(newPassword)
        val saltAndHash = passwordUtil.createSaltAndHash(validPassword)
        // The reset itself is the authority for the password-change event —
        // there's no logged-in actor at this point. Authority "system" plus the
        // token's userName claim is the closest honest representation.
        eventLog.appendEvent(
            "system",
            clock.now(),
            DomainEvent.UserPasswordChanged(user.name, saltAndHash.salt, saltAndHash.hash),
        )
        synchronize()
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
        return accessToken.userName == userName
    }

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
        if (election.ownerName != accessToken.userName) {
            throw ServiceException(
                ServiceException.Category.UNAUTHORIZED,
                "User ${accessToken.userName} is not the owner of election '$electionName'"
            )
        }
    }

    // Helper methods for diff-based operations
    private fun computeCandidateChanges(
        electionName: String,
        existing: List<String>,
        desired: List<String>
    ): CandidateChanges {
        val toAdd = desired.filter { it !in existing }
        val toRemove = existing.filter { it !in desired }
        return CandidateChanges(electionName, toAdd, toRemove)
    }

    private fun applyCandidateEvents(authority: String, changes: CandidateChanges) {
        if (changes.toAdd.isNotEmpty()) {
            eventLog.appendEvent(
                authority,
                clock.now(),
                DomainEvent.CandidatesAdded(changes.electionName, changes.toAdd)
            )
        }
        if (changes.toRemove.isNotEmpty()) {
            eventLog.appendEvent(
                authority,
                clock.now(),
                DomainEvent.CandidatesRemoved(changes.electionName, changes.toRemove)
            )
        }
    }

    // Data classes for change computation
    private data class CandidateChanges(
        val electionName: String,
        val toAdd: List<String>,
        val toRemove: List<String>
    )
}
