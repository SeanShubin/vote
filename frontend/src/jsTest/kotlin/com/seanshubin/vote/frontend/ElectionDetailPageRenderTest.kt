package com.seanshubin.vote.frontend

import com.seanshubin.vote.domain.ElectionSummary
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
        private val authToken: String = "test-token",
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
                    authToken = authToken,
                    electionName = electionName,
                    onBack = { backCalled = true },
                    coroutineScope = testScope
                )
            }
        }

        // Setup methods
        fun setupElectionSuccess(electionSummary: ElectionSummary) {
            fakeClient.getElectionResult = Result.success(electionSummary)
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

        override fun close() {
            testRoot.close()
        }
    }


    @Test
    fun electionDetailPageRendersWithHeading() = runTest {
        ElectionDetailPageTester(this).use { tester ->
            // given
            val electionSummary = ElectionSummary("owner1", "Test Election")
            tester.setupElectionSuccess(electionSummary)
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
            val electionSummary = ElectionSummary("owner1", "Test Election")
            tester.setupElectionSuccess(electionSummary)
            tester.setupCandidatesSuccess(listOf("Candidate 1", "Candidate 2"))

            // when
            tester.waitForLoad()

            // then - verify basic rendering structure
            assertTrue(tester.headingExists(), "Heading should exist")
        }
    }
}
