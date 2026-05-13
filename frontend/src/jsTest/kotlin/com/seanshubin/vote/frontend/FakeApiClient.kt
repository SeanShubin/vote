package com.seanshubin.vote.frontend

import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.contract.AuthResponse
import kotlinx.coroutines.CancellationException
import com.seanshubin.vote.domain.ElectionDetail
import com.seanshubin.vote.domain.ElectionSummary
import com.seanshubin.vote.domain.Ranking
import com.seanshubin.vote.domain.Role
import com.seanshubin.vote.domain.TableData
import com.seanshubin.vote.domain.ElectionTally
import com.seanshubin.vote.domain.UserActivity
import com.seanshubin.vote.domain.UserNameEmail
import com.seanshubin.vote.domain.UserNameRole

class FakeApiClient : ApiClient {
    val registerCalls = mutableListOf<RegisterCall>()
    val authenticateCalls = mutableListOf<AuthenticateCall>()
    val listElectionsCalls = mutableListOf<Unit>()
    val createElectionCalls = mutableListOf<CreateElectionCall>()
    val getElectionCalls = mutableListOf<String>()
    val setCandidatesCalls = mutableListOf<SetCandidatesCall>()
    val listCandidatesCalls = mutableListOf<String>()
    val setTiersCalls = mutableListOf<SetTiersCall>()
    val setElectionDescriptionCalls = mutableListOf<SetElectionDescriptionCall>()
    val listTiersCalls = mutableListOf<String>()
    val castBallotCalls = mutableListOf<CastBallotCall>()
    val deleteMyBallotCalls = mutableListOf<String>()
    val getTallyCalls = mutableListOf<String>()
    val deleteElectionCalls = mutableListOf<String>()
    val transferElectionOwnershipCalls = mutableListOf<TransferElectionOwnershipCall>()
    val removeUserCalls = mutableListOf<String>()
    val loggedErrors = mutableListOf<Throwable>()

    var registerResult: Result<AuthResponse> = Result.failure(Exception("Register not configured"))
    var authenticateResult: Result<AuthResponse> = Result.failure(Exception("Authenticate not configured"))
    var refreshResult: Result<AuthResponse?> = Result.success(null)
    val refreshCalls = mutableListOf<Unit>()
    val logoutCalls = mutableListOf<Unit>()
    var requestPasswordResetResult: Result<Unit> = Result.success(Unit)
    var resetPasswordResult: Result<Unit> = Result.success(Unit)
    var changeMyPasswordResult: Result<Unit> = Result.success(Unit)
    var adminSetPasswordResult: Result<Unit> = Result.success(Unit)
    var getMyUserResult: Result<UserNameEmail> = Result.success(UserNameEmail("user", ""))
    var updateMyEmailResult: Result<Unit> = Result.success(Unit)
    val requestPasswordResetCalls = mutableListOf<String>()
    val resetPasswordCalls = mutableListOf<ResetPasswordCall>()
    val changeMyPasswordCalls = mutableListOf<ChangeMyPasswordCall>()
    val adminSetPasswordCalls = mutableListOf<AdminSetPasswordCall>()
    val getMyUserCalls = mutableListOf<Unit>()
    val updateMyEmailCalls = mutableListOf<String>()
    var listElectionsResult: Result<List<ElectionSummary>> = Result.success(emptyList())
    var createElectionResult: Result<String> = Result.success("")
    var getElectionResult: Result<ElectionDetail> = Result.failure(Exception("Get election not configured"))
    var setCandidatesResult: Result<Unit> = Result.success(Unit)
    var listCandidatesResult: Result<List<String>> = Result.success(emptyList())
    var setTiersResult: Result<Unit> = Result.success(Unit)
    var setElectionDescriptionResult: Result<Unit> = Result.success(Unit)
    var listTiersResult: Result<List<String>> = Result.success(emptyList())
    var castBallotResult: Result<String> = Result.success("ballot-confirmation-123")
    var deleteMyBallotResult: Result<Unit> = Result.success(Unit)
    var myRankingsResult: Result<List<Ranking>> = Result.success(emptyList())
    val myRankingsCalls = mutableListOf<String>()
    var getTallyResult: Result<ElectionTally> = Result.failure(Exception("Get tally not configured"))
    var deleteElectionResult: Result<Unit> = Result.success(Unit)
    var transferElectionOwnershipResult: Result<Unit> = Result.success(Unit)
    var removeUserResult: Result<Unit> = Result.success(Unit)
    var listTablesResult: Result<List<String>> = Result.success(emptyList())
    var tableDataResult: Result<TableData> = Result.success(TableData("", emptyList(), emptyList()))
    var listDebugTablesResult: Result<List<String>> = Result.success(emptyList())
    var debugTableDataResult: Result<TableData> = Result.success(TableData("", emptyList(), emptyList()))
    val listTablesCalls = mutableListOf<Unit>()
    val tableDataCalls = mutableListOf<String>()
    val listDebugTablesCalls = mutableListOf<Unit>()
    val debugTableDataCalls = mutableListOf<String>()
    var listUsersResult: Result<List<UserNameRole>> = Result.success(emptyList())
    var listUserNamesResult: Result<List<String>> = Result.success(emptyList())
    var setRoleResult: Result<Unit> = Result.success(Unit)
    var userActivityResult: Result<UserActivity> = Result.failure(Exception("getUserActivity not configured"))
    val listUsersCalls = mutableListOf<Unit>()
    val listUserNamesCalls = mutableListOf<Unit>()
    val setRoleCalls = mutableListOf<SetRoleCall>()
    val userActivityCalls = mutableListOf<Unit>()

