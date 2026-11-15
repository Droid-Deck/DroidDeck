package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import com.droiddeck.launcher.R
import com.droiddeck.launcher.gpu.FrameGen
import com.droiddeck.launcher.gpu.Lossless
import com.droiddeck.launcher.input.SecondScreenMode
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w800dp-h600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PenSettingsGroupTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun theDrawerHasNoPenGroupUntilAPenIsKnown() {
        compose.setContent { DroidDeckTheme { SessionDrawer(true, SessionDrawerPage.CONTROLLER, false, {}, actions(pen = null)) } }
        compose.onNodeWithTag("setting-touch").assertExists()
        compose.onNodeWithTag("setting-pen").assertDoesNotExist()
        compose.onNodeWithTag("setting-pen-button").assertDoesNotExist()
    }

    @Test fun theDrawerSavesPenPicks() {
        val picks = mutableListOf<Pair<String, String>>()
        compose.setContent {
            DroidDeckTheme {
                SessionDrawer(true, SessionDrawerPage.CONTROLLER, false, {}, actions(SessionPrefs.Pen()) { k, v -> picks += k to v })
            }
        }
        compose.onNodeWithTag("setting-pen-button").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.gesture_middle_click)).performScrollTo().performClick()
        compose.onNodeWithTag("setting-pen").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.mode_pen_touch)).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("penButton" to SessionPrefs.PEN_BUTTON_MIDDLE, "pen" to SessionPrefs.PEN_TOUCH), picks)
        }
    }

    @Test fun theSideButtonRowIsHiddenInTouchMode() {
        val pen = mutableStateOf(SessionPrefs.Pen())
        compose.setContent { DroidDeckTheme { Column { PenSettingsGroup(rememberMenuHost(), pen.value) { _, _ -> } } } }
        compose.onNodeWithTag("setting-pen-button").assertExists()
        compose.runOnIdle { pen.value = SessionPrefs.Pen(mode = SessionPrefs.PEN_TOUCH) }
        compose.onNodeWithTag("setting-pen").assertExists()
        compose.onNodeWithTag("setting-pen-button").assertDoesNotExist()
    }

    @Test fun theControlsTabShowsThePenGroupAfterTheTouchRowsOnceAPenIsKnown() {
        val pen = mutableStateOf<SessionPrefs.Pen?>(null)
        val picks = mutableListOf<Pair<String, String>>()
        compose.setContent {
            DroidDeckTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ModeSheetRows(ModeSettings(mode = SessionService.MODE_DESKTOP, resolution = "auto", hdr = false, hdrReason = null,
                        touchMode = "auto", pen = pen.value, suspendPolicy = "auto", oscMode = "auto", directAudio = null, mic = null),
                        ModeSettingsActions(onResolution = {}, onHdr = {}, onTouch = {}, onPen = { k, v -> picks += k to v },
                            onSuspendPolicy = {}, onOsc = {}, onInputSettings = {}, onDirectAudio = {}, onMic = {}, onDismiss = {}),
                        SheetTab.CONTROLS)
                }
            }
        }
        compose.onNodeWithTag("setting-touch").assertExists()
        compose.onNodeWithTag("setting-pen").assertDoesNotExist()
        compose.runOnIdle { pen.value = SessionPrefs.Pen() }
        compose.onNodeWithTag("setting-pen-button").assertExists()
        assertTrue(compose.onNodeWithTag("setting-touch").fetchSemanticsNode().positionInRoot.y <
            compose.onNodeWithTag("setting-pen").fetchSemanticsNode().positionInRoot.y)
        compose.onNodeWithTag("setting-pen-button").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.gesture_middle_click)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("penButton" to SessionPrefs.PEN_BUTTON_MIDDLE), picks) }
    }

    private fun actions(pen: SessionPrefs.Pen?, onPen: (String, String) -> Unit = { _, _ -> }) =
        DrawerActions(steam = false, desktop = true, isHomeApp = false, androidApps = emptyList(), hudOn = false,
            frameGen = FrameGen.Mode.OFF, lossless = Lossless.State.NONE, oscMode = "never",
            suspendPolicy = "auto", backActionsInverted = false,
            touchMode = "auto", touchAuto = "touchpad", pen = pen, onPen = onPen, fexPreset = "default",
            secondScreenMode = SecondScreenMode.NONE, secondScreenDisplays = emptyList(), selectedSecondScreenDisplay = 1,
            onHud = {}, onFrameGenPick = {}, onImportLossless = {}, onKeyboard = {}, onHardwareKeyboard = {},
            onSteamMenu = null, onQam = null, onOsc = {}, onSuspendPolicy = {}, onBackActionsInverted = {},
            onTouch = {}, onFexPreset = {}, onSecondScreenMode = {}, onSecondScreenDisplay = {},
            onLaunchAndroidApp = { _, _ -> }, onBackground = {}, onShareLogs = {}, onStop = {}, onClose = {})
}
