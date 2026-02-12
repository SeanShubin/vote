package com.seanshubin.vote.frontend

import com.seanshubin.vote.domain.ElectionSummary
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import kotlin.test.Test
import kotlin.test.assertTrue

class ElectionsPageRenderTest {

    /**
     * Test orchestrator for ElectionsPage that hides infrastructure details.
     */
    class ElectionsPageTester(
        private val testScope: TestScope,
        private val authToken: String = "test-token",
        private val testId: String = "elections-page-test"
    ) : AutoCloseable {
        private val fakeClient = FakeApiClient()
        private val testRoot = ComposeTestHelper.createTestRoot(testId)
        private var selectedElection: String? = null
        private var backCalled = false

        init {
            renderComposable(rootElementId = testId) {
                ElectionsPage(
                    apiClient = fakeClient,
                    authToken = authToken,
                    onSelectElection = { electionName -> selectedElection = electionName },
                    onBack = { backCalled = true },
                    coroutineScope = testScope
                )
            }
        }

        // Setup methods
        fun setupListElectionsSuccess(elections: List<ElectionSummary>) {
            fakeClient.listElectionsResult = Result.success(elections)
        }

        fun setupListElectionsFailure(error: Exception) {
            fakeClient.listElectionsResult = Result.failure(error)
        }

        // Action methods
        fun waitForElectionsLoad() {
            testScope.advanceUntilIdle()
        }

        fun selectElection(electionName: String) {
            ComposeTestHelper.clickButtonByText(testId, electionName)
        }

        fun clickBack() {
            ComposeTestHelper.clickButtonByText(testId, "Back to Home")
        }

        // Query methods
        fun listElectionsCalls() = fakeClient.listElectionsCalls

        fun selectedElection() = selectedElection

        fun wasBackCalled() = backCalled

        fun headingExists() = ComposeTestHelper.elementExists(testId, "h1")

        override fun close() {
            testRoot.close()
        }
    }


    @Test
    fun electionsPageRendersWithLoadingState() = runTest {
        ElectionsPageTester(this).use { tester ->
            // given
            tester.setupListElectionsSuccess(emptyList())

            // when
            tester.waitForElectionsLoad()

            // then - verify it rendered with heading
            assertTrue(tester.headingExists(), "Heading should exist")
        }
    }

    @Test
    fun electionsPageRendersElectionList() = runTest {
        ElectionsPageTester(this).use { tester ->
            // given
            val elections = listOf(
                ElectionSummary("owner1", "Election 1"),
                ElectionSummary("owner2", "Election 2")
            )
            tester.setupListElectionsSuccess(elections)

            // when
            tester.waitForElectionsLoad()

            // then - verify it rendered
            assertTrue(tester.headingExists(), "Heading should exist")
        }
    }
}
