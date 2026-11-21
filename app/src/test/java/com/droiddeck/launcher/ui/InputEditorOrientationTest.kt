package com.droiddeck.launcher.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.droiddeck.launcher.R
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
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InputEditorOrientationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>().also {
        RingVeil.reset()
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, ComponentActivity::class.java))
    }
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        for (name in listOf("input-profiles", "input-surfaces", "controller")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun theEditorHoldsItsScreenLandscapeUntilItCloses() {
        val library = InputLibraries.read(context)
        val layout = library.layouts.first { it.id == InputLibraries.OVERLAY }
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
        val open = mutableStateOf(true)
        compose.setContent { DroidDeckTheme { if (open.value) InputSurfaceDialog(InputProfile.GAME, { open.value = false }, {}, role = InputRole.PRIMARY,
            layoutId = layout.id, bindingsId = library.profile(InputProfile.GAME).binding(InputRole.PRIMARY)) } }
        compose.runOnIdle { assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, compose.activity.requestedOrientation) }
        compose.runOnIdle { open.value = false }
        compose.runOnIdle { assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER, compose.activity.requestedOrientation) }
    }

    @Test fun aFixedLandscapeIsKept() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, landscapeOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, landscapeOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT))
    }

    @Test fun savingWhileTheScreenIsPortraitKeepsTheLayoutLandscape() {
        val library = InputLibraries.read(context)
        val layout = library.layout(InputProfile.GAME, InputRole.SECONDARY)!!
        compose.setContent { DroidDeckTheme { InputSurfaceDialog(InputProfile.GAME, {}, {}, role = InputRole.PRIMARY, layoutId = layout.id,
            bindingsId = library.profile(InputProfile.GAME).binding(InputRole.SECONDARY)) } }
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithText(context.getString(R.string.input_editor_add)).performClick()
        compose.onNodeWithTag("input-add-control-b").performClick()
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithTag("input-editor-save").performClick()
        compose.runOnIdle {
            val saved = InputLibraries.read(context).layouts.first { it.id == layout.id }
            val metrics = context.resources.displayMetrics
            val landscape = maxOf(metrics.widthPixels, metrics.heightPixels).toFloat() / minOf(metrics.widthPixels, metrics.heightPixels)
            assertEquals(landscape, saved.aspect, .01f)
            val added = saved.elements.single { e -> layout.elements.none { it.id == e.id } }
            assertEquals("a round button stays round on the landscape screen", added.height, added.width * saved.aspect, .01f)
        }
    }

    @Test fun aDraftOpenedInPortraitIsBuiltAgainForTheLandscapeEditor() {
        val library = InputLibraries.read(context)
        val layout = library.layout(InputProfile.GAME, InputRole.SECONDARY)!!
        compose.setContent { DroidDeckTheme { InputSurfaceDialog(InputProfile.GAME, {}, {}, role = InputRole.PRIMARY, layoutId = layout.id,
            bindingsId = library.profile(InputProfile.GAME).binding(InputRole.SECONDARY)) } }
        compose.runOnIdle {
            ShadowDisplayManager.changeDisplay(Display.DEFAULT_DISPLAY, "+land")
            val turned = Configuration(compose.activity.resources.configuration).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
                screenWidthDp = screenHeightDp.also { screenHeightDp = screenWidthDp }
            }
            compose.activity.window.decorView.dispatchConfigurationChanged(turned)
        }
        val (w, h) = InputLibraries.primaryDisplay(context).second.let { it.widthPixels to it.heightPixels }
        assertTrue("the screen turned landscape", w > h)
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithText(context.getString(R.string.input_editor_add)).performClick()
        compose.onNodeWithTag("input-add-control-b").performClick()
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithTag("input-editor-save").performClick()
        compose.runOnIdle {
            val saved = InputLibraries.read(context).layouts.first { it.id == layout.id }.elements.associateBy { it.id }
            for (e in OnScreenLayout.elements(context, layout, w, h, Rect())) {
                val s = saved.getValue(e.id)
                assertEquals("${e.id} x", e.x * w, s.x * w, .5f)
                assertEquals("${e.id} y", e.y * h, s.y * h, .5f)
                assertEquals("${e.id} width", e.width * w, s.width * w, .5f)
                assertEquals("${e.id} height", e.height * h, s.height * h, .5f)
            }
        }
    }
}
