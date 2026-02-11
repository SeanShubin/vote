package com.seanshubin.vote.frontend

import com.seanshubin.vote.contract.AccessToken
import com.seanshubin.vote.contract.RefreshToken
import com.seanshubin.vote.contract.Tokens
import com.seanshubin.vote.domain.Role
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Testing LoginPage by rendering and simulating interactions.
 *
 * Pattern demonstrated:
 * 1. Create root element dynamically
 * 2. Render composable with fake dependencies
 * 3. Query rendered DOM
 * 4. Simulate user interactions (typing, clicking, keyboard events)
 * 5. Verify state changes and API calls
 *
 * This follows the React @testing-library pattern with createTester factory.
 */
class LoginPageRenderTest {

    @Test
    fun loginPageRendersWithUsernameAndPasswordFields() = runTest {
        // given
        val fakeClient = FakeApiClient()
        val testId = "login-render-test"
        val root = js("document.createElement('div')")
        js("root.id = 'login-render-test'")
        js("document.body.appendChild(root)")

        var loginSuccessCalled = false

        try {
            // when
            renderComposable(rootElementId = testId) {
                LoginPage(
                    apiClient = fakeClient,
                    onLoginSuccess = { _, _ -> loginSuccessCalled = true },
                    onNavigateToRegister = { }
                )
            }

            // then - verify it rendered
            val content = js("document.querySelector('#login-render-test')") as? Any
            assertTrue(content != null, "LoginPage should render")

            // Verify input fields exist
            val usernameInput = js("document.querySelector('#login-render-test input[type=\"text\"]')") as? Any
            val passwordInput = js("document.querySelector('#login-render-test input[type=\"password\"]')") as? Any
            assertTrue(usernameInput != null, "Username input should exist")
            assertTrue(passwordInput != null, "Password input should exist")
        } finally {
            js("document.body.removeChild(root)")
        }
    }

    @Test
    fun loginPageInputsCanBeSet() = runTest {
        // Diagnostic test: can we set input values and read them back?
        val fakeClient = FakeApiClient()
        val testId = "login-diagnostic-test"
        val root = js("document.createElement('div')")
        js("root.id = 'login-diagnostic-test'")
        js("document.body.appendChild(root)")

        try {
            renderComposable(rootElementId = testId) {
                LoginPage(
                    apiClient = fakeClient,
                    onLoginSuccess = { _, _ -> },
                    onNavigateToRegister = { }
                )
            }

            // Try to set input values
            js("""
                var usernameInput = document.querySelector('#login-diagnostic-test input[type="text"]')
                usernameInput.value = 'testuser'
            """)

            // Read back the value
            val readValue = js("""
                var usernameInput = document.querySelector('#login-diagnostic-test input[type="text"]')
                usernameInput.value
            """) as? String

            println("DEBUG: Input value after setting: $readValue")
            assertEquals("testuser", readValue, "Input value should be readable")
        } finally {
            js("document.body.removeChild(root)")
        }
    }

    // UPDATE: Experimental tests show Compose DOES respond to DOM events!
    // Re-enabling tests to see why they originally failed.

    @Test
    fun loginPageEnterKeyInPasswordFieldTriggersAuthentication() = runTest {
        // given
        val fakeClient = FakeApiClient()
        val expectedTokens = Tokens(
            AccessToken("alice", Role.USER),
            RefreshToken("alice")
        )
        fakeClient.authenticateResult = Result.success(expectedTokens)

        val testId = "login-enter-password-test"
        val root = js("document.createElement('div')")
        js("root.id = 'login-enter-password-test'")
        js("document.body.appendChild(root)")

        var loginSuccessCalled = false

        try {
            renderComposable(rootElementId = testId) {
                LoginPage(
                    apiClient = fakeClient,
                    onLoginSuccess = { _, _ -> loginSuccessCalled = true },
                    onNavigateToRegister = { }
                )
            }

            // when - enter username and password, then press Enter in password field
            js("""
                var usernameInput = document.querySelector('#login-enter-password-test input[type="text"]')
                usernameInput.value = 'alice'
                usernameInput.dispatchEvent(new Event('input', { bubbles: true }))
            """)
            delay(100)

            js("""
                var passwordInput = document.querySelector('#login-enter-password-test input[type="password"]')
                passwordInput.value = 'password123'
                passwordInput.dispatchEvent(new Event('input', { bubbles: true }))
            """)
            delay(100)

            js("""
                var passwordInput = document.querySelector('#login-enter-password-test input[type="password"]')
                var event = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })
                passwordInput.dispatchEvent(event)
            """)
            delay(200)

            // then
            assertEquals(1, fakeClient.authenticateCalls.size, "Expected 1 authenticate call but got ${fakeClient.authenticateCalls.size}")
            assertEquals("alice", fakeClient.authenticateCalls[0].userName)
            assertEquals("password123", fakeClient.authenticateCalls[0].password)
        } finally {
            js("document.body.removeChild(root)")
        }
    }

