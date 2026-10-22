package com.droiddeck.launcher.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.droiddeck.launcher.R
import com.droiddeck.launcher.artifact.RepositorySources
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RepositorySourcesEditorTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val builtIns = listOf("Droid-Deck/DroidDeck")
    private val repository = "droid-deck/droiddeck"

    @Before fun resetMenu() { Steps.drop() }
    @After fun closeMenu() { Steps.drop() }

    private fun show(
        scope: String,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        visible: () -> Boolean = { true },
        showCatalogBranch: Boolean = false,
        onSaved: () -> Unit = {},
    ) {
        Snapshot.sendApplyNotifications()
        compose.setContent {
            DroidDeckTheme {
                Box(Modifier.fillMaxSize()) {
                    if (visible()) Box(Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                        RepositorySourcesButton(
                            scope, builtIns, showCatalogBranch = showCatalogBranch,
                            ioDispatcher = dispatcher, onSaved = onSaved,
                        )
                    }
                    StepsLayer(Offset.Zero)
                }
            }
        }
    }

    private fun showDriverPage(scope: String, onSaved: () -> Unit = {}) {
        Snapshot.sendApplyNotifications()
        compose.setContent {
            DroidDeckTheme {
                Box(Modifier.fillMaxSize()) {
                    DriverPage(
                        title = "Runtime driver",
                        hint = "Runtime hint",
                        rows = emptyList(),
                        selected = "",
                        downloads = emptyList(),
                        status = "",
                        checking = false,
                        importLabel = "Import",
                        canRestore = false,
                        onSelect = {},
                        onDelete = {},
                        onRefresh = {},
                        onDownload = {},
                        onImport = {},
                        onRestore = {},
                        onBack = {},
                        sourceKey = scope,
                        sourceBuiltIns = builtIns,
                        onSourcesChanged = onSaved,
                    )
                    StepsLayer(Offset.Zero)
                }
            }
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun runPending() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }

    private fun open() {
        compose.onNodeWithContentDescription(context.getString(R.string.sources_component_title)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription(context.getString(R.string.sources_hide, repository))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun openingUsesTheSameContinuousOutlineAsTheDeviceChip() {
        show("editor/connected")
        val button = compose.onNodeWithContentDescription(context.getString(R.string.sources_component_title))
        val bounds = button.fetchSemanticsNode().boundsInRoot
        open()
        compose.runOnIdle {
            val menu = requireNotNull(Steps.step)
            assertEquals(bounds, menu.anchor)
            assertEquals(StepSide.Left, menu.side)
            assertEquals(12.dp, menu.pillCorner)
            assertTrue(Steps.open)
        }
        compose.onAllNodes(isPopup()).assertCountEquals(0)
    }

    @Test fun driverPageSourcesUseTheirScopeAndNotifyAfterSave() {
        val scope = "driver-page/runtime"
        var saves = 0
        showDriverPage(scope) { saves++ }
        compose.onNodeWithContentDescription(context.getString(R.string.comp_check_drivers)).assertExists()
        open()
        compose.onNodeWithContentDescription(context.getString(R.string.sources_hide, repository)).performClick()
        compose.onNodeWithText(context.getString(R.string.sources_save)).performClick()
        compose.waitUntil(5_000) { saves == 1 }

        assertTrue(RepositorySources.list(context, scope, builtIns).single().hidden)
        assertFalse(RepositorySources.list(context, "driver-page/display", builtIns).single().hidden)
    }

    @Test fun leavingThePageRemovesItsSourceMenu() {
        var visible by mutableStateOf(true)
        show("editor/leave", visible = { visible })
        open()
        compose.runOnIdle {
            visible = false
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnUiThread {
            Snapshot.sendApplyNotifications()
            assertNull(Steps.step)
        }
        compose.onNodeWithText(context.getString(R.string.sources_save)).assertDoesNotExist()
    }

    @Test fun dismissIsBlockedUntilTheSaveFinishes() {
        val dispatcher = QueuedDispatcher()
        var saves = 0
        show("editor/saving", dispatcher, onSaved = { saves++ })
        compose.onNodeWithContentDescription(context.getString(R.string.sources_component_title)).performClick()
        compose.runOnIdle { dispatcher.runPending() }
        compose.onNodeWithText(context.getString(R.string.sources_save)).performClick()
        compose.runOnIdle {
            Steps.fold()
            assertTrue(Steps.open)
        }
        compose.onNodeWithText(context.getString(R.string.sources_saving)).assertIsNotEnabled()
        compose.runOnIdle { dispatcher.runPending() }
        compose.waitUntil(5_000) { saves == 1 }
        compose.runOnIdle { assertNull(Steps.step) }
    }

    @Test fun editingWaitsForTheSourceReadToComplete() {
        val dispatcher = QueuedDispatcher()
        show("editor/loading", dispatcher)
        compose.onNodeWithContentDescription(context.getString(R.string.sources_component_title)).performClick()
        compose.onNodeWithText(context.getString(R.string.sources_add)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.sources_save)).assertIsNotEnabled()
        compose.runOnIdle { dispatcher.runPending() }
        compose.onNodeWithContentDescription(context.getString(R.string.sources_hide, repository)).assertExists()
        compose.onNodeWithText(context.getString(R.string.sources_add)).assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.sources_save)).assertIsEnabled()
    }

    @Test fun hidingIsDraftUntilSaveAndBuiltInCannotBeRemoved() {
        val scope = "editor/hide"
        var saves = 0
        show(scope, onSaved = { saves++ })
        open()
        compose.onNodeWithContentDescription(context.getString(R.string.sources_remove, repository)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.sources_hide, repository)).performClick()
        assertFalse(RepositorySources.list(context, scope, builtIns).single().hidden)
        compose.onNodeWithText(context.getString(R.string.sources_save)).performClick()
        compose.waitUntil(5_000) { saves == 1 }
        assertTrue(RepositorySources.list(context, scope, builtIns).single().hidden)
        assertTrue(RepositorySources.visible(context, scope, builtIns).isEmpty())
    }

    @Test fun closingDiscardsDraft() {
        val scope = "editor/discard"
        show(scope)
        open()
        compose.onNodeWithContentDescription(context.getString(R.string.sources_hide, repository)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.sources_component_title)).performClick()
        compose.waitForIdle()
        open()
        assertFalse(RepositorySources.list(context, scope, builtIns).single().hidden)
        compose.onNodeWithContentDescription(context.getString(R.string.sources_hide, repository)).assertExists()
    }

    @Test fun addValidatesRepositoryBeforeSaveAndCustomRowCanBeRemoved() {
        val scope = "editor/add"
        var saves = 0
        show(scope, onSaved = { saves++ })
        open()
        compose.onNodeWithText(context.getString(R.string.sources_add)).performClick()
        val input = compose.onNodeWithText(context.getString(R.string.sources_repository))
        input.performTextReplacement("https://example.com/owner/repo")
        compose.onNodeWithText(context.getString(R.string.sources_save)).performClick()
        compose.onNodeWithText(context.getString(R.string.sources_invalid_repository)).assertExists()
        assertEquals(0, saves)
        input.performTextReplacement("https://github.com/Wallentx/DroidDeck")
        compose.onNodeWithText(context.getString(R.string.sources_save)).performClick()
        compose.waitUntil(5_000) { saves == 1 }
        assertEquals(listOf(repository, "wallentx/droiddeck"), RepositorySources.list(context, scope, builtIns).map { it.repository })
        open()
        compose.onNodeWithContentDescription(context.getString(R.string.sources_remove, "wallentx/droiddeck")).performClick()
        assertEquals(2, RepositorySources.list(context, scope, builtIns).size)
        compose.onNodeWithText(context.getString(R.string.sources_save)).performClick()
        compose.waitUntil(5_000) { saves == 2 }
        assertEquals(listOf(repository), RepositorySources.list(context, scope, builtIns).map { it.repository })
    }

    @Test fun catalogBranchIsDraftUntilSaveAndCanReturnToTheDefault() {
        val scope = "editor/branch"
        var saves = 0
        show(scope, showCatalogBranch = true, onSaved = { saves++ })
        open()
        val branch = compose.onNodeWithText(context.getString(R.string.sources_catalog_branch))
        branch.performTextReplacement("Wallentx/PowerVR")
        compose.onNodeWithContentDescription(context.getString(R.string.sources_component_title)).performClick()
        compose.waitForIdle()
        assertNull(RepositorySources.list(context, scope, builtIns).single().catalogBranch)

        open()
        compose.onNodeWithText(context.getString(R.string.sources_catalog_branch)).performTextReplacement("Wallentx/PowerVR")
        compose.onNodeWithText(context.getString(R.string.sources_save)).performClick()
        compose.waitUntil(5_000) { saves == 1 }
        assertEquals("Wallentx/PowerVR", RepositorySources.list(context, scope, builtIns).single().catalogBranch)

        open()
        compose.onNodeWithText(context.getString(R.string.sources_catalog_branch)).performTextReplacement("")
        compose.onNodeWithText(context.getString(R.string.sources_save)).performClick()
        compose.waitUntil(5_000) { saves == 2 }
        assertNull(RepositorySources.list(context, scope, builtIns).single().catalogBranch)
    }

    @Test fun invalidCatalogBranchHasADistinctError() {
        val branchScope = "editor/invalid-branch"
        show(branchScope, showCatalogBranch = true)
        open()
        compose.onNodeWithText(context.getString(R.string.sources_catalog_branch)).performTextReplacement("bad..branch")
        compose.onNodeWithText(context.getString(R.string.sources_save)).performClick()
        compose.onNodeWithText(context.getString(R.string.sources_invalid_catalog_branch)).assertExists()
        assertNull(RepositorySources.list(context, branchScope, builtIns).single().catalogBranch)
    }

    @Test fun componentEditorDoesNotShowCatalogBranches() {
        show("editor/no-branch")
        open()
        compose.onNodeWithText(context.getString(R.string.sources_catalog_branch)).assertDoesNotExist()
    }
}
