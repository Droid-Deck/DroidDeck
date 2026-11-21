package com.droiddeck.launcher.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "ar-ldrtl-w914dp-h411dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InputEditorRtlTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>().also {
        RingVeil.reset()
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, ComponentActivity::class.java))
        // The manifest leaves RTL layout off, so views would stay left to right in Arabic without this.
        context.applicationInfo.flags = context.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
    }
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        for (name in listOf("input-profiles", "input-surfaces", "controller")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun selectionSlidersSitOnTheSelectedControlInARightToLeftLayout() {
        val library = InputLibraries.read(context)
        val layout = library.layouts.first { it.id == InputLibraries.OVERLAY }
        compose.setContent { DroidDeckTheme { InputSurfaceDialog(InputProfile.GAME, {}, {}, role = InputRole.PRIMARY, layoutId = layout.id,
            bindingsId = library.profile(InputProfile.GAME).binding(InputRole.PRIMARY)) } }
        compose.onNodeWithTag("input-editor-tools").performClick()
        val close = compose.onNodeWithTag("input-editor-close").fetchSemanticsNode().boundsInRoot
        val undo = compose.onNodeWithTag("input-editor-undo").fetchSemanticsNode().boundsInRoot
        assertTrue("the menu is laid out right to left", close.left > undo.left)
        compose.onNodeWithTag("input-editor-tools").performClick()
        val canvas = compose.onNodeWithTag("input-layout-canvas").fetchSemanticsNode().boundsInRoot
        for (id in listOf("legacy:ls", "legacy:rs")) {
            val e = layout.elements.first { it.id == id }
            val stick = Rect(canvas.left + e.x * canvas.width, canvas.top + e.y * canvas.height,
                canvas.left + (e.x + e.width) * canvas.width, canvas.top + (e.y + e.height) * canvas.height)
            compose.onNodeWithTag("input-layout-canvas").performTouchInput { click(stick.center - canvas.topLeft) }
            for (tag in listOf("input-selection-size-panel", "input-selection-opacity-panel")) {
                val panel = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                assertEquals("$id $tag", stick.center.x, panel.center.x, 1f)
                assertFalse("$id $tag", panel.overlaps(stick))
            }
        }
    }
}
