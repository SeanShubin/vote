package com.seanshubin.vote.frontend

import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.contract.Tokens
import com.seanshubin.vote.domain.ElectionSummary
import com.seanshubin.vote.domain.Ranking
import com.seanshubin.vote.domain.Tally

class FakeApiClient : ApiClient {
    val registerCalls = mutableListOf<RegisterCall>()
    val authenticateCalls = mutableListOf<AuthenticateCall>()
    val listElectionsCalls = mutableListOf<ListElectionsCall>()
    val createElectionCalls = mutableListOf<CreateElectionCall>()
    val getElectionCalls = mutableListOf<GetElectionCall>()
    val setCandidatesCalls = mutableListOf<SetCandidatesCall>()
    val listCandidatesCalls = mutableListOf<ListCandidatesCall>()
    val setEligibleVotersCalls = mutableListOf<SetEligibleVotersCall>()
    val launchElectionCalls = mutableListOf<LaunchElectionCall>()
    val castBallotCalls = mutableListOf<CastBallotCall>()
    val getTallyCalls = mutableListOf<GetTallyCall>()
    val loggedErrors = mutableListOf<Throwable>()

    var registerResult: Result<Tokens> = Result.failure(Exception("Register not configured"))
    var authenticateResult: Result<Tokens> = Result.failure(Exception("Authenticate not configured"))
    var listElectionsResult: Result<List<ElectionSummary>> = Result.success(emptyList())
    var createElectionResult: Result<String> = Result.success("")
    var getElectionResult: Result<ElectionSummary> = Result.failure(Exception("Get election not configured"))
    var setCandidatesResult: Result<Unit> = Result.success(Unit)
    var listCandidatesResult: Result<List<String>> = Result.success(emptyList())
    var setEligibleVotersResult: Result<Unit> = Result.success(Unit)
    var launchElectionResult: Result<Unit> = Result.success(Unit)
    var castBallotResult: Result<String> = Result.success("ballot-confirmation-123")
    var getTallyResult: Result<Tally> = Result.failure(Exception("Get tally not configured"))

    override suspend fun register(userName: String, email: String, password: String): Tokens {
        registerCalls.add(RegisterCall(userName, email, password))
        return registerResult.getOrThrow()
    }

    override suspend fun authenticate(userName: String, password: String): Tokens {
        authenticateCalls.add(AuthenticateCall(userName, password))
        return authenticateResult.getOrThrow()
    }

    override suspend fun listElections(authToken: String): List<ElectionSummary> {
        listElectionsCalls.add(ListElectionsCall(authToken))
        return listElectionsResult.getOrThrow()
    }

    override suspend fun createElection(authToken: String, electionName: String): String {
        createElectionCalls.add(CreateElectionCall(authToken, electionName))
        return createElectionResult.getOrThrow()
    }

    override suspend fun getElection(authToken: String, electionName: String): ElectionSummary {
        getElectionCalls.add(GetElectionCall(authToken, electionName))
        return getElectionResult.getOrThrow()
    }

    override suspend fun setCandidates(authToken: String, electionName: String, candidates: List<String>) {
        setCandidatesCalls.add(SetCandidatesCall(authToken, electionName, candidates))
        setCandidatesResult.getOrThrow()
    }

    override suspend fun listCandidates(authToken: String, electionName: String): List<String> {
        listCandidatesCalls.add(ListCandidatesCall(authToken, electionName))
        return listCandidatesResult.getOrThrow()
    }

    override suspend fun setEligibleVoters(authToken: String, electionName: String, voters: List<String>) {
        setEligibleVotersCalls.add(SetEligibleVotersCall(authToken, electionName, voters))
        setEligibleVotersResult.getOrThrow()
    }

    override suspend fun launchElection(authToken: String, electionName: String) {
        launchElectionCalls.add(LaunchElectionCall(authToken, electionName))
        launchElectionResult.getOrThrow()
    }

    override suspend fun castBallot(authToken: String, electionName: String, rankings: List<Ranking>): String {
        castBallotCalls.add(CastBallotCall(authToken, electionName, rankings))
        return castBallotResult.getOrThrow()
    }

    override suspend fun getTally(authToken: String, electionName: String): Tally {
        getTallyCalls.add(GetTallyCall(authToken, electionName))
        return getTallyResult.getOrThrow()
    }

    override fun logErrorToServer(error: Throwable) {
        loggedErrors.add(error)
    }

    data class RegisterCall(val userName: String, val email: String, val password: String)
    data class AuthenticateCall(val userName: String, val password: String)
    data class ListElectionsCall(val authToken: String)
    data class CreateElectionCall(val authToken: String, val electionName: String)
    data class GetElectionCall(val authToken: String, val electionName: String)
    data class SetCandidatesCall(val authToken: String, val electionName: String, val candidates: List<String>)
    data class ListCandidatesCall(val authToken: String, val electionName: String)
    data class SetEligibleVotersCall(val authToken: String, val electionName: String, val voters: List<String>)
    data class LaunchElectionCall(val authToken: String, val electionName: String)
    data class CastBallotCall(val authToken: String, val electionName: String, val rankings: List<Ranking>)
    data class GetTallyCall(val authToken: String, val electionName: String)
}
