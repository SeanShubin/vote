package com.seanshubin.vote.frontend

import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.renderComposable
import kotlin.test.Test

/**
 * Deep investigation into how Compose for Web's event system actually works.
 *
 * Goal: Understand exactly why dispatching DOM events doesn't trigger Compose handlers.
 */
class ComposeEventSystemInvestigation {

    @Test
    fun investigateWhatEventListenersComposeAttaches() = runTest {
        val testId = "event-investigation"
        val root = js("document.createElement('div')")
        js("root.id = 'event-investigation'")
        js("document.body.appendChild(root)")

        var clickCount = 0
        var inputValue = ""

        try {
            renderComposable(rootElementId = testId) {
                Button({
                    onClick { clickCount++ }
                }) {
                    Text("Test Button")
                }

                Input(InputType.Text) {
                    onInput { inputValue = it.value }
                }
            }

            // Now inspect what event listeners are actually attached
            val buttonListenersInfo = js("""
                (function() {
                    var button = document.querySelector('#event-investigation button');
                    if (!button) return 'Button not found';

                    var info = {
                        tagName: button.tagName,
                        hasOnClick: button.onclick !== null,
                        hasClickAttribute: button.hasAttribute('onclick'),
                        className: button.className,
                        dataAttrs: Array.from(button.attributes).filter(function(a) { return a.name.startsWith('data-'); })
                            .map(function(a) { return a.name + '=' + a.value; })
                    };

                    return JSON.stringify(info);
                })()
            """) as String

            // Use console.log which should appear in browser test output
            js("console.log('=== BUTTON ANALYSIS ===')")
            js("console.log(buttonListenersInfo)")

            val inputListenersInfo = js("""
                (function() {
                    var input = document.querySelector('#event-investigation input');
                    if (!input) return 'Input not found';

                    var info = {
                        tagName: input.tagName,
                        type: input.type,
                        hasOnInput: input.oninput !== null,
                        hasInputAttribute: input.hasAttribute('oninput'),
                        className: input.className,
                        dataAttrs: Array.from(input.attributes).filter(function(a) { return a.name.startsWith('data-'); })
                            .map(function(a) { return a.name + '=' + a.value; })
                    };

                    return JSON.stringify(info);
                })()
            """) as String

            println("=== INPUT ANALYSIS ===")
            println(inputListenersInfo)

            // Check if there's a parent element that might have delegated listeners
            val rootAnalysis = js("""
                (function() {
                    var root = document.querySelector('#event-investigation');
                    var info = {
                        children: root.children.length,
                        hasOnClick: root.onclick !== null,
                        hasOnInput: root.oninput !== null,
                        dataAttrs: Array.from(root.attributes).filter(function(a) { return a.name.startsWith('data-'); })
                            .map(function(a) { return a.name + '=' + a.value; })
                    };
                    return JSON.stringify(info);
                })()
            """) as String

            println("=== ROOT CONTAINER ANALYSIS ===")
            println(rootAnalysis)

            // Now try dispatching events and see what happens
            println("\n=== TESTING EVENT DISPATCH ===")
            println("Before: clickCount=$clickCount, inputValue='$inputValue'")

            js("document.querySelector('#event-investigation button').click()")
            println("After button.click(): clickCount=$clickCount")

            js("""
                var input = document.querySelector('#event-investigation input');
                input.value = 'test';
                input.dispatchEvent(new Event('input', { bubbles: true }));
            """)
            println("After input dispatch: inputValue='$inputValue'")

        } finally {
            js("document.body.removeChild(root)")
        }
    }

    @Test
    fun compareComposeVsPlainDOMEventHandling() = runTest {
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

            println("=== BEFORE CLICKS ===")
            println("Compose: $composeClickCount, Plain: $plainClickCount")

            // Click both buttons
            js("document.getElementById('compose-button').click()")
            js("document.getElementById('plain-button').click()")

            println("=== AFTER CLICKS ===")
            println("Compose: $composeClickCount, Plain: $plainClickCount")

            // Try dispatching events instead of .click()
            js("document.getElementById('compose-button').dispatchEvent(new MouseEvent('click', { bubbles: true }))")
            js("document.getElementById('plain-button').dispatchEvent(new MouseEvent('click', { bubbles: true }))")

            println("=== AFTER dispatchEvent ===")
            println("Compose: $composeClickCount, Plain: $plainClickCount")

        } finally {
            js("document.body.removeChild(root)")
        }
    }

    @Test
    fun investigateComposeRuntimeAccess() = runTest {
        // Can we access Compose runtime state?
        val testId = "runtime-test"
        val root = js("document.createElement('div')")
        js("root.id = 'runtime-test'")
        js("document.body.appendChild(root)")

        var stateValue = "initial"

        try {
            renderComposable(rootElementId = testId) {
                Input(InputType.Text) {
                    value(stateValue)
                    onInput { stateValue = it.value }
                    id("runtime-input")
                }
            }

            println("=== COMPOSE STATE INVESTIGATION ===")
            println("Kotlin state value: '$stateValue'")

            // Check DOM value
            val domValue = js("document.getElementById('runtime-input').value") as String
            println("DOM input value: '$domValue'")

            // Set DOM value directly
            js("document.getElementById('runtime-input').value = 'changed-in-dom'")
            val newDomValue = js("document.getElementById('runtime-input').value") as String
            println("After DOM change, DOM value: '$newDomValue'")
            println("After DOM change, Kotlin state: '$stateValue'")

            // Try to trigger Compose recomposition somehow
            js("document.getElementById('runtime-input').dispatchEvent(new Event('input', { bubbles: true }))")
            println("After input event, Kotlin state: '$stateValue'")

        } finally {
            js("document.body.removeChild(root)")
        }
    }
}
