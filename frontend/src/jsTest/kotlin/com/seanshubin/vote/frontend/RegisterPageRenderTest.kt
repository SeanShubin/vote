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

class RegisterPageRenderTest {

    @Test
    fun registerPageRendersWithUsernameEmailAndPasswordFields() = runTest {
        // given
        val fakeClient = FakeApiClient()
        val testId = "register-render-test"
        val root = js("document.createElement('div')")
        js("root.id = 'register-render-test'")
        js("document.body.appendChild(root)")

        var registrationSuccessCalled = false

        try {
            // when
            renderComposable(rootElementId = testId) {
                RegisterPage(
                    apiClient = fakeClient,
                    onLoginSuccess = { _, _ -> registrationSuccessCalled = true },
                    onNavigateToLogin = { }
                )
            }

            // then - verify it rendered
            val content = js("document.querySelector('#register-render-test')") as? Any
            assertTrue(content != null, "RegisterPage should render")

            // Verify input fields exist
            val usernameInput = js("document.querySelector('#register-render-test input[type=\"text\"]')") as? Any
            val emailInput = js("document.querySelector('#register-render-test input[type=\"email\"]')") as? Any
            val passwordInput = js("document.querySelector('#register-render-test input[type=\"password\"]')") as? Any
            assertTrue(usernameInput != null, "Username input should exist")
            assertTrue(emailInput != null, "Email input should exist")
            assertTrue(passwordInput != null, "Password input should exist")
        } finally {
            js("document.body.removeChild(root)")
        }
    }

    @Test
    fun registerPageEnterKeyInPasswordFieldTriggersRegistration() = runTest {
        // given
        val fakeClient = FakeApiClient()
        val expectedTokens = Tokens(
            AccessToken("alice", Role.USER),
            RefreshToken("alice")
        )
        fakeClient.registerResult = Result.success(expectedTokens)

        val testId = "register-enter-password-test"
        val root = js("document.createElement('div')")
        js("root.id = 'register-enter-password-test'")
        js("document.body.appendChild(root)")

        var registrationSuccessCalled = false

        try {
            renderComposable(rootElementId = testId) {
                RegisterPage(
                    apiClient = fakeClient,
                    onLoginSuccess = { _, _ -> registrationSuccessCalled = true },
                    onNavigateToLogin = { },
                    coroutineScope = this@runTest
                )
            }

            // when - enter username, email, and password, then press Enter in password field
            js("""
                var usernameInput = document.querySelector('#register-enter-password-test input[type="text"]')
                usernameInput.value = 'alice'
                usernameInput.dispatchEvent(new Event('input', { bubbles: true }))
            """)
            delay(100)

            js("""
                var emailInput = document.querySelector('#register-enter-password-test input[type="email"]')
                emailInput.value = 'alice@example.com'
                emailInput.dispatchEvent(new Event('input', { bubbles: true }))
            """)
            delay(100)

            js("""
                var passwordInput = document.querySelector('#register-enter-password-test input[type="password"]')
                passwordInput.value = 'password123'
                passwordInput.dispatchEvent(new Event('input', { bubbles: true }))
            """)
            delay(100)

            js("""
                var passwordInput = document.querySelector('#register-enter-password-test input[type="password"]')
                var event = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })
                passwordInput.dispatchEvent(event)
            """)
            delay(200)

            // then
            assertEquals(1, fakeClient.registerCalls.size, "Expected 1 register call but got ${fakeClient.registerCalls.size}")
            assertEquals("alice", fakeClient.registerCalls[0].userName)
            assertEquals("alice@example.com", fakeClient.registerCalls[0].email)
            assertEquals("password123", fakeClient.registerCalls[0].password)
        } finally {
            js("document.body.removeChild(root)")
        }
    }

    @Test
    fun registerPageEnterKeyInEmailFieldTriggersRegistration() = runTest {
        // given
        val fakeClient = FakeApiClient()
        val expectedTokens = Tokens(
            AccessToken("bob", Role.USER),
            RefreshToken("bob")
        )
        fakeClient.registerResult = Result.success(expectedTokens)

        val testId = "register-enter-email-test"
        val root = js("document.createElement('div')")
        js("root.id = 'register-enter-email-test'")
        js("document.body.appendChild(root)")

        var registrationSuccessCalled = false

        try {
            renderComposable(rootElementId = testId) {
                RegisterPage(
                    apiClient = fakeClient,
                    onLoginSuccess = { _, _ -> registrationSuccessCalled = true },
                    onNavigateToLogin = { },
                    coroutineScope = this@runTest
                )
            }

            // when - enter username, email, and password, then press Enter in email field
            js("""
                var usernameInput = document.querySelector('#register-enter-email-test input[type="text"]')
                usernameInput.value = 'bob'
                usernameInput.dispatchEvent(new Event('input', { bubbles: true }))
            """)
            delay(100)

            js("""
                var emailInput = document.querySelector('#register-enter-email-test input[type="email"]')
                emailInput.value = 'bob@example.com'
                emailInput.dispatchEvent(new Event('input', { bubbles: true }))
            """)
            delay(100)

            js("""
                var passwordInput = document.querySelector('#register-enter-email-test input[type="password"]')
                passwordInput.value = 'securepass'
                passwordInput.dispatchEvent(new Event('input', { bubbles: true }))
            """)
            delay(100)

            js("""
                var emailInput = document.querySelector('#register-enter-email-test input[type="email"]')
                var event = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })
                emailInput.dispatchEvent(event)
            """)
            delay(200)

            // then
            assertEquals(1, fakeClient.registerCalls.size, "Expected 1 register call but got ${fakeClient.registerCalls.size}")
            assertEquals("bob", fakeClient.registerCalls[0].userName)
            assertEquals("bob@example.com", fakeClient.registerCalls[0].email)
            assertEquals("securepass", fakeClient.registerCalls[0].password)
        } finally {
            js("document.body.removeChild(root)")
        }
    }

