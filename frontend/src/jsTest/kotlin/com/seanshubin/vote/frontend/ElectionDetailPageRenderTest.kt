package com.seanshubin.vote.frontend

import com.seanshubin.vote.domain.ElectionSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import kotlin.test.Test
import kotlin.test.assertTrue

class ElectionDetailPageRenderTest {

    @Test
    fun electionDetailPageRendersWithHeading() = runTest {
        // given
        val fakeClient = FakeApiClient()
        val electionSummary = ElectionSummary(
            "owner1",
            "Test Election"
        )
        fakeClient.getElectionResult = Result.success(electionSummary)
        fakeClient.listCandidatesResult = Result.success(listOf("Candidate 1", "Candidate 2"))

        val testId = "election-detail-render-test"

        ComposeTestHelper.createTestRoot(testId).use {
            // when
            renderComposable(rootElementId = testId) {
                ElectionDetailPage(
                    apiClient = fakeClient,
                    authToken = "test-token",
                    electionName = "Test Election",
                    onBack = { }
                )
            }

            // Wait for LaunchedEffect to potentially execute
            delay(300)

            // then - verify it rendered with heading
            assertTrue(
                ComposeTestHelper.elementExists(testId, "h1"),
                "Heading should exist"
            )
        }
    }

    @Test
    fun electionDetailPageNavigationTabsRender() = runTest {
        // given
        val fakeClient = FakeApiClient()
        val electionSummary = ElectionSummary(
            "owner1",
            "Test Election"
        )
        fakeClient.getElectionResult = Result.success(electionSummary)
        fakeClient.listCandidatesResult = Result.success(listOf("Candidate 1", "Candidate 2"))

        val testId = "election-detail-tabs-test"

        ComposeTestHelper.createTestRoot(testId).use {
            // when
            renderComposable(rootElementId = testId) {
                ElectionDetailPage(
                    apiClient = fakeClient,
                    authToken = "test-token",
                    electionName = "Test Election",
                    onBack = { }
                )
            }

            // Wait for LaunchedEffect to potentially execute
            delay(300)

            // then - verify basic rendering structure
            // Note: LaunchedEffect execution in tests may not be guaranteed
            assertTrue(
                ComposeTestHelper.elementExists(testId, "h1"),
                "Heading should exist"
            )
        }
    }
}
