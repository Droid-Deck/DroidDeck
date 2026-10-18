package com.droiddeck.launcher.ui

import android.view.Display
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.glasses.GlassesResolution
import com.droiddeck.launcher.glasses.GlassesSettings
import com.droiddeck.launcher.glasses.GlassesState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@OptIn(ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassesMenuNavigationTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private lateinit var view: android.view.View
    private lateinit var inputMode: InputModeManager
    private val firstTab = FocusRequester()
    private val glasses = GlassesState(present = true, connected = true, controllable = true,
        displayId = Display.DEFAULT_DISPLAY + 1, model = "VITURE Luma Ultra", width = 1920, height = 1200,
        hz = 60f, sbs90 = true, supportsSpatial = true, supportsScreen3dof = true, brightness = 4, volume = 3,
        resolutions = listOf(GlassesResolution(1920, 1080), GlassesResolution(1920, 1200)))

    @Test fun narrowMenuSeparatesDisplayVrTrackingAndDevice() {
        show(inSession = false)
        compose.onNodeWithTag("setting-glasses-resolution-two_d").assertExists()
        compose.onNodeWithTag("setting-glasses-resolution-three_d").assertExists()
        savePreview("display")
        compose.onNodeWithTag("glasses-distance").performScrollTo()
        savePreview("virtual-screen")
        compose.onNodeWithTag("glasses-tab-vr").assertIsDisplayed().performClick()
        compose.onNodeWithTag("setting-glasses-resolution-vr").assertIsDisplayed()
        compose.onNodeWithTag("setting-glasses-resolution-two_d").assertDoesNotExist()
        compose.onNodeWithTag("glasses-vr-fov").assertExists()
        savePreview("vr")
        compose.onNodeWithTag("glasses-tab-tracking").performClick()
        compose.onNodeWithText("LUMA ULTRA CAMERAS — EXPERIMENTAL").assertExists()
        compose.onNodeWithTag("setting-glasses-position").assertExists()
        savePreview("tracking")
        compose.onNodeWithTag("setting-glasses-hands").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("setting-glasses-joycons").assertExists()
        savePreview("hands")
        compose.onNodeWithTag("glasses-tab-hardware").assertIsDisplayed().performClick()
        compose.onNodeWithTag("glasses-brightness").assertIsDisplayed()
        compose.onNodeWithTag("glasses-volume").assertIsDisplayed()
        compose.onNodeWithTag("setting-glasses-sunblock").assertIsDisplayed()
        compose.onNodeWithTag("setting-glasses-position").assertDoesNotExist()
        savePreview("device")
    }

    @Test fun controllerCanReachVrTabAndItsLaunchAction() {
        var launches = 0
        show(inSession = true, launch = { launches++ })
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard); firstTab.requestFocus() }
        compose.onNodeWithTag("glasses-tab-display").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.Enter)
        }
        compose.onNodeWithTag("glasses-tab-vr").assertIsSelected()
        compose.onNodeWithTag("glasses-tab-vr").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Start SteamVR").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, launches)
        savePreview("session-vr")
    }

    @Test fun unavailableHardwareDoesNotLeaveEmptyTabs() {
        var tabs = emptyList<Pair<String, String>>()
        compose.setContent { DroidDeckTheme { tabs = glassesTabs(glasses.copy(connected = false)) } }
        compose.runOnIdle { assertEquals(listOf("display", "vr"), tabs.map { it.first }) }
    }

    @Test fun threeDofRecenterComesFirstOnEveryTab() {
        assertRecenterComesFirst(GlassesSettings(picture3d = GlassesSettings.PICTURE_SCREEN), false)
    }

    @Test fun sixDofRecenterComesFirstOnEveryTab() {
        assertRecenterComesFirst(GlassesSettings(picture3d = GlassesSettings.PICTURE_SCREEN, positionalTracking = true), false)
    }

    @Test fun vrRecenterComesFirstOnEveryTab() {
        assertRecenterComesFirst(GlassesSettings(), true)
    }

    private fun assertRecenterComesFirst(settings: GlassesSettings, vr: Boolean) {
        var recenters = 0
        show(inSession = true, settings = settings, steamVrRunning = vr, recenter = { recenters++ })
        val firstControls = listOf(
            GlassesTab.DISPLAY to "setting-glasses-game",
            GlassesTab.VR to "setting-glasses-auto3d",
            GlassesTab.TRACKING to "setting-glasses-tracking",
            GlassesTab.HARDWARE to "glasses-brightness",
        )
        for ((tab, control) in firstControls) {
            compose.onNodeWithTag("glasses-tab-$tab").performClick()
            val button = compose.onNodeWithText("Recenter").assertIsDisplayed()
            assertTrue("Recenter must precede $control", button.fetchSemanticsNode().boundsInRoot.bottom <=
                compose.onNodeWithTag(control).fetchSemanticsNode().boundsInRoot.top)
            button.performClick()
        }
        assertEquals(4, recenters)
        savePreview(if (vr) "recenter-vr" else if (settings.positionalTracking) "recenter-6dof" else "recenter-3dof")
    }

    private fun show(inSession: Boolean, launch: () -> Unit = {},
        settings: GlassesSettings = GlassesSettings(positionalTracking = true),
        steamVrRunning: Boolean = false, recenter: (() -> Unit)? = null) {
        compose.setContent {
            DroidDeckTheme {
                view = LocalView.current
                inputMode = LocalInputModeManager.current
                var selected by remember { mutableStateOf(GlassesTab.DISPLAY) }
                val host = rememberMenuHost()
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                    SegmentedTabs(glassesTabs(glasses), selected, Modifier.fillMaxWidth(), tabModifier = { key ->
                        Modifier.testTag("glasses-tab-$key").then(if (key == GlassesTab.DISPLAY)
                            Modifier.focusRequester(firstTab) else Modifier)
                    }) { selected = it; host.open = null }
                    key(selected) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            CompositionLocalProvider(LocalChipMinWidth provides 120.dp) {
                                GlassesGroups(host, glasses, settings, onRecenter = recenter, steamVrRunning = steamVrRunning,
                                    track = { if (it in listOf("glasses-width", "glasses-distance", "glasses-vr-fov",
                                        "glasses-brightness", "glasses-volume")) Modifier.testTag(it) else Modifier }, inSession = inSession, tab = selected,
                                    steamVr = { if (inSession) ActionRow("SteamVR", null, "Start SteamVR", launch) })
                            }
                        }
                    }
                }
            }
        }
    }

    private fun savePreview(name: String) {
        val file = File("build/reports/glasses-menus/$name.png")
        file.parentFile?.mkdirs()
        compose.runOnIdle {
            val root = view.rootView
            val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bitmap))
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
