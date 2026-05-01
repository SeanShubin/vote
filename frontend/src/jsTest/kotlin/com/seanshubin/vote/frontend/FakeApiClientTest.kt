package com.seanshubin.vote.frontend

import com.seanshubin.vote.contract.AuthResponse
import com.seanshubin.vote.domain.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FakeApiClientTest {
    @Test
    fun registerCapturesCallAndReturnsConfiguredResult() = runTest {
        val fakeClient = FakeApiClient()
        val expectedAuth = AuthResponse("token-alice", "alice", Role.USER)
        fakeClient.registerResult = Result.success(expectedAuth)

        val auth = fakeClient.register("alice", "alice@example.com", "password123")

        assertEquals(1, fakeClient.registerCalls.size)
        assertEquals("alice", fakeClient.registerCalls[0].userName)
        assertEquals("alice@example.com", fakeClient.registerCalls[0].email)
        assertEquals("password123", fakeClient.registerCalls[0].password)
        assertEquals(expectedAuth, auth)
    }

    @Test
    fun registerThrowsWhenConfiguredToFail() = runTest {
        val fakeClient = FakeApiClient()
        fakeClient.registerResult = Result.failure(Exception("User already exists"))

        val exception = assertFailsWith<Exception> {
            fakeClient.register("alice", "alice@example.com", "password123")
        }

        assertEquals("User already exists", exception.message)
        assertEquals(1, fakeClient.registerCalls.size)
    }

    @Test
    fun authenticateCapturesCallAndReturnsConfiguredResult() = runTest {
        val fakeClient = FakeApiClient()
        val expectedAuth = AuthResponse("token-alice", "alice", Role.USER)
        fakeClient.authenticateResult = Result.success(expectedAuth)

        val auth = fakeClient.authenticate("alice", "password123")

        assertEquals(1, fakeClient.authenticateCalls.size)
        assertEquals("alice", fakeClient.authenticateCalls[0].nameOrEmail)
        assertEquals("password123", fakeClient.authenticateCalls[0].password)
        assertEquals(expectedAuth, auth)
    }

    @Test
    fun authenticateThrowsWhenConfiguredToFail() = runTest {
        val fakeClient = FakeApiClient()
        fakeClient.authenticateResult = Result.failure(Exception("Invalid credentials"))

        val exception = assertFailsWith<Exception> {
            fakeClient.authenticate("alice", "wrongpassword")
        }

        assertEquals("Invalid credentials", exception.message)
        assertEquals(1, fakeClient.authenticateCalls.size)
    }

    @Test
    fun listElectionsCapturesCallAndReturnsConfiguredResult() = runTest {
        val fakeClient = FakeApiClient()
        val expectedElections = listOf(
            ElectionSummary(
                electionName = "Best Language",
                ownerName = "alice",
            )
        )
        fakeClient.listElectionsResult = Result.success(expectedElections)

        val elections = fakeClient.listElections()

        assertEquals(1, fakeClient.listElectionsCalls.size)
        assertEquals(expectedElections, elections)
    }

    @Test
    fun createElectionCapturesCallAndReturnsConfiguredResult() = runTest {
        val fakeClient = FakeApiClient()
        fakeClient.createElectionResult = Result.success("Best Language")

        val electionName = fakeClient.createElection("Best Language")

        assertEquals(1, fakeClient.createElectionCalls.size)
        assertEquals("Best Language", fakeClient.createElectionCalls[0])
        assertEquals("Best Language", electionName)
    }

    @Test
    fun getElectionCapturesCallAndReturnsConfiguredResult() = runTest {
        val fakeClient = FakeApiClient()
        val expectedElection = ElectionDetail(
            electionName = "Best Language",
            ownerName = "alice",
            candidateCount = 3,
            ballotCount = 0,
        )
        fakeClient.getElectionResult = Result.success(expectedElection)

        val election = fakeClient.getElection("Best Language")

        assertEquals(1, fakeClient.getElectionCalls.size)
        assertEquals("Best Language", fakeClient.getElectionCalls[0])
        assertEquals(expectedElection, election)
    }

    @Test
    fun setCandidatesCapturesCallWithCandidateList() = runTest {
        val fakeClient = FakeApiClient()
        val candidates = listOf("Kotlin", "Rust", "Go")

        fakeClient.setCandidates("Best Language", candidates)

        assertEquals(1, fakeClient.setCandidatesCalls.size)
        assertEquals("Best Language", fakeClient.setCandidatesCalls[0].electionName)
        assertEquals(candidates, fakeClient.setCandidatesCalls[0].candidates)
    }

    @Test
    fun listCandidatesCapturesCallAndReturnsConfiguredResult() = runTest {
        val fakeClient = FakeApiClient()
        val expectedCandidates = listOf("Kotlin", "Rust", "Go")
        fakeClient.listCandidatesResult = Result.success(expectedCandidates)

        val candidates = fakeClient.listCandidates("Best Language")

        assertEquals(1, fakeClient.listCandidatesCalls.size)
        assertEquals("Best Language", fakeClient.listCandidatesCalls[0])
        assertEquals(expectedCandidates, candidates)
    }

    @Test
    fun castBallotCapturesCallWithRankingsAndReturnsConfirmation() = runTest {
        val fakeClient = FakeApiClient()
        val rankings = listOf(
            Ranking("Kotlin", 1),
            Ranking("Rust", 2),
            Ranking("Go", 3)
        )
        fakeClient.castBallotResult = Result.success("ballot-456")

        val confirmation = fakeClient.castBallot("Best Language", rankings)

        assertEquals(1, fakeClient.castBallotCalls.size)
        assertEquals("Best Language", fakeClient.castBallotCalls[0].electionName)
        assertEquals(rankings, fakeClient.castBallotCalls[0].rankings)
        assertEquals("ballot-456", confirmation)
    }

    @Test
    fun logErrorToServerCapturesErrors() {
        val fakeClient = FakeApiClient()
        val error1 = Exception("Network error")
        val error2 = Exception("Parse error")

        fakeClient.logErrorToServer(error1)
        fakeClient.logErrorToServer(error2)

        assertEquals(2, fakeClient.loggedErrors.size)
        assertEquals("Network error", fakeClient.loggedErrors[0].message)
        assertEquals("Parse error", fakeClient.loggedErrors[1].message)
    }

    @Test
    fun multipleCallsAccumulateInHistory() = runTest {
        val fakeClient = FakeApiClient()
        fakeClient.authenticateResult = Result.success(
            AuthResponse("token-alice", "alice", Role.USER)
        )

        fakeClient.authenticate("alice", "pass1")
        fakeClient.authenticate("bob", "pass2")
        fakeClient.authenticate("charlie", "pass3")

        assertEquals(3, fakeClient.authenticateCalls.size)
        assertEquals("alice", fakeClient.authenticateCalls[0].nameOrEmail)
        assertEquals("bob", fakeClient.authenticateCalls[1].nameOrEmail)
        assertEquals("charlie", fakeClient.authenticateCalls[2].nameOrEmail)
    }
}
