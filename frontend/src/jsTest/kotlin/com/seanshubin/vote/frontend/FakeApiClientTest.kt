package com.seanshubin.vote.frontend

import com.seanshubin.vote.contract.AccessToken
import com.seanshubin.vote.contract.RefreshToken
import com.seanshubin.vote.contract.Tokens
import com.seanshubin.vote.domain.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FakeApiClientTest {
    @Test
    fun registerCapturesCallAndReturnsConfiguredResult() = runTest {
        val fakeClient = FakeApiClient()
        val expectedTokens = Tokens(
            AccessToken("alice", Role.USER),
            RefreshToken("alice")
        )
        fakeClient.registerResult = Result.success(expectedTokens)

        val tokens = fakeClient.register("alice", "alice@example.com", "password123")

        assertEquals(1, fakeClient.registerCalls.size)
        assertEquals("alice", fakeClient.registerCalls[0].userName)
        assertEquals("alice@example.com", fakeClient.registerCalls[0].email)
        assertEquals("password123", fakeClient.registerCalls[0].password)
        assertEquals(expectedTokens, tokens)
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
        val expectedTokens = Tokens(
            AccessToken("alice", Role.USER),
            RefreshToken("alice")
        )
        fakeClient.authenticateResult = Result.success(expectedTokens)

        val tokens = fakeClient.authenticate("alice", "password123")

        assertEquals(1, fakeClient.authenticateCalls.size)
        assertEquals("alice", fakeClient.authenticateCalls[0].userName)
        assertEquals("password123", fakeClient.authenticateCalls[0].password)
        assertEquals(expectedTokens, tokens)
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
                secretBallot = true,
                allowEdit = true,
                allowVote = false,
                noVotingBefore = null,
                noVotingAfter = null
            )
        )
        fakeClient.listElectionsResult = Result.success(expectedElections)

        val elections = fakeClient.listElections("token-123")

        assertEquals(1, fakeClient.listElectionsCalls.size)
        assertEquals("token-123", fakeClient.listElectionsCalls[0].authToken)
        assertEquals(expectedElections, elections)
    }

    @Test
    fun createElectionCapturesCallAndReturnsConfiguredResult() = runTest {
        val fakeClient = FakeApiClient()
        fakeClient.createElectionResult = Result.success("Best Language")

        val electionName = fakeClient.createElection("token-123", "Best Language")

        assertEquals(1, fakeClient.createElectionCalls.size)
        assertEquals("token-123", fakeClient.createElectionCalls[0].authToken)
        assertEquals("Best Language", fakeClient.createElectionCalls[0].electionName)
        assertEquals("Best Language", electionName)
    }

    @Test
    fun getElectionCapturesCallAndReturnsConfiguredResult() = runTest {
        val fakeClient = FakeApiClient()
        val expectedElection = ElectionSummary(
            electionName = "Best Language",
            ownerName = "alice",
            secretBallot = true,
            allowEdit = true,
            allowVote = false,
            noVotingBefore = null,
            noVotingAfter = null
        )
        fakeClient.getElectionResult = Result.success(expectedElection)

        val election = fakeClient.getElection("token-123", "Best Language")

        assertEquals(1, fakeClient.getElectionCalls.size)
        assertEquals("token-123", fakeClient.getElectionCalls[0].authToken)
        assertEquals("Best Language", fakeClient.getElectionCalls[0].electionName)
        assertEquals(expectedElection, election)
    }

    @Test
    fun setCandidatesCapturesCallWithCandidateList() = runTest {
        val fakeClient = FakeApiClient()
        val candidates = listOf("Kotlin", "Rust", "Go")

        fakeClient.setCandidates("token-123", "Best Language", candidates)

        assertEquals(1, fakeClient.setCandidatesCalls.size)
        assertEquals("token-123", fakeClient.setCandidatesCalls[0].authToken)
        assertEquals("Best Language", fakeClient.setCandidatesCalls[0].electionName)
        assertEquals(candidates, fakeClient.setCandidatesCalls[0].candidates)
    }

    @Test
    fun listCandidatesCapturesCallAndReturnsConfiguredResult() = runTest {
        val fakeClient = FakeApiClient()
        val expectedCandidates = listOf("Kotlin", "Rust", "Go")
        fakeClient.listCandidatesResult = Result.success(expectedCandidates)

        val candidates = fakeClient.listCandidates("token-123", "Best Language")

        assertEquals(1, fakeClient.listCandidatesCalls.size)
        assertEquals("token-123", fakeClient.listCandidatesCalls[0].authToken)
        assertEquals("Best Language", fakeClient.listCandidatesCalls[0].electionName)
        assertEquals(expectedCandidates, candidates)
    }

    @Test
    fun setEligibleVotersCapturesCallWithVoterList() = runTest {
        val fakeClient = FakeApiClient()
        val voters = listOf("bob", "charlie")

        fakeClient.setEligibleVoters("token-123", "Best Language", voters)

        assertEquals(1, fakeClient.setEligibleVotersCalls.size)
        assertEquals("token-123", fakeClient.setEligibleVotersCalls[0].authToken)
        assertEquals("Best Language", fakeClient.setEligibleVotersCalls[0].electionName)
        assertEquals(voters, fakeClient.setEligibleVotersCalls[0].voters)
    }

    @Test
    fun launchElectionCapturesCall() = runTest {
        val fakeClient = FakeApiClient()

        fakeClient.launchElection("token-123", "Best Language")

        assertEquals(1, fakeClient.launchElectionCalls.size)
        assertEquals("token-123", fakeClient.launchElectionCalls[0].authToken)
        assertEquals("Best Language", fakeClient.launchElectionCalls[0].electionName)
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

        val confirmation = fakeClient.castBallot("token-123", "Best Language", rankings)

        assertEquals(1, fakeClient.castBallotCalls.size)
        assertEquals("token-123", fakeClient.castBallotCalls[0].authToken)
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
            Tokens(AccessToken("alice", Role.USER), RefreshToken("alice"))
        )

        fakeClient.authenticate("alice", "pass1")
        fakeClient.authenticate("bob", "pass2")
        fakeClient.authenticate("charlie", "pass3")

        assertEquals(3, fakeClient.authenticateCalls.size)
        assertEquals("alice", fakeClient.authenticateCalls[0].userName)
        assertEquals("bob", fakeClient.authenticateCalls[1].userName)
        assertEquals("charlie", fakeClient.authenticateCalls[2].userName)
    }
}
