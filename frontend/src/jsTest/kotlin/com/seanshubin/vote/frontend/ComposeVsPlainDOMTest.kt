package com.seanshubin.vote.frontend

import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.renderComposable
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Critical test: Compare Compose button vs Plain DOM button event handling.
 *
 * This test reveals the fundamental difference between Compose for Web's event system
 * and plain DOM event handling.
 */
class ComposeVsPlainDOMTest {

    @Test
    fun composeButtonVsPlainDOMButton() = runTest {
        val testId = "comparison-test"
        val root = js("document.createElement('div')")
        js("root.id = 'comparison-test'")
        js("document.body.appendChild(root)")

        var composeClickCount = 0
        var plainClickCount = 0

        try {
            // Create Compose button
            renderComposable(rootElementId = testId) {
                Button({
                    onClick { composeClickCount++ }
                    id("compose-button")
                }) {
                    Text("Compose Button")
                }
            }

            // Create plain DOM button with addEventListener
            js("""
                var plainButton = document.createElement('button');
                plainButton.id = 'plain-button';
                plainButton.textContent = 'Plain Button';
                document.getElementById('comparison-test').appendChild(plainButton);
            """)

            val plainClickHandler = { plainClickCount++ }
            js("document.getElementById('plain-button').addEventListener('click', plainClickHandler)")

            // TEST 1: Use .click() method
            js("document.getElementById('compose-button').click()")
            js("document.getElementById('plain-button').click()")

            assertEquals(0, composeClickCount, "Compose button: .click() should NOT trigger onClick handler")
            assertEquals(1, plainClickCount, "Plain DOM button: .click() SHOULD trigger addEventListener handler")

            // TEST 2: Dispatch MouseEvent
            js("document.getElementById('compose-button').dispatchEvent(new MouseEvent('click', { bubbles: true }))")
            js("document.getElementById('plain-button').dispatchEvent(new MouseEvent('click', { bubbles: true }))")

            assertEquals(0, composeClickCount, "Compose button: dispatchEvent SHOULD NOT trigger onClick handler")
            assertEquals(2, plainClickCount, "Plain DOM button: dispatchEvent SHOULD trigger addEventListener handler")

            // CONCLUSION: Compose's onClick is NOT connected to DOM's click events

        } finally {
            js("document.body.removeChild(root)")
        }
    }
}
