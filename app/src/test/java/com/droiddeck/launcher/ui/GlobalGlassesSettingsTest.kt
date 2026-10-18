package com.droiddeck.launcher.ui

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.droiddeck.launcher.R
import com.droiddeck.launcher.glasses.Glasses
import com.droiddeck.launcher.glasses.GlassesSettings
import com.droiddeck.launcher.glasses.GlassesState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1200dp-h752dp-land", application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class GlobalGlassesSettingsTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val handler = Handler(Looper.getMainLooper())
    private val connected = GlassesState(present = true, connected = true, controllable = true,
        displayId = 2, width = 1920, height = 1080, model = "VITURE Luma Ultra",
        supportsScreen3dof = true, supportsSpatial = true)
    private lateinit var stateBefore: GlassesState
    private lateinit var settingsBefore: GlassesSettings
    private var appBefore: Any? = null
    private var handlerBefore: Any? = null

    @Before fun prepare() {
        stateBefore = Glasses.state.value
        settingsBefore = Glasses.settings.value
        appBefore = field("app").get(null)
        handlerBefore = field("handler").get(null)
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("glasses", Context.MODE_PRIVATE).edit().clear().commit()
        field("app").set(null, app)
        field("handler").set(null, handler)
        state().value = GlassesState()
        settings().value = GlassesSettings()
    }

    @After fun restore() {
        handler.removeCallbacksAndMessages(null)
        state().value = stateBefore
        settings().value = settingsBefore
        field("app").set(null, appBefore)
        field("handler").set(null, handlerBefore)
    }

    @Test fun globalSetupOffersGlassesWithoutSteamOrAnInstalledRuntime() {
        show()
        openGlasses()

        compose.onNodeWithText(text(R.string.setup_glasses_scope)).assertExists()
        compose.onNodeWithText(text(R.string.setup_glasses_connect)).assertExists()
        compose.onNodeWithTag("setting-glasses-game").assertDoesNotExist()
        compose.onNodeWithTag("play-steamvr").assertDoesNotExist()

        compose.onNodeWithText(text(R.string.setup_tab_diagnostics)).performClick()
        compose.onNodeWithTag("setting-logs").assertExists()
        openGlasses()
        compose.onNodeWithText(text(R.string.setup_glasses_connect)).assertExists()
    }

    @Test fun hotplugKeepsTheGlobalPageAndSharedTrackingPreferences() {
        val saved = GlassesSettings(positionalTracking = true, handControllers = true)
        settings().value = saved
        show()
        openGlasses()

        compose.runOnIdle { state().value = connected }
        compose.onNodeWithTag("setting-glasses-3dof").assertExists()
        compose.onNodeWithTag("setup-glasses-tab-tracking").performScrollTo().performClick()
        compose.onNodeWithTag("setting-glasses-position").assertExists()

        compose.runOnIdle { state().value = GlassesState() }
        compose.onNodeWithText(text(R.string.setup_glasses_connect)).assertExists()
        compose.onNodeWithTag("setting-glasses-position").assertDoesNotExist()
        assertEquals(saved, Glasses.settings.value)

        compose.runOnIdle { state().value = connected }
        compose.onNodeWithTag("setting-glasses-position").assertExists()
        compose.onNodeWithTag("setting-glasses-hands").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.widgets_on)))
        assertEquals(saved, Glasses.settings.value)
    }

    @Test fun globalScreenTrackingUsesTheExistingSavedSettings() {
        state().value = connected
        settings().value = GlassesSettings(gameOnGlasses = false, headTracking = false)
        show()
        openGlasses()
        compose.onNodeWithTag("setting-glasses-3dof").performScrollTo().performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(Glasses.settings.value.screen3dof)
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("glasses", Context.MODE_PRIVATE)
        assertTrue(prefs.getBoolean("gameOnGlasses", false))
        assertTrue(prefs.getBoolean("headTracking", false))
        assertEquals(GlassesSettings.PICTURE_SCREEN, prefs.getInt("picture3d", -1))

        compose.onNodeWithText(text(R.string.setup_tab_launcher)).performClick()
        openGlasses()
        compose.onNodeWithTag("setting-glasses-3dof").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.widgets_on)))
    }

    private fun openGlasses() {
        compose.onNodeWithText(text(R.string.setup_tab_glasses)).performClick()
    }

    private fun show() {
        compose.setContent {
            DroidDeckTheme {
                CompositionLocalProvider(LocalFrontFocus provides FrontFocus(), LocalNarrowPane provides false) {
                    FocusGlideHost {
                        SetupPanel(FrontEndState(
                            installed = null, ready = false, available = null, busy = false, stage = "", percent = -1,
                            desktopInstalled = false, offlineAccount = null, offline = false, frameGenLabel = "",
                            romsDir = null, logsEnabled = false, steamGames = emptyList(), emulators = emptyList(), running = null,
                        ), FrontEndActions(
                            onPlay = {}, onPlayDesktopUi = {}, onSteamGame = {}, onDesktop = {}, onEmulator = {},
                            onRom = {}, onResume = {}, onSteamSettings = {}, onDesktopSettings = {},
                            onInstallPackage = {}, onRemovePackage = {}, onRuntime = {}, onFrameGenPick = {},
                            onImportLossless = {}, onProtons = {}, onComponents = {}, onPerformance = {},
                            onRoms = {}, onFiles = {}, onLogs = {}, onOffline = {},
                            onWirelessAdbPair = { _, _, _, _ -> }, onWirelessAdbApply = { _, _, _, _ -> },
                            onSetPhantomProcessLimit = { _, _ -> },
                        ), onOpenDeveloperOptions = {}, onRequestWirelessAdb = {})
                    }
                }
            }
        }
    }

    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)
    private fun field(name: String) = Glasses::class.java.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private fun state() = field("_state").get(null) as MutableStateFlow<GlassesState>
    @Suppress("UNCHECKED_CAST")
    private fun settings() = field("_settings").get(null) as MutableStateFlow<GlassesSettings>
}
