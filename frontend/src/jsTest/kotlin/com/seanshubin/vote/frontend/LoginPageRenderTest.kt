package com.seanshubin.vote.frontend

import com.seanshubin.vote.contract.AccessToken
import com.seanshubin.vote.contract.RefreshToken
import com.seanshubin.vote.contract.Tokens
import com.seanshubin.vote.domain.Role
import kotlinx.coroutines.test.TestScope
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

    /**
     * Test orchestrator that handles infrastructure details and provides a clean API for testing LoginPage.
     *
     * Pattern: Hide test infrastructure (fake client, test root, rendering, DOM interaction)
     * behind domain-specific methods that focus on behavior.
     */
    class LoginPageTester(
        private val testScope: TestScope,
        private val testId: String = "login-page-test"
    ) : AutoCloseable {
        private val fakeClient = FakeApiClient()
        private val testRoot = ComposeTestHelper.createTestRoot(testId)
        private var loginSuccessCalled = false
        private var capturedToken: String? = null
        private var capturedUserName: String? = null
        private var navigateToRegisterCalled = false

        init {
            renderComposable(rootElementId = testId) {
                LoginPage(
                    apiClient = fakeClient,
                    onLoginSuccess = { token, userName ->
                        loginSuccessCalled = true
                        capturedToken = token
                        capturedUserName = userName
                    },
                    onNavigateToRegister = { navigateToRegisterCalled = true },
                    coroutineScope = testScope
                )
            }
        }

        // Setup methods - configure fake behavior
        fun setupAuthenticateSuccess(tokens: Tokens) {
            fakeClient.authenticateResult = Result.success(tokens)
        }

        fun setupAuthenticateFailure(error: Exception) {
            fakeClient.authenticateResult = Result.failure(error)
        }

        // Action methods - interact with the UI
        fun enterCredentials(userName: String, password: String) {
            ComposeTestHelper.setInputByPlaceholder(testId, "Username", userName)
            ComposeTestHelper.setInputByPlaceholder(testId, "Password", password)
        }

        fun pressEnterInPasswordField() {
            ComposeTestHelper.pressEnterInInput(testId, "Password")
            testScope.advanceUntilIdle()
        }

        fun pressEnterInUsernameField() {
            ComposeTestHelper.pressEnterInInput(testId, "Username")
            testScope.advanceUntilIdle()
        }

        fun clickLoginButton() {
            ComposeTestHelper.clickButtonByText(testId, "Login")
            testScope.advanceUntilIdle()
        }

        // Query methods - verify state
        fun authenticateCalls() = fakeClient.authenticateCalls

        fun wasLoginSuccessCallbackInvoked() = loginSuccessCalled

        fun capturedAuthToken() = capturedToken

        fun capturedUserName() = capturedUserName

        fun wasNavigateToRegisterCalled() = navigateToRegisterCalled

        fun usernameInputExists() = ComposeTestHelper.inputExistsByPlaceholder(testId, "Username")

        fun passwordInputExists() = ComposeTestHelper.inputExistsByPlaceholder(testId, "Password")

        override fun close() {
            testRoot.close()
        }
    }


    @Test
    fun loginPageRendersWithUsernameAndPasswordFields() = runTest {
        LoginPageTester(this).use { tester ->
            // then - verify it rendered with expected input fields
            assertTrue(tester.usernameInputExists(), "Username input should exist")
            assertTrue(tester.passwordInputExists(), "Password input should exist")
        }
    }

    @Test
    fun loginPageEnterKeyInPasswordFieldTriggersAuthentication() = runTest {
        LoginPageTester(this).use { tester ->
            // given
            val expectedTokens = Tokens(AccessToken("alice", Role.USER), RefreshToken("alice"))
            tester.setupAuthenticateSuccess(expectedTokens)

            // when
            tester.enterCredentials("alice", "password123")
            tester.pressEnterInPasswordField()

            // then
            assertEquals(1, tester.authenticateCalls().size)
            assertEquals("alice", tester.authenticateCalls()[0].userName)
            assertEquals("password123", tester.authenticateCalls()[0].password)
        }
    }

    @Test
    fun loginPageEnterKeyInUsernameFieldTriggersAuthentication() = runTest {
        LoginPageTester(this).use { tester ->
            // given
            val expectedTokens = Tokens(AccessToken("bob", Role.USER), RefreshToken("bob"))
            tester.setupAuthenticateSuccess(expectedTokens)

            // when
            tester.enterCredentials("bob", "securepass")
            tester.pressEnterInUsernameField()

            // then
            assertEquals(1, tester.authenticateCalls().size)
            assertEquals("bob", tester.authenticateCalls()[0].userName)
            assertEquals("securepass", tester.authenticateCalls()[0].password)
        }
    }

    @Test
    fun loginButtonClickTriggersAuthentication() = runTest {
        LoginPageTester(this).use { tester ->
            // given
            val expectedTokens = Tokens(AccessToken("charlie", Role.USER), RefreshToken("charlie"))
            tester.setupAuthenticateSuccess(expectedTokens)

            // when
            tester.enterCredentials("charlie", "mypassword")
            tester.clickLoginButton()

            // then
            assertEquals(1, tester.authenticateCalls().size)
            assertEquals("charlie", tester.authenticateCalls()[0].userName)
            assertEquals("mypassword", tester.authenticateCalls()[0].password)
        }
    }

    @Test
    fun loginSuccessCallbackInvokedWithCorrectToken() = runTest {
        LoginPageTester(this).use { tester ->
            // given
            val expectedTokens = Tokens(AccessToken("dave", Role.OWNER), RefreshToken("dave"))
            tester.setupAuthenticateSuccess(expectedTokens)

            // when
            tester.enterCredentials("dave", "password")
            tester.clickLoginButton()

            // then
            assertTrue(tester.wasLoginSuccessCallbackInvoked(), "Login success callback should be invoked")
            assertEquals("dave", tester.capturedUserName())
            assertNotNull(tester.capturedAuthToken())
        }
    }
}
