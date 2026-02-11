package com.seanshubin.vote.frontend

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.renderComposable
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Investigation: Why do buttons work but inputs don't?
 *
 * Hypothesis: button.click() triggers Compose's onClick, but
 * dispatching 'input' or 'keydown' events doesn't trigger Compose's onInput/onKeyDown.
 */
class ComposeInputEventsTest {

    @Test
    fun buttonClickActuallyWorks() = runTest {
        val testId = "button-test"
        val root = js("document.createElement('div')")
        js("root.id = 'button-test'")
        js("document.body.appendChild(root)")

        var clickCount = 0

        try {
            renderComposable(rootElementId = testId) {
                Button({
                    onClick { clickCount++ }
                    id("test-button")
                }) {
                    Text("Click Me")
                }
            }

            // Call .click() on the button
            js("document.getElementById('test-button').click()")
            delay(50)

            assertEquals(1, clickCount, "Button .click() SHOULD trigger Compose onClick")

            // Try dispatchEvent too
            js("document.getElementById('test-button').dispatchEvent(new MouseEvent('click', { bubbles: true }))")
            delay(50)

            assertEquals(2, clickCount, "Button dispatchEvent SHOULD also trigger Compose onClick")

        } finally {
            js("document.body.removeChild(root)")
        }
    }

    @Test
    fun inputEventDoesNotWork() = runTest {
        val testId = "input-test"
        val root = js("document.createElement('div')")
        js("root.id = 'input-test'")
        js("document.body.appendChild(root)")

        var inputValue = ""
        var inputEventCount = 0

        try {
            renderComposable(rootElementId = testId) {
                Input(InputType.Text) {
                    id("test-input")
                    value(inputValue)
                    onInput {
                        inputValue = it.value
                        inputEventCount++
                    }
                }
            }

            // Set value and dispatch input event
            js("document.getElementById('test-input').value = 'hello'")
            js("document.getElementById('test-input').dispatchEvent(new Event('input', { bubbles: true }))")
            delay(50)

            assertEquals(0, inputEventCount, "Input dispatchEvent should NOT trigger Compose onInput")
            assertEquals("", inputValue, "Compose state should NOT be updated by DOM event")

            // Verify DOM value changed but Compose state didn't
            val domValue = js("document.getElementById('test-input').value") as String
            assertEquals("hello", domValue, "DOM value SHOULD be 'hello'")
            assertEquals("", inputValue, "But Compose state SHOULD still be empty")

        } finally {
            js("document.body.removeChild(root)")
        }
    }

    @Test
    fun keyboardEventDoesNotWork() = runTest {
        val testId = "keyboard-test"
        val root = js("document.createElement('div')")
        js("root.id = 'keyboard-test'")
        js("document.body.appendChild(root)")

        var keyDownCount = 0
        var lastKey = ""

        try {
            renderComposable(rootElementId = testId) {
                Input(InputType.Text) {
                    id("test-keyboard")
                    onKeyDown {
                        keyDownCount++
                        lastKey = it.key
                    }
                }
            }

            // Dispatch keyboard event
            js("document.getElementById('test-keyboard').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))")
            delay(50)

            assertEquals(0, keyDownCount, "KeyboardEvent dispatchEvent should NOT trigger Compose onKeyDown")
            assertEquals("", lastKey, "Compose should NOT capture the key")

        } finally {
            js("document.body.removeChild(root)")
        }
    }
}
