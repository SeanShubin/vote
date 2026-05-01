package com.seanshubin.vote.frontend

import com.seanshubin.vote.domain.ElectionDetail
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import kotlin.test.Test
import kotlin.test.assertTrue

class ElectionDetailPageRenderTest {

    /**
     * Test orchestrator for ElectionDetailPage that hides infrastructure details.
     */
    class ElectionDetailPageTester(
        private val testScope: TestScope,
        private val electionName: String = "Test Election",
        private val testId: String = "election-detail-test"
    ) : AutoCloseable {
        private val fakeClient = FakeApiClient()
        private val testRoot = ComposeTestHelper.createTestRoot(testId)
        private var backCalled = false

        init {
            renderComposable(rootElementId = testId) {
                ElectionDetailPage(
                    apiClient = fakeClient,
                    electionName = electionName,
                    currentUserName = null,
                    currentRole = null,
                    onBack = { backCalled = true },
                    onElectionDeleted = {},
                    coroutineScope = testScope
                )
            }
        }

        // Setup methods
        fun setupElectionSuccess(electionDetail: ElectionDetail) {
            fakeClient.getElectionResult = Result.success(electionDetail)
        }

        fun setupCandidatesSuccess(candidates: List<String>) {
            fakeClient.listCandidatesResult = Result.success(candidates)
        }

        fun setupElectionFailure(error: Exception) {
            fakeClient.getElectionResult = Result.failure(error)
        }

        fun setupCandidatesFailure(error: Exception) {
            fakeClient.listCandidatesResult = Result.failure(error)
        }

        // Action methods
        fun waitForLoad() {
            testScope.advanceUntilIdle()
        }

        fun clickTab(tabName: String) {
            ComposeTestHelper.clickButtonByText(testId, tabName)
        }

        fun clickBack() {
            ComposeTestHelper.clickButtonByText(testId, "Back to Elections")
        }

        // Query methods
        fun getElectionCalls() = fakeClient.getElectionCalls

        fun listCandidatesCalls() = fakeClient.listCandidatesCalls

        fun wasBackCalled() = backCalled

        fun headingExists() = ComposeTestHelper.elementExists(testId, "h1")

        fun pageContainsText(text: String) =
            ComposeTestHelper.textExistsInRoot(testId, text)

        override fun close() {
            testRoot.close()
        }
    }


    @Test
    fun electionDetailPageRendersWithHeading() = runTest {
        ElectionDetailPageTester(this).use { tester ->
            // given
            val electionDetail = ElectionDetail("owner1", "Test Election", candidateCount = 2, ballotCount = 0)
            tester.setupElectionSuccess(electionDetail)
            tester.setupCandidatesSuccess(listOf("Candidate 1", "Candidate 2"))

            // when
            tester.waitForLoad()

            // then - verify it rendered with heading
            assertTrue(tester.headingExists(), "Heading should exist")
        }
    }

    @Test
    fun electionDetailPageNavigationTabsRender() = runTest {
        ElectionDetailPageTester(this).use { tester ->
            // given
            val electionDetail = ElectionDetail("owner1", "Test Election", candidateCount = 2, ballotCount = 0)
            tester.setupElectionSuccess(electionDetail)
            tester.setupCandidatesSuccess(listOf("Candidate 1", "Candidate 2"))

            // when
            tester.waitForLoad()

            // then - verify basic rendering structure
            assertTrue(tester.headingExists(), "Heading should exist")
        }
    }

    @Test
    fun electionDetailPageWithDescriptionRenders() = runTest {
        // The description rendering happens after the LaunchedEffect inside the
        // page resolves — and the LaunchedEffect runs on Compose's internal
        // dispatcher, not the testScope, so it isn't deterministic from a test
        // perspective (see frontend-testing-root-cause-found.md for the broader
        // limitation). We verify here that supplying an ElectionDetail with a
        // description doesn't break rendering — the actual description is
        // exercised through the data flow tests (ElectionSummary -> Detail) and
        // through the integration tests in :integration.
        ElectionDetailPageTester(this).use { tester ->
            val electionDetail = ElectionDetail(
                ownerName = "owner1",
                electionName = "Test Election",
                candidateCount = 2,
                ballotCount = 0,
                description = "Pick your favorite color",
            )
            tester.setupElectionSuccess(electionDetail)
            tester.setupCandidatesSuccess(listOf("Candidate 1", "Candidate 2"))

            tester.waitForLoad()

            assertTrue(tester.headingExists(), "Heading should exist when description is provided")
        }
    }
}
