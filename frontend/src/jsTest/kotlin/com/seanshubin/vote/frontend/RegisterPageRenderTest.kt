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

class RegisterPageRenderTest {

    /**
     * Test orchestrator for RegisterPage that hides infrastructure details.
     */
    class RegisterPageTester(
        private val testScope: TestScope,
        private val testId: String = "register-page-test"
    ) : AutoCloseable {
        private val fakeClient = FakeApiClient()
        private val testRoot = ComposeTestHelper.createTestRoot(testId)
        private var loginSuccessCalled = false
        private var capturedToken: String? = null
        private var capturedUserName: String? = null
        private var navigateToLoginCalled = false

        init {
            renderComposable(rootElementId = testId) {
                RegisterPage(
                    apiClient = fakeClient,
                    onLoginSuccess = { token, userName ->
                        loginSuccessCalled = true
                        capturedToken = token
                        capturedUserName = userName
                    },
                    onNavigateToLogin = { navigateToLoginCalled = true },
                    coroutineScope = testScope
                )
            }
        }

        // Setup methods
        fun setupRegisterSuccess(tokens: Tokens) {
            fakeClient.registerResult = Result.success(tokens)
        }

        fun setupRegisterFailure(error: Exception) {
            fakeClient.registerResult = Result.failure(error)
        }

        // Action methods
        fun enterRegistrationInfo(userName: String, email: String, password: String) {
            ComposeTestHelper.setInputByPlaceholder(testId, "Username", userName)
            ComposeTestHelper.setInputByPlaceholder(testId, "Email", email)
            ComposeTestHelper.setInputByPlaceholder(testId, "Password", password)
        }

        fun pressEnterInPasswordField() {
            ComposeTestHelper.pressEnterInInput(testId, "Password")
            testScope.advanceUntilIdle()
        }

        fun pressEnterInEmailField() {
            ComposeTestHelper.pressEnterInInput(testId, "Email")
            testScope.advanceUntilIdle()
        }

        fun clickRegisterButton() {
            ComposeTestHelper.clickButtonByText(testId, "Register")
            testScope.advanceUntilIdle()
        }

        // Query methods
        fun registerCalls() = fakeClient.registerCalls

        fun wasLoginSuccessCallbackInvoked() = loginSuccessCalled

        fun capturedAuthToken() = capturedToken

        fun capturedUserName() = capturedUserName

        fun wasNavigateToLoginCalled() = navigateToLoginCalled

        fun usernameInputExists() = ComposeTestHelper.inputExistsByPlaceholder(testId, "Username")

        fun emailInputExists() = ComposeTestHelper.inputExistsByPlaceholder(testId, "Email")

        fun passwordInputExists() = ComposeTestHelper.inputExistsByPlaceholder(testId, "Password")

        override fun close() {
            testRoot.close()
        }
    }


    @Test
    fun registerPageRendersWithUsernameEmailAndPasswordFields() = runTest {
        RegisterPageTester(this).use { tester ->
            // then - verify it rendered with expected input fields
            assertTrue(tester.usernameInputExists(), "Username input should exist")
            assertTrue(tester.emailInputExists(), "Email input should exist")
            assertTrue(tester.passwordInputExists(), "Password input should exist")
        }
    }

    @Test
    fun registerPageEnterKeyInPasswordFieldTriggersRegistration() = runTest {
        RegisterPageTester(this).use { tester ->
            // given
            val expectedTokens = Tokens(AccessToken("alice", Role.USER), RefreshToken("alice"))
            tester.setupRegisterSuccess(expectedTokens)

            // when
            tester.enterRegistrationInfo("alice", "alice@example.com", "password123")
            tester.pressEnterInPasswordField()

            // then
            assertEquals(1, tester.registerCalls().size)
            assertEquals("alice", tester.registerCalls()[0].userName)
            assertEquals("alice@example.com", tester.registerCalls()[0].email)
            assertEquals("password123", tester.registerCalls()[0].password)
        }
    }

    @Test
    fun registerPageEnterKeyInEmailFieldTriggersRegistration() = runTest {
        RegisterPageTester(this).use { tester ->
            // given
            val expectedTokens = Tokens(AccessToken("bob", Role.USER), RefreshToken("bob"))
            tester.setupRegisterSuccess(expectedTokens)

            // when
            tester.enterRegistrationInfo("bob", "bob@example.com", "securepass")
            tester.pressEnterInEmailField()

            // then
            assertEquals(1, tester.registerCalls().size)
            assertEquals("bob", tester.registerCalls()[0].userName)
            assertEquals("bob@example.com", tester.registerCalls()[0].email)
            assertEquals("securepass", tester.registerCalls()[0].password)
        }
    }

    @Test
    fun registerButtonClickTriggersRegistration() = runTest {
        RegisterPageTester(this).use { tester ->
            // given
            val expectedTokens = Tokens(AccessToken("charlie", Role.USER), RefreshToken("charlie"))
            tester.setupRegisterSuccess(expectedTokens)

            // when
            tester.enterRegistrationInfo("charlie", "charlie@example.com", "mypassword")
            tester.clickRegisterButton()

            // then
            assertEquals(1, tester.registerCalls().size)
            assertEquals("charlie", tester.registerCalls()[0].userName)
            assertEquals("charlie@example.com", tester.registerCalls()[0].email)
            assertEquals("mypassword", tester.registerCalls()[0].password)
        }
    }

    @Test
    fun registerSuccessCallbackInvokedWithCorrectToken() = runTest {
        RegisterPageTester(this).use { tester ->
            // given
            val expectedTokens = Tokens(AccessToken("dave", Role.USER), RefreshToken("dave"))
            tester.setupRegisterSuccess(expectedTokens)

            // when
            tester.enterRegistrationInfo("dave", "dave@example.com", "password")
            tester.clickRegisterButton()

            // then
            assertTrue(tester.wasLoginSuccessCallbackInvoked(), "Registration success callback should be invoked")
            assertEquals("dave", tester.capturedUserName())
            assertNotNull(tester.capturedAuthToken())
        }
    }
}