    override suspend fun register(userName: String, email: String, password: String, inviteCode: String): AuthResponse {
        registerCalls.add(RegisterCall(userName, email, password, inviteCode))
        return registerResult.getOrThrow()
    }

    override suspend fun authenticate(nameOrEmail: String, password: String): AuthResponse {
        authenticateCalls.add(AuthenticateCall(nameOrEmail, password))
        return authenticateResult.getOrThrow()
    }

    override suspend fun refresh(): AuthResponse? {
        refreshCalls.add(Unit)
        return refreshResult.getOrThrow()
    }

    override suspend fun logout() {
        logoutCalls.add(Unit)
    }

    override var onSessionLost: (() -> Unit)? = null

    override suspend fun requestPasswordReset(nameOrEmail: String) {
        requestPasswordResetCalls.add(nameOrEmail)
        requestPasswordResetResult.getOrThrow()
    }

    override suspend fun resetPassword(resetToken: String, newPassword: String) {
        resetPasswordCalls.add(ResetPasswordCall(resetToken, newPassword))
        resetPasswordResult.getOrThrow()
    }

    override suspend fun changeMyPassword(oldPassword: String, newPassword: String) {
        changeMyPasswordCalls.add(ChangeMyPasswordCall(oldPassword, newPassword))
        changeMyPasswordResult.getOrThrow()
    }

    override suspend fun adminSetPassword(userName: String, newPassword: String) {
        adminSetPasswordCalls.add(AdminSetPasswordCall(userName, newPassword))
        adminSetPasswordResult.getOrThrow()
    }

    override suspend fun getMyUser(): UserNameEmail {
        getMyUserCalls.add(Unit)
        return getMyUserResult.getOrThrow()
    }

    override suspend fun updateMyEmail(newEmail: String) {
        updateMyEmailCalls.add(newEmail)
        updateMyEmailResult.getOrThrow()
    }

    override suspend fun listElections(): List<ElectionSummary> {
        listElectionsCalls.add(Unit)
        return listElectionsResult.getOrThrow()
    }

    override suspend fun createElection(electionName: String, description: String): String {
        createElectionCalls.add(CreateElectionCall(electionName, description))
        return createElectionResult.getOrThrow()
    }

    override suspend fun getElection(electionName: String): ElectionDetail {
        getElectionCalls.add(electionName)
        return getElectionResult.getOrThrow()
    }

    override suspend fun setElectionDescription(electionName: String, description: String) {
        setElectionDescriptionCalls.add(SetElectionDescriptionCall(electionName, description))
        setElectionDescriptionResult.getOrThrow()
    }

    override suspend fun setCandidates(electionName: String, candidates: List<String>) {
        setCandidatesCalls.add(SetCandidatesCall(electionName, candidates))
        setCandidatesResult.getOrThrow()
    }

