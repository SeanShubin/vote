package com.seanshubin.vote.frontend

import com.seanshubin.vote.contract.AccessToken
import com.seanshubin.vote.contract.RefreshToken
import com.seanshubin.vote.contract.Tokens
import com.seanshubin.vote.domain.Role
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
 * 1. Create test root with ComposeTestHelper
 * 2. Render composable with fake dependencies
 * 3. Interact with UI using ComposeTestHelper utilities (no raw JavaScript)
 * 4. Use advanceUntilIdle() to wait for all coroutines to complete
 * 5. Verify state changes and API calls
 *
 * ComposeTestHelper eliminates raw JavaScript, advanceUntilIdle() eliminates arbitrary delays.
 */
class LoginPageRenderTest {

    @Test
    fun loginPageRendersWithUsernameAndPasswordFields() = runTest {
        // given
        val fakeClient = FakeApiClient()
        val testId = "login-render-test"

        ComposeTestHelper.createTestRoot(testId).use {
            // when
            renderComposable(rootElementId = testId) {
                LoginPage(
                    apiClient = fakeClient,
                    onLoginSuccess = { _, _ -> },
                    onNavigateToRegister = { }
                )
            }

            // then - verify it rendered with expected input fields
            assertTrue(
                ComposeTestHelper.inputExistsByPlaceholder(testId, "Username"),
                "Username input should exist"
            )
            assertTrue(
                ComposeTestHelper.inputExistsByPlaceholder(testId, "Password"),
                "Password input should exist"
            )
        }
    }

    @Test
    fun loginPageInputsCanBeSet() = runTest {
        // Diagnostic test: can we set input values and read them back?
        val fakeClient = FakeApiClient()
        val testId = "login-diagnostic-test"

        ComposeTestHelper.createTestRoot(testId).use {
            renderComposable(rootElementId = testId) {
                LoginPage(
                    apiClient = fakeClient,
                    onLoginSuccess = { _, _ -> },
                    onNavigateToRegister = { }
                )
            }

            // Set input value using helper
            ComposeTestHelper.setInputByPlaceholder(testId, "Username", "testuser")

            // Read back the value to verify it was set
            val readValue = js("""
                var usernameInput = document.querySelector('#login-diagnostic-test input[placeholder="Username"]')
                usernameInput.value
            """) as? String

            println("DEBUG: Input value after setting: $readValue")
            assertEquals("testuser", readValue, "Input value should be readable")
        }
    }

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

        ComposeTestHelper.createTestRoot(testId).use {
            renderComposable(rootElementId = testId) {
                LoginPage(
                    apiClient = fakeClient,
                    onLoginSuccess = { _, _ -> },
                    onNavigateToRegister = { },
                    coroutineScope = this@runTest
                )
            }

            // when - enter username and password, then press Enter in password field
            ComposeTestHelper.setInputByPlaceholder(testId, "Username", "alice")
            ComposeTestHelper.setInputByPlaceholder(testId, "Password", "password123")
            ComposeTestHelper.pressEnterInInput(testId, "Password")

            // Wait for all coroutines (handleLogin coroutine) to complete
            this@runTest.testScheduler.advanceUntilIdle()

            // then
            assertEquals(1, fakeClient.authenticateCalls.size, "Expected 1 authenticate call but got ${fakeClient.authenticateCalls.size}")
            assertEquals("alice", fakeClient.authenticateCalls[0].userName)
            assertEquals("password123", fakeClient.authenticateCalls[0].password)
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

        ComposeTestHelper.createTestRoot(testId).use {
            renderComposable(rootElementId = testId) {
                LoginPage(
                    apiClient = fakeClient,
                    onLoginSuccess = { _, _ -> },
                    onNavigateToRegister = { },
                    coroutineScope = this@runTest
                )
            }

            // when - enter username and password, then press Enter in username field
            ComposeTestHelper.setInputByPlaceholder(testId, "Username", "bob")
            ComposeTestHelper.setInputByPlaceholder(testId, "Password", "securepass")
            ComposeTestHelper.pressEnterInInput(testId, "Username")

            // Wait for all coroutines to complete
            this@runTest.testScheduler.advanceUntilIdle()

            // then
            assertEquals(1, fakeClient.authenticateCalls.size, "Expected 1 authenticate call but got ${fakeClient.authenticateCalls.size}")
            assertEquals("bob", fakeClient.authenticateCalls[0].userName)
            assertEquals("securepass", fakeClient.authenticateCalls[0].password)
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

        ComposeTestHelper.createTestRoot(testId).use {
            renderComposable(rootElementId = testId) {
                LoginPage(
                    apiClient = fakeClient,
                    onLoginSuccess = { _, _ -> },
                    onNavigateToRegister = { },
                    coroutineScope = this@runTest
                )
            }

            // when - enter username and password, then click login button
            ComposeTestHelper.setInputByPlaceholder(testId, "Username", "charlie")
            ComposeTestHelper.setInputByPlaceholder(testId, "Password", "mypassword")
            ComposeTestHelper.clickButtonByText(testId, "Login")

            // Wait for all coroutines to complete
            this@runTest.testScheduler.advanceUntilIdle()

            // then
            assertEquals(1, fakeClient.authenticateCalls.size, "Expected 1 authenticate call but got ${fakeClient.authenticateCalls.size}")
            assertEquals("charlie", fakeClient.authenticateCalls[0].userName)
            assertEquals("mypassword", fakeClient.authenticateCalls[0].password)
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

        var loginSuccessCalled = false
        var capturedToken: String? = null
        var capturedUserName: String? = null

        ComposeTestHelper.createTestRoot(testId).use {
            renderComposable(rootElementId = testId) {
                LoginPage(
                    apiClient = fakeClient,
                    onLoginSuccess = { token, userName ->
                        loginSuccessCalled = true
                        capturedToken = token
                        capturedUserName = userName
                    },
                    onNavigateToRegister = { },
                    coroutineScope = this@runTest
                )
            }

            // when - enter username and password, then click login button
            ComposeTestHelper.setInputByPlaceholder(testId, "Username", "dave")
            ComposeTestHelper.setInputByPlaceholder(testId, "Password", "password")
            ComposeTestHelper.clickButtonByText(testId, "Login")

            // Wait for all coroutines to complete
            this@runTest.testScheduler.advanceUntilIdle()

            // then
            assertTrue(loginSuccessCalled, "Login success callback should be invoked")
            assertEquals("dave", capturedUserName)
            assertNotNull(capturedToken)
        }
    }
}
