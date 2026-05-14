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
    val listElectionsCalls = mutableListOf<Unit>()
    val createElectionCalls = mutableListOf<CreateElectionCall>()
    val getElectionCalls = mutableListOf<String>()
    val addCandidatesCalls = mutableListOf<AddCandidatesCall>()
    val removeCandidateCalls = mutableListOf<RemoveCandidateCall>()
    val renameCandidateCalls = mutableListOf<RenameCandidateCall>()
    val candidateBallotCountsCalls = mutableListOf<String>()
    val listCandidatesCalls = mutableListOf<String>()
    val setTiersCalls = mutableListOf<SetTiersCall>()
    val renameTierCalls = mutableListOf<RenameTierCall>()
    val setElectionDescriptionCalls = mutableListOf<SetElectionDescriptionCall>()
    val listTiersCalls = mutableListOf<String>()
    val castBallotCalls = mutableListOf<CastBallotCall>()
    val deleteMyBallotCalls = mutableListOf<String>()
    val getTallyCalls = mutableListOf<String>()
    val deleteElectionCalls = mutableListOf<String>()
    val transferElectionOwnershipCalls = mutableListOf<TransferElectionOwnershipCall>()
    val removeUserCalls = mutableListOf<String>()
    val loggedErrors = mutableListOf<Throwable>()

    var refreshResult: Result<AuthResponse?> = Result.success(null)
    val refreshCalls = mutableListOf<Unit>()
    val logoutCalls = mutableListOf<Unit>()
    var versionResult: Result<Long> = Result.success(0L)
    val versionCalls = mutableListOf<Unit>()
    var getMyUserResult: Result<UserNameEmail> = Result.success(UserNameEmail("user"))
    val getMyUserCalls = mutableListOf<Unit>()
    var listElectionsResult: Result<List<ElectionSummary>> = Result.success(emptyList())
    var createElectionResult: Result<String> = Result.success("")
    var getElectionResult: Result<ElectionDetail> = Result.failure(Exception("Get election not configured"))
    var addCandidatesResult: Result<Unit> = Result.success(Unit)
    var removeCandidateResult: Result<Unit> = Result.success(Unit)
    var renameCandidateResult: Result<Unit> = Result.success(Unit)
    var candidateBallotCountsResult: Result<Map<String, Int>> = Result.success(emptyMap())
    var listCandidatesResult: Result<List<String>> = Result.success(emptyList())
    var setTiersResult: Result<Unit> = Result.success(Unit)
    var renameTierResult: Result<Unit> = Result.success(Unit)
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

    var discordLoginStartUrlResult: Result<String> = Result.success("https://discord.com/oauth2/authorize?fake=1")
    val discordLoginStartUrlCalls = mutableListOf<Unit>()

    override suspend fun refresh(): AuthResponse? {
        refreshCalls.add(Unit)
        return refreshResult.getOrThrow()
    }

    override suspend fun logout() {
        logoutCalls.add(Unit)
    }

    override suspend fun version(): Long {
        versionCalls.add(Unit)
        return versionResult.getOrThrow()
    }

    override var onSessionLost: (() -> Unit)? = null

    override suspend fun getMyUser(): UserNameEmail {
        getMyUserCalls.add(Unit)
        return getMyUserResult.getOrThrow()
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

    override suspend fun addCandidates(electionName: String, candidateNames: List<String>) {
        addCandidatesCalls.add(AddCandidatesCall(electionName, candidateNames))
        addCandidatesResult.getOrThrow()
    }

    override suspend fun removeCandidate(electionName: String, candidateName: String) {
        removeCandidateCalls.add(RemoveCandidateCall(electionName, candidateName))
        removeCandidateResult.getOrThrow()
    }

    override suspend fun listCandidates(electionName: String): List<String> {
        listCandidatesCalls.add(electionName)
        return listCandidatesResult.getOrThrow()
    }

    override suspend fun renameCandidate(electionName: String, oldName: String, newName: String) {
        renameCandidateCalls.add(RenameCandidateCall(electionName, oldName, newName))
        renameCandidateResult.getOrThrow()
    }

    override suspend fun candidateBallotCounts(electionName: String): Map<String, Int> {
        candidateBallotCountsCalls.add(electionName)
        return candidateBallotCountsResult.getOrThrow()
    }

    override suspend fun setTiers(electionName: String, tiers: List<String>) {
        setTiersCalls.add(SetTiersCall(electionName, tiers))
        setTiersResult.getOrThrow()
    }

    override suspend fun listTiers(electionName: String): List<String> {
        listTiersCalls.add(electionName)
        return listTiersResult.getOrThrow()
    }

    override suspend fun renameTier(electionName: String, oldName: String, newName: String) {
        renameTierCalls.add(RenameTierCall(electionName, oldName, newName))
        renameTierResult.getOrThrow()
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

    override suspend fun discordLoginStartUrl(): String {
        discordLoginStartUrlCalls.add(Unit)
        return discordLoginStartUrlResult.getOrThrow()
    }

    override fun logErrorToServer(error: Throwable) {
        // Match HttpApiClient: cancellation rethrows so callers' catch blocks
        // exit cleanly instead of recording it.
        if (error is CancellationException) throw error
        loggedErrors.add(error)
    }

    data class CreateElectionCall(val electionName: String, val description: String)
    data class AddCandidatesCall(val electionName: String, val candidateNames: List<String>)
    data class RemoveCandidateCall(val electionName: String, val candidateName: String)
    data class RenameCandidateCall(val electionName: String, val oldName: String, val newName: String)
    data class SetTiersCall(val electionName: String, val tiers: List<String>)
    data class RenameTierCall(val electionName: String, val oldName: String, val newName: String)
    data class SetElectionDescriptionCall(val electionName: String, val description: String)
    data class CastBallotCall(val electionName: String, val rankings: List<Ranking>)
    data class SetRoleCall(val userName: String, val role: Role)
    data class TransferElectionOwnershipCall(val electionName: String, val newOwnerName: String)
}
