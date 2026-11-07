package com.droiddeck.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.ControllerPrefs
import com.droiddeck.launcher.input.InputLibraries
import com.droiddeck.launcher.input.InputProfiles
import org.junit.Assert.assertEquals
import com.droiddeck.launcher.input.InputAction
import com.droiddeck.launcher.input.InputControl
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InputUiLayoutTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private lateinit var root: View
    @Before fun reset() {
        val context = RuntimeEnvironment.getApplication()
        for (name in listOf("input-profiles", "input-surfaces", "controller")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun layoutThumbnailDoesNotPaintOverItsRowOrAdjacentControls() {
        val context = RuntimeEnvironment.getApplication()
        val library = InputLibraries.read(context)
        val preview = InputLayoutPreviewView(context)
        preview.update(library.layout("game", com.droiddeck.launcher.input.InputRole.SECONDARY)!!, com.droiddeck.launcher.input.InputRole.SECONDARY)
        preview.measure(View.MeasureSpec.makeMeasureSpec(112, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(76, View.MeasureSpec.EXACTLY))
        preview.layout(0, 0, 112, 76)
        val bitmap = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.MAGENTA)
        preview.draw(Canvas(bitmap))
        assertEquals(android.graphics.Color.MAGENTA, bitmap.getPixel(150, 30))
        assertEquals(android.graphics.Color.MAGENTA, bitmap.getPixel(30, 120))
        bitmap.recycle()
    }

    @Test @Config(qualifiers = "w360dp-h800dp")
    fun phoneKeepsLibraryNavigationAndProfileEditingReachable() {
        compose.setContent { root = LocalView.current; DroidDeckTheme { Surface { BoxWithConstraints {
            CompositionLocalProvider(LocalNarrowPane provides (maxWidth < 600.dp)) { InputSettingsPage({}) }
        } } } }
        compose.onNodeWithTag("input-profile-desktop").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("input-part-physical").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("input-binding-a").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("input-action-save").assertIsDisplayed()
        screenshot("phone-properties")
    }

    @Test @Config(qualifiers = "w640dp-h360dp")
    fun handheldPropertiesLeaveSaveVisibleAboveScrollableContent() {
        compose.setContent { root = LocalView.current; DroidDeckTheme { Surface { InputActionEditor(InputControl.LS, InputAction.Pointer(), "Left stick", {}, {}) } } }
        compose.onNodeWithTag("input-action-save").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performScrollTo().assertIsDisplayed()
        screenshot("handheld-properties")
    }

    @Test @Config(qualifiers = "w960dp-h600dp")
    fun largeScreenShowsTheProfileLibraryInTheExistingTheme() {
        compose.setContent { root = LocalView.current; DroidDeckTheme { Surface { InputSettingsPage({}) } } }
        compose.onNodeWithTag("input-profile-game").assertIsDisplayed()
        screenshot("profiles")
    }

    @Test @Config(qualifiers = "w960dp-h600dp")
    fun controllerTabKeepsSetupNavigationWithoutDuplicateEditors() {
        val context = RuntimeEnvironment.getApplication()
        compose.setContent {
            root = LocalView.current
            DroidDeckTheme {
                CompositionLocalProvider(LocalFrontFocus provides FrontFocus(), LocalNarrowPane provides false) {
                    FocusGlideHost {
                        SetupPanel(FrontEndState(
                            installed = null, ready = false, available = null, busy = false, stage = "", percent = -1,
                            desktopInstalled = false, offlineAccount = null, offline = false, frameGenLabel = "",
                            romsDir = null, logsEnabled = false, steamGames = emptyList(), emulators = emptyList(), running = null,
                            controller = ControllerPrefs.read(context),
                        ), FrontEndActions(
                            onPlay = {}, onPlayDesktopUi = {}, onSteamGame = {}, onDesktop = {}, onEmulator = {},
                            onRom = {}, onResume = {}, onSteamSettings = {}, onDesktopSettings = {},
                            onInstallPackage = {}, onRemovePackage = {}, onRuntime = {}, onFrameGenPick = {},
                            onImportLossless = {}, onProtons = {}, onComponents = {}, onPerformance = {},
                            onRoms = {}, onFiles = {}, onLogs = {}, onOffline = {},
                            onWirelessAdbPair = { _, _, _, _ -> }, onWirelessAdbApply = { _, _, _, _ -> },
                            onSetPhantomProcessLimit = { _, _ -> }, controller = ControllerActions({}),
                        ), onOpenDeveloperOptions = {}, onRequestWirelessAdb = {}, onPairWirelessDebugging = {})
                    }
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.setup_tab_controller)).performClick()
        compose.onNodeWithTag("input-profile-game").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.ctrl_layout)).assertDoesNotExist()
        screenshot("controller-tab")
        compose.onNodeWithTag("input-profile-game").performClick()
        compose.onNodeWithTag("input-part-physical").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.setup_tab_launcher)).assertIsDisplayed()
        screenshot("controller-physical")
        compose.runOnIdle { assertEquals(InputProfiles.AUTO, InputLibraries.read(context).selected) }
    }

    private fun screenshot(name: String) {
        compose.runOnIdle {
            val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(image))
            val file = File("build/reports/input-ui/$name.png").apply { parentFile?.mkdirs() }
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
}
