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

class RegisterPageRenderTest {

    @Test
    fun registerPageRendersWithUsernameEmailAndPasswordFields() = runTest {
        // given
        val fakeClient = FakeApiClient()
        val testId = "register-render-test"

        ComposeTestHelper.createTestRoot(testId).use {
            // when
            renderComposable(rootElementId = testId) {
                RegisterPage(
                    apiClient = fakeClient,
                    onLoginSuccess = { _, _ -> },
                    onNavigateToLogin = { }
                )
            }

            // then - verify it rendered with expected input fields
            assertTrue(
                ComposeTestHelper.inputExistsByPlaceholder(testId, "Username"),
                "Username input should exist"
            )
            assertTrue(
                ComposeTestHelper.inputExistsByPlaceholder(testId, "Email"),
                "Email input should exist"
            )
            assertTrue(
                ComposeTestHelper.inputExistsByPlaceholder(testId, "Password"),
                "Password input should exist"
            )
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

        ComposeTestHelper.createTestRoot(testId).use {
            renderComposable(rootElementId = testId) {
                RegisterPage(
                    apiClient = fakeClient,
                    onLoginSuccess = { _, _ -> },
                    onNavigateToLogin = { },
                    coroutineScope = this@runTest
                )
            }

            // when - enter username, email, and password, then press Enter in password field
            ComposeTestHelper.setInputByPlaceholder(testId, "Username", "alice")
            ComposeTestHelper.setInputByPlaceholder(testId, "Email", "alice@example.com")
            ComposeTestHelper.setInputByPlaceholder(testId, "Password", "password123")
            ComposeTestHelper.pressEnterInInput(testId, "Password")

            // Wait for all coroutines to complete
            this@runTest.testScheduler.advanceUntilIdle()

            // then
            assertEquals(1, fakeClient.registerCalls.size, "Expected 1 register call but got ${fakeClient.registerCalls.size}")
            assertEquals("alice", fakeClient.registerCalls[0].userName)
            assertEquals("alice@example.com", fakeClient.registerCalls[0].email)
            assertEquals("password123", fakeClient.registerCalls[0].password)
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

        ComposeTestHelper.createTestRoot(testId).use {
            renderComposable(rootElementId = testId) {
                RegisterPage(
                    apiClient = fakeClient,
                    onLoginSuccess = { _, _ -> },
                    onNavigateToLogin = { },
                    coroutineScope = this@runTest
                )
            }

            // when - enter username, email, and password, then press Enter in email field
            ComposeTestHelper.setInputByPlaceholder(testId, "Username", "bob")
            ComposeTestHelper.setInputByPlaceholder(testId, "Email", "bob@example.com")
            ComposeTestHelper.setInputByPlaceholder(testId, "Password", "securepass")
            ComposeTestHelper.pressEnterInInput(testId, "Email")

            // Wait for all coroutines to complete
            this@runTest.testScheduler.advanceUntilIdle()

            // then
            assertEquals(1, fakeClient.registerCalls.size, "Expected 1 register call but got ${fakeClient.registerCalls.size}")
            assertEquals("bob", fakeClient.registerCalls[0].userName)
            assertEquals("bob@example.com", fakeClient.registerCalls[0].email)
            assertEquals("securepass", fakeClient.registerCalls[0].password)
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

        ComposeTestHelper.createTestRoot(testId).use {
            renderComposable(rootElementId = testId) {
                RegisterPage(
                    apiClient = fakeClient,
                    onLoginSuccess = { _, _ -> },
                    onNavigateToLogin = { },
                    coroutineScope = this@runTest
                )
            }

            // when - enter username, email, and password, then click register button
            ComposeTestHelper.setInputByPlaceholder(testId, "Username", "charlie")
            ComposeTestHelper.setInputByPlaceholder(testId, "Email", "charlie@example.com")
            ComposeTestHelper.setInputByPlaceholder(testId, "Password", "mypassword")
            ComposeTestHelper.clickButtonByText(testId, "Register")

            // Wait for all coroutines to complete
            this@runTest.testScheduler.advanceUntilIdle()

            // then
            assertEquals(1, fakeClient.registerCalls.size, "Expected 1 register call but got ${fakeClient.registerCalls.size}")
            assertEquals("charlie", fakeClient.registerCalls[0].userName)
            assertEquals("charlie@example.com", fakeClient.registerCalls[0].email)
            assertEquals("mypassword", fakeClient.registerCalls[0].password)
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

        var registrationSuccessCalled = false
        var capturedToken: String? = null
        var capturedUserName: String? = null

        ComposeTestHelper.createTestRoot(testId).use {
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
            ComposeTestHelper.setInputByPlaceholder(testId, "Username", "dave")
            ComposeTestHelper.setInputByPlaceholder(testId, "Email", "dave@example.com")
            ComposeTestHelper.setInputByPlaceholder(testId, "Password", "password")
            ComposeTestHelper.clickButtonByText(testId, "Register")

            // Wait for all coroutines to complete
            this@runTest.testScheduler.advanceUntilIdle()

            // then
            assertTrue(registrationSuccessCalled, "Registration success callback should be invoked")
            assertEquals("dave", capturedUserName)
            assertNotNull(capturedToken)
        }
    }
}
