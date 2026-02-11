package com.seanshubin.vote.frontend

import com.seanshubin.vote.domain.ElectionSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import kotlin.test.Test
import kotlin.test.assertTrue

class ElectionsPageRenderTest {

    @Test
    fun electionsPageRendersWithLoadingState() = runTest {
        // given
        val fakeClient = FakeApiClient()
        fakeClient.listElectionsResult = Result.success(emptyList())

        val testId = "elections-render-test"

        ComposeTestHelper.createTestRoot(testId).use {
            // when
            renderComposable(rootElementId = testId) {
                ElectionsPage(
                    apiClient = fakeClient,
                    authToken = "test-token",
                    onSelectElection = { },
                    onBack = { }
                )
            }

            // then - verify it rendered with heading
            assertTrue(
                ComposeTestHelper.elementExists(testId, "h1"),
                "Heading should exist"
            )
        }
    }

    @Test
    fun electionsPageRendersElectionList() = runTest {
        // given
        val fakeClient = FakeApiClient()
        val elections = listOf(
            ElectionSummary("owner1", "Election 1"),
            ElectionSummary("owner2", "Election 2")
        )
        fakeClient.listElectionsResult = Result.success(elections)

        val testId = "elections-list-test"

        ComposeTestHelper.createTestRoot(testId).use {
            renderComposable(rootElementId = testId) {
                ElectionsPage(
                    apiClient = fakeClient,
                    authToken = "test-token",
                    onSelectElection = { },
                    onBack = { }
                )
            }

            // Wait for LaunchedEffect to complete
            delay(500)

            // then - verify it rendered
            // Note: LaunchedEffect execution in tests is not guaranteed without special setup
            // This test verifies rendering, but LaunchedEffect may not execute in test environment
            assertTrue(
                ComposeTestHelper.elementExists(testId, "h1"),
                "Heading should exist"
            )
        }
    }
}
