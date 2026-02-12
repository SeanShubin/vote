package com.seanshubin.vote.frontend

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CreateElectionPageRenderTest {

    /**
     * Test orchestrator for CreateElectionPage that hides infrastructure details.
     */
    class CreateElectionPageTester(
        private val testScope: TestScope,
        private val authToken: String = "test-token",
        private val testId: String = "create-election-test"
    ) : AutoCloseable {
        private val fakeClient = FakeApiClient()
        private val testRoot = ComposeTestHelper.createTestRoot(testId)
        private var capturedElectionName: String? = null
        private var backCalled = false

        init {
            renderComposable(rootElementId = testId) {
                CreateElectionPage(
                    apiClient = fakeClient,
                    authToken = authToken,
                    onElectionCreated = { name -> capturedElectionName = name },
                    onBack = { backCalled = true },
                    coroutineScope = testScope
                )
            }
        }

        // Setup methods
        fun setupCreateElectionSuccess(electionName: String) {
            fakeClient.createElectionResult = Result.success(electionName)
        }

        fun setupCreateElectionFailure(error: Exception) {
            fakeClient.createElectionResult = Result.failure(error)
        }

        // Action methods
        fun enterElectionName(name: String) {
            ComposeTestHelper.setInputByPlaceholder(testId, "Election Name", name)
        }

        fun clickCreateButton() {
            ComposeTestHelper.clickButtonByText(testId, "Create")
            testScope.advanceUntilIdle()
        }

        // Query methods
        fun createElectionCalls() = fakeClient.createElectionCalls

        fun capturedElectionName() = capturedElectionName

        fun wasBackCalled() = backCalled

        fun electionNameInputExists() = ComposeTestHelper.inputExistsByPlaceholder(testId, "Election Name")

        override fun close() {
            testRoot.close()
        }
    }


    @Test
    fun createElectionPageRendersWithElectionNameField() = runTest {
        CreateElectionPageTester(this).use { tester ->
            // then - verify it rendered with expected input field
            assertTrue(tester.electionNameInputExists(), "Election name input should exist")
        }
    }

    @Test
    fun createElectionButtonClickCreatesElection() = runTest {
        CreateElectionPageTester(this).use { tester ->
            // given
            tester.setupCreateElectionSuccess("Test Election")

            // when
            tester.enterElectionName("Test Election")
            tester.clickCreateButton()

            // then
            assertEquals(1, tester.createElectionCalls().size)
            assertEquals("test-token", tester.createElectionCalls()[0].authToken)
            assertEquals("Test Election", tester.createElectionCalls()[0].electionName)
            assertEquals("Test Election", tester.capturedElectionName())
        }
    }

    @Test
    fun createElectionDoesNotSubmitWithEmptyName() = runTest {
        CreateElectionPageTester(this).use { tester ->
            // given
            tester.setupCreateElectionSuccess("Test Election")

            // when - click create without entering name
            tester.clickCreateButton()

            // then - should not call createElection
            assertEquals(0, tester.createElectionCalls().size, "Should not create election with empty name")
        }
    }
}
