package com.droiddeck.launcher.ui

import android.app.Application
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.session.ComponentsManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// Exercise the component UI without starting App's process-wide servers or crash handler.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ComponentsPageProfileTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun gamepadAActivatesGpuModesAndPairsOnceAndRespectsBusyRows() {
        val context = RuntimeEnvironment.getApplication()
        val modes = mutableListOf<String>()
        val pairs = mutableListOf<String>()
        var mode by mutableStateOf(GPU_MODE_MIX)
        var busy by mutableStateOf<String?>(null)
        lateinit var gpuView: View
        lateinit var inputModeManager: InputModeManager
        val first = FocusRequester()
        val slots = mapOf(SLOT_DISPLAY to FocusRequester(), SLOT_RUNTIME to FocusRequester())
        compose.setContent {
            DroidDeckTheme {
                val view = LocalView.current
                val inputs = LocalInputModeManager.current
                SideEffect { gpuView = view; inputModeManager = inputs }
                GpuDriversBody(
                    gpu = GpuDriversState(
                        mode = mode, auto = mode == GPU_MODE_AUTO, busy = busy,
                        pairs = listOf(PairRow(
                            key = "installed", name = "Installed pair", version = "1", detail = "",
                            recommended = false, suits = true, complete = true, installed = true, active = false,
                        )),
                    ),
                    actions = GpuDriversActions(
                        onMode = { modes += it; mode = it },
                        onPair = { pairs += it },
                    ),
                    single = true, first = first, slotFocus = slots, onOpenSlot = { _, _ -> },
                )
            }
        }
        compose.runOnIdle {
            inputModeManager.requestInputMode(InputMode.Keyboard)
            first.requestFocus()
        }
        compose.onNodeWithText(context.getString(R.string.gpu_mode_mix)).assertIsFocused()

        fun pressA() {
            compose.runOnIdle {
                val now = SystemClock.uptimeMillis()
                for ((action, repeat) in listOf(KeyEvent.ACTION_DOWN to 0, KeyEvent.ACTION_DOWN to 1, KeyEvent.ACTION_UP to 0)) {
                    gpuView.dispatchKeyEvent(KeyEvent(
                        now, now, action, KeyEvent.KEYCODE_BUTTON_A, repeat, 0, 0, 0, 0, InputDevice.SOURCE_GAMEPAD,
                    ))
                }
            }
        }
        fun select(label: String) {
            // The panel redirects group entry to its selected mode.
            compose.runOnIdle { first.requestFocus() }
            compose.onNodeWithText(label).performScrollTo()
                .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                .assertIsFocused()
            pressA()
        }

        select(context.getString(R.string.common_auto))
        compose.runOnIdle { assertEquals(listOf(GPU_MODE_AUTO), modes) }
        select(context.getString(R.string.gpu_mode_pair))
        compose.runOnIdle { assertEquals(listOf(GPU_MODE_AUTO, GPU_MODE_PAIR), modes) }
        select("Installed pair")
        compose.runOnIdle {
            assertEquals(listOf("installed"), pairs)
            busy = "installed"
        }
        compose.onNodeWithText("Installed pair").assertIsNotEnabled()
        pressA()
        compose.runOnIdle {
            assertEquals(listOf("installed"), pairs)
            busy = null
        }
        select(context.getString(R.string.gpu_mode_mix))
        compose.runOnIdle { assertEquals(listOf(GPU_MODE_AUTO, GPU_MODE_PAIR, GPU_MODE_MIX), modes) }
    }

    @Test fun systemAutoExplainsMatchingComponentsAndRetainsManualModes() {
        val context = RuntimeEnvironment.getApplication()
        var chosenMode = GPU_MODE_AUTO
        compose.setContent {
            DroidDeckTheme {
                GpuDriversBody(
                    gpu = GpuDriversState(
                        gpuName = "Mali-G715", gpuFamily = "Mali", systemGpu = true,
                        systemVulkan = true, systemDriverName = "Mali",
                    ),
                    actions = GpuDriversActions(onMode = { chosenMode = it }),
                    single = true,
                    first = FocusRequester(),
                    slotFocus = emptyMap(),
                    onOpenSlot = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.gpu_auto_system_title, "Mali-G715")).assertExists()
        compose.onAllNodesWithText(context.getString(R.string.gpu_auto_system_hint)).onLast().assertExists()
        compose.onNodeWithText(context.getString(R.string.gpu_auto_reason, "Mali")).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.gpu_mode_auto_detail)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.gpu_mode_pair)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(GPU_MODE_PAIR, chosenMode) }
        compose.onNodeWithText(context.getString(R.string.gpu_mode_mix)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(GPU_MODE_MIX, chosenMode) }
    }

    @Test fun gameProfilesCoexistWithTheDefaultComponentStack() {
        val context = RuntimeEnvironment.getApplication()
        compose.setContent {
            DroidDeckTheme {
                ComponentsPage(
                    snapshot = null,
                    catalog = emptyList(),
                    catalogAt = 0,
                    protonId = null,
                    comp = PROTON_TAB,
                    checking = false,
                    busy = null,
                    downloads = emptyMap(),
                    onProton = {},
                    onComp = {},
                    onSwap = {},
                    onRestore = {},
                    onCancelQueued = {},
                    onDeletePackage = {},
                    onDeleteOriginal = {},
                    onDownload = {},
                    onRefresh = {},
                    onImport = {},
                    onBack = {},
                    games = listOf(Library.SteamGame(42, "Game A", null, "internal")),
                    requestInitialFocus = false,
                )
            }
        }
        val stackHeading = context.getString(R.string.comp_this_proton).uppercase()
        compose.onNodeWithText(stackHeading).assertExists()
        compose.onNodeWithText(context.getString(R.string.comp_scope_general)).performClick()
        compose.onNodeWithText("Game A").performClick()
        compose.onNodeWithText(context.getString(R.string.comp_game_hint)).assertExists()
        compose.onNodeWithText(stackHeading).assertDoesNotExist()

        compose.onNodeWithText("Game A").performClick()
        compose.onNodeWithText(context.getString(R.string.comp_scope_general)).performClick()
        compose.onNodeWithText(stackHeading).assertExists()
    }

    @Test @Config(qualifiers = "w1280dp-h1000dp")
    fun systemPowerVrKeepsStoredDxvkDisabledAndQueuedDxvkCancellable() {
        val context = RuntimeEnvironment.getApplication()
        var cancelled = 0
        var imported = 0
        var swapped = 0
        var restored = 0
        showDxvk(
            systemVulkan = true,
            onCancel = { cancelled++ },
            onImport = { imported++ },
            onSwap = { swapped++ },
            onRestore = { restored++ },
        )

        compose.onNodeWithText(context.getString(R.string.cmgr_system_dxvk_managed, "PowerVR")).assertExists()
        compose.onNodeWithText(context.getString(R.string.cmgr_powervr_dxvk_queued, "Next DXVK"), substring = true).assertExists()
        compose.onNodeWithText("Stored DXVK").assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.comp_original, "1.0")).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.comp_restore_original)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.common_cancel)).performClick()
        compose.onNodeWithText(context.getString(R.string.comp_import_wcp)).performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, cancelled)
            assertEquals(1, imported)
            assertEquals(0, swapped)
            assertEquals(0, restored)
        }
    }

    @Test @Config(qualifiers = "w1280dp-h1000dp")
    fun manualRuntimeKeepsDxvkSelectionAvailable() {
        val context = RuntimeEnvironment.getApplication()
        showDxvk(systemVulkan = false)

        compose.onNode(hasText("Stored DXVK") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.comp_original, "1.0")).assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.comp_restore_original)).assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.cmgr_system_dxvk_managed, "PowerVR")).assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w1280dp-h1000dp")
    fun systemPowerVrGameProfileKeepsOtherComponentChoicesAndRuntimeOptionsAvailable() {
        val context = RuntimeEnvironment.getApplication()
        showDxvk(systemVulkan = true)
        compose.onNodeWithText(context.getString(R.string.comp_scope_general)).performClick()
        compose.onNodeWithText("Game A").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(context.getString(R.string.comp_game_reset)).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText(context.getString(R.string.gpu_system_driver, "PowerVR")).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.cmgr_system_dxvk_managed, "PowerVR")).assertExists()
        val inheritedChoices = compose.onAllNodesWithText(context.getString(R.string.comp_game_use_general))
        inheritedChoices.assertCountEquals(4) // FEX preset, FEX package, Proton and VKD3D remain selectable.
        repeat(4) { inheritedChoices[it].assertIsEnabled() }
        val runtimeOptions = compose.onAllNodesWithText(context.getString(R.string.comp_game_runtime_options))
        runtimeOptions.onLast().assertExists()
        repeat(2) { runtimeOptions[it].assertIsEnabled() }
        compose.onNodeWithText(context.getString(R.string.comp_this_proton).uppercase()).assertDoesNotExist()

        compose.onNodeWithText("Game A").performClick()
        compose.onNodeWithText(context.getString(R.string.comp_scope_general)).performClick()
        compose.onNodeWithText(context.getString(R.string.comp_this_proton).uppercase()).assertExists()
    }

    @Test @Config(qualifiers = "w1280dp-h1000dp")
    fun systemPowerVrGameProfileCanClearAnExistingDxvkOverrideWithoutSelectingAnother() {
        val context = RuntimeEnvironment.getApplication()
        showDxvk(systemVulkan = true, gameDxvkOverride = true)
        compose.onNodeWithText(context.getString(R.string.comp_scope_general)).performClick()
        compose.onNodeWithText("Game A").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(context.getString(R.string.comp_game_reset)).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText(context.getString(R.string.gpu_system_driver, "PowerVR")).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Stored DXVK").assertDoesNotExist()
        compose.onAllNodesWithText(context.getString(R.string.comp_game_use_general)).onLast().performClick()
        compose.waitUntil(5_000) { "dxvk" !in ComponentsManager.gameComponents(context, "42") }
        compose.onNodeWithText(context.getString(R.string.gpu_system_driver, "PowerVR")).assertIsNotEnabled()
    }

    @Test @Config(qualifiers = "w1280dp-h1000dp")
    fun systemMaliKeepsDxvkSelectableAndShowsTheSelectedGameComponent() {
        val context = RuntimeEnvironment.getApplication()
        showDxvk(
            systemVulkan = true,
            runtimeManagedDxvk = false,
            systemDriverName = "Mali",
            gameDxvkOverride = true,
        )
        compose.onNodeWithText(context.getString(R.string.gpu_system_driver, "Mali")).assertExists()
        compose.onNodeWithText(context.getString(R.string.cmgr_system_dxvk_managed, "Mali")).assertDoesNotExist()
        compose.onNode(hasText("Stored DXVK") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.comp_original, "1.0")).assertIsEnabled()
        compose.onAllNodesWithText(context.getString(R.string.gpu_system_driver, "PowerVR"), substring = true).assertCountEquals(0)

        compose.onNodeWithText(context.getString(R.string.comp_scope_general)).performClick()
        compose.onNodeWithText("Game A").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(context.getString(R.string.comp_game_reset)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Stored DXVK").assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.cmgr_system_dxvk_managed, "Mali")).assertDoesNotExist()
        compose.onAllNodesWithText(context.getString(R.string.gpu_system_driver, "PowerVR"), substring = true).assertCountEquals(0)
    }

    @Test @Config(qualifiers = "w1280dp-h1000dp")
    fun systemXclipseNamesItsManagedDxvkWithoutPowerVrLabels() {
        val context = RuntimeEnvironment.getApplication()
        showDxvk(systemVulkan = true, systemDriverName = "Xclipse", runtimeManagedDxvk = true)
        compose.onAllNodesWithText(context.getString(R.string.gpu_system_driver, "Xclipse"), substring = true)
            .onLast().assertExists()
        compose.onNodeWithText(context.getString(R.string.cmgr_system_dxvk_managed, "Xclipse")).assertExists()
        compose.onNode(hasText("Stored DXVK") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertIsNotEnabled()
        compose.onAllNodesWithText("PowerVR", substring = true).assertCountEquals(0)

        compose.onNodeWithText(context.getString(R.string.comp_scope_general)).performClick()
        compose.onNodeWithText("Game A").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(context.getString(R.string.comp_game_reset)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(context.getString(R.string.gpu_system_driver, "Xclipse")).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.cmgr_system_dxvk_managed, "Xclipse")).assertExists()
        compose.onAllNodesWithText("PowerVR", substring = true).assertCountEquals(0)
    }

    private fun showDxvk(
        systemVulkan: Boolean,
        gameDxvkOverride: Boolean = false,
        systemDriverName: String = "PowerVR",
        runtimeManagedDxvk: Boolean = systemVulkan,
        onCancel: () -> Unit = {},
        onImport: () -> Unit = {},
        onSwap: (String) -> Unit = {},
        onRestore: (String) -> Unit = {},
    ) {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "components/state.json").apply {
            parentFile!!.mkdirs()
            writeText(if (gameDxvkOverride) """{"profiles":{"42":{"dxvk":"stored.wcp"}}}""" else "{}")
        }
        val proton = ComponentsManager.Proton(
            "test-proton", "Test Proton", File(context.filesDir, "test-proton"), "/test-proton", "1.0", false,
        )
        val snapshot = ComponentsManager.Snapshot(
            protons = listOf(ComponentsManager.ProtonView(
                proton = proton,
                components = mapOf("dxvk" to ComponentsManager.Component("1.0", "", "Stored DXVK", "", "stored.wcp", "Next DXVK")),
                originals = listOf(ComponentsManager.Original("dxvk", "1.0", "Original DXVK", 1)),
                inUseByGame = false,
            )),
            packages = listOf(ComponentsManager.Package("stored.wcp", "dxvk", "Stored DXVK", "", 1)),
        )
        compose.setContent {
            DroidDeckTheme {
                ComponentsPage(
                    snapshot = snapshot,
                    catalog = emptyList(),
                    catalogAt = 0,
                    protonId = proton.id,
                    comp = "dxvk",
                    checking = false,
                    busy = null,
                    downloads = emptyMap(),
                    onProton = {},
                    onComp = {},
                    onSwap = onSwap,
                    onRestore = onRestore,
                    onCancelQueued = onCancel,
                    onDeletePackage = {},
                    onDeleteOriginal = {},
                    onDownload = {},
                    onRefresh = {},
                    onImport = onImport,
                    onBack = {},
                    gpu = GpuDriversState(systemGpu = true, systemVulkan = systemVulkan,
                        systemDriverName = systemDriverName, runtimeManagedDxvk = runtimeManagedDxvk),
                    games = listOf(Library.SteamGame(42, "Game A", null, "internal")),
                    requestInitialFocus = false,
                )
            }
        }
    }

}
