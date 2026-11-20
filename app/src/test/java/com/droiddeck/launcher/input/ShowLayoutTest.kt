package com.droiddeck.launcher.input

import android.content.Context
import android.view.View
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.ui.InputSurfaceView
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "w833dp-h468dp-land-369dpi")
class ShowLayoutTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @After fun close() { context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit() }

    private fun sized(view: View, w: Int, h: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
    }

    @Test fun aGeneratedLayoutIsDrawnFromTheKitAtTheViewSize() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_XBOX360)
        val view = InputSurfaceView(context)
        sized(view, 1240, 1080)
        view.showLayout(SavedInputLayout(InputLibraries.OVERLAY, "Gamepad"))
        assertEquals(KitLayout.elements(context, PadKit.XBOX, 1240, 1080), view.shownElements)
        view.releaseInputs()
    }

    @Test fun overlayExtrasAreIndependentAndKeepTheirPreferencesWhenRecreated() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_DECK)
        val kit = SavedInputLayout(InputLibraries.OVERLAY, "Gamepad")
        val grips = setOf(InputControl.L4, InputControl.L5, InputControl.R4, InputControl.R5)
        val trackpads = setOf(InputControl.LEFT_PAD, InputControl.RIGHT_PAD)
        fun controls(dock: OnScreenLayout.Dock? = null, layout: SavedInputLayout = kit): OnScreenControls =
            OnScreenControls(context, null, dock).also { it.updateLayout(layout); sized(it, 1920, 1080) }
        fun shown(view: OnScreenControls) = view.inputSurface.shownElements.map { it.control }.toSet()
        val overlay = controls()
        assertTrue(shown(overlay).intersect(grips + trackpads).isEmpty())
        SessionPrefs.setOscRearButtons(context, true)
        overlay.reload()
        assertTrue(shown(overlay).containsAll(grips))
        assertTrue(shown(overlay).intersect(trackpads).isEmpty())
        SessionPrefs.setOscTrackpads(context, true)
        SessionPrefs.setOscRearButtons(context, false)
        assertTrue(shown(controls()).containsAll(trackpads))
        assertTrue(shown(controls()).intersect(grips).isEmpty())

        SessionPrefs.setOscTrackpads(context, false)
        val customPad = InputSurfaceElement(InputControl.LEFT_PAD, .4f, .4f, .15f, .15f, id = "custom-pad")
        val edited = kit.copy(elements = listOf(customPad,
            InputSurfaceElement(InputControl.RIGHT_PAD, .6f, .4f, .15f, .15f, id = "legacy:right-pad")))
        assertEquals(listOf(customPad.id), controls(layout = edited).inputSurface.shownElements.map { it.id })

        val dock = OnScreenLayout.Dock(portrait = true, grips = true)
        val panel = OnScreenControls(context, null, dock).also { it.updateLayout(kit); sized(it, 1080, 1300) }
        assertTrue(shown(panel).containsAll(grips + trackpads))
    }

    @Test fun aSavedLayoutIsDrawnAsSaved() {
        val saved = listOf(InputSurfaceElement(InputControl.A, .4f, .4f, .1f, .1f))
        val view = InputSurfaceView(context)
        sized(view, 1240, 1080)
        view.showLayout(SavedInputLayout("mine", "Mine", saved))
        assertEquals(saved, view.shownElements)
        view.showLayout(null)
        assertTrue(view.shownElements.isEmpty())
        view.releaseInputs()
    }
}
