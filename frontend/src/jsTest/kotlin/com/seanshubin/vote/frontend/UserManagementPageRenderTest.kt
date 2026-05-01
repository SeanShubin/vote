package com.seanshubin.vote.frontend

import com.seanshubin.vote.contract.AuthResponse
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Render tests for UserManagementPage. Following the same convention as
 * ElectionsPageRenderTest / TablesPageRenderTest: this only verifies the page
 * mounts and the static UI surface is present. The LaunchedEffect-driven data
 * load and the role-change flow itself are covered by RoleManagementTest at
 * the service level (the page is a thin pass-through to apiClient.setRole).
 */
class UserManagementPageRenderTest {

    class UserManagementPageTester(
        private val testScope: TestScope,
        private val testId: String = "user-management-page-test",
    ) : AutoCloseable {
        val fakeClient = FakeApiClient()
        private val testRoot = ComposeTestHelper.createTestRoot(testId)
        private var backCalled = false
        private var lastSelfRoleChange: AuthResponse? = null

        init {
            renderComposable(rootElementId = testId) {
                UserManagementPage(
                    apiClient = fakeClient,
                    currentUserName = "alice",
                    onSelfRoleChanged = { lastSelfRoleChange = it },
                    onBack = { backCalled = true },
                    coroutineScope = testScope,
                )
            }
        }

        fun clickBack() = ComposeTestHelper.clickButtonByText(testId, "Back to Home")
        fun wasBackCalled(): Boolean = backCalled
        fun selfRoleChange(): AuthResponse? = lastSelfRoleChange
        fun headingExists(): Boolean = ComposeTestHelper.elementExists(testId, "h1")

        override fun close() {
            testRoot.close()
        }
    }

    @Test
    fun userManagementPageMountsWithHeading() = runTest {
        UserManagementPageTester(this).use { tester ->
            assertTrue(tester.headingExists(), "Heading should exist")
        }
    }

    @Test
    fun clickingBackTriggersOnBack() = runTest {
        UserManagementPageTester(this).use { tester ->
            tester.clickBack()
            assertTrue(tester.wasBackCalled(), "onBack should have been invoked")
        }
    }
}
