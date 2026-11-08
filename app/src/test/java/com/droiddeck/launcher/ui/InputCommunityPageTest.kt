package com.droiddeck.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.*
import org.junit.Assert.*
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
@Config(sdk = [28], qualifiers = "w1000dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InputCommunityPageTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var root: View
    @Before fun reset() {
        for (name in listOf("input-profiles", "input-surfaces", "controller", "input-game-controls"))
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun open() {
        compose.setContent { root = LocalView.current; DroidDeckTheme { Surface { BoxWithConstraints {
            CompositionLocalProvider(LocalNarrowPane provides (maxWidth < 600.dp)) { InputSettingsPage({}) }
        } } } }
        compose.onNodeWithTag("input-community").performScrollTo().performClick()
    }
    @Test fun communityRequiresReviewBeforeApplyingAndPreservesOtherProfiles() {
        val before = InputLibraries.read(context)
        open()
        compose.onNodeWithText("Compact gamepad").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(context.getString(R.string.community_apply)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(before, InputLibraries.read(context))
        screenshot("community-review", org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView)
        compose.onNodeWithText(context.getString(R.string.community_apply)).performClick()
        compose.waitUntil(10_000) { InputLibraries.read(context).layout(InputProfile.GAME, InputRole.PRIMARY)?.name == "Compact gamepad" }
        val after = InputLibraries.read(context)
        assertEquals(before.profile(InputProfile.DESKTOP), after.profile(InputProfile.DESKTOP))
        compose.onNodeWithText(context.getString(R.string.community_export)).performScrollTo().assertIsDisplayed()
    }
    @Test @Config(qualifiers = "w360dp-h800dp")
    fun phoneCanBrowseSearchAndReachImportAndSubmission() {
        open()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("community-preview-packs/compact-gamepad.ddcontrols.zip", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        screenshot("community-phone")
        compose.onNodeWithText(context.getString(R.string.community_search)).performTextInput("absent-game")
        compose.onNodeWithText(context.getString(R.string.community_empty)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("community-import").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.community_submit)).performScrollTo().assertIsDisplayed()
    }
    @Test fun starterPreviewUsesTheSameRendererAsInstalledControls() {
        val bytes = context.assets.open("community-controls/packs/compact-gamepad.ddcontrols.zip").use { it.readBytes() }
        val pack = CommunityControls.parse(context, bytes)
        val view = InputLayoutPreviewView(context)
        view.update(pack.layout, InputRole.PRIMARY)
        view.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 640, 360)
        screenshot("compact-gamepad", view)
    }
    private fun screenshot(name: String, view: View = root) {
        compose.runOnIdle {
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            val file = File("build/reports/community-controls/$name.png").apply { parentFile?.mkdirs() }
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
}
