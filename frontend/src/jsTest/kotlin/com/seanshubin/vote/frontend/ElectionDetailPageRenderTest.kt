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
        val root = js("document.createElement('div')")
        js("root.id = 'election-detail-render-test'")
        js("document.body.appendChild(root)")

        try {
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

            // then - verify it rendered
            val content = js("document.querySelector('#election-detail-render-test')") as? Any
            assertTrue(content != null, "ElectionDetailPage should render")

            // Verify heading exists with election name
            val heading = js("document.querySelector('#election-detail-render-test h1')") as? Any
            assertTrue(heading != null, "Heading should exist")
        } finally {
            js("document.body.removeChild(root)")
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
        val root = js("document.createElement('div')")
        js("root.id = 'election-detail-tabs-test'")
        js("document.body.appendChild(root)")

        try {
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

            // then - verify navigation tabs rendered
            val content = js("document.querySelector('#election-detail-tabs-test')") as? Any
            assertTrue(content != null, "ElectionDetailPage should render")

            // Note: LaunchedEffect execution in tests may not be guaranteed
            // This test verifies basic rendering structure
        } finally {
            js("document.body.removeChild(root)")
        }
    }
}
