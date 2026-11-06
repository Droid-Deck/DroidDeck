package com.droiddeck.launcher.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
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
    private val gameA = Library.SteamGame(42, "Game A", null, "internal")
    private val gameB = Library.SteamGame(43, "Game B", null, "internal")

    @Test fun gameProfilesCoexistWithTheDefaultComponentStack() {
        val context = RuntimeEnvironment.getApplication()
        show()
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

    @Test fun opensTheRequestedGameProfileAndAllowsSwitchingToDefault() {
        val context = RuntimeEnvironment.getApplication()
        show { gameB.profileKey }
        compose.onNodeWithText("Game B").assertExists()
        compose.onNodeWithText(context.getString(R.string.comp_game_hint)).assertExists()
        compose.onNodeWithText(context.getString(R.string.comp_this_proton).uppercase()).assertDoesNotExist()

        compose.onNodeWithText("Game B").performClick()
        compose.onNodeWithText(context.getString(R.string.comp_scope_general)).performClick()
        compose.onNodeWithText(context.getString(R.string.comp_this_proton).uppercase()).assertExists()
    }

    @Test fun aNewNavigationTargetReplacesThePreviousProfile() {
        val context = RuntimeEnvironment.getApplication()
        val target = mutableStateOf<String?>(gameA.profileKey)
        show { target.value }
        compose.onNodeWithText("Game A").assertExists()

        compose.runOnIdle { target.value = gameB.profileKey }
        compose.onNodeWithText("Game B").assertExists()
        compose.onNodeWithText("Game A").assertDoesNotExist()

        compose.runOnIdle { target.value = null }
        compose.onNodeWithText(context.getString(R.string.comp_scope_general)).assertExists()
        compose.onNodeWithText(context.getString(R.string.comp_this_proton).uppercase()).assertExists()
    }

    @Test fun anUnavailableGameFallsBackToTheDefaultProfile() {
        val context = RuntimeEnvironment.getApplication()
        show { "missing-game" }
        compose.onNodeWithText(context.getString(R.string.comp_scope_general)).assertExists()
        compose.onNodeWithText(context.getString(R.string.comp_this_proton).uppercase()).assertExists()
    }

    @Test fun componentSourcesStayDisabledWhileADownloadIsActive() {
        val context = RuntimeEnvironment.getApplication()
        show(comp = "dxvk", downloads = mapOf("dxvk.wcp" to 42))

        compose.onNodeWithContentDescription(context.getString(R.string.sources_component_title))
            .assertIsNotEnabled()
    }

    @Test fun gpuSourcesStayDisabledWhileADriverDownloadIsActive() {
        val context = RuntimeEnvironment.getApplication()
        show(
            comp = GPU_TAB,
            gpu = GpuDriversState(
                sourceKey = "gpu/downloads",
                sourceBuiltIns = listOf("Droid-Deck/DroidDeck"),
                linuxDownloads = listOf(DownloadRow("driver", "Driver", "Downloading", progress = 42)),
            ),
        )

        compose.onNodeWithContentDescription(context.getString(R.string.sources_component_title))
            .assertIsNotEnabled()
    }

    @Test fun gpuSourcesStayEnabledForAnAvailableDriverCandidate() {
        val context = RuntimeEnvironment.getApplication()
        show(
            comp = GPU_TAB,
            gpu = GpuDriversState(
                sourceKey = "gpu/candidates",
                sourceBuiltIns = listOf("Droid-Deck/DroidDeck"),
                androidDownloads = listOf(DownloadRow("driver", "Driver", "Candidate")),
            ),
        )

        compose.onNodeWithContentDescription(context.getString(R.string.sources_component_title))
            .assertIsEnabled()
    }

    @Test
    @Config(qualifiers = "w1200dp-h752dp-land")
    fun sameNamedStableRowsFromDifferentSourcesToggleIndependently() {
        fun item(file: String, source: String, nightly: Boolean, asset: Long) = ComponentsManager.CatalogItem(
            file, "dxvk", if (nightly) ComponentsManager.DD_NIGHTLY else "Dxvk-Linux",
            "https://github.com/$source/releases/download/v1/$file", 100, "sha256:$source",
            sourceId = source, repository = source, assetId = asset,
        )
        val stableA = item("dxvk-3.1.1-linux.wcp", "owner/a", false, 1)
        val stableB = item("dxvk-3.1.1-linux.wcp", "owner/b", false, 2)
        val nightlyA = item("DD-dxvk-3.1.1+101-nightly.wcp", "owner/a", true, 3)
        val nightlyB = item("DD-dxvk-3.1.1+202-nightly.wcp", "owner/b", true, 4)
        show(comp = "dxvk", catalog = listOf(stableA, stableB, nightlyA, nightlyB))

        try {
            compose.onRoot().assertWidthIsAtLeast(1000.dp)
            compose.onAllNodesWithText("dxvk-3.1.1-linux").assertCountEquals(2)
            val switchA = compose.onNodeWithTag("component-channel:${stableA.id}")
            val switchB = compose.onNodeWithTag("component-channel:${stableB.id}")
            switchA.assertWidthIsAtLeast(44.dp)
            switchB.assertWidthIsAtLeast(44.dp)
            switchA.assertIsOff()
            switchB.assertIsOff()
            switchA.performClick()
            switchA.assertIsOn()
            switchB.assertIsOff()
            compose.onNodeWithText("DD-dxvk-3.1.1+101-nightly").assertExists()
            compose.onNodeWithText("DD-dxvk-3.1.1+202-nightly").assertDoesNotExist()
            compose.onAllNodesWithText("dxvk-3.1.1-linux").assertCountEquals(1)
            switchB.performClick()
            switchA.assertIsOn()
            switchB.assertIsOn()
            compose.onNodeWithText("DD-dxvk-3.1.1+101-nightly").assertExists()
            compose.onNodeWithText("DD-dxvk-3.1.1+202-nightly").assertExists()
        } catch (failure: AssertionError) {
            val merged = runCatching { compose.onRoot().printToString() }.getOrElse { it.toString() }
            val unmerged = runCatching { compose.onRoot(useUnmergedTree = true).printToString() }
                .getOrElse { it.toString() }
            throw AssertionError("${failure.message}\nMerged semantics:\n$merged\nUnmerged semantics:\n$unmerged", failure)
        }
    }

    private fun show(
        downloads: Map<String, Int> = emptyMap(),
        comp: String = PROTON_TAB,
        gpu: GpuDriversState = GpuDriversState(),
        catalog: List<ComponentsManager.CatalogItem> = emptyList(),
        initialGameKey: () -> String? = { null },
    ) {
        compose.setContent {
            DroidDeckTheme {
                ComponentsPage(
                    snapshot = null,
                    catalog = catalog,
                    catalogAt = 0,
                    protonId = null,
                    comp = comp,
                    checking = false,
                    busy = null,
                    downloads = downloads,
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
                    games = listOf(gameA, gameB),
                    initialGameKey = initialGameKey(),
                    requestInitialFocus = false,
                    gpu = gpu,
                )
            }
        }
    }

    @Test @Config(qualifiers = "w1280dp-h1000dp")
    fun systemPowerVrKeepsStoredDxvkDisabledAndQueuedDxvkCancellable() {
        val context = RuntimeEnvironment.getApplication()
        var cancelled = 0
        var imported = 0
        var swapped = 0
        var restored = 0
        showDxvk(
            systemPowerVr = true,
            onCancel = { cancelled++ },
            onImport = { imported++ },
            onSwap = { swapped++ },
            onRestore = { restored++ },
        )

        compose.onNodeWithText(context.getString(R.string.cmgr_powervr_dxvk_managed)).assertExists()
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
        showDxvk(systemPowerVr = false)

        compose.onNode(hasText("Stored DXVK") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.comp_original, "1.0")).assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.comp_restore_original)).assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.cmgr_powervr_dxvk_managed)).assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w1280dp-h1000dp")
    fun systemPowerVrGameProfileKeepsOtherComponentChoicesAndRuntimeOptionsAvailable() {
        val context = RuntimeEnvironment.getApplication()
        showDxvk(systemPowerVr = true)
        compose.onNodeWithText(context.getString(R.string.comp_scope_general)).performClick()
        compose.onNodeWithText("Game A").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(context.getString(R.string.comp_game_reset)).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText(context.getString(R.string.gpu_system_powervr)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.cmgr_powervr_dxvk_managed)).assertExists()
        val inheritedChoices = compose.onAllNodesWithText(context.getString(R.string.comp_game_use_general))
        inheritedChoices.assertCountEquals(4) // FEX preset, FEX package, Proton and VKD3D remain selectable.
        repeat(4) { inheritedChoices[it].assertIsEnabled() }
        val runtimeOptions = compose.onAllNodesWithText(context.getString(R.string.comp_game_runtime_options))
        runtimeOptions.assertCountEquals(2)
        repeat(2) { runtimeOptions[it].assertIsEnabled() }
        compose.onNodeWithText(context.getString(R.string.comp_this_proton).uppercase()).assertDoesNotExist()

        compose.onNodeWithText("Game A").performClick()
        compose.onNodeWithText(context.getString(R.string.comp_scope_general)).performClick()
        compose.onNodeWithText(context.getString(R.string.comp_this_proton).uppercase()).assertExists()
    }

    @Test @Config(qualifiers = "w1280dp-h1000dp")
    fun systemPowerVrGameProfileCanClearAnExistingDxvkOverrideWithoutSelectingAnother() {
        val context = RuntimeEnvironment.getApplication()
        showDxvk(systemPowerVr = true, gameDxvkOverride = true)
        compose.onNodeWithText(context.getString(R.string.comp_scope_general)).performClick()
        compose.onNodeWithText("Game A").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(context.getString(R.string.comp_game_reset)).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText(context.getString(R.string.gpu_system_powervr)).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Stored DXVK").assertDoesNotExist()
        compose.onAllNodesWithText(context.getString(R.string.comp_game_use_general)).onLast().performClick()
        compose.waitUntil(5_000) { "dxvk" !in ComponentsManager.gameComponents(context, "42") }
        compose.onNodeWithText(context.getString(R.string.gpu_system_powervr)).assertIsNotEnabled()
    }

    private fun showDxvk(
        systemPowerVr: Boolean,
        gameDxvkOverride: Boolean = false,
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
                    gpu = GpuDriversState(powerVr = true, systemPowerVr = systemPowerVr),
                    games = listOf(Library.SteamGame(42, "Game A", null, "internal")),
                    requestInitialFocus = false,
                )
            }
        }
    }

}
