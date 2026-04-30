package com.seanshubin.vote.frontend

import com.seanshubin.vote.domain.TableData
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Render tests for TablesPage. Mirrors the structural checks that
 * ElectionsPageRenderTest does — the LaunchedEffect-driven data load
 * is covered by the backend/integration tests; here we just verify
 * the page mounts and the static UI surface is present.
 */
class TablesPageRenderTest {

    class TablesPageTester(
        private val testScope: TestScope,
        loadNames: () -> List<String> = { emptyList() },
        loadData: (String) -> TableData = { TableData(it, emptyList(), emptyList()) },
        private val testId: String = "tables-page-test",
    ) : AutoCloseable {
        private val testRoot = ComposeTestHelper.createTestRoot(testId)
        private var backCalled = false

        init {
            renderComposable(rootElementId = testId) {
                TablesPage(
                    title = "Test Tables",
                    emptyMessage = "No tables to show",
                    loadNames = { loadNames() },
                    loadData = { name -> loadData(name) },
                    onError = { /* swallow in render tests */ },
                    onBack = { backCalled = true },
                    coroutineScope = testScope,
                )
            }
        }

        fun clickBack() = ComposeTestHelper.clickButtonByText(testId, "Back to Home")
        fun wasBackCalled() = backCalled
        fun headingExists() = ComposeTestHelper.elementExists(testId, "h1")
        fun backButtonExists(): Boolean {
            val getButton: dynamic = js("""
                (function(testId) {
                    var buttons = Array.from(document.querySelectorAll('#' + testId + ' button'));
                    return buttons.some(function(b) { return b.textContent.trim() === 'Back to Home'; });
                })
            """)
            return getButton(testId).unsafeCast<Boolean>()
        }

        override fun close() {
            testRoot.close()
        }
    }

    @Test
    fun tablesPageMountsWithTitleAndBackButton() = runTest {
        TablesPageTester(this).use { tester ->
            assertTrue(tester.headingExists(), "Heading should exist")
            assertTrue(tester.backButtonExists(), "Back button should exist")
        }
    }

    @Test
    fun clickingBackTriggersOnBack() = runTest {
        TablesPageTester(this).use { tester ->
            tester.clickBack()
            assertTrue(tester.wasBackCalled(), "onBack should have been invoked")
        }
    }
}
