package com.droiddeck.launcher.ui

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.droiddeck.launcher.input.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.droiddeck.launcher.session.SessionPrefs

class InputSettingsHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DroidDeckTheme { InputSettingsPage({}) } }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w914dp-h411dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InputEditorRecreateTest {
    @get:Rule val compose = createAndroidComposeRule<InputSettingsHostActivity>().also {
        RingVeil.reset()
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, InputSettingsHostActivity::class.java))
    }
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        for (name in listOf("input-profiles", "input-surfaces", "controller")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        // Every controller kit redraws the stock overlay in its own places; these tests check the editor on the plain one.
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_NONE)
    }

    @Test fun aFontOrLanguageChangeKeepsTheEditorOpenWithItsUnsavedEdits() {
        val layout = InputLibraries.read(context).layouts.first { it.id == InputLibraries.OVERLAY }
        compose.onNodeWithTag("input-profile-game").performScrollTo().performClick()
        compose.onNodeWithTag("input-part-primary").performScrollTo().performClick()
        compose.onNodeWithTag("input-surface-primary").performScrollTo().performClick()
        val canvas = compose.onNodeWithTag("input-layout-canvas").fetchSemanticsNode().boundsInRoot
        val stick = compose.runOnIdle { layout.elements.first { it.id == "legacy:ls" } }
        val at = Rect(canvas.left + stick.x * canvas.width, canvas.top + stick.y * canvas.height,
            canvas.left + (stick.x + stick.width) * canvas.width, canvas.top + (stick.y + stick.height) * canvas.height).center - canvas.topLeft
        compose.onNodeWithTag("input-layout-canvas").performTouchInput { click(at) }
        val badge = editorBadge(context, layout, listOf("legacy:ls"), canvas)
        compose.onNodeWithTag("input-layout-canvas").performTouchInput { click(androidx.compose.ui.geometry.Offset(badge.first, badge.second)) }
        compose.runOnIdle { assertEquals("the edit is not saved yet", emptySet<String>(), InputLibraries.read(context).layouts.first { it.id == layout.id }.style.removed) }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithTag("input-editor-save").performClick()
        compose.runOnIdle { assertEquals(setOf("legacy:ls"), InputLibraries.read(context).layouts.first { it.id == layout.id }.style.removed) }
    }

    @Test fun discardingAfterALanguageChangeRestoresSnapFromWhenTheEditorOpened() {
        val layout = InputLibraries.read(context).layouts.first { it.id == InputLibraries.OVERLAY }
        val opened = ControllerPrefs.snap(context)
        compose.onNodeWithTag("input-profile-game").performScrollTo().performClick()
        compose.onNodeWithTag("input-part-primary").performScrollTo().performClick()
        compose.onNodeWithTag("input-surface-primary").performScrollTo().performClick()
        val canvas = compose.onNodeWithTag("input-layout-canvas").fetchSemanticsNode().boundsInRoot
        val badge = editorBadge(context, layout, listOf("legacy:ls"), canvas)
        val stick = layout.elements.first { it.id == "legacy:ls" }
        compose.onNodeWithTag("input-layout-canvas").performTouchInput { click(androidx.compose.ui.geometry.Offset((stick.x + stick.width / 2) * canvas.width, (stick.y + stick.height / 2) * canvas.height)) }
        compose.onNodeWithTag("input-layout-canvas").performTouchInput { click(androidx.compose.ui.geometry.Offset(badge.first, badge.second)) }
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithTag("input-editor-snap").performClick()
        compose.runOnIdle { assertNotEquals(opened, ControllerPrefs.snap(context)) }
        compose.activityRule.scenario.recreate()
        if (compose.onAllNodesWithTag("input-editor-close").fetchSemanticsNodes().isEmpty()) compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithTag("input-editor-close").performClick()
        compose.onNodeWithText(context.getString(com.droiddeck.launcher.R.string.input_editor_discard_confirm)).performClick()
        compose.runOnIdle {
            assertEquals(emptySet<String>(), InputLibraries.read(context).layouts.first { it.id == layout.id }.style.removed)
            assertEquals(opened, ControllerPrefs.snap(context))
        }
    }
}
