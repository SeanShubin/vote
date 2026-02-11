package com.seanshubin.vote.frontend

import com.seanshubin.vote.contract.AccessToken
import com.seanshubin.vote.contract.RefreshToken
import com.seanshubin.vote.contract.Tokens
import com.seanshubin.vote.domain.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FrontendBehaviorTest {
    @Test
    fun loginPageAuthenticatesUserWithCredentials() = runTest {
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
    fun loginPageLogsErrorWhenAuthenticationFails() = runTest {
        val fakeClient = FakeApiClient()
        fakeClient.authenticateResult = Result.failure(Exception("Invalid credentials"))

        val exception = assertFailsWith<Exception> {
            fakeClient.authenticate("alice", "wrongpassword")
        }

        assertEquals("Invalid credentials", exception.message)
        fakeClient.logErrorToServer(exception)
        assertEquals(1, fakeClient.loggedErrors.size)
    }

    @Test
    fun registerPageCreatesNewUserAccount() = runTest {
        val fakeClient = FakeApiClient()
        val expectedTokens = Tokens(
            AccessToken("bob", Role.USER),
            RefreshToken("bob")
        )
        fakeClient.registerResult = Result.success(expectedTokens)

        val tokens = fakeClient.register("bob", "bob@example.com", "securepass")

        assertEquals(1, fakeClient.registerCalls.size)
        assertEquals("bob", fakeClient.registerCalls[0].userName)
        assertEquals("bob@example.com", fakeClient.registerCalls[0].email)
        assertEquals("securepass", fakeClient.registerCalls[0].password)
        assertEquals(expectedTokens, tokens)
    }

    @Test
    fun registerPageLogsErrorWhenRegistrationFails() = runTest {
        val fakeClient = FakeApiClient()
        fakeClient.registerResult = Result.failure(Exception("Username already taken"))

        val exception = assertFailsWith<Exception> {
            fakeClient.register("alice", "alice@example.com", "password123")
        }

        assertEquals("Username already taken", exception.message)
        fakeClient.logErrorToServer(exception)
        assertEquals(1, fakeClient.loggedErrors.size)
    }

    @Test
    fun electionsPageLoadsListOfElectionsOnMount() = runTest {
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
            ),
            ElectionSummary(
                electionName = "Best Framework",
                ownerName = "bob",
                secretBallot = true,
                allowEdit = false,
                allowVote = true,
                noVotingBefore = null,
                noVotingAfter = null
            )
        )
        fakeClient.listElectionsResult = Result.success(expectedElections)

        val elections = fakeClient.listElections("auth-token-123")

        assertEquals(1, fakeClient.listElectionsCalls.size)
        assertEquals("auth-token-123", fakeClient.listElectionsCalls[0].authToken)
        assertEquals(2, elections.size)
        assertEquals("Best Language", elections[0].electionName)
        assertEquals("Best Framework", elections[1].electionName)
    }

    @Test
    fun electionsPageLogsErrorWhenLoadingFails() = runTest {
        val fakeClient = FakeApiClient()
        fakeClient.listElectionsResult = Result.failure(Exception("Network timeout"))

        val exception = assertFailsWith<Exception> {
            fakeClient.listElections("auth-token-123")
        }

        assertEquals("Network timeout", exception.message)
        fakeClient.logErrorToServer(exception)
        assertEquals(1, fakeClient.loggedErrors.size)
    }

    @Test
    fun createElectionPageSubmitsNewElection() = runTest {
        val fakeClient = FakeApiClient()
        fakeClient.createElectionResult = Result.success("Programming Language Poll")

        val electionName = fakeClient.createElection("auth-token-123", "Programming Language Poll")

        assertEquals(1, fakeClient.createElectionCalls.size)
        assertEquals("auth-token-123", fakeClient.createElectionCalls[0].authToken)
        assertEquals("Programming Language Poll", fakeClient.createElectionCalls[0].electionName)
        assertEquals("Programming Language Poll", electionName)
    }

    @Test
    fun createElectionPageLogsErrorWhenCreationFails() = runTest {
        val fakeClient = FakeApiClient()
        fakeClient.createElectionResult = Result.failure(Exception("Election name already exists"))

        val exception = assertFailsWith<Exception> {
            fakeClient.createElection("auth-token-123", "Duplicate Name")
        }

        assertEquals("Election name already exists", exception.message)
        fakeClient.logErrorToServer(exception)
        assertEquals(1, fakeClient.loggedErrors.size)
    }

    @Test
    fun electionDetailPageLoadsElectionAndCandidatesOnMount() = runTest {
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
        val expectedCandidates = listOf("Kotlin", "Rust", "Go")
        fakeClient.getElectionResult = Result.success(expectedElection)
        fakeClient.listCandidatesResult = Result.success(expectedCandidates)

        val election = fakeClient.getElection("auth-token-123", "Best Language")
        val candidates = fakeClient.listCandidates("auth-token-123", "Best Language")

        assertEquals(1, fakeClient.getElectionCalls.size)
        assertEquals("Best Language", fakeClient.getElectionCalls[0].electionName)
        assertEquals(expectedElection, election)
        assertEquals(1, fakeClient.listCandidatesCalls.size)
        assertEquals("Best Language", fakeClient.listCandidatesCalls[0].electionName)
        assertEquals(expectedCandidates, candidates)
    }

    @Test
    fun electionSetupSavesCandidates() = runTest {
        val fakeClient = FakeApiClient()
        val candidates = listOf("Kotlin", "Rust", "Go")

        fakeClient.setCandidates("auth-token-123", "Best Language", candidates)

        assertEquals(1, fakeClient.setCandidatesCalls.size)
        assertEquals("auth-token-123", fakeClient.setCandidatesCalls[0].authToken)
        assertEquals("Best Language", fakeClient.setCandidatesCalls[0].electionName)
        assertEquals(candidates, fakeClient.setCandidatesCalls[0].candidates)
    }

    @Test
    fun electionSetupLogsErrorWhenSavingCandidatesFails() = runTest {
        val fakeClient = FakeApiClient()
        fakeClient.setCandidatesResult = Result.failure(Exception("Election already launched"))

        val exception = assertFailsWith<Exception> {
            fakeClient.setCandidates("auth-token-123", "Best Language", listOf("Kotlin"))
        }

        assertEquals("Election already launched", exception.message)
        fakeClient.logErrorToServer(exception)
        assertEquals(1, fakeClient.loggedErrors.size)
    }

    @Test
    fun electionSetupSavesEligibleVoters() = runTest {
        val fakeClient = FakeApiClient()
        val voters = listOf("bob", "charlie", "dave")

        fakeClient.setEligibleVoters("auth-token-123", "Best Language", voters)

        assertEquals(1, fakeClient.setEligibleVotersCalls.size)
        assertEquals("auth-token-123", fakeClient.setEligibleVotersCalls[0].authToken)
        assertEquals("Best Language", fakeClient.setEligibleVotersCalls[0].electionName)
        assertEquals(voters, fakeClient.setEligibleVotersCalls[0].voters)
    }

    @Test
    fun electionSetupLogsErrorWhenSavingVotersFails() = runTest {
        val fakeClient = FakeApiClient()
        fakeClient.setEligibleVotersResult = Result.failure(Exception("Invalid voter name"))

        val exception = assertFailsWith<Exception> {
            fakeClient.setEligibleVoters("auth-token-123", "Best Language", listOf("invalid-user"))
        }

        assertEquals("Invalid voter name", exception.message)
        fakeClient.logErrorToServer(exception)
        assertEquals(1, fakeClient.loggedErrors.size)
    }

    @Test
    fun electionSetupLaunchesElection() = runTest {
        val fakeClient = FakeApiClient()

        fakeClient.launchElection("auth-token-123", "Best Language")

        assertEquals(1, fakeClient.launchElectionCalls.size)
        assertEquals("auth-token-123", fakeClient.launchElectionCalls[0].authToken)
        assertEquals("Best Language", fakeClient.launchElectionCalls[0].electionName)
    }

    @Test
    fun electionSetupLogsErrorWhenLaunchFails() = runTest {
        val fakeClient = FakeApiClient()
        fakeClient.launchElectionResult = Result.failure(Exception("Must set candidates first"))

        val exception = assertFailsWith<Exception> {
            fakeClient.launchElection("auth-token-123", "Best Language")
        }

        assertEquals("Must set candidates first", exception.message)
        fakeClient.logErrorToServer(exception)
        assertEquals(1, fakeClient.loggedErrors.size)
    }

    @Test
    fun votingViewCastsBallotWithRankings() = runTest {
        val fakeClient = FakeApiClient()
        val rankings = listOf(
            Ranking("Kotlin", 1),
            Ranking("Rust", 2),
            Ranking("Go", 3)
        )
        fakeClient.castBallotResult = Result.success("ballot-confirmation-789")

        val confirmation = fakeClient.castBallot("auth-token-123", "Best Language", rankings)

        assertEquals(1, fakeClient.castBallotCalls.size)
        assertEquals("auth-token-123", fakeClient.castBallotCalls[0].authToken)
        assertEquals("Best Language", fakeClient.castBallotCalls[0].electionName)
        assertEquals(rankings, fakeClient.castBallotCalls[0].rankings)
        assertEquals("ballot-confirmation-789", confirmation)
    }

    @Test
    fun votingViewLogsErrorWhenCastingBallotFails() = runTest {
        val fakeClient = FakeApiClient()
        fakeClient.castBallotResult = Result.failure(Exception("Election not launched"))

        val exception = assertFailsWith<Exception> {
            fakeClient.castBallot("auth-token-123", "Best Language", listOf(Ranking("Kotlin", 1)))
        }

        assertEquals("Election not launched", exception.message)
        fakeClient.logErrorToServer(exception)
        assertEquals(1, fakeClient.loggedErrors.size)
    }

    @Test
    fun completeWorkflowFromRegistrationToViewingResults() = runTest {
        val fakeClient = FakeApiClient()
        fakeClient.registerResult = Result.success(
            Tokens(AccessToken("alice", Role.OWNER), RefreshToken("alice"))
        )
        fakeClient.createElectionResult = Result.success("Best Language")
        fakeClient.getElectionResult = Result.success(
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
        fakeClient.listCandidatesResult = Result.success(listOf("Kotlin", "Rust", "Go"))
        fakeClient.castBallotResult = Result.success("ballot-123")

        fakeClient.register("alice", "alice@example.com", "password123")
        fakeClient.createElection("alice-token", "Best Language")
        fakeClient.setCandidates("alice-token", "Best Language", listOf("Kotlin", "Rust", "Go"))
        fakeClient.setEligibleVoters("alice-token", "Best Language", listOf("bob"))
        fakeClient.launchElection("alice-token", "Best Language")
        fakeClient.castBallot("bob-token", "Best Language", listOf(
            Ranking("Kotlin", 1),
            Ranking("Rust", 2),
            Ranking("Go", 3)
        ))

        assertEquals(1, fakeClient.registerCalls.size)
        assertEquals(1, fakeClient.createElectionCalls.size)
        assertEquals(1, fakeClient.setCandidatesCalls.size)
        assertEquals(1, fakeClient.setEligibleVotersCalls.size)
        assertEquals(1, fakeClient.launchElectionCalls.size)
        assertEquals(1, fakeClient.castBallotCalls.size)
    }
}
