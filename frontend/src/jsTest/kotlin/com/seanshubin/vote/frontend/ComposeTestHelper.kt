package com.seanshubin.vote.frontend

import kotlinx.coroutines.test.TestScope

/**
 * Test helper utilities that encapsulate JavaScript DOM interactions for testing Compose for Web.
 *
 * These helpers eliminate the need to write raw JavaScript in tests, making them:
 * - Easier to write (pure Kotlin, no JS syntax to remember)
 * - Easier to read (intent is clear from function names)
 * - Easier to maintain (JavaScript details centralized here)
 * - Type-safe (Kotlin compiler validates parameters)
 *
 * Note: These helpers do NOT include delays. Tests should use TestScope.advanceUntilIdle()
 * after actions that trigger coroutines to wait for all async work to complete.
 */
object ComposeTestHelper {

    /**
     * Sets an input value by finding it via placeholder text and dispatches the input event.
     *
     * Note: This is synchronous - it updates the DOM immediately. If the input event triggers
     * a coroutine, the test should call advanceUntilIdle() afterward.
     *
     * @param containerId The ID of the container element (test root)
     * @param placeholder The placeholder text that identifies the input (e.g., "Username", "Password")
     * @param value The value to set
     */
    fun setInputByPlaceholder(
        containerId: String,
        placeholder: String,
        value: String
    ) {
        val setInputFunction = js("""
            (function(containerId, placeholder, value) {
                var input = document.querySelector('#' + containerId + ' input[placeholder="' + placeholder + '"]');
                input.value = value;
                input.dispatchEvent(new Event('input', { bubbles: true }));
            })
        """)
        setInputFunction(containerId, placeholder, value)
    }

    /**
     * Clicks a button by finding it via visible text.
     *
     * Note: This is synchronous - it clicks the button immediately. If the button's onClick
     * handler launches a coroutine, the test should call advanceUntilIdle() afterward.
     *
     * @param containerId The ID of the container element (test root)
     * @param buttonText The visible text on the button (e.g., "Login", "Register", "Create")
     */
    fun clickButtonByText(
        containerId: String,
        buttonText: String
    ) {
        val clickButtonFunction = js("""
            (function(containerId, buttonText) {
                var buttons = Array.from(document.querySelectorAll('#' + containerId + ' button'));
                var button = buttons.find(function(btn) { return btn.textContent.trim() === buttonText; });
                if (button) button.click();
            })
        """)
        clickButtonFunction(containerId, buttonText)
    }

    /**
     * Presses the Enter key in an input field found by placeholder.
     *
     * Note: This is synchronous - it dispatches the keyboard event immediately. If the keydown
     * handler launches a coroutine, the test should call advanceUntilIdle() afterward.
     *
     * @param containerId The ID of the container element (test root)
     * @param placeholder The placeholder text that identifies the input (e.g., "Username", "Password")
     */
    fun pressEnterInInput(
        containerId: String,
        placeholder: String
    ) {
        val pressEnterFunction = js("""
            (function(containerId, placeholder) {
                var input = document.querySelector('#' + containerId + ' input[placeholder="' + placeholder + '"]');
                var event = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true });
                input.dispatchEvent(event);
            })
        """)
        pressEnterFunction(containerId, placeholder)
    }

    /**
     * Verifies that an element exists by querying for it.
     *
     * @param containerId The ID of the container element (test root)
     * @param selector The CSS selector to find the element
     * @return true if element exists, false otherwise
     */
    fun elementExists(containerId: String, selector: String): Boolean {
        val queryFunction = js("""
            (function(containerId, selector) {
                return document.querySelector('#' + containerId + ' ' + selector);
            })
        """)
        return queryFunction(containerId, selector) != null
    }

    /**
     * Verifies that an input with a specific placeholder exists.
     *
     * @param containerId The ID of the container element (test root)
     * @param placeholder The placeholder text to look for
     * @return true if input exists, false otherwise
     */
    fun inputExistsByPlaceholder(containerId: String, placeholder: String): Boolean {
        val queryFunction = js("""
            (function(containerId, placeholder) {
                return document.querySelector('#' + containerId + ' input[placeholder="' + placeholder + '"]');
            })
        """)
        return queryFunction(containerId, placeholder) != null
    }

    /**
     * Creates a test root element and returns an object to manage cleanup.
     * Use with Kotlin's `use {}` block for automatic cleanup:
     *
     * ```
     * ComposeTestHelper.createTestRoot("my-test-id").use {
     *     // Test code here
     * } // Automatically cleans up
     * ```
     *
     * @param testId The ID for the test root element
     * @return A TestRoot that will clean up the DOM element when closed
     */
    fun createTestRoot(testId: String): TestRoot {
        val createRootFunction = js("""
            (function(testId) {
                var root = document.createElement('div');
                root.id = testId;
                document.body.appendChild(root);
                return root;
            })
        """)
        createRootFunction(testId)
        return TestRoot(testId)
    }

    /**
     * Manages the lifecycle of a test root DOM element.
     * Automatically removes the element from the document when closed.
     */
    class TestRoot(private val testId: String) : AutoCloseable {
        override fun close() {
            val removeRootFunction = js("""
                (function(testId) {
                    var element = document.querySelector('#' + testId);
                    if (element) document.body.removeChild(element);
                })
            """)
            removeRootFunction(testId)
        }
    }
}

/**
 * Extension function to simplify advancing test coroutines to idle.
 * Use this after triggering async operations in tests to wait for all coroutines to complete.
 */
fun TestScope.advanceUntilIdle() {
    testScheduler.advanceUntilIdle()
}