    @Test
    fun loginPageEnterKeyInUsernameFieldTriggersAuthentication() = runTest {
        // given
        val fakeClient = FakeApiClient()
        val expectedTokens = Tokens(
            AccessToken("bob", Role.USER),
            RefreshToken("bob")
        )
        fakeClient.authenticateResult = Result.success(expectedTokens)

        val testId = "login-enter-username-test"
        val root = js("document.createElement('div')")
        js("root.id = 'login-enter-username-test'")
        js("document.body.appendChild(root)")

        var loginSuccessCalled = false

        try {
            renderComposable(rootElementId = testId) {
                LoginPage(
                    apiClient = fakeClient,
                    onLoginSuccess = { _, _ -> loginSuccessCalled = true },
                    onNavigateToRegister = { }
                )
            }

            // when - enter username and password, then press Enter in username field
            js("""
                var usernameInput = document.querySelector('#login-enter-username-test input[type="text"]')
                usernameInput.value = 'bob'
                usernameInput.dispatchEvent(new Event('input', { bubbles: true }))
            """)
            delay(100)

            js("""
                var passwordInput = document.querySelector('#login-enter-username-test input[type="password"]')
                passwordInput.value = 'securepass'
                passwordInput.dispatchEvent(new Event('input', { bubbles: true }))
            """)
            delay(100)

            js("""
                var usernameInput = document.querySelector('#login-enter-username-test input[type="text"]')
                var event = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })
                usernameInput.dispatchEvent(event)
            """)
            delay(200)

            // then
            assertEquals(1, fakeClient.authenticateCalls.size, "Expected 1 authenticate call but got ${fakeClient.authenticateCalls.size}")
            assertEquals("bob", fakeClient.authenticateCalls[0].userName)
            assertEquals("securepass", fakeClient.authenticateCalls[0].password)
        } finally {
            js("document.body.removeChild(root)")
        }
    }

    @Test
    fun loginButtonClickTriggersAuthentication() = runTest {
        // given
        val fakeClient = FakeApiClient()
        val expectedTokens = Tokens(
            AccessToken("charlie", Role.USER),
            RefreshToken("charlie")
        )
        fakeClient.authenticateResult = Result.success(expectedTokens)

        val testId = "login-button-click-test"
        val root = js("document.createElement('div')")
        js("root.id = 'login-button-click-test'")
        js("document.body.appendChild(root)")

        var loginSuccessCalled = false

        try {
            renderComposable(rootElementId = testId) {
                LoginPage(
                    apiClient = fakeClient,
                    onLoginSuccess = { _, _ -> loginSuccessCalled = true },
                    onNavigateToRegister = { }
                )
            }

            // when - enter username and password, then click login button
            js("""
                var usernameInput = document.querySelector('#login-button-click-test input[type="text"]')
                usernameInput.value = 'charlie'
                usernameInput.dispatchEvent(new Event('input', { bubbles: true }))
            """)
            delay(100)

            js("""
                var passwordInput = document.querySelector('#login-button-click-test input[type="password"]')
                passwordInput.value = 'mypassword'
                passwordInput.dispatchEvent(new Event('input', { bubbles: true }))
            """)
            delay(100)

            js("""
                var buttons = document.querySelectorAll('#login-button-click-test button')
                if (buttons && buttons.length > 0) {
                    buttons[0].click()
                }
            """)
            delay(200)

            // then
            assertEquals(1, fakeClient.authenticateCalls.size, "Expected 1 authenticate call but got ${fakeClient.authenticateCalls.size}")
            assertEquals("charlie", fakeClient.authenticateCalls[0].userName)
            assertEquals("mypassword", fakeClient.authenticateCalls[0].password)
        } finally {
            js("document.body.removeChild(root)")
        }
    }

    @Test
    fun loginSuccessCallbackInvokedWithCorrectToken() = runTest {
        // given
        val fakeClient = FakeApiClient()
        val expectedTokens = Tokens(
            AccessToken("dave", Role.OWNER),
            RefreshToken("dave")
        )
        fakeClient.authenticateResult = Result.success(expectedTokens)

        val testId = "login-callback-test"
        val root = js("document.createElement('div')")
        js("root.id = 'login-callback-test'")
        js("document.body.appendChild(root)")

        var loginSuccessCalled = false
        var capturedToken: String? = null
        var capturedUserName: String? = null

        try {
            renderComposable(rootElementId = testId) {
                LoginPage(
                    apiClient = fakeClient,
                    onLoginSuccess = { token, userName ->
                        loginSuccessCalled = true
                        capturedToken = token
                        capturedUserName = userName
                    },
                    onNavigateToRegister = { }
                )
            }

            // when - enter username and password, then click login button
            js("""
                var usernameInput = document.querySelector('#login-callback-test input[type="text"]')
                usernameInput.value = 'dave'
                usernameInput.dispatchEvent(new Event('input', { bubbles: true }))
            """)
            delay(100)

            js("""
                var passwordInput = document.querySelector('#login-callback-test input[type="password"]')
                passwordInput.value = 'password'
                passwordInput.dispatchEvent(new Event('input', { bubbles: true }))
            """)
            delay(100)

            js("""
                var buttons = document.querySelectorAll('#login-callback-test button')
                if (buttons && buttons.length > 0) {
                    buttons[0].click()
                }
            """)
            delay(200)

            // then
            assertTrue(loginSuccessCalled, "Login success callback should be invoked")
            assertEquals("dave", capturedUserName)
            assertNotNull(capturedToken)
        } finally {
            js("document.body.removeChild(root)")
        }
    }
}
