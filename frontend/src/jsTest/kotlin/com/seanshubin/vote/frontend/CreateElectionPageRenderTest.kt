package com.seanshubin.vote.frontend

import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CreateElectionPageRenderTest {

    @Test
    fun createElectionPageRendersWithElectionNameField() = runTest {
        // given
        val fakeClient = FakeApiClient()
        val testId = "create-election-render-test"

        ComposeTestHelper.createTestRoot(testId).use {
            // when
            renderComposable(rootElementId = testId) {
                CreateElectionPage(
                    apiClient = fakeClient,
                    authToken = "test-token",
                    onElectionCreated = { },
                    onBack = { }
                )
            }

            // then - verify it rendered with expected input field
            assertTrue(
                ComposeTestHelper.inputExistsByPlaceholder(testId, "Election Name"),
                "Election name input should exist"
            )
        }
    }

    @Test
    fun createElectionButtonClickCreatesElection() = runTest {
        // given
        val fakeClient = FakeApiClient()
        fakeClient.createElectionResult = Result.success("Test Election")

        val testId = "create-election-button-test"

        var capturedElectionName: String? = null

        ComposeTestHelper.createTestRoot(testId).use {
            renderComposable(rootElementId = testId) {
                CreateElectionPage(
                    apiClient = fakeClient,
                    authToken = "test-token",
                    onElectionCreated = { name -> capturedElectionName = name },
                    onBack = { },
                    coroutineScope = this@runTest
                )
            }

            // when - enter election name and click create button
            ComposeTestHelper.setInputByPlaceholder(testId, "Election Name", "Test Election")
            ComposeTestHelper.clickButtonByText(testId, "Create")

            // Wait for all coroutines to complete
            advanceUntilIdle()

            // then
            assertEquals(1, fakeClient.createElectionCalls.size, "Expected 1 createElection call but got ${fakeClient.createElectionCalls.size}")
            assertEquals("test-token", fakeClient.createElectionCalls[0].authToken)
            assertEquals("Test Election", fakeClient.createElectionCalls[0].electionName)
            assertEquals("Test Election", capturedElectionName)
        }
    }

    @Test
    fun createElectionDoesNotSubmitWithEmptyName() = runTest {
        // given
        val fakeClient = FakeApiClient()
        fakeClient.createElectionResult = Result.success("Test Election")

        val testId = "create-election-empty-test"

        ComposeTestHelper.createTestRoot(testId).use {
            renderComposable(rootElementId = testId) {
                CreateElectionPage(
                    apiClient = fakeClient,
                    authToken = "test-token",
                    onElectionCreated = { },
                    onBack = { },
                    coroutineScope = this@runTest
                )
            }

            // when - click create button without entering election name
            ComposeTestHelper.clickButtonByText(testId, "Create")

            // Wait for all coroutines to complete
            advanceUntilIdle()

            // then - should not call createElection
            assertEquals(0, fakeClient.createElectionCalls.size, "Should not create election with empty name")
        }
    }
}
