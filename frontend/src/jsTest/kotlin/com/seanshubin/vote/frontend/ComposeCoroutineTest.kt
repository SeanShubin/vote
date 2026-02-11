package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.renderComposable
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Test if rememberCoroutineScope() works in test environment.
 *
 * Hypothesis: The LoginPage tests fail because coroutines launched with
 * rememberCoroutineScope().launch {} don't execute in tests.
 */
class ComposeCoroutineTest {

    @Test
    fun rememberCoroutineScopeDoesNotWorkInTests() = runTest {
        val testId = "coroutine-test"
        val root = js("document.createElement('div')")
        js("root.id = 'coroutine-test'")
        js("document.body.appendChild(root)")

        var clickCount = 0
        var coroutineExecuted = false

        try {
            renderComposable(rootElementId = testId) {
                val scope = rememberCoroutineScope()

                Button({
                    onClick {
                        clickCount++
                        scope.launch {
                            delay(10)
                            coroutineExecuted = true
                        }
                    }
                    id("coroutine-button")
                }) {
                    Text("Click Me")
                }
            }

            // Click the button
            js("document.getElementById('coroutine-button').click()")
            delay(100) // Wait for coroutine to complete

            assertEquals(1, clickCount, "Click handler should execute")
            assertEquals(false, coroutineExecuted, "Coroutine launched with rememberCoroutineScope SHOULD NOT execute in tests")

        } finally {
            js("document.body.removeChild(root)")
        }
    }

    @Test
    fun directCoroutineLaunchWorks() = runTest {
        val testId = "direct-coroutine-test"
        val root = js("document.createElement('div')")
        js("root.id = 'direct-coroutine-test'")
        js("document.body.appendChild(root)")

        var clickCount = 0
        var coroutineExecuted = false

        try {
            renderComposable(rootElementId = testId) {
                Button({
                    onClick {
                        clickCount++
                        // Launch directly in the test coroutine context instead
                        GlobalScope.launch {
                            delay(10)
                            coroutineExecuted = true
                        }
                    }
                    id("direct-coroutine-button")
                }) {
                    Text("Click Me")
                }
            }

            // Click the button
            js("document.getElementById('direct-coroutine-button').click()")
            delay(100) // Wait for coroutine to complete

            assertEquals(1, clickCount, "Click handler should execute")
            // This might also not work - testing different coroutine scopes

        } finally {
            js("document.body.removeChild(root)")
        }
    }
}
