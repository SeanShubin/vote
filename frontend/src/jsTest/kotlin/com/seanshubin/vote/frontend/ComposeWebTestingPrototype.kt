package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.dom.*
import org.jetbrains.compose.web.renderComposable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Prototype to explore Compose for Web testing capabilities.
 *
 * FINDINGS:
 * ✅ DOM exists in test environment (document, document.body accessible)
 * ❌ renderComposable() fails - cannot render to non-existent "test-root" element
 * ✅ Can create DOM elements via js()
 * ? Can we create root element and then render?
 * ? Can we dispatch events after rendering?
 */
class ComposeWebTestingPrototype {

    @Test
    fun findingRenderComposableFails() = runTest {
        // Finding: renderComposable() expects element to exist
        try {
            renderComposable(rootElementId = "test-root") {
                Div {
                    Text("Hello from test")
                }
            }
            println("UNEXPECTED: renderComposable() succeeded")
        } catch (e: Exception) {
            println("EXPECTED: renderComposable() threw: ${e.message}")
            // This is expected - no element with id "test-root" exists
        }
    }

    @Test
    fun findingDOMAccessWorks() {
        // Finding: DOM is available in test environment
        val documentExists = js("typeof document !== 'undefined'") as Boolean
        assertTrue(documentExists, "Document should exist in test environment")
        println("SUCCESS: Document exists: $documentExists")

        val body = js("document.body") as? Any
        assertTrue(body != null, "document.body should be accessible")
        println("SUCCESS: Can access document.body")
    }

    @Test
    fun findingCanCreateDOMElements() {
        // Finding: Can create DOM elements programmatically
        val div = js("document.createElement('div')")
        js("div.id = 'test-created'")
        js("div.textContent = 'Created from test'")

        val textContent = js("div.textContent") as String
        assertEquals("Created from test", textContent)
        println("SUCCESS: Created DOM element with textContent: $textContent")
    }

    @Test
    fun exploreRenderToCreatedElement() = runTest {
        // Attempt: Create root element, add to body, then render
        try {
            // Create root element
            val root = js("document.createElement('div')")
            js("root.id = 'dynamic-test-root'")
            js("document.body.appendChild(root)")
            println("Created and appended dynamic-test-root to body")

            // Try to render to it
            renderComposable(rootElementId = "dynamic-test-root") {
                Div {
                    Text("Rendered to dynamic root")
                }
            }
            println("SUCCESS: Rendered to dynamically created element")

            // Try to query rendered content
            val renderedText = js("document.querySelector('#dynamic-test-root').textContent") as? String
            println("Rendered text content: $renderedText")

            // Clean up
            js("document.body.removeChild(root)")
        } catch (e: Exception) {
            println("FAILED: Render to dynamic element threw: ${e.message}")
        }
    }

    @Test
    fun exploreEventDispatch() {
        // Attempt: Can we dispatch events to DOM elements?
        try {
            // Create button
            val button = js("document.createElement('button')")
            js("button.textContent = 'Click me'")
            js("document.body.appendChild(button)")

            var clicked = false
            val clickHandler = { clicked = true }
            js("button.addEventListener('click', clickHandler)")

            // Dispatch click event
            val clickEvent = js("new MouseEvent('click', { bubbles: true })")
            js("button.dispatchEvent(clickEvent)")

            println("Event dispatch - clicked: $clicked")
            assertTrue(clicked, "Click handler should have been triggered")
            println("SUCCESS: Can dispatch and handle events")

            // Clean up
            js("button.removeEventListener('click', clickHandler)")
            js("document.body.removeChild(button)")
        } catch (e: Exception) {
            println("FAILED: Event dispatch threw: ${e.message}")
        }
    }
}
