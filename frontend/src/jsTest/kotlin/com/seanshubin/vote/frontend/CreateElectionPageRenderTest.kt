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

        fun enterDescription(text: String) {
            ComposeTestHelper.setTextAreaByPlaceholder(testId, "Description (optional)", text)
        }

        fun clickCreateButton() {
            ComposeTestHelper.clickButtonByText(testId, "Create")
            testScope.advanceUntilIdle()
        }

        fun pressEnterInElectionName() {
            ComposeTestHelper.pressEnterInInput(testId, "Election Name")
            testScope.advanceUntilIdle()
        }

        // Query methods
        fun createElectionCalls() = fakeClient.createElectionCalls

        fun capturedElectionName() = capturedElectionName

        fun wasBackCalled() = backCalled

        fun electionNameInputExists() = ComposeTestHelper.inputExistsByPlaceholder(testId, "Election Name")

        fun descriptionInputExists() =
            ComposeTestHelper.textAreaExistsByPlaceholder(testId, "Description (optional)")

        override fun close() {
            testRoot.close()
        }
    }


    @Test
    fun createElectionPageRendersWithElectionNameField() = runTest {
        CreateElectionPageTester(this).use { tester ->
            // then - verify it rendered with expected input field
            assertTrue(tester.electionNameInputExists(), "Election name input should exist")
            assertTrue(tester.descriptionInputExists(), "Description textarea should exist")
        }
    }

    @Test
    fun createElectionForwardsDescription() = runTest {
        CreateElectionPageTester(this).use { tester ->
            // given
            tester.setupCreateElectionSuccess("Test Election")

            // when
            tester.enterElectionName("Test Election")
            tester.enterDescription("Pick the best one")
            tester.clickCreateButton()

            // then
            assertEquals(1, tester.createElectionCalls().size)
            assertEquals("Test Election", tester.createElectionCalls()[0].electionName)
            assertEquals("Pick the best one", tester.createElectionCalls()[0].description)
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
            assertEquals("Test Election", tester.createElectionCalls()[0].electionName)
            assertEquals("", tester.createElectionCalls()[0].description)
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

    @Test
    fun pressingEnterCreatesElection() = runTest {
        CreateElectionPageTester(this).use { tester ->
            // given
            tester.setupCreateElectionSuccess("Test Election")

            // when
            tester.enterElectionName("Test Election")
            tester.pressEnterInElectionName()

            // then
            assertEquals(1, tester.createElectionCalls().size)
            assertEquals("Test Election", tester.createElectionCalls()[0].electionName)
            assertEquals("Test Election", tester.capturedElectionName())
        }
    }
}