    @Test
    fun registerButtonClickTriggersRegistration() = runTest {
        // given
        val fakeClient = FakeApiClient()
        val expectedTokens = Tokens(
            AccessToken("charlie", Role.USER),
            RefreshToken("charlie")
        )
        fakeClient.registerResult = Result.success(expectedTokens)

        val testId = "register-button-click-test"
        val root = js("document.createElement('div')")
        js("root.id = 'register-button-click-test'")
        js("document.body.appendChild(root)")

        var registrationSuccessCalled = false

        try {
            renderComposable(rootElementId = testId) {
                RegisterPage(
                    apiClient = fakeClient,
                    onLoginSuccess = { _, _ -> registrationSuccessCalled = true },
                    onNavigateToLogin = { },
                    coroutineScope = this@runTest
                )
            }

            // when - enter username, email, and password, then click register button
            js("""
                var usernameInput = document.querySelector('#register-button-click-test input[type="text"]')
                usernameInput.value = 'charlie'
                usernameInput.dispatchEvent(new Event('input', { bubbles: true }))
            """)
            delay(100)

            js("""
                var emailInput = document.querySelector('#register-button-click-test input[type="email"]')
                emailInput.value = 'charlie@example.com'
                emailInput.dispatchEvent(new Event('input', { bubbles: true }))
            """)
            delay(100)

            js("""
                var passwordInput = document.querySelector('#register-button-click-test input[type="password"]')
                passwordInput.value = 'mypassword'
                passwordInput.dispatchEvent(new Event('input', { bubbles: true }))
            """)
            delay(100)

            js("""
                var buttons = document.querySelectorAll('#register-button-click-test button')
                if (buttons && buttons.length > 0) {
                    buttons[0].click()
                }
            """)
            delay(200)

            // then
            assertEquals(1, fakeClient.registerCalls.size, "Expected 1 register call but got ${fakeClient.registerCalls.size}")
            assertEquals("charlie", fakeClient.registerCalls[0].userName)
            assertEquals("charlie@example.com", fakeClient.registerCalls[0].email)
            assertEquals("mypassword", fakeClient.registerCalls[0].password)
        } finally {
            js("document.body.removeChild(root)")
        }
    }

    @Test
    fun registerSuccessCallbackInvokedWithCorrectToken() = runTest {
        // given
        val fakeClient = FakeApiClient()
        val expectedTokens = Tokens(
            AccessToken("dave", Role.USER),
            RefreshToken("dave")
        )
        fakeClient.registerResult = Result.success(expectedTokens)

        val testId = "register-callback-test"
        val root = js("document.createElement('div')")
        js("root.id = 'register-callback-test'")
        js("document.body.appendChild(root)")

        var registrationSuccessCalled = false
        var capturedToken: String? = null
        var capturedUserName: String? = null

        try {
            renderComposable(rootElementId = testId) {
                RegisterPage(
                    apiClient = fakeClient,
                    onLoginSuccess = { token, userName ->
                        registrationSuccessCalled = true
                        capturedToken = token
                        capturedUserName = userName
                    },
                    onNavigateToLogin = { },
                    coroutineScope = this@runTest
                )
            }

            // when - enter username, email, and password, then click register button
            js("""
                var usernameInput = document.querySelector('#register-callback-test input[type="text"]')
                usernameInput.value = 'dave'
                usernameInput.dispatchEvent(new Event('input', { bubbles: true }))
            """)
            delay(100)

            js("""
                var emailInput = document.querySelector('#register-callback-test input[type="email"]')
                emailInput.value = 'dave@example.com'
                emailInput.dispatchEvent(new Event('input', { bubbles: true }))
            """)
            delay(100)

            js("""
                var passwordInput = document.querySelector('#register-callback-test input[type="password"]')
                passwordInput.value = 'password'
                passwordInput.dispatchEvent(new Event('input', { bubbles: true }))
            """)
            delay(100)

            js("""
                var buttons = document.querySelectorAll('#register-callback-test button')
                if (buttons && buttons.length > 0) {
                    buttons[0].click()
                }
            """)
            delay(200)

            // then
            assertTrue(registrationSuccessCalled, "Registration success callback should be invoked")
            assertEquals("dave", capturedUserName)
            assertNotNull(capturedToken)
        } finally {
            js("document.body.removeChild(root)")
        }
    }
}
