package com.seanshubin.vote.frontend

import kotlinx.coroutines.delay
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
        val root = js("document.createElement('div')")
        js("root.id = 'create-election-render-test'")
        js("document.body.appendChild(root)")

        var electionCreatedCalled = false

        try {
            // when
            renderComposable(rootElementId = testId) {
                CreateElectionPage(
                    apiClient = fakeClient,
                    authToken = "test-token",
                    onElectionCreated = { electionCreatedCalled = true },
                    onBack = { }
                )
            }

            // then - verify it rendered
            val content = js("document.querySelector('#create-election-render-test')") as? Any
            assertTrue(content != null, "CreateElectionPage should render")

            // Verify input field exists
            val electionNameInput = js("document.querySelector('#create-election-render-test input[type=\"text\"]')") as? Any
            assertTrue(electionNameInput != null, "Election name input should exist")
        } finally {
            js("document.body.removeChild(root)")
        }
    }

    @Test
    fun createElectionButtonClickCreatesElection() = runTest {
        // given
        val fakeClient = FakeApiClient()
        fakeClient.createElectionResult = Result.success("Test Election")

        val testId = "create-election-button-test"
        val root = js("document.createElement('div')")
        js("root.id = 'create-election-button-test'")
        js("document.body.appendChild(root)")

        var capturedElectionName: String? = null

        try {
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
            js("""
                var input = document.querySelector('#create-election-button-test input[type="text"]')
                input.value = 'Test Election'
                input.dispatchEvent(new Event('input', { bubbles: true }))
            """)
            delay(100)

            js("""
                var buttons = document.querySelectorAll('#create-election-button-test button')
                if (buttons && buttons.length > 0) {
                    buttons[0].click()
                }
            """)
            delay(200)

            // then
            assertEquals(1, fakeClient.createElectionCalls.size, "Expected 1 createElection call but got ${fakeClient.createElectionCalls.size}")
            assertEquals("test-token", fakeClient.createElectionCalls[0].authToken)
            assertEquals("Test Election", fakeClient.createElectionCalls[0].electionName)
            assertEquals("Test Election", capturedElectionName)
        } finally {
            js("document.body.removeChild(root)")
        }
    }

    @Test
    fun createElectionDoesNotSubmitWithEmptyName() = runTest {
        // given
        val fakeClient = FakeApiClient()
        fakeClient.createElectionResult = Result.success("Test Election")

        val testId = "create-election-empty-test"
        val root = js("document.createElement('div')")
        js("root.id = 'create-election-empty-test'")
        js("document.body.appendChild(root)")

        try {
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
            js("""
                var buttons = document.querySelectorAll('#create-election-empty-test button')
                if (buttons && buttons.length > 0) {
                    buttons[0].click()
                }
            """)
            delay(200)

            // then - should not call createElection
            assertEquals(0, fakeClient.createElectionCalls.size, "Should not create election with empty name")
        } finally {
            js("document.body.removeChild(root)")
        }
    }
}