    override suspend fun listCandidates(electionName: String): List<String> {
        listCandidatesCalls.add(electionName)
        return listCandidatesResult.getOrThrow()
    }

    override suspend fun setTiers(electionName: String, tiers: List<String>) {
        setTiersCalls.add(SetTiersCall(electionName, tiers))
        setTiersResult.getOrThrow()
    }

    override suspend fun listTiers(electionName: String): List<String> {
        listTiersCalls.add(electionName)
        return listTiersResult.getOrThrow()
    }

    override suspend fun castBallot(electionName: String, rankings: List<Ranking>): String {
        castBallotCalls.add(CastBallotCall(electionName, rankings))
        return castBallotResult.getOrThrow()
    }

    override suspend fun deleteMyBallot(electionName: String) {
        deleteMyBallotCalls.add(electionName)
        deleteMyBallotResult.getOrThrow()
    }

    override suspend fun getMyRankings(electionName: String): List<Ranking> {
        myRankingsCalls.add(electionName)
        return myRankingsResult.getOrThrow()
    }

    override suspend fun getTally(electionName: String): ElectionTally {
        getTallyCalls.add(electionName)
        return getTallyResult.getOrThrow()
    }

    override suspend fun deleteElection(electionName: String) {
        deleteElectionCalls.add(electionName)
        deleteElectionResult.getOrThrow()
    }

    override suspend fun transferElectionOwnership(electionName: String, newOwnerName: String) {
        transferElectionOwnershipCalls.add(TransferElectionOwnershipCall(electionName, newOwnerName))
        transferElectionOwnershipResult.getOrThrow()
    }

    override suspend fun removeUser(userName: String) {
        removeUserCalls.add(userName)
        removeUserResult.getOrThrow()
    }

    override suspend fun listTables(): List<String> {
        listTablesCalls.add(Unit)
        return listTablesResult.getOrThrow()
    }

    override suspend fun tableData(tableName: String): TableData {
        tableDataCalls.add(tableName)
        return tableDataResult.getOrThrow()
    }

    override suspend fun listDebugTables(): List<String> {
        listDebugTablesCalls.add(Unit)
        return listDebugTablesResult.getOrThrow()
    }

    override suspend fun debugTableData(tableName: String): TableData {
        debugTableDataCalls.add(tableName)
        return debugTableDataResult.getOrThrow()
    }

    override suspend fun listUsers(): List<UserNameRole> {
        listUsersCalls.add(Unit)
        return listUsersResult.getOrThrow()
    }

    override suspend fun listUserNames(): List<String> {
        listUserNamesCalls.add(Unit)
        return listUserNamesResult.getOrThrow()
    }

    override suspend fun getUserActivity(): UserActivity {
        userActivityCalls.add(Unit)
        return userActivityResult.getOrThrow()
    }

    override suspend fun setRole(userName: String, role: Role) {
        setRoleCalls.add(SetRoleCall(userName, role))
        setRoleResult.getOrThrow()
    }

    override fun logErrorToServer(error: Throwable) {
        // Match HttpApiClient: cancellation rethrows so callers' catch blocks
        // exit cleanly instead of recording it.
        if (error is CancellationException) throw error
        loggedErrors.add(error)
    }

    data class RegisterCall(val userName: String, val email: String, val password: String, val inviteCode: String = "")
    data class CreateElectionCall(val electionName: String, val description: String)
    data class AuthenticateCall(val nameOrEmail: String, val password: String)
    data class SetCandidatesCall(val electionName: String, val candidates: List<String>)
    data class SetTiersCall(val electionName: String, val tiers: List<String>)
    data class SetElectionDescriptionCall(val electionName: String, val description: String)
    data class CastBallotCall(val electionName: String, val rankings: List<Ranking>)
    data class ResetPasswordCall(val resetToken: String, val newPassword: String)
    data class ChangeMyPasswordCall(val oldPassword: String, val newPassword: String)
    data class AdminSetPasswordCall(val userName: String, val newPassword: String)
    data class SetRoleCall(val userName: String, val role: Role)
    data class TransferElectionOwnershipCall(val electionName: String, val newOwnerName: String)
}
